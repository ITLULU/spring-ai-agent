package com.example.ragkb.qa;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.example.ragkb.retrieval.HybridRetrievalService;
import com.example.ragkb.retrieval.RetrievedChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 生成 + 幻觉治理（引用溯源、拒答机制）+ 会话记忆（AGT-01/AGT-02）。
 * 两道防线：
 *  1) 检索层拒答：最佳向量相似度低于阈值 → 直接返回"无法回答"，不调 LLM
 *     （省一次生成的 token 成本，也避免模型强答）；
 *  2) 提示层约束：只允许基于编号上下文作答、每句结论必须带 [n] 引用、
 *     上下文不足时必须输出固定拒答语（模板外置在 resources/prompts/qa-system.st）。
 * 面试点：引用为什么用编号而不是让模型复述原文——可校验（后验检查 [n] 是否越界、
 * 引用块是否真的支撑结论），能做自动化忠实度评测。
 *
 * <p>AGT-01 改造：不再裸调 {@code ChatModel.call(new Prompt(...))}，改走 ChatClient
 * 抽象层——system 提示词外置成模板资源（改文案不动 Java），记忆经
 * {@link MessageChatMemoryAdvisor} 织入，为阶段 2 工单 Agent 复用同一套装配。
 *
 * <p>AGT-02 的裁剪决策（引用与记忆并存怎么不互相污染）：
 * 检索上下文只进 **system 模板**，user 消息就是那句问题本身，
 * 所以写进会话窗口的是 question/answer 两条，不带上一轮的整坨上下文——
 * 否则窗口会被检索块撑爆、历史上下文还会被模型当成本轮依据（幻觉来源）。
 *
 * <p>记忆是**按请求启用**的：conversationId 为空时不挂 memory advisor，
 * 评测机每题因此仍是无状态独立问答（否则 36 题共享一个会话，
 * 前一题的证据会串进后一题，评测数字直接失真）。
 */
@Service
public class QaService {

    /** 拒答语全局唯一定义在 QueryResponse.REFUSAL，本类不再自设副本 */
    private static final String REFUSAL = QueryResponse.REFUSAL;

    /** SSE 载荷序列化（record/Map → JSON），线程安全可共享 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HybridRetrievalService retrieval;
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final Resource systemTemplate;

    @Value("${rag.retrieval.min-vector-similarity:0.30}")
    private double minVectorSimilarity;

    /**
     * kbChatClient 必须 @Lazy：ChatClient.builder() 在构造 kbChatClient 时就解引用
     * ChatModel 代理，而 openAiChatModel 那条链（toolCallingManager → provider →
     * RagKbTools）会回指本 Bean，两边都延迟到首次真正调用才解析才成环不成。
     */
    public QaService(HybridRetrievalService retrieval, @Lazy ChatClient kbChatClient,
                     ChatMemory chatMemory,
                     @Value("classpath:prompts/qa-system.st") Resource systemTemplate) {
        this.retrieval = retrieval;
        this.chatClient = kbChatClient;
        this.chatMemory = chatMemory;
        this.systemTemplate = systemTemplate;
    }

    /**
     * 线上主入口：检索 + 生成一体。QA-01：检索段在这里掐表，
     * elapsedMs 从"只含生成"修成"含检索"，线上与评测两套延迟数字才能对得上。
     *
     * @param conversationId 可空；非空则启用多轮记忆（同一 ID 内问题可带指代）
     */
    public QueryResponse answer(String question, String conversationId) {
        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> chunks = retrieval.retrieve(question);
        return answer(question, chunks, System.currentTimeMillis() - t0, conversationId);
    }

