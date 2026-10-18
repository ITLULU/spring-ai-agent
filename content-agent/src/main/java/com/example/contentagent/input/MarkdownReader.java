package com.example.contentagent.input;

import com.example.contentagent.config.BasePath;
import com.example.contentagent.config.ContentAgentProperties;
import com.example.contentagent.model.ContentInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 读取 input-dir 下的 *.md，转成 ContentInput。
 */
@Component
public class MarkdownReader {

    private static final Logger log = LoggerFactory.getLogger(MarkdownReader.class);

    private final ContentAgentProperties props;

    public MarkdownReader(ContentAgentProperties props) {
        this.props = props;
    }

    /** 读取目录下所有 markdown 文件。目录不存在不再静默返回空表：WARN 暴露真实解析路径。 */
    public List<ContentInput> readAll() {
        Path dir = BasePath.resolve(props.getInputDir());
        if (!Files.isDirectory(dir)) {
            log.warn("输入目录不存在: {}（CWD={}，相对路径基于 {} 解析；"
                    + "可用 CONTENT_AGENT_BASE_DIR 指定数据根）",
                    dir, Path.of(".").toAbsolutePath(), BasePath.baseDir());
            return List.of();
        }
        try (Stream<Path> walk = Files.list(dir)) {
            return walk
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(this::toInput)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取输入目录失败: " + dir, e);
        }
    }

    /** 按文件名读取单个 markdown。文件名限纯名：含路径分隔符/.. 一律 400 拦截（防穿越）。 */
    public ContentInput readByName(String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("非法文件名（不允许路径分隔符/相对段）: " + fileName);
        }
        Path file = BasePath.resolve(props.getInputDir()).resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("找不到输入文件: " + file.toAbsolutePath());
        }
        return toInput(file);
    }

    private ContentInput toInput(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            String id = name.replaceAll("\\.md$", "");
            String title = extractTitle(raw, id);
            return new ContentInput(id, title, raw, name, Instant.now());
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + file, e);
        }
    }

    /** 取首个 "# " 标题行作为标题，否则用文件名。 */
    private String extractTitle(String raw, String fallback) {
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("# ")) {
                return t.substring(2).trim();
            }
        }
        return fallback;
    }
}
