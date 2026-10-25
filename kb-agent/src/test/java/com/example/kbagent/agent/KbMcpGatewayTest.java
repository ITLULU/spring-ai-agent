package com.example.kbagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 消费侧网关（PRO-02）的契约锁。这里验的不是"能不能连上 rag-kb"——
 * 那要两个进程同时带 key 才能跑；验的是<b>拿到 ToolCallback 之后本进程的读法</b>：
 * ① 客户端未启用/未连上时返回空而不是炸（"没有知识库也能跑状态机"的前提）；
 * ② 工具名找不到时给可读错误，不能让 NPE 冒进工作流被当成一次可重试的失败白烧三轮退避；
 * ③ MCP 返回的文本可能是 JSON，也可能被再包一层引号——两种都要能解析；
 * ④ 参数名必须与 rag-kb 的 inputSchema 对齐（question/conversationId），
 *    且 conversationId 为空时不发送该键——评测/批量场景要保无状态。
 */
class KbMcpGatewayTest {

    /** 记录每次收到的原始 arguments JSON，用于核对发给 Server 的字段 */
    private static final class RecordingTool implements ToolCallback {
        final List<String> inputs = new ArrayList<>();
        final String payload;
        final RuntimeException failure;

        RecordingTool(String name, String payload) {
            this(name, payload, null);
        }

        RecordingTool(String name, String payload, RuntimeException failure) {
            this.payload = payload;
            this.failure = failure;
            this.name = name;
        }

        private final String name;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("桩工具")
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String toolInput) {
            inputs.add(toolInput);
            if (failure != null) {
                throw failure;
            }
            return payload;
        }
    }

    @SuppressWarnings("unchecked")
    private static KbMcpGateway gatewayWith(ToolCallback... callbacks) {
        ToolCallbackProvider provider = () -> callbacks;
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        // thenAnswer 而不是 thenReturn：Stream 是一次性的，而网关每次调用都重新取流
        // （真实容器给的 ObjectProvider 每次 stream() 都是新流，这里必须同构）
        when(providers.stream()).thenAnswer(inv ->
                callbacks.length == 0 ? Stream.empty() : Stream.of(provider));
        return new KbMcpGateway(providers);
    }

    @Test
    void disabledClientYieldsEmptyCapabilitySetInsteadOfCrash() {
        KbMcpGateway gateway = gatewayWith();

        assertFalse(gateway.available());
        assertEquals(0, gateway.discovered().length, "空数组挂到 ChatClient 上等价于本轮没工具，不能是 null");
        assertEquals(0, gateway.toolNames().length);
        assertNull(gateway.find(KbMcpGateway.KB_QUERY));

        KbMcpGateway.ToolUnavailableException e = assertThrows(KbMcpGateway.ToolUnavailableException.class,
                () -> gateway.queryJson("年假几天", null));
        assertTrue(e.getMessage().contains("kb_query"), "错误要报出工具名: " + e.getMessage());
        assertTrue(e.getMessage().contains("enabled=false"),
                "要把'没启用'和'传输炸了'写在同一句里，排障时一眼分出是哪种: " + e.getMessage());
    }

    @Test
    void onlyKbQueryPresenceCountsAsAvailable() {
        KbMcpGateway gateway = gatewayWith(new RecordingTool(KbMcpGateway.KB_RETRIEVE, "[]"));

        // available 的语义是"能问知识库拿依据"，只有 kb_retrieve 不算；能力清单仍如实报出
        assertFalse(gateway.available(), "只有召回工具时不该报告知识库可用");
        assertEquals(List.of("kb_retrieve"), List.of(gateway.toolNames()));
    }

    @Test
    void argumentsMatchRagKbInputSchemaAndBlankConversationIdIsOmitted() {
        RecordingTool query = new RecordingTool(KbMcpGateway.KB_QUERY, "{\"answer\":\"10 天 [1]\"}");
        KbMcpGateway gateway = gatewayWith(query);

        gateway.queryJson("入职满3年几天年假", "ticket-7");
        gateway.queryJson("独立一问", "  ");

        assertTrue(query.inputs.get(0).contains("\"question\""), "字段名要对齐 kb_query 的 inputSchema: " + query.inputs.get(0));
        assertTrue(query.inputs.get(0).contains("ticket-7"));
        assertFalse(query.inputs.get(1).contains("conversationId"),
                "空会话 ID 不能把键发过去——Server 侧会当成一次带记忆的调用: " + query.inputs.get(1));
    }

    @Test
    void plainJsonAndDoubleQuotedTextualPayloadAreBothReadable() {
        RecordingTool plain = new RecordingTool(KbMcpGateway.KB_QUERY,
                "{\"answer\":\"满 3 年 10 天 [1]\",\"refused\":false,\"citations\":[{\"no\":1}]}");
        // MCP 的 content 是文本：工具返回的 JSON 串常被再序列化一次，整体变成一个 JSON 字符串字面量
        RecordingTool wrapped = new RecordingTool(KbMcpGateway.KB_QUERY,
                "\"{\\\"answer\\\":\\\"满 3 年 10 天 [1]\\\",\\\"refused\\\":false}\"");

        JsonNode a = gatewayWith(plain).queryJson("q", null);
        JsonNode b = gatewayWith(wrapped).queryJson("q", null);

        assertEquals("满 3 年 10 天 [1]", a.path("answer").asText());
        assertEquals("满 3 年 10 天 [1]", b.path("answer").asText(), "被包一层引号的返回也要解得开，否则线上判定读不到 refused");
        assertTrue(a.path("citations").isArray());
    }

    @Test
    void missingFieldsReadAsDefaultsNotExceptions() {
        KbMcpGateway gateway = gatewayWith(new RecordingTool(KbMcpGateway.KB_QUERY, "{}"));

        JsonNode node = gateway.queryJson("q", null);

        assertTrue(node.path("refused").isMissingNode());
        // 工作流用 path("refused").asBoolean(true)：缺字段一律按"拒答"处理，宁转人工不误结单
        assertTrue(node.path("refused").asBoolean(true));
        assertEquals("", node.path("answer").asText(""));
    }

    @Test
    void transportFailureIsWrappedButUnparseablePayloadIsCalledOutSeparately() {
        KbMcpGateway boom = gatewayWith(new RecordingTool(KbMcpGateway.KB_RETRIEVE, null,
                new IllegalStateException("connection reset")));
        KbMcpGateway.ToolUnavailableException e = assertThrows(KbMcpGateway.ToolUnavailableException.class,
                () -> boom.retrieveJson("q"));
        assertTrue(e.getMessage().contains("connection reset"), "上游原因要留在消息里: " + e.getMessage());

        KbMcpGateway garbage = gatewayWith(new RecordingTool(KbMcpGateway.KB_RETRIEVE, "not json at all"));
        KbMcpGateway.ToolUnavailableException e2 = assertThrows(KbMcpGateway.ToolUnavailableException.class,
                () -> garbage.retrieveJson("q"));
        assertTrue(e2.getMessage().contains("不是可解析的 JSON"), "协议层出问题要和业务判定分开表述: " + e2.getMessage());
        assertNotNull(e2.getCause());
    }

    @Test
    void toolNamesComeFromDefinitionsForSelfCheckEndpoint() {
        KbMcpGateway gateway = gatewayWith(new RecordingTool(KbMcpGateway.KB_QUERY, "{}"),
                new RecordingTool(KbMcpGateway.KB_RETRIEVE, "[]"));

        assertEquals(List.of("kb_query", "kb_retrieve"), List.of(gateway.toolNames()));
        assertTrue(gateway.available());
    }
}
