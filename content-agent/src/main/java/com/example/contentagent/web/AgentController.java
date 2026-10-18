package com.example.contentagent.web;

import com.example.contentagent.input.MarkdownReader;
import com.example.contentagent.model.ContentInput;
import com.example.contentagent.model.PipelineResult;
import com.example.contentagent.output.FileOutputWriter;
import com.example.contentagent.pipeline.ContentPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST 入口。三种用法：
 *   POST /api/agent/run-all              跑 input-dir 下全部 md
 *   POST /api/agent/run?file=xxx.md      跑指定 md
 *   POST /api/agent/run-raw              直接贴 markdown 文本跑（不落 input-dir）
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final MarkdownReader reader;
    private final ContentPipeline pipeline;
    private final FileOutputWriter outputWriter;

    public AgentController(MarkdownReader reader, ContentPipeline pipeline, FileOutputWriter outputWriter) {
        this.reader = reader;
        this.pipeline = pipeline;
        this.outputWriter = outputWriter;
    }

    /**
     * 批量执行逐条容错：4 次 LLM 调用的长链路里单篇失败（模型不合规/网络抖动）
     * 不再拖垮整批——已成功的稿件照常返回，失败篇目在 errors 里逐个点名。
     * 空目录时附 hint 暴露真实解析路径，不再只给一个无语义的 []。
     */
    @PostMapping("/run-all")
    public ResponseEntity<Map<String, Object>> runAll() {
        List<ContentInput> inputs = reader.readAll();
        List<PipelineResult> results = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (ContentInput input : inputs) {
            try {
                results.add(runOne(input));
            } catch (Exception e) {
                log.warn("[run-all] 单篇失败（不连坐）: {}", input.title(), e);
                errors.put(input.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("total", inputs.size());
        out.put("succeeded", results.size());
        out.put("failed", errors.size());
        out.put("results", results);
        if (!errors.isEmpty()) {
            out.put("errors", errors);
        }
        if (inputs.isEmpty()) {
            out.put("hint", "输入目录为空或不存在，检查 content-agent.input-dir 与启动目录（日志已暴露真实解析路径）");
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/run")
    public ResponseEntity<PipelineResult> run(@RequestParam("file") String fileName) {
        return ResponseEntity.ok(runOne(reader.readByName(fileName)));
    }

    @PostMapping("/run-raw")
    public ResponseEntity<PipelineResult> runRaw(@RequestBody RawRequest req) {
        ContentInput input = new ContentInput(
                sanitize(req.title()), req.title(), req.markdown(), "(inline)",
                java.time.Instant.now());
        return ResponseEntity.ok(runOne(input));
    }

    private PipelineResult runOne(ContentInput input) {
        PipelineResult result = pipeline.run(input);
        Path dir = outputWriter.write(result);
        return result;
    }

    /** 参数/文件类错误回 400 而非裸 500：客户端错误语义要能被调用方区分处理 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    private String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "untitled-" + System.currentTimeMillis();
        }
        return s.replaceAll("[^\\p{L}\\p{N}]", "-");
    }

    public record RawRequest(String title, String markdown) {
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "content-agent");
    }
}
