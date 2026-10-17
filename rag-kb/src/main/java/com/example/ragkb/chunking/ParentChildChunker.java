package com.example.ragkb.chunking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parent-Child（小块检索、大块生成）切块。
 * 动机：检索希望块小——语义聚焦，向量相似度不会被无关内容稀释；
 *       生成希望块大——LLM 需要完整上下文才能给出正确结论。
 * 实现：parent 按段落聚合（至 maxParentChars），child 在 parent 内按 childChars 切；
 *       只有 child 进向量库/BM25，child 的 metadata 携带 parentId 与 parentContent，
 *       检索命中 child 后回取 parent 文本喂给 LLM。
 * 面试点：这就是 LlamaIndex 的 Small-to-Toy / auto-retrieval 思路的最小实现。
 */
@Component
public class ParentChildChunker implements ChunkingStrategy {

    /** 父块上限字符数：段落贪心聚合到此长度封块（rag.chunk.parent-size） */
    @Value("${rag.chunk.parent-size:1200}")
    private int maxParentChars;

    /** 子块固定长度：父块内硬切（不再递归），子块是检索单元，短=聚焦 */
    @Value("${rag.chunk.child-size:300}")
    private int childChars;

    @Override
    public String name() {
        return "parent-child";
    }

    /**
     * 两阶段切块，返回的只是子块列表——父块不入库、以副本形式挂在每个子块的
     * metadata.parentContent 上（用存储换简单：无需检索时二次回表查父块）。
     * <p>阶段 1·段落聚合出 parent：按空行切段后贪心拼接至 maxParentChars，
     * 边界尽量落在自然段之间（文档结构感）；拼接用 \n\n 回填保持原文段落分隔。
     * <p>阶段 2·父块内硬切 child：固定 childChars 步进、无重叠——故意不如
     * FixedSizeChunker 那样加 overlap：一个事实被拦腰切到两个子块时，
     * 两个子块都指向同一父块，生成时父块全文完整，信息并不丢——
     * 父块机制本身就是 overlap 的替代品（面试对比点）。
     * <p>字段语义提示：Chunk.index 记的是父块序号（非全局块号），同父兄弟子块
     * index 相同；引用展示时靠它判断"两条命中来自同一父块"（重复信息信号）。
     * 代价（已知短处）：parentContent 随每个子块存一份副本，父块多子块时内存
     * 约放大 parentSize/childSize 倍，落库演进（Postgres）时应改成 parent 表 + 外键。
     */
    @Override
    public List<Chunk> split(String source, String text) {
        List<Chunk> children = new ArrayList<>();
        int parentIndex = 0;

        // 1. 按空行聚合成 parent
        List<String> parents = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + p.length() + 2 > maxParentChars) {
                parents.add(current.toString());
                current.setLength(0);
            }
            current.append(current.isEmpty() ? "" : "\n\n").append(p);
        }
        if (!current.isEmpty()) {
            parents.add(current.toString());
        }

        // 2. parent 内切 child，child 携带 parent 全文
        for (String parent : parents) {
            String parentId = UUID.randomUUID().toString();
            for (int start = 0; start < parent.length(); start += childChars) {
                int end = Math.min(parent.length(), start + childChars);
                String childText = parent.substring(start, end).trim();
                if (childText.isEmpty()) {
                    continue;
                }
                Map<String, Object> meta = new HashMap<>();
                meta.put("parentId", parentId);
                meta.put("parentContent", parent);
                children.add(new Chunk(UUID.randomUUID().toString(), childText, source, parentIndex, meta));
            }
            parentIndex++;
        }
        return children;
    }
}
