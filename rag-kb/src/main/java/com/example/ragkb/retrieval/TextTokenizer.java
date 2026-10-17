package com.example.ragkb.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * 中英混合分词（BM25 索引用）。
 * 英文：按词切分并小写化；中文：无空格分词，采用 bigram（二元切分）——
 * "知识库检索" → "知识 / 识库 / 库检 / 检索"。bigram 是工程上不用装 jieba 时
 * 召回与噪声平衡最好的折中；面试可对比 n-gram vs 分词器的取舍。
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    /**
     * 主分词入口：单遍扫描字符流，维护两个待发缓冲区（latin 连续字母数字段 /
     * cjkRun 连续中文段），字符类型切换或遇标点分隔符时 flush 对应缓冲。
     * <p>三类字符的处理规则：
     * <ul>
     *   <li>CJK 字符：累积成串，遇到非 CJK 时整串输出 bigram 序列；</li>
     *   <li>字母数字：累积成单词（已小写化），西文按空格天然分词无需再切；</li>
     *   <li>其余（标点/空白）：两边都 flush，自身不产生 token——但注意
     *       CJK↔拉丁切换时两边缓冲都会落地，所以"血糖AI系统"会同时产出中文 bigram
     *       和英文 token，跨语言边界不丢词。</li>
     * </ul>
     * <p>核心约束（必须记住）：入库与查询共用这一个 tokenize，分词口径不一致
     * 是词法检索的第一杀手——比如入库用 bigram、查询用整句，倒排表永远查不中。
     *
     * @param text 待分词文本，null 安全（返回空表）
     * @return token 序列（含重复，词频统计由调用方 merge 完成）
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        List<Character> cjkRun = new ArrayList<>();

        for (char c : text.toLowerCase().toCharArray()) {
            if (isCjk(c)) {
                flushLatin(tokens, latin);
                cjkRun.add(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(tokens, cjkRun);
                latin.append(c);
            } else {
                flushLatin(tokens, latin);
                flushCjk(tokens, cjkRun);
            }
        }
        flushLatin(tokens, latin);
        flushCjk(tokens, cjkRun);
        return tokens;
    }

    /** CJK 基本区判定（\u4e00~\u9fff）：覆盖常用汉字；日文假名/韩文也落在此区间的副作用未处理，当前语料纯中文可接受 */
    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    /** 拉丁缓冲落地为一个完整单词 token（已小写）；空缓冲直接返回 */
    private static void flushLatin(List<String> tokens, StringBuilder latin) {
        if (!latin.isEmpty()) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    /**
     * 中文串落地：长度 1 的孤字直接成 token；否则相邻两字滑窗产出 n-1 个 bigram。
     * <p>为什么选 bigram 而不是单字/整串：单字区分度太低（"制""度"满世界都是），
     * 整串匹配不上查询里的部分词；bigram 在召回（"知识"能中"知识库"的首半）与
     * 噪声（比 unigram 减半候选）之间平衡最好，且零词库、零依赖。
     * 代价（面试可讲）：查询词必须是原文连续两字才能命中，长句检索靠多 bigram 叠加
     * tf/idf 得分补偿；真要更准就升jieba分词或直接靠向量路互补。
     */
    private static void flushCjk(List<String> tokens, List<Character> run) {
        if (run.isEmpty()) {
            return;
        }
        if (run.size() == 1) {
            tokens.add(String.valueOf(run.get(0)));
        } else {
            for (int i = 0; i + 1 < run.size(); i++) {
                tokens.add("" + run.get(i) + run.get(i + 1));
            }
        }
        run.clear();
    }
}
