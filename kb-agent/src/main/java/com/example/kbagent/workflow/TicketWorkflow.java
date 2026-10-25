package com.example.kbagent.workflow;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.kbagent.agent.KbMcpGateway;
import com.example.kbagent.agent.TicketBrain;
import com.example.kbagent.domain.Ticket;
import com.example.kbagent.domain.TicketStatus;
import com.example.kbagent.store.TicketRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工单处理的**可控工作流**（阶段2 第6周主线的代码形态）。
 *
 * 为什么是代码节点而不是"把工单丢给模型让它自己 ReAct 到结单"：
 * 线上要的是可复现、可审计、可归因的链路。五个节点顺序固定、每个节点的产物都落库，
 * 出问题时能立刻回答"卡在检索还是卡在判定"——这跟 rag-kb 的失败归因分层是同一套方法论。
 * 模型只出现在两个节点里（分诊、答复装配），而且装配节点的输入是上一步工具的真实产物。
 *
 * <pre>
 * 受理 → ① 分诊(LLM 结构化) → ② 检索证据(kb_retrieve, 代码直调)
 *      → ③ 政策答复(kb_query, 代码直调) → ④ 装配回复(LLM 结构化)
 *      → ⑤ 置信闸门(确定性代码) → AUTO_RESOLVED / PENDING_HUMAN
 * 任一节点抛异常 → FAILED + 指数退避时间戳 → 调度器捞回 → RETRYING → 重跑②③④⑤
 * </pre>
 *
 * 第⑤节点是本设计的核心立场：<b>自动结单的权力不交给模型自评</b>。
 * 置信度是三项加权：检索证据分（事实）0.5 + 引用有效性（可机器校验的忠实度代理）0.3
 * + 模型自评 0.2。前两项确定、可复算；模型自评权重最低，因为它系统性偏高。
 * 高风险类别（钱/合规/离职）无论分数多高都不自动结单——闸门之上还有一层业务红线。
 */
@Service
public class TicketWorkflow {

    private static final Pattern CITE = Pattern.compile("\\[(\\d+)]");

