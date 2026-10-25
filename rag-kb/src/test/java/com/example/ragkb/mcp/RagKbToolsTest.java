package com.example.ragkb.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import com.example.ragkb.eval.DatasetService;
import com.example.ragkb.eval.EvalJudgeErrorLog;
import com.example.ragkb.eval.EvalService;
import com.example.ragkb.ingest.IngestService;
import com.example.ragkb.qa.QaService;
import com.example.ragkb.qa.QueryResponse;
import com.example.ragkb.retrieval.HybridRetrievalService;
import com.example.ragkb.retrieval.RetrievedChunk;

/**
 * MCP tools 层测试：不启动传输协议，直接验证
 * ① @Tool 注解能被 ToolCallbackProvider 完整发现（名字/数量即对外契约）；
 * ② description 非空（LLM 靠它选工具，空描述=工具不可用）；
 * ③ 各 tool 的包装行为与底层 Service 语义一致（含错误→结构化返回，不抛裸异常）。
 */
class RagKbToolsTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "kb_query", "kb_retrieve", "kb_ingest",
            "eval_dataset_validate", "eval_run_start", "eval_run_status",
            "eval_run_report", "eval_run_diff", "eval_list_runs", "judge_error_record");

    private QaService qaService = mock(QaService.class);
    private HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
    private IngestService ingestService = mock(IngestService.class);
    private EvalService evalService = mock(EvalService.class);
    private DatasetService datasetService = mock(DatasetService.class);
    private EvalJudgeErrorLog judgeErrorLog = mock(EvalJudgeErrorLog.class);

    private RagKbTools tools() {
        return new RagKbTools(qaService, retrievalService, ingestService,
                evalService, datasetService, judgeErrorLog);
    }

    @Test
    void toolCallbacksExposeExactContract() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools()).build().getToolCallbacks();
        Set<String> names = Arrays.stream(callbacks)
                .map(c -> c.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet());
        assertEquals(EXPECTED_TOOLS, names, "MCP 对外工具契约发生了变化，README 与客户端配置需同步");
        // 每个工具的 schema 必须能生成（description + input schema 非空），否则客户端无法选用
        for (ToolCallback c : callbacks) {
            assertFalse(c.getToolDefinition().description().isBlank());
            assertTrue(c.getToolDefinition().inputSchema().contains("properties"));
        }
    }

    @Test
    void everyToolParameterIsDocumented() {
        for (Method m : RagKbTools.class.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Tool.class)) {
                continue;
            }
            assertFalse(m.getAnnotation(Tool.class).description().isBlank(),
                    m.getName() + " 缺少 description");
            for (Parameter p : m.getParameters()) {
                if (p.getType() == String.class || p.getType() == Boolean.class) {
                    assertTrue(p.isAnnotationPresent(ToolParam.class),
                            m.getName() + " 参数 " + p.getName() + " 缺 @ToolParam");
                }
            }
        }
    }

    @Test
    void kbQueryMapsAnswerFieldsAndTokens() {
        when(qaService.answer(anyString(), any())).thenReturn(new QueryResponse(
                "满 3 年享有 10 天。[1]", false,
                List.of(new QueryResponse.Citation(1, "c-1", "attendance_policy.md", "满 3 年")),
                1234L, 421L, 800, 42));

        var out = tools().kbQuery("年假几天？", "conv-1");

        assertEquals("满 3 年享有 10 天。[1]", out.get("answer"));
        assertEquals(false, out.get("refused"));
        assertEquals(1234L, out.get("elapsedMs"));
        assertEquals(421L, out.get("retrievalMs"), "QA-01：检索段单独透出，MCP 侧也能归因慢在哪一段");
    }

    @Test
    void kbQueryAdvertisesOptionalConversationId() {
        ToolCallback kbQuery = Arrays.stream(MethodToolCallbackProvider.builder()
                .toolObjects(tools()).build().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals("kb_query"))
                .findFirst().orElseThrow();
        String schema = kbQuery.getToolDefinition().inputSchema();
        assertTrue(schema.contains("conversationId"), "多轮入口未进契约，Agent 侧无法消解指代");
        assertFalse(schema.contains("\"required\":[\"question\",\"conversationId\""),
                "conversationId 必须是可选参数");
    }

    @Test
    void kbRetrieveAddsEvidenceHitWithSharedProbe() {
        String content = "## 年假\n员工入职满 1 年享有 5 天带薪年假，满 3 年享有 10 天。";
        when(retrievalService.retrieve(any())).thenReturn(List.of(
                new RetrievedChunk("c-1", content, content, "attendan ce_policy.md",
                        0.5, 3.0, 0.03, null)));

        var rows = tools().kbRetrieve("年假几天", "员工入职满 1 年享有 5 天带薪年假");

        assertEquals(1, rows.size());
        assertEquals(true, rows.get(0).get("evidenceHit"), "与评测机同口径的锚点命中判定");
    }

    @Test
    void evalRunStartReturnsRejectionInsteadOfThrowingWhenBusy() {
        when(evalService.startRun(anyString(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("run in progress: 20261016-000000-x-1"));

        var out = tools().evalRunStart("busy-probe", null, false, null);

        assertEquals("rejected", out.get("status"));
        assertTrue(String.valueOf(out.get("reason")).contains("run in progress"));
    }
}
