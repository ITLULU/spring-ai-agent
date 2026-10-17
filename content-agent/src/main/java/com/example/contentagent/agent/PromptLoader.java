package com.example.contentagent.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 从 classpath:prompts/ 加载系统提示词模板。
 * 把 prompt 外置成文件，方便你像调参一样迭代 prompt（这是 Agent 开发的核心手艺）。
 */
@Component
public class PromptLoader {

    /**
     * @param fileName prompts/ 下的文件名，如 "analysis-system.txt"
     * @return 文件内容
     */
    public String load(String fileName) {
        try {
            return new ClassPathResource("prompts/" + fileName)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 prompt 失败: " + fileName, e);
        }
    }
}
