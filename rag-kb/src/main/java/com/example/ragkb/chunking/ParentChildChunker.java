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

    @Value("${rag.chunk.parent-size:1200}")
    private int maxParentChars;

    @Value("${rag.chunk.child-size:300}")
    private int childChars;

    @Override
    public String name() {
        return "parent-child";
    }

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
