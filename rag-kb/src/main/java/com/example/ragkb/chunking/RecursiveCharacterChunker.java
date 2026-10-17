package com.example.ragkb.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 递归字符切块（LangChain RecursiveCharacterTextSplitter 的等价实现）。
 * 思路：按分隔符优先级 ["\n\n", "\n", "。", "；", ".", ";", " ", ""] 依次尝试拆分，
 * 能把块边界落在自然段落/句子边界上就绝不硬切；切不动才降级到更小的分隔符。
 * 与固定长度的对比结论（面试可讲）：中文文档上递归切块的 context recall 通常更高，
 * 因为句子完整性直接影响 embedding 质量。
 */
@Component
public class RecursiveCharacterChunker implements ChunkingStrategy {

    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "；", ".", ";", " ", ""};

    @Value("${rag.chunk.size:500}")
    private int size;

    @Override
    public String name() {
        return "recursive";
    }

    @Override
    public List<Chunk> split(String source, String text) {
        List<String> pieces = new ArrayList<>();
        recursiveSplit(text, 0, pieces);

        // 相邻小片段合并到接近 size，避免产生大量碎片
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int index = 0;
        for (String piece : pieces) {
            if (buffer.length() > 0 && buffer.length() + piece.length() > size) {
                chunks.add(new Chunk(UUID.randomUUID().toString(), buffer.toString().trim(), source, index++, Map.of()));
                buffer.setLength(0);
            }
            buffer.append(piece);
        }
        if (!buffer.isEmpty()) {
            chunks.add(new Chunk(UUID.randomUUID().toString(), buffer.toString().trim(), source, index, Map.of()));
        }
        return chunks;
    }

    private void recursiveSplit(String text, int sepIndex, List<String> out) {
        if (text.length() <= size || sepIndex >= SEPARATORS.length) {
            out.add(text);
            return;
        }
        String separator = SEPARATORS[sepIndex];
        String[] parts;
        if (separator.isEmpty()) {
            // 终极降级：硬切固定长度
            for (int i = 0; i < text.length(); i += size) {
                out.add(text.substring(i, Math.min(text.length(), i + size)));
            }
            return;
        } else {
            parts = text.split(java.util.regex.Pattern.quote(separator));
        }
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (part.length() <= size) {
                out.add(part + separator); // 保留分隔符，还原原文可读性
            } else {
                recursiveSplit(part, sepIndex + 1, out);
            }
        }
    }
}