    /**
     * 评测专用重载：接受预取检索结果与已测得的检索耗时，
     * 避免 eval 为拿检索快照而重复调用 embedding（省一次向量计算）。
     * <p>内部五步（两防线的第二道在这里）：
     * <ol>
     *   <li>防线 1·检索层低置信拒答：候选为空或最佳向量分 &lt; 阈值 → 直接返回拒答，
     *       零 token 成本、零幻觉风险；elapsedMs=retrievalMs（拒答也是一次线上行为）；</li>
     *   <li>编号上下文装配：喂 LLM 的是 generationContent（parent-child 时=父块全文，
     *       保上下文完整），而 citation 里的 snippet 取 content（子块，命中单元）——
     *       检索单元与生成单元分离但引用可回查到子块，忠实度校验的锚点是子块；</li>
     *   <li>提示层约束：system 模板把答案钉死在"只准引用编号上下文"上，
     *       [n] 标注使后验校验（越界/是否真支撑）成为可能；</li>
     *   <li>模型主动拒答识别：输出以"无法回答"开头也归为 refused（防线 2 生效），
     *       并清空 citations——拒答不应带引用；</li>
     *   <li>记账：prompt/completion tokens 从响应 metadata 取，供应商未返回时记 -1
     *       （评测机汇总时把 -1 当未知处理，不能当 0 掩盖问题）。</li>
     * </ol>
     */
    public QueryResponse answer(String question, List<RetrievedChunk> chunks,
                                long retrievalMs, String conversationId) {
        // 防线 1：检索层低置信拒答（不调模型，所以记忆要手动补一轮，
        // 否则下一句"那加班呢"看不到自己被拒过，指代消解直接断链）
        if (chunks.isEmpty() || retrieval.bestVectorScore(chunks) < minVectorSimilarity) {
            rememberTurn(conversationId, question, REFUSAL);
            return new QueryResponse(REFUSAL, true, List.of(), retrievalMs, retrievalMs, 0, 0);
        }

        // 组装编号上下文：[n] + 来源名 + 生成文本；citation 另存子块 snippet 供人工/程序回查
        NumberedContext ctx = numberedContext(chunks);
        List<QueryResponse.Citation> citations = ctx.citations();

        String cid = normalizedConversationId(conversationId);
        long start = System.currentTimeMillis();

        var request = chatClient.prompt()
                // 防线 2 的载体：规则文案在 qa-system.st，上下文经模板变量注入
                .system(s -> s.text(systemTemplate, StandardCharsets.UTF_8).param("context", ctx.systemContext()))
                .user(question);
        if (cid != null) {
            request = request.advisors(a -> a
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .param(ChatMemory.CONVERSATION_ID, cid));
        }

        ChatResponse chatResponse = request.call().chatResponse();
        long generationMs = System.currentTimeMillis() - start;

        String raw = chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null
                ? null : chatResponse.getResult().getOutput().getText();
        Usage usage = chatResponse == null || chatResponse.getMetadata() == null
                ? null : chatResponse.getMetadata().getUsage();
        return toQueryResponse(raw, citations, retrievalMs, generationMs, usage);
    }

    /**
     * 把模型输出收敛成对外契约，同步与流式共用一处——
     * 拒答识别、token 记账的 -1 语义、elapsedMs 口径都只在这里定义一次。
     */
    private QueryResponse toQueryResponse(String raw, List<QueryResponse.Citation> citations,
                                          long retrievalMs, long generationMs, Usage usage) {
        int promptTokens = usage == null || usage.getPromptTokens() == null ? -1 : usage.getPromptTokens();
        int completionTokens = usage == null || usage.getCompletionTokens() == null ? -1 : usage.getCompletionTokens();
        // 模型空响应按拒答处理：answer 落固定拒答语而非 null，
        // 下游（报告/评测/MCP 客户端）拿到的永远是可渲染的字符串，不会见 NPE；
        // 非空但命中拒答前缀的，保留原文（可能带拒答理由，供人工复核）
        boolean refused = raw == null || raw.isBlank() || raw.trim().startsWith(REFUSAL);
        String answer = raw == null || raw.isBlank() ? REFUSAL : raw;
        return new QueryResponse(answer, refused, refused ? List.of() : citations,
                retrievalMs + generationMs, retrievalMs, promptTokens, completionTokens);
    }

