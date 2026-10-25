package com.example.kbagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.kbagent.agent.KbMcpGateway;
import com.example.kbagent.agent.TicketBrain;
import com.example.kbagent.domain.Ticket;
import com.example.kbagent.domain.TicketStatus;
import com.example.kbagent.store.TicketRepository;
import com.example.kbagent.testsupport.FreshH2;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 可控工作流的行为锁：<b>存储用真 H2，模型与 MCP 用桩</b>。
 * 理由和 rag-kb 评测机一致——要验的是"判定与流转"这段确定性代码，
 * 把它依赖的两个不确定源（LLM、远程工具）冻起来，断言才只有一种可能解释。
 *
 * 锁住的关键立场：
 * ① 自动结单权在确定性闸门手里（证据 0.5 + 引用 0.3 + 自评 0.2），不在模型自评手里；
 * ② 高风险类别是业务红线，分数再高也 PENDING_HUMAN；
 * ③ 引用判据与 rag-kb 评测机 checkCitations 完全对齐（缺引用或编号越界即无效）；
 * ④ 只有工具层异常才进重试通道；人工抢先改状态是"并发让路"，不烧重试额度；
 * ⑤ 驳回带线索立刻重跑，人在回路 ≠ 人接管。
 */
class TicketWorkflowTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private TicketRepository repo;
    private TicketBrain brain;
    private KbMcpGateway kb;
    private TicketWorkflow workflow;

    @BeforeEach
    void setUp() {
        repo = new TicketRepository(FreshH2.dataSource());
        brain = mock(TicketBrain.class);
        kb = mock(KbMcpGateway.class);
        workflow = new TicketWorkflow(repo, brain, kb);
        // @Value 字段手工 new 出来是 Java 默认值（0/null），必须按 application.yml 的口径注入，
        // 否则测的是"阈值 0、权重 0"这条线上不存在的路径
        ReflectionTestUtils.setField(workflow, "autoResolveThreshold", 0.75);
        ReflectionTestUtils.setField(workflow, "weightEvidence", 0.5);
        ReflectionTestUtils.setField(workflow, "weightCitation", 0.3);
        ReflectionTestUtils.setField(workflow, "weightSelfReport", 0.2);
        ReflectionTestUtils.setField(workflow, "maxAttempts", 3);
        ReflectionTestUtils.setField(workflow, "backoffBaseMs", 2000L);
        ReflectionTestUtils.setField(workflow, "backoffCapMs", 60000L);
        ReflectionTestUtils.setField(workflow, "highRiskCategories", "payroll,dismissal,compliance");
    }

    // ---- 桩数据 ------------------------------------------------------------
    // 统一用 doReturn 形式：同一个桩在一段测试里会被"先抛异常、后返回正常"覆盖，
    // when(...) 会真的调用一次已被配置成抛异常的桩方法，测试还没跑就红了

    private void givenClassify(String category, String priority, String searchQuery) {
        doReturn(new TicketBrain.Classification(category, priority, searchQuery))
                .when(brain).classify(anyString(), anyString());
    }

    private void givenEvidence(double bestVectorScore) {
        doReturn(node("[{\"chunkId\":\"c-1\",\"source\":\"attendance_policy.md\",\"vectorScore\":"
                + bestVectorScore + ",\"bm25Score\":7.4,\"fusedScore\":0.032,\"evidenceHit\":true}]"))
                .when(kb).retrieveJson(anyString());
    }

    private void givenKbAnswer(String answer, boolean refused, int citationCount) {
        StringBuilder cites = new StringBuilder();
        for (int i = 1; i <= citationCount; i++) {
            cites.append(i > 1 ? "," : "").append("{\"no\":").append(i)
                    .append(",\"chunkId\":\"c-").append(i).append("\",\"source\":\"policy.md\"}");
        }
        doReturn(node("{\"answer\":" + quote(answer) + ",\"refused\":" + refused
                + ",\"citations\":[" + cites + "]}")).when(kb).queryJson(anyString(), any());
    }

    private void givenDraft(String reply, double selfConfidence) {
        doReturn(new TicketBrain.Draft(reply, true, selfConfidence))
                .when(brain).draft(anyString(), anyString(), anyString());
    }

    private void givenToolFailure(String message) {
        doThrow(new KbMcpGateway.ToolUnavailableException(message))
                .when(kb).retrieveJson(anyString());
    }

    /** 证据足、引用齐、自评高：闸门应当放行 AUTO_RESOLVED */
    private void givenHappyPath() {
        givenClassify("hr_policy", "P2", "入职满3年带薪年假几天");
        givenEvidence(0.9);
        givenKbAnswer("满 3 年享有 10 天。[1]", false, 1);
        givenDraft("按公司考勤制度，入职满 3 年享有 10 天带薪年假 [1]", 0.9);
    }

    /** 走到 PENDING_HUMAN 的一套桩：知识库拒答 */
    private void givenRefusedPath() {
        givenClassify("it_support", "P3", "公司几点上班");
        doReturn(node("[]")).when(kb).retrieveJson(anyString());
        givenKbAnswer("无法回答", true, 0);
        givenDraft("知识库暂无相关政策，需人工确认", 0.4);
    }

    private JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private List<Ticket.Note> notes(String ticketId) {
        return repo.notes(ticketId);
    }

    private boolean anyNote(String ticketId, String fragment) {
        return notes(ticketId).stream().anyMatch(n -> n.body().contains(fragment));
    }

    // ---- ① 置信闸门：三项加权可复算 ---------------------------------------

    @Test
    void autoResolveNeedsEvidenceAndCitationNotJustModelSelfReport() {
        givenHappyPath();

        Ticket t = workflow.accept("年假咨询", "入职满3年有几天年假", "hr_policy", "P2");

        assertEquals(TicketStatus.AUTO_RESOLVED, t.status());
        // 0.5*0.9 + 0.3*1 + 0.2*0.9 = 0.93
        assertEquals(0.93, t.confidence(), 1e-6, "置信度必须是三项加权的算术结果，评审能手工复算");
        assertEquals("AGENT", t.resolvedBy());
        assertEquals("入职满3年有几天年假", t.description(), "窄更新不许动原文");
        assertNotNull(t.citationsJson(), "引用快照要落库，审核界面不必回查知识库");

        assertTrue(anyNote(t.id(), "分诊: category=hr_policy"));
        assertTrue(anyNote(t.id(), "检索证据: 召回 1 块，最佳向量分 0.900"));
        assertTrue(anyNote(t.id(), "判定: confidence=0.930"));
    }

    @Test
    void weakEvidenceSendsTicketToHumanEvenWithPerfectSelfReport() {
        givenClassify("hr_policy", "P2", "VPN 认证失败怎么办");
        givenEvidence(0.2); // 检索层没有硬证据
        givenKbAnswer("建议联系 IT。[1]", false, 1);
        givenDraft("请联系 IT 同事协助处理 [1]", 1.0); // 模型自评满分

        Ticket t = workflow.accept("VPN 连不上", "今早 VPN 认证失败", "hr_policy", "P2");

        // 0.5*0.2 + 0.3*1 + 0.2*1.0 = 0.6 < 0.75
        assertEquals(TicketStatus.PENDING_HUMAN, t.status());
        assertEquals(0.6, t.confidence(), 1e-6);
        assertNull(t.resolvedBy(), "没结单就不该有结单人");
        assertTrue(anyNote(t.id(), "置信度 0.600 < 阈值 0.75"),
                "转人工的理由要写进轨迹，否则人工只能猜 Agent 在想什么");
    }

    @Test
    void highRiskCategoryIsRedLineRegardlessOfScore() {
        givenClassify("payroll", "P1", "工资为什么少发了");
        givenEvidence(1.0);
        givenKbAnswer("按薪酬制度核算。[1]", false, 1);
        givenDraft("本月个税专项扣除调整导致实发变化 [1]", 1.0);

        Ticket t = workflow.accept("工资异常", "这个月工资比上月少了 800", "payroll", "P1");

        assertEquals(1.0, t.confidence(), 1e-9, "分数是满的");
        assertEquals(TicketStatus.PENDING_HUMAN, t.status(), "钱相关的口径错不起，红线高于分数");
        assertTrue(anyNote(t.id(), "高风险类别，业务红线不允许自动结单"));
    }

    @Test
    void refusedKnowledgeBaseNeverAutoResolves() {
        givenRefusedPath();

        Ticket t = workflow.accept("上班时间", "公司几点上班", "it_support", "P3");

        assertEquals(TicketStatus.PENDING_HUMAN, t.status());
        assertTrue(anyNote(t.id(), "知识库拒答，无政策依据"));
    }

    // ---- ③ 引用判据与 rag-kb 评测机对齐 -----------------------------------

    @Test
    void outOfRangeCitationIsTreatedAsInvalid() {
        givenClassify("hr_policy", "P2", "加班调休规则");
        givenEvidence(1.0);
        givenKbAnswer("加班可调休。[1][2]", false, 2);
        givenDraft("加班可以申请调休，详见制度 [7]", 1.0); // [7] 越界

        Ticket t = workflow.accept("调休", "加班怎么调休", "hr_policy", "P2");

        assertEquals(TicketStatus.PENDING_HUMAN, t.status(), "编号越界就是幻觉信号，不能自动结单");
        assertEquals(0.7, t.confidence(), 1e-6, "证据满分但引用项清零: 0.5*1.0 + 0 + 0.2*1.0");
        assertTrue(anyNote(t.id(), "引用校验未通过（缺引用或编号越界）"));
    }

    @Test
    void replyWithoutAnyCitationIsInvalid() {
        givenClassify("hr_policy", "P2", "年假几天");
        givenEvidence(1.0);
        givenKbAnswer("满 3 年 10 天。[1]", false, 1);
        givenDraft("满 3 年有 10 天年假。", 1.0); // 装配时把引用弄丢了

        Ticket t = workflow.accept("年假", "入职满3年几天年假", "hr_policy", "P2");

        // rag-kb checkCitations 要求"非拒答必须至少一个 [n]"，这里同判据 → 不可自动结单
        assertEquals(TicketStatus.PENDING_HUMAN, t.status());
        assertEquals(0.7, t.confidence(), 1e-6);
    }

    // ---- ④ 失败、退避与重试 ------------------------------------------------

    @Test
    void toolFailureLandsFailedWithBackoffTimestampInsteadOfThrowing() {
        givenClassify("it_support", "P2", "打印机脱机");
        givenToolFailure("调用 MCP 工具 kb_retrieve 失败: connection refused");

        Ticket t = workflow.accept("打印机", "打印机脱机了", "it_support", "P2");

        assertEquals(TicketStatus.FAILED, t.status(),
                "accept 不把异常抛给调用方：单已受理，处理失败要留痕、可重试");
        assertEquals(1, t.attempts());
        long delay = t.nextRetryAt() - System.currentTimeMillis();
        assertTrue(delay > 1500 && delay <= 2000, "第 1 次失败退避 2s，实际 " + delay + "ms");
        assertTrue(t.lastError().contains("connection refused"));
        assertTrue(anyNote(t.id(), "处理失败(attempts=1)"));

        assertEquals(2000L, workflow.backoffMillis(1));
        assertEquals(4000L, workflow.backoffMillis(2));
        assertEquals(8000L, workflow.backoffMillis(3));
        assertEquals(60000L, workflow.backoffMillis(9), "指数退避必须封顶，否则一次抖动排到天荒地老");
    }

    @Test
    void retryRunsPipelineAgainFromRetrying() {
        givenClassify("it_support", "P2", "打印机脱机");
        givenToolFailure("rag-kb 未启动");
        Ticket t = workflow.accept("打印机", "打印机脱机了", "it_support", "P2");
        assertEquals(TicketStatus.FAILED, t.status());

        givenEvidence(0.9);
        givenKbAnswer("重启打印机后重试。[1]", false, 1);
        givenDraft("请先重启打印机电源，再重新发送任务 [1]", 0.9);

        Ticket retried = workflow.retry(t.id());

        assertEquals(TicketStatus.AUTO_RESOLVED, retried.status());
        assertEquals(1, retried.attempts(), "重试成功不清失败计数——它是审计事实");
        assertEquals("AGENT", retried.resolvedBy());
        assertNotNull(retried.suggestedReply());
    }

    @Test
    void exhaustedAttemptsHandOverToHumanInsteadOfBurningForever() {
        Ticket t = repo.insert("打印机", "打印机脱机了", "it_support", "P2");
        for (int i = 0; i < 3; i++) {
            repo.recordFailure(t.id(), "mcp down", System.currentTimeMillis() - 1);
        }

        Ticket given = workflow.retry(t.id());

        assertEquals(TicketStatus.PENDING_HUMAN, given.status());
        assertEquals("RETRY_EXHAUSTED", given.resolvedBy());
        verify(brain, never()).classify(anyString(), anyString());
        assertTrue(anyNote(t.id(), "放弃重试: attempts=3 已达上限 3"));
    }

    /**
     * 上面那条测的是"人工点了重试"，本条测"没人点"。
     * 双进程实跑（rag-kb:8000 + kb-agent:8001，无 key → 每次调用 401）抓到的坑：
     * findRetryable 的 attempts < max 让最后一轮失败之后没有任何驱动者会再碰这单，
     * 于是它带着 next_retry_at 一直躺在 FAILED，"重试耗尽转人工"这条承诺在自动链路上落空。
     * 这里用真 workflow + 真 RetryScheduler 跑一遍扫描，把自动链路的那条路径钉住。
     */
    @Test
    void sweepAloneHandsExhaustedTicketsToHumanWithoutAnyoneClickingRetry() {
        Ticket t = repo.insert("打印机", "打印机脱机了", "it_support", "P2");
        for (int i = 0; i < 3; i++) {
            repo.recordFailure(t.id(), "mcp down", System.currentTimeMillis() - 1);
        }
        RetryScheduler scheduler = new RetryScheduler(repo, workflow);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "maxAttempts", 3);

        scheduler.scan();

        Ticket after = repo.findById(t.id()).orElseThrow();
        assertEquals(TicketStatus.PENDING_HUMAN, after.status(),
                "扫描一轮就要把耗尽单送到人工面前，否则它等于从队列里消失了");
        assertEquals("RETRY_EXHAUSTED", after.resolvedBy());
        verify(brain, never()).classify(anyString(), anyString());
        assertTrue(anyNote(t.id(), "放弃重试: attempts=3 已达上限 3"));

        scheduler.scan();
        assertEquals(TicketStatus.PENDING_HUMAN, repo.findById(t.id()).orElseThrow().status(),
                "交完人工就不再被捞：状态即去重标记，重复扫描幂等");
    }

    @Test
    void onlyFailedTicketsAreRetryable() {
        Ticket t = repo.insert("门禁", "卡丢了", null, null);

        assertThrows(IllegalStateException.class, () -> workflow.retry(t.id()));
    }

    @Test
    void humanChangedStatusFirstSoAgentStepsAsideWithoutBurningRetry() {
        Ticket t = repo.insert("年假咨询", "入职满3年有几天年假", "hr_policy", "P2");
        givenEvidence(0.9);
        givenKbAnswer("满 3 年 10 天。[1]", false, 1);
        givenDraft("满 3 年享 10 天年假 [1]", 0.9);
        // 分诊跑完后，另一个执行体（人工/另一实例）先把这单结掉了
        doAnswer(inv -> {
            repo.transition(t.id(), TicketStatus.NEW, TicketStatus.AUTO_RESOLVED, "HUMAN");
            return new TicketBrain.Classification("hr_policy", "P2", "年假几天");
        }).when(brain).classify(anyString(), anyString());

        Ticket after = workflow.process(t.id());

        assertEquals(TicketStatus.AUTO_RESOLVED, after.status());
        assertEquals("HUMAN", after.resolvedBy(), "CAS 让路后不能覆盖别人的结单人");
        assertEquals(0, after.attempts(), "并发让路不是失败，不该进重试通道");
        assertTrue(anyNote(t.id(), "并发让路:"));
    }

    // ---- ⑤ HITL ------------------------------------------------------------

    @Test
    void approveClosesTicketWithHumanAttribution() {
        givenRefusedPath();
        Ticket t = workflow.accept("上班时间", "公司几点上班", "it_support", "P3");
        assertEquals(TicketStatus.PENDING_HUMAN, t.status());

        Ticket approved = workflow.review(t.id(), TicketWorkflow.Decision.APPROVE, "口径可以，采纳");

        assertEquals(TicketStatus.AUTO_RESOLVED, approved.status());
        assertEquals("HUMAN", approved.resolvedBy());
        assertTrue(notes(t.id()).stream().anyMatch(n ->
                n.author().equals("HUMAN") && n.body().contains("批准: 口径可以，采纳")));
    }

    @Test
    void rejectReopensWithClueAndImmediatelyReRunsPipeline() {
        Ticket pending = repo.insert("差旅报销", "打车票怎么贴", "finance", "P2");
        repo.saveDraft(pending.id(), "旧答复 [1]", 0.4, "[{\"no\":1}]");
        repo.transition(pending.id(), TicketStatus.NEW, TicketStatus.PENDING_HUMAN, null);
        givenHappyPath();

        Ticket after = workflow.review(pending.id(), TicketWorkflow.Decision.REJECT, "补充：说清在哪个系统贴票");

        assertEquals(TicketStatus.AUTO_RESOLVED, after.status(), "驳回不是终点，带线索重跑才是人在回路");
        assertEquals("按公司考勤制度，入职满 3 年享有 10 天带薪年假 [1]", after.suggestedReply(),
                "旧答复必须被新结论覆盖，而不是留在库里误导复核");
        assertEquals(0.93, after.confidence(), 1e-6);
        assertTrue(notes(pending.id()).stream().anyMatch(n ->
                n.author().equals("HUMAN") && n.body().contains("驳回并补充线索")));
    }

    @Test
    void rejectOnlyAppliesToPendingHuman() {
        Ticket t = repo.insert("门禁", "卡丢了", null, null);

        assertThrows(IllegalStateException.class,
                () -> workflow.review(t.id(), TicketWorkflow.Decision.REJECT, "不行"));
    }

    // ---- 会话与视图 --------------------------------------------------------

    @Test
    void pipelineFeedsTicketConversationIdIntoKbQuery() {
        givenHappyPath();

        Ticket t = workflow.accept("年假咨询", "入职满3年有几天年假", "hr_policy", "P2");

        assertEquals(t.id(), t.conversationId(), "工单默认用自己的会话 ID");
        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cid = ArgumentCaptor.forClass(String.class);
        verify(kb).queryJson(question.capture(), cid.capture());
        assertEquals("入职满3年带薪年假几天", question.getValue(), "送给知识库的是分诊改写后的检索问句，不是原始诉求");
        assertEquals(t.id(), cid.getValue(), "追问接在同一条会话线上才能力达上下文");
    }

    @Test
    void viewExposesEverythingReviewersNeed() {
        givenHappyPath();
        Ticket t = workflow.accept("年假咨询", "入职满3年有几天年假", "hr_policy", "P2");

        var v = workflow.view(t);

        assertEquals(t.id(), v.get("id"));
        assertEquals(TicketStatus.AUTO_RESOLVED, v.get("status"));
        assertTrue(v.get("citations") instanceof List<?>, "引用解析成结构化字段，别把 JSON 字符串甩给前端");
        assertEquals(t.id(), v.get("conversationId"));
        assertTrue(workflow.isHighRisk("Payroll"), "类别匹配大小写不敏感");
        assertTrue(!workflow.isHighRisk("hr_policy"));
        assertTrue(!workflow.isHighRisk(null));
    }
}
