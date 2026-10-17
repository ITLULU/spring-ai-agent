package com.example.contentagent.web;

import com.example.contentagent.input.MarkdownReader;
import com.example.contentagent.model.ContentInput;
import com.example.contentagent.model.PipelineResult;
import com.example.contentagent.output.FileOutputWriter;
import com.example.contentagent.pipeline.ContentPipeline;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
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

    private final MarkdownReader reader;
    private final ContentPipeline pipeline;
    private final FileOutputWriter outputWriter;

    public AgentController(MarkdownReader reader, ContentPipeline pipeline, FileOutputWriter outputWriter) {
        this.reader = reader;
        this.pipeline = pipeline;
        this.outputWriter = outputWriter;
    }

    @PostMapping("/run-all")
    public ResponseEntity<List<PipelineResult>> runAll() {
        List<PipelineResult> results = reader.readAll().stream()
                .map(this::runOne)
                .toList();
        return ResponseEntity.ok(results);
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
