package com.example.contentagent.input;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.contentagent.config.ContentAgentProperties;
import com.example.contentagent.model.ContentInput;

/**
 * 输入读取冒烟测试（content-agent 此前零测试，本类开始补齐"测试对称"纪律）。
 * 钉住三个修复点：目录不存在不再静默吞错、文件名穿越拦截、标题提取契约。
 * inputDir 用绝对路径（BasePath 对绝对路径原样透传），不依赖 CWD 探测。
 */
class MarkdownReaderTest {

    @TempDir
    Path dir;

    private MarkdownReader reader;

    @BeforeEach
    void setUp() {
        ContentAgentProperties props = new ContentAgentProperties();
        props.setInputDir(dir.toAbsolutePath().toString());
        reader = new MarkdownReader(props);
    }

    @Test
    void readAll_loadsMdOnly_extractsFirstHeadingTitle() throws IOException {
        Files.writeString(dir.resolve("a.md"), "# 标题甲\n\n正文内容。".repeat(2), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.md"), "无标题文件正文", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("skip.txt"), "非 markdown 应被忽略");

        List<ContentInput> inputs = reader.readAll();

        assertEquals(2, inputs.size());
        assertEquals("a.md", inputs.get(0).sourceFile());
        assertEquals("标题甲", inputs.get(0).title(), "首个 \"# \" 行应被提取为标题");
        assertEquals("b", inputs.get(1).id(), "无标题时回退文件名（去 .md 后缀）");
    }

    @Test
    void readAll_missingDir_returnsEmptyWithoutThrowing() {
        ContentAgentProperties props = new ContentAgentProperties();
        props.setInputDir(dir.resolve("not-exists").toAbsolutePath().toString());

        assertDoesNotThrow(() -> new MarkdownReader(props).readAll());
    }

    @Test
    void readByName_rejectsPathTraversalAndSeparators() {
        assertThrows(IllegalArgumentException.class, () -> reader.readByName("../secret.md"));
        assertThrows(IllegalArgumentException.class, () -> reader.readByName("sub/a.md"));
        assertThrows(IllegalArgumentException.class, () -> reader.readByName("sub\\a.md"));
        assertThrows(IllegalArgumentException.class, () -> reader.readByName(" "));
    }

    @Test
    void readByName_missingFile_messageContainsResolvedAbsolutePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> reader.readByName("ghost.md"));
        assertTrue(e.getMessage().contains(dir.toAbsolutePath().toString()),
                "报错必须暴露真实解析路径，调用方才可能 5 秒定位文件应该放哪");
    }
}