    /**
     * API-02 · 流式问答（SSE）：事件序列 {@code meta → delta* → done}，失败时收一条 {@code error}。
     *
     * <p>为什么不是裸 {@code Flux<String>}：本项目答案的价值在"可校验的引用"，只流 token 的话
     * 前端边渲染边不知道有没有出处、编号有没有越界。所以 {@code meta} 先把引用清单
     * （no/chunkId/source/snippet）发出去让 UI 直接挂脚注，{@code done} 再把后验结论
     * （refused / citationValid / 耗时分段 / token）补上——字段与同步 {@code /api/query} 的
     * {@link QueryResponse} 一一对应，两条路径不会出现两套口径。
     *
     * <p>三条与同步路径一致的纪律：
     * <ol>
     *   <li>防线 1（检索层拒答）在开流之前跑完：不该让客户端先收到半句"真话"再被打脸，
     *       而且这种问题一个生成 token 都不花；</li>
     *   <li>记忆仍是**按请求**挂 {@link MessageChatMemoryAdvisor}，conversationId 为空即无状态；</li>
     *   <li>检索是阻塞调用，整条链放在 boundedElastic 上，不要占着 servlet 容器线程等 embedding。</li>
     * </ol>
     */
    public Flux<ServerSentEvent<String>> answerStream(String question, String conversationId) {
        long start = System.currentTimeMillis();
        String cid = normalizedConversationId(conversationId);
        return Flux.<ServerSentEvent<String>>defer(() -> {
            List<RetrievedChunk> chunks = retrieval.retrieve(question);
            long retrievalMs = System.currentTimeMillis() - start;
            if (chunks.isEmpty() || retrieval.bestVectorScore(chunks) < minVectorSimilarity) {
                rememberTurn(cid, question, REFUSAL);
                QueryResponse r = toQueryResponse(REFUSAL, List.of(), retrievalMs, 0, null);
                return Flux.just(sse("meta", Map.of("refused", true, "citations", List.of())),
                        sse("delta", Map.of("text", REFUSAL)),
                        sse("done", donePayload(r)));
            }

            NumberedContext ctx = numberedContext(chunks);
            var spec = chatClient.prompt()
                    .system(s -> s.text(systemTemplate, StandardCharsets.UTF_8).param("context", ctx.systemContext()))
                    .user(question);
            if (cid != null) {
                spec = spec.advisors(a -> a
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .param(ChatMemory.CONVERSATION_ID, cid));
            }

            StringBuilder full = new StringBuilder();
            AtomicReference<Usage> usage = new AtomicReference<>();
            long genStart = System.currentTimeMillis();
            // chatResponse() 而不是 content()：最后一个只带 usage 的分片也要收下，
            // 否则流式的 token 记账永远是 -1，"效果-成本"叙事在流式这条路径上就断了
            Flux<ServerSentEvent<String>> deltas = spec.stream().chatResponse()
                    .mapNotNull(resp -> {
                        if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                            usage.set(resp.getMetadata().getUsage());
                        }
                        String text = resp.getResult() == null || resp.getResult().getOutput() == null
                                ? null : resp.getResult().getOutput().getText();
                        if (text == null || text.isEmpty()) {
                            return null;
                        }
                        full.append(text);
                        return sse("delta", Map.of("text", text));
                    });

            return Flux.concat(
                    Flux.just(sse("meta", Map.of("refused", false, "citationCount", ctx.citations().size(),
                            "citations", ctx.citations()))),
                    deltas,
                    Flux.defer(() -> Flux.just(sse("done",
                            donePayload(toQueryResponse(full.toString(), ctx.citations(), retrievalMs,
                                    System.currentTimeMillis() - genStart, usage.get()))))));
        }).subscribeOn(Schedulers.boundedElastic())
                // 模型侧异常（如 401）要变成一条 error 事件：客户端已经拿到 200 + SSE 头，
                // 这时抛异常只会让连接悄悄断掉，前端看到的是"流没了"而不是"为什么没了"
                .onErrorResume(e -> Flux.just(sse("error", Map.of(
                        "type", e.getClass().getSimpleName(),
                        "message", String.valueOf(e.getMessage())))));
    }

    private Map<String, Object> donePayload(QueryResponse r) {
        return Map.of("refused", r.refused(),
                "citationValid", r.citationValid(),
                "elapsedMs", r.elapsedMs(),
                "retrievalMs", r.retrievalMs(),
                "promptTokens", r.promptTokens(),
                "completionTokens", r.completionTokens(),
                "answer", r.answer());
    }

    /** SSE 事件统一从这里出：payload 一律序列化成 JSON，前端不用猜分隔符 */
    private static ServerSentEvent<String> sse(String event, Object payload) {
        try {
            return ServerSentEvent.<String>builder(JSON.writeValueAsString(payload)).event(event).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSE 载荷序列化失败: " + event, e);
        }
    }

    /** 编号上下文：喂模型的文本与引用清单一处产出，同步/流式共用，口径不分叉 */
    private record NumberedContext(String systemContext, List<QueryResponse.Citation> citations) {
    }

    private static NumberedContext numberedContext(List<RetrievedChunk> chunks) {
        StringBuilder context = new StringBuilder();
        List<QueryResponse.Citation> citations = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            int no = i + 1;
            context.append("[%d] (来源: %s)\n%s\n\n".formatted(no, c.source(), c.generationContent()));
            String snippet = c.content().length() > 120 ? c.content().substring(0, 120) + "…" : c.content();
            citations.add(new QueryResponse.Citation(no, c.chunkId(), c.source(), snippet));
        }
        return new NumberedContext(context.toString(), citations);
    }

    /** 会话历史（多轮可追溯的验收面）：只含 question/answer，不含检索上下文 */
    public List<Message> session(String conversationId) {
        String cid = normalizedConversationId(conversationId);
        return cid == null ? List.of() : chatMemory.get(cid);
    }

    /** 显式清空会话：内存模式下这是唯一的"重置上下文"手段（重启进程亦可） */
    public void clearSession(String conversationId) {
        String cid = normalizedConversationId(conversationId);
        if (cid != null) {
            chatMemory.clear(cid);
        }
    }

    /**
     * 防线 1 拒答轮手动入记忆。正常生成轮由 MessageChatMemoryAdvisor 自动写入，
     * 这里不重复加，否则同一问句在窗口里出现两次、窗口提前把有效轮次挤掉。
     */
    private void rememberTurn(String conversationId, String question, String answer) {
        String cid = normalizedConversationId(conversationId);
        if (cid == null) {
            return;
        }
        chatMemory.add(cid, List.of(new UserMessage(question), new AssistantMessage(answer)));
    }

    /** 空串/空白按"无会话"处理：与评测链路的 null 走同一条无状态分支 */
    private static String normalizedConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank() ? null : conversationId.trim();
    }
}