    private final TicketRepository tickets;
    private final TicketBrain brain;
    private final KbMcpGateway kb;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ticket.agent.auto-resolve-threshold:0.75}")
    private double autoResolveThreshold;

    @Value("${ticket.agent.weight-evidence:0.5}")
    private double weightEvidence;

    @Value("${ticket.agent.weight-citation:0.3}")
    private double weightCitation;

    @Value("${ticket.agent.weight-self-report:0.2}")
    private double weightSelfReport;

    @Value("${ticket.agent.max-attempts:3}")
    private int maxAttempts;

    @Value("${ticket.agent.retry-backoff-base-ms:2000}")
    private long backoffBaseMs;

    @Value("${ticket.agent.retry-backoff-cap-ms:60000}")
    private long backoffCapMs;

    @Value("${ticket.agent.high-risk-categories:payroll,dismissal,compliance}")
    private String highRiskCategories;

    public TicketWorkflow(TicketRepository tickets, TicketBrain brain, KbMcpGateway kb) {
        this.tickets = tickets;
        this.brain = brain;
        this.kb = kb;
    }

    /** 受理入口：落库即 NEW，随后立刻跑一遍流水线（同步跑，异步化演进见 README 局限） */
    public Ticket accept(String title, String description, String category, String priority) {
        Ticket t = tickets.insert(title, description, category, priority);
        tickets.appendNote(t.id(), "AGENT", "受理: 状态 NEW");
        return process(t.id());
    }

    /** 从当前状态跑一遍流水线；只有 NEW / RETRYING 是"可处理"的入口态 */
    public Ticket process(String ticketId) {
        Ticket t = tickets.findById(ticketId).orElseThrow();
        return run(t, t.status());
    }

    /**
     * 重试入口（调度器与人工点"重试"共用）。
     * attempts 已达上限时不再消耗一次模型调用——直接把单留在 FAILED 并留痕。
     */
    public Ticket retry(String ticketId) {
        Ticket t = tickets.findById(ticketId).orElseThrow();
        if (t.status() != TicketStatus.FAILED) {
            throw new IllegalStateException("只有 FAILED 工单能重试，当前 " + t.status());
        }
        if (t.attempts() >= maxAttempts) {
            tickets.appendNote(ticketId, "AGENT",
                    "放弃重试: attempts=%d 已达上限 %d，转人工处理".formatted(t.attempts(), maxAttempts));
            return tickets.transition(ticketId, TicketStatus.FAILED, TicketStatus.PENDING_HUMAN, "RETRY_EXHAUSTED");
        }
        Ticket retrying = tickets.markRetrying(ticketId);
        return run(retrying, TicketStatus.RETRYING);
    }

    /** 流水线主体：legalFrom 是这次决策的依据态，写进 CAS 条件 */
    private Ticket run(Ticket t, TicketStatus legalFrom) {
        try {
            // ① 分诊（LLM 结构化输出；searchQuery 就是"口语诉求 → 检索问句"的改写）
            TicketBrain.Classification cls = brain.classify(t.title(), t.description());
            if (cls != null) {
                tickets.saveClassification(t.id(), cls.category(), cls.priority());
                tickets.appendNote(t.id(), "AGENT",
                        "分诊: category=%s priority=%s query=%s".formatted(cls.category(), cls.priority(), cls.searchQuery()));
            }
            String searchQuery = cls != null && cls.searchQuery() != null && !cls.searchQuery().isBlank()
                    ? cls.searchQuery() : t.description();

            // ② 检索证据：代码直调 kb_retrieve（不调 LLM，零生成 token，专供归因与打分）
            double evidenceScore = retrieveEvidenceScore(t.id(), searchQuery);

            // ③ 政策答复：代码直调 kb_query，拿回带编号引用的答复原文
            JsonNode kbAnswer = kb.queryJson(searchQuery, t.conversationId());
            boolean refused = kbAnswer.path("refused").asBoolean(true);
            String citationsJson = kbAnswer.path("citations").isMissingNode()
                    ? "[]" : kbAnswer.path("citations").toString();

            // ④ 装配：把政策答复改写成发给提问人的工单回复（模型只见真实工具产物）
            TicketBrain.Draft draft = brain.draft(t.title(), t.description(), kbAnswer.toString());
            String reply = draft != null && draft.reply() != null && !draft.reply().isBlank()
                    ? draft.reply() : kbAnswer.path("answer").asText("（无答复）");
            boolean citationOk = citationsValid(reply, citationsJson, refused);
            double selfConfidence = draft == null ? 0 : Math.max(0, Math.min(1, draft.selfConfidence()));

            // ⑤ 置信闸门（确定性）：三项加权 + 高风险红线 + 拒答短路
            double confidence = weightEvidence * evidenceScore
                    + weightCitation * (citationOk ? 1 : 0)
                    + weightSelfReport * selfConfidence;
            confidence = Math.max(0, Math.min(1, confidence));
            tickets.saveDraft(t.id(), reply, confidence, citationsJson);

            boolean highRisk = isHighRisk(cls == null ? t.category() : cls.category());
            TicketStatus target;
            String reason;
            if (refused || highRisk || confidence < autoResolveThreshold || !citationOk) {
                target = TicketStatus.PENDING_HUMAN;
                reason = refuseReason(refused, highRisk, confidence, citationOk);
            } else {
                target = TicketStatus.AUTO_RESOLVED;
                reason = "置信度 %.3f ≥ 阈值 %.2f，引用有效，自动结单"
                        .formatted(confidence, autoResolveThreshold);
            }
            tickets.appendNote(t.id(), "AGENT",
                    "判定: confidence=%.3f evidence=%.3f selfConf=%.3f citationsValid=%s → %s（%s）"
                            .formatted(confidence, evidenceScore, selfConfidence, citationOk, target, reason));
            return tickets.transition(t.id(), legalFrom, target,
                    target == TicketStatus.AUTO_RESOLVED ? "AGENT" : null);
        } catch (TicketStatus.IllegalTransitionException e) {
            // 状态被人抢先改过：这不是失败，不该进重试通道（重试也改不动状态）
            tickets.appendNote(t.id(), "AGENT", "并发让路: " + e.getMessage());
            return tickets.findById(t.id()).orElseThrow();
        } catch (Exception e) {
            long nextRetryAt = System.currentTimeMillis() + backoffMillis(t.attempts() + 1);
            tickets.recordFailure(t.id(), rootMessage(e), nextRetryAt);
            tickets.appendNote(t.id(), "AGENT",
                    "处理失败(attempts=%d): %s；下次重试 %dms 后"
                            .formatted(t.attempts() + 1, rootMessage(e), nextRetryAt - System.currentTimeMillis()));
            return tickets.findById(t.id()).orElseThrow();
        }
    }

    /** kb_retrieve 返回逐块分数表；取最佳向量分作为"证据强度"——与 rag-kb 拒答阈值同口径 */
    private double retrieveEvidenceScore(String ticketId, String query) {
        JsonNode rows = kb.retrieveJson(query);
        double best = 0;
        for (JsonNode row : rows) {
            best = Math.max(best, row.path("vectorScore").asDouble(0));
        }
        tickets.appendNote(ticketId, "AGENT", "检索证据: 召回 %d 块，最佳向量分 %.3f".formatted(rows.size(), best));
        return best;
    }

    /**
     * 引用有效性：与 rag-kb 评测机 {@code checkCitations} 逐条同判据——
     * 非拒答复必须<b>至少有一个 [n]</b>，且每个编号都落在 kb_query 返回的 citations 范围内。
     * 线上判定和离线评测两套口径的话，评测分数就没有资格预测线上行为。
     * 拒答在这里返回 false：它没有政策依据，不该白拿 0.3 的忠实度分项。
     */
    private boolean citationsValid(String reply, String citationsJson, boolean refused) {
        if (refused) {
            return false;
        }
        int citationCount;
        try {
            JsonNode arr = mapper.readTree(citationsJson);
            citationCount = arr.isArray() ? arr.size() : 0;
        } catch (Exception e) {
            return false;
        }
        if (citationCount == 0) {
            return false;
        }
        Matcher m = CITE.matcher(reply == null ? "" : reply);
        boolean any = false;
        while (m.find()) {
            any = true;
            int no = Integer.parseInt(m.group(1));
            if (no < 1 || no > citationCount) {
                return false;
            }
        }
        return any;
    }

    /** 指数退避：base * 2^(attempts-1)，封顶 cap。第 1/2/3 次 = 2s/4s/8s（base=2000） */
    public long backoffMillis(int attempts) {
        int n = Math.max(1, attempts);
        long delay = backoffBaseMs * (1L << Math.min(n - 1, 20));
        return Math.min(delay, backoffCapMs);
    }

    public boolean isHighRisk(String category) {
        if (category == null) {
            return false;
        }
        return Arrays.stream(highRiskCategories.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .anyMatch(s -> s.equalsIgnoreCase(category));
    }

    private String refuseReason(boolean refused, boolean highRisk, double confidence, boolean citationOk) {
        if (refused) {
            return "知识库拒答，无政策依据";
        }
        if (highRisk) {
            return "高风险类别，业务红线不允许自动结单";
        }
        if (!citationOk) {
            return "引用校验未通过（缺引用或编号越界）";
        }
        return "置信度 %.3f < 阈值 %.2f".formatted(confidence, autoResolveThreshold);
    }

    /** 人工审核（HITL 的落点）：批准即结单，驳回带线索重开 */
    public Ticket review(String ticketId, Decision decision, String note) {
        Ticket t = tickets.findById(ticketId).orElseThrow();
        if (decision == Decision.APPROVE) {
            Ticket resolved = tickets.transition(ticketId, t.status(), TicketStatus.AUTO_RESOLVED, "HUMAN");
            tickets.appendNote(ticketId, "HUMAN", "批准: " + (note == null ? "" : note));
            return resolved;
        }
        if (t.status() != TicketStatus.PENDING_HUMAN) {
            throw new IllegalStateException("驳回只适用于 PENDING_HUMAN，当前 " + t.status());
        }
        Ticket reopened = tickets.transition(ticketId, TicketStatus.PENDING_HUMAN, TicketStatus.NEW, null);
        tickets.resetForRerun(reopened.id());
        tickets.appendNote(ticketId, "HUMAN", "驳回并补充线索: " + (note == null ? "" : note));
        // 驳回不是终点：带着人的线索立刻重跑，这才是"人在回路"而不是"人接管"
        return process(reopened.id());
    }

    /** 审核轨迹 */
    public List<Ticket.Note> notes(String ticketId) {
        return tickets.notes(ticketId);
    }

    public Map<String, Object> view(Ticket t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("title", t.title());
        m.put("description", t.description());
        m.put("category", t.category());
        m.put("priority", t.priority());
        m.put("status", t.status());
        m.put("suggestedReply", t.suggestedReply());
        m.put("confidence", t.confidence());
        m.put("citations", parseOrRaw(t.citationsJson()));
        m.put("attempts", t.attempts());
        m.put("lastError", t.lastError());
        m.put("nextRetryAt", t.nextRetryAt());
        m.put("resolvedBy", t.resolvedBy());
        m.put("conversationId", t.conversationId());
        return m;
    }

    private Object parseOrRaw(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    public enum Decision {
        APPROVE, REJECT
    }
}
