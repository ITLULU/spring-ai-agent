package com.example.kbagent.agent;

import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP 消费侧网关（PRO-02 的落点）：阶段1 的 rag-kb 通过 Streamable HTTP 的 /mcp
 * 把 kb_query / kb_retrieve / kb_ingest / eval_* 声明成标准工具，starter 在启动时
 * 完成 initialize → tools/list，把每个工具翻译成进程内的 {@link ToolCallback}。
 *
 * 这个类的职责被刻意压到最小——**按名字找到回调并调用**，不写任何 RAG 逻辑：
 * rag-kb 换实现（比如拒答阈值、检索策略）时，本 Agent 一行不改，
 * 因为它依赖的是协议契约（工具名 + inputSchema），不是代码接口。
 *
 * 为什么还要包一层而不是直接注入 ToolCallback[]：
 * ① MCP 未启用（{@code spring.ai.mcp.client.enabled=false}）时容器里没有这些 Bean，
 *    ObjectProvider 让"没有知识库也能跑状态机"成为可能（本地验证/单测都靠它）；
 * ② 工具名拼错、Server 版本没这个工具，都要在**调用点**给出可读错误，
 *    而不是让 NPE 冒到工作流里被当成一次普通失败去重试（重试不了的工具名错误会白烧三轮退避）。
 */
@Component
public class KbMcpGateway {

    /** 与 rag-kb RagKbTools 对齐的工具名常量：改名即断链，集中一处便于排查 */
    public static final String KB_QUERY = "kb_query";
    public static final String KB_RETRIEVE = "kb_retrieve";

    private final ObjectProvider<ToolCallbackProvider> providers;
    private final ObjectMapper mapper = new ObjectMapper();

    public KbMcpGateway(ObjectProvider<ToolCallbackProvider> providers) {
        this.providers = providers;
    }

    /** 知识库是否可达（MCP 客户端已连接且暴露了工具） */
    public boolean available() {
        return find(KB_QUERY) != null;
    }

    /** 对外可见的工具名清单，用于 /api/agent/capabilities 自检端点 */
    public String[] toolNames() {
        return java.util.Arrays.stream(discovered())
                .map(cb -> cb.getToolDefinition().name()).toArray(String[]::new);
    }

    /**
     * MCP 发现的全部工具回调；客户端未启用/未连上时是空数组而不是 null——
     * 空数组挂到 ChatClient 上等价于"本轮没有工具可用"，比抛异常更容易在自检端点上
     * 看出是哪种情况。
     */
    public ToolCallback[] discovered() {
        return callbacks().toArray(ToolCallback[]::new);
    }

    private java.util.stream.Stream<ToolCallback> callbacks() {
        return providers.stream().flatMap(p -> java.util.Arrays.stream(p.getToolCallbacks()));
    }

    public ToolCallback find(String toolName) {
        return callbacks().filter(c -> c.getToolDefinition().name().equals(toolName))
                .findFirst().orElse(null);
    }

    /**
     * 调用一个 MCP 工具，参数按 inputSchema 的字段名给。
     * 返回的是 Server 侧方法返回值序列化成的 JSON 字符串（rag-kb 的工具返回 Map/List，
     * 这里不提前反序列化成强类型——评测机字段在演进，弱类型读比硬编码 DTO 更耐改）。
     */
    public String call(String toolName, Map<String, Object> arguments) {
        ToolCallback callback = find(toolName);
        if (callback == null) {
            throw new ToolUnavailableException(
                    "MCP 工具 %s 不可用：rag-kb 未启动、未暴露该工具，或 spring.ai.mcp.client.enabled=false"
                            .formatted(toolName));
        }
        try {
            return callback.call(mapper.writeValueAsString(arguments));
        } catch (Exception e) {
            // 传输层/Server 侧异常统一包装：工作流据此判定"这是一次可重试的失败"
            throw new ToolUnavailableException("调用 MCP 工具 %s 失败: %s"
                    .formatted(toolName, e.getMessage()), e);
        }
    }

    /**
     * kb_query 的强类型读取口：返回答复原文 + refused + citations。
     * 用 JsonNode 而不是 DTO——这些字段属于阶段1 的契约，它会演进（比如新加 token 记账），
     * 弱类型读 + path().asXxx() 缺省值让"上游加字段"永远不需要同步改这里。
     */
    public com.fasterxml.jackson.databind.JsonNode queryJson(String question, String conversationId) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("question", question);
        if (conversationId != null && !conversationId.isBlank()) {
            args.put("conversationId", conversationId);
        }
        return readTree(call(KB_QUERY, args));
    }

    /** kb_retrieve 的强类型读取口：逐块三路分数表，用于确定性证据打分 */
    public com.fasterxml.jackson.databind.JsonNode retrieveJson(String question) {
        return readTree(call(KB_RETRIEVE, Map.of("question", question)));
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
            // ToolCallback 的返回是 MCP 的文本内容：可能是 JSON 字符串，也可能被再包一层引号
            if (node.isTextual()) {
                node = mapper.readTree(node.asText());
            }
            return node;
        } catch (Exception e) {
            throw new ToolUnavailableException("MCP 返回内容不是可解析的 JSON: " + truncate(json), e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }

    /** 可重试语义由工作流决定，这里只负责把"工具层出问题"和"业务判定不通过"分开 */
    public static class ToolUnavailableException extends RuntimeException {
        public ToolUnavailableException(String message) {
            super(message);
        }

        public ToolUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
