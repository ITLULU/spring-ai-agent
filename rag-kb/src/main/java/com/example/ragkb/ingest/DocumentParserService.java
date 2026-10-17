package com.example.ragkb.ingest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/**
 * 文档解析（阶段1深度点之一）。
 * 当前覆盖：txt/md 直读；pdf 用 PDFBox 抽文本层。
 * 待扩展（写进 README roadmap，面试可讲方案）：
 *  - 表格 PDF：PDFBox 的文本抽取会打乱表格结构 → 用 tabula/或视觉模型转 Markdown；
 *  - 扫描件（无文本层）：OCR（PaddleOCR / 多模态 LLM 转写）；
 *  - docx：poi-tl / docx4j。
 * 解析质量的天花板决定 RAG 的天花板——垃圾进垃圾出。
 */
@Service
public class DocumentParserService {

    public String parseToText(String path) throws IOException {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(new File(path))) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true); // 按版面位置排序，减少多栏错乱
                return stripper.getText(doc);
            }
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("暂不支持的格式: " + path);
        }
    }
}
