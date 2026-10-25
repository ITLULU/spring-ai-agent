package com.example.ragkb.qa;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对外问答响应。refused=true 表示触发拒答；citations 为引用溯源列表。
 * promptTokens/completionTokens 供"效果-成本"叙事记账（《方案》§3.1 tokens 字段）；
 * -1 表示模型未返回 usage。注意：查询 embedding 的 token 发生在 VectorStore 内部，
 * 此处不可见——成本口径要讲清楚边界。
 * <p>延迟口径（QA-01）：elapsedMs = 检索段 + 生成段，是线上端到端时延；
 * retrievalMs 单独暴露，用于判断"慢在检索还是慢在生成"。拒答时生成段为 0，
 * elapsedMs == retrievalMs。评测链路的 latencyMs 由 EvalService 自己掐表（含 Judge），
 * 两者口径不同不要混用。
 */
public record QueryResponse(
        String answer,
        boolean refused,
        List<Citation> citations,
        long elapsedMs,
        long retrievalMs,
        int promptTokens,
        int completionTokens) {

    /**
     * 固定拒答语——全局唯一定义处：防线 1（检索层）、防线 2（提示层要求原样输出）、
     * 拒答识别（startsWith）与裁判短路全部引用本常量，改文案只改这一处，
     * 不存在"提示词让模型输出 A、代码在比 B"的双口径漂移。
     */
    public static final String REFUSAL = "无法回答";

    /** 答案里的引用标记：[1]、[2]… */
    private static final Pattern CITE = Pattern.compile("\\[(\\d+)]");

    /**
     * 引用后验（幻觉治理的第一道机器校验）：非拒答的答案必须至少带一个 [n]，
     * 且所有编号都落在本次给出的引用清单范围内。
     * <p>拒答在这里算 <b>true</b>——它是评测口径"这条回答没有引用问题"，
     * 不是"这条回答可以放行"。阶段 2 的 kb-agent 把同样的判据用作自动结单闸门时
     * 拒答记 false（闸门要的是"能不能自己结单"），两处语义不同是刻意的，不要互相"修正"。
     */
    public boolean citationValid() {
        if (refused || answer == null) {
            return true;
        }
        if (citations.isEmpty()) {
            return false;
        }
        Matcher m = CITE.matcher(answer);
        boolean any = false;
        while (m.find()) {
            any = true;
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > citations.size()) {
                return false;
            }
        }
        return any;
    }

    /**
     * 一条可回查的引用：no 对应提示词里的 [n]，chunkId 可回 ChunkStore 取完整原文，
     * snippet 取子块 content 前 120 字——忠实度评测（答案句是否真被引用块支撑）
     * 的校验锚点就是它。
     */
    public record Citation(int no, String chunkId, String source, String snippet) {
    }
}
