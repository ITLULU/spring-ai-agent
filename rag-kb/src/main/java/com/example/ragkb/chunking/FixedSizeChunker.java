package com.example.ragkb.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 固定长度切块（带重叠窗口）。
 * overlap 的作用：跨块边界的信息不丢失——一个事实被切断时，至少有一个块是完整的。
 * 代价：存储与 embedding 成本增加约 overlap/size。
 */
@Component
public class FixedSizeChunker implements ChunkingStrategy {

    @Value("${rag.chunk.size:500}")
    private int size;

    @Value("${rag.chunk.overlap:80}")
    private int overlap;

    @Override
    public String name() {
        return "fixed";
    }

    @Override
    public List<Chunk> split(String source, String text) {
        List<Chunk> chunks = new ArrayList<>();
        String normalized = text.replaceAll("\\s{2,}", " ").trim();
        int stride = Math.max(1, size - overlap);
        int index = 0;
        for (int start = 0; start < normalized.length(); start += stride) {
            int end = Math.min(normalized.length(), start + size);
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(new Chunk(UUID.randomUUID().toString(), piece, source, index++, Map.of()));
            }
            if (end == normalized.length()) {
                break;
            }
        }
        return chunks;
    }
}
