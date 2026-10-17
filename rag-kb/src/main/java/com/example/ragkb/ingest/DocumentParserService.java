package com.example.ragkb.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import com.example.ragkb.config.DataPath;

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
        // 多模块下工作目录可能是聚合根，相对路径统一解析到数据根目录（见 DataPath）
        Path p = DataPath.resolve(path);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("文件不存在: " + p
                    + "（数据根目录: " + DataPath.baseDir() + "；可传绝对路径或设置 RAG_BASE_DIR 覆盖）");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true); // 按版面位置排序，减少多栏错乱
                return stripper.getText(doc);
            }
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return Files.readString(p, StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("暂不支持的格式: " + path);
        }
    }
}
