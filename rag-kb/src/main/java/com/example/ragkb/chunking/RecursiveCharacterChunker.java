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

    /**
     * 分隔符优先级阶梯：段落（\n\n）→ 行（\n）→ 中文句末/分句（。 ；）→
     * 西文句末/分句（. ;）→ 词（空格）→ ""（终极降级硬切）。
     * 排序原则 = 语义单元从大到小：优先在最大粒度的自然边界处断开，
     * 块边界越贴近原文结构，embedding 向量越能代表完整语义。
     */
    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "；", ".", ";", " ", ""};

    /** 单块目标长度上限（字符数），来自 application.yml 的 rag.chunk.size，默认 500 */
    @Value("${rag.chunk.size:500}")
    private int size;

    /** 策略标识，对应 rag.chunk.strategy 配置值与 Chunk metadata 中的 strategy 字段 */
    @Override
    public String name() {
        return "recursive";
    }

    /**
     * 入口：两阶段完成切块。
     * <ol>
     *   <li>拆分阶段（{@link #recursiveSplit}）：把全文递归切成 ≤ size 的小片段，
     *       但此时片段可能远小于 size（一句只有几十字），直接入库会产生大量碎片，
     *       既浪费检索名额（top-n 被同一主题的句子占满）又稀释语义密度；</li>
     *   <li>合并阶段（本方法内）：贪心地按原文顺序把相邻小片段拼接，接近 size 就封块——
     *       与 LangChain 实现里 isSeparatorWhitespace/kSplit 要解决的问题是同一个：
     *       "拆得碎"必须配"合得回"。</li>
     * </ol>
     * 注意：封块时机是「加入当前 piece 会超长」才flush，所以块长可能略超过 size
     * （单个 piece 自身 ≤ size 但 buffer+piece > size 时先 flush 旧 buffer，
     * 该 piece 完整进入新块，不会拦腰截断）——宁可略长，不破坏句子。
     *
     * @param source 文档名（来自入库路径尾段），作为 Chunk 的 source 字段供引用溯源
     * @param text   解析后的文档全文
     * @return 带 UUID、序号、原始文本的块列表（Map.of()：递归切块无父子关系元数据）
     */
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

    /**
     * 递归拆分：对当前文本尝试用 SEPARATORS[sepIndex] 切开，切不动（仍超长）的部分
     * 降级到下一个更细的分隔符继续递归（sepIndex + 1）。
     * <p>递归出口三种：
     * <ul>
     *   <li>text 已 ≤ size：达标，直接收录（末尾补回分隔符，见下）；</li>
     *   <li>分隔符阶梯用尽（sepIndex 越界）：收录现状，不再强拆；</li>
     *   <li>separator == ""（最后一级）：无语义边界可用，按 size 硬切兜底——
     *       到这一步行为退化成 FixedSizeChunker，但正常情况下极少触发
     *       （整篇无标点无空格的文本才会走到）。</li>
     * </ul>
     * <p>两个实现细节（面试高频追问点）：
     * <ul>
     *   <li>为什么 split 用 Pattern.quote？分隔符里的 "." 是正则元字符（任意字符），
     *       必须转义为字面量匹配；这也是与 LangChain 版（保留捕获组分隔符）的简化差异；</li>
     *   <li>为什么每个达标片段要拼回 separator（part + separator）？split 会把分隔符丢掉，
     *       拼回后相邻片段在合并阶段首尾相接即可还原原文可读性，
     *       块内不会出现"因为被切分而粘连"的断句。</li>
     * </ul>
     *
     * @param text     当前待拆的文本段（递归变短）
     * @param sepIndex 当前层级的分隔符下标，越大越细粒度
     * @param out      收集结果片段的列表（累积传出，避免返回值的拷贝开销）
     */
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
                continue; // 连续分隔符（如空行）会产生空白片段，直接丢弃
            }
            if (part.length() <= size) {
                out.add(part + separator); // 保留分隔符，还原原文可读性
            } else {
                recursiveSplit(part, sepIndex + 1, out);
            }
        }
    }
}
