package com.example.ragkb.qa;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.example.ragkb.retrieval.HybridRetrievalService;
import com.example.ragkb.retrieval.RetrievedChunk;

/**
 * 生成 + 幻觉治理（引用溯源、拒答机制）。
 * 两道防线：
 *  1) 检索层拒答：最佳向量相似度低于阈值 → 直接返回"无法回答"，不调 LLM
 *     （省一次生成的 token 成本，也避免模型强答）；
 *  2) 提示层约束：只允许基于编号上下文作答、每句结论必须带 [n] 引用、
 *     上下文不足时必须输出固定拒答语。
 * 面试点：引用为什么用编号而不是让模型复述原文——可校验（后验检查 [n] 是否越界、
 * 引用块是否真的支撑结论），能做自动化忠实度评测。
 */
@Service
public class QaService {

    private static final String REFUSAL = "无法回答";

    private final HybridRetrievalService retrieval;
    private final ChatModel chatModel;

    @Value("${rag.retrieval.min-vector-similarity:0.30}")
    private double minVectorSimilarity;

    // @Lazy：MCP Server 的 ToolCallbackProvider 链路会回指 openAiChatModel，
    // 在 ChatModel 注入点断环（构造只存代理，首次 call 时解析真实 bean）
    public QaService(HybridRetrievalService retrieval, @Lazy ChatModel chatModel) {
        this.retrieval = retrieval;
        this.chatModel = chatModel;
    }

    public QueryResponse answer(String question) {
        return answer(question, retrieval.retrieve(question));
    }

    /**
     * 评测专用重载：接受预取检索结果，避免 eval 为拿检索快照而重复调用 embedding。
     * 线上链路（answer(question)）行为不变。
     */
    public QueryResponse answer(String question, List<RetrievedChunk> chunks) {
        long start = System.currentTimeMillis();

        // 防线 1：检索层低置信拒答
        if (chunks.isEmpty() || retrieval.bestVectorScore(chunks) < minVectorSimilarity) {
            return new QueryResponse(REFUSAL, true, List.of(), System.currentTimeMillis() - start, 0, 0);
        }

        // 组装编号上下文
        StringBuilder context = new StringBuilder();
        List<QueryResponse.Citation> citations = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            int no = i + 1;
            context.append("[%d] (来源: %s)\n%s\n\n".formatted(no, c.source(), c.generationContent()));
            String snippet = c.content().length() > 120 ? c.content().substring(0, 120) + "…" : c.content();
            citations.add(new QueryResponse.Citation(no, c.chunkId(), c.source(), snippet));
        }

        String system = """
                你是企业知识库助手。规则：
                1. 只依据下面提供的编号上下文回答问题，禁止使用上下文之外的知识；
                2. 每个结论句末必须标注支撑它的上下文编号，如 [1] 或 [2][3]；
                3. 如果上下文不足以支撑一个可靠的回答，只输出四个字：无法回答；
                4. 回答用中文，简洁分点。
                """;
        String user = "上下文：\n" + context + "\n问题：" + question;

        ChatResponse chatResponse = chatModel.call(
                new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
        String raw = chatResponse.getResult().getOutput().getText();
        Usage usage = chatResponse.getMetadata() == null ? null : chatResponse.getMetadata().getUsage();
        int promptTokens = usage == null || usage.getPromptTokens() == null ? -1 : usage.getPromptTokens();
        int completionTokens = usage == null || usage.getCompletionTokens() == null ? -1 : usage.getCompletionTokens();

        boolean refused = raw == null || raw.trim().startsWith(REFUSAL);
        return new QueryResponse(raw, refused, refused ? List.of() : citations,
                System.currentTimeMillis() - start, promptTokens, completionTokens);
    }
}
