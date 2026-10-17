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

    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    private static void flushLatin(List<String> tokens, StringBuilder latin) {
        if (!latin.isEmpty()) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

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
