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

    /**
     * 滑动窗口硬切（对照组基准策略）：窗口 size、步长 stride = size - overlap。
     * <p>三个实现细节：
     * <ul>
     *   <li>先归一化空白（连续空白压成单空格 + trim）：否则 Markdown 缩进/空行
     *       会白白占窗口长度，块间实际信息密度不一致；注意 BM25/向量用的是归一后
     *       文本，引用展示与锚点校验都基于它（DatasetService 比对时同样做空白剥离，
     *       两边口径必须一致）；</li>
     *   <li>overlap 的作用再具体一层：窗口以 stride 前移，相邻块尾部/头部重叠
     *       overlap 字——被切断的句子至少在一个块里完整；代价是存储与 embedding
     *       成本约增加 overlap/size；</li>
     *   <li>end 到文末即 break：避免最后窗口不足 stride 时产生近乎全重复的尾块。</li>
     * </ul>
     * 已知失败模式（面试可讲，另外两种策略就是冲着它来的）：句子/表格被拦腰斩断，
     * 块边界与文档结构无关——所以才有 recursive（按语义边界）和 parent-child（生成端补救）。
     */
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
