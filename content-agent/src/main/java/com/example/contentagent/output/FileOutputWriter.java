package com.example.contentagent.output;

import com.example.contentagent.config.BasePath;
import com.example.contentagent.config.ContentAgentProperties;
import com.example.contentagent.model.PlatformArticle;
import com.example.contentagent.model.PipelineResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把一次 pipeline 结果落盘：
 *   output/{id}/monetization.json  变现方案 + 分析结果
 *   output/{id}/zhihu.md           知乎稿（含标题/标签/CTA 头）
 *   output/{id}/wechat.md          公众号稿
 */
@Component
public class FileOutputWriter {

    private static final Logger log = LoggerFactory.getLogger(FileOutputWriter.class);

    private final ContentAgentProperties props;
    private final ObjectMapper mapper;

    public FileOutputWriter(ContentAgentProperties props) {
        this.props = props;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Path write(PipelineResult result) {
        // 相对输出目录走 BasePath 探测：聚合根启动时落点与输入目录同一数据根，
        // 避免"读在模块目录、写在仓库根"的两套路径分裂
        Path dir = BasePath.resolve(props.getOutputDir()).resolve(result.input().id());
        try {
            Files.createDirectories(dir);

            mapper.writeValue(dir.resolve("result.json").toFile(), result);

            writeArticle(dir.resolve("zhihu.md"), result.zhihuArticle());
            writeArticle(dir.resolve("wechat.md"), result.wechatArticle());

            log.info("[output] 已写入: {}", dir.toAbsolutePath());
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("写出结果失败: " + dir, e);
        }
    }

    private void writeArticle(Path file, PlatformArticle a) throws IOException {
        String md = """
                # %s
                
                > 平台: %s | 预计阅读: %d 分钟
                > 标签: %s
                > 备选标题: %s
                
                ---
                
                %s
                
                ---
                
                **引流语(CTA)**: %s
                """.formatted(
                a.title(),
                a.platform(),
                a.readingMinutes(),
                a.tags() == null ? "" : String.join("、", a.tags()),
                a.alternativeTitles() == null ? "" : String.join(" / ", a.alternativeTitles()),
                a.bodyMarkdown(),
                a.cta());
        Files.writeString(file, md, StandardCharsets.UTF_8);
    }
}
