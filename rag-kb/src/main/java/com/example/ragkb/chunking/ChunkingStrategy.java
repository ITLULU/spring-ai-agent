package com.example.ragkb.chunking;

import java.util.List;

/**
 * 切块策略接口 —— 阶段1深度点：固定长度 vs 递归字符 vs parent-child。
 * 面试要能讲清每种策略的失败模式：
 *  - 固定长度：可能把一句话/一个表格截断，语义割裂；
 *  - 递归字符：按分隔符优先级回退，块边界更贴合自然结构；
 *  - parent-child：用小块（精准命中）检索、用大块（上下文完整）喂给 LLM，
 *    代价是检索单元与生成单元分离，状态管理复杂。
 */
public interface ChunkingStrategy {

    String name();

    List<Chunk> split(String source, String text);
}
