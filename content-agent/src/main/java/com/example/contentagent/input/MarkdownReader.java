package com.example.contentagent.input;

import com.example.contentagent.config.ContentAgentProperties;
import com.example.contentagent.model.ContentInput;
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

    private final ContentAgentProperties props;

    public MarkdownReader(ContentAgentProperties props) {
        this.props = props;
    }

    /** 读取目录下所有 markdown 文件。 */
    public List<ContentInput> readAll() {
        Path dir = Path.of(props.getInputDir());
        if (!Files.isDirectory(dir)) {
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

    /** 按文件名读取单个 markdown。 */
    public ContentInput readByName(String fileName) {
        Path file = Path.of(props.getInputDir(), fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("找不到输入文件: " + file);
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
