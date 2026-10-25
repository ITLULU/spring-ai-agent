package com.example.kbagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.kbagent.agent.KbMcpGateway;
import com.example.kbagent.domain.Ticket;
import com.example.kbagent.store.TicketRepository;
import com.example.kbagent.workflow.TicketWorkflow;

/**
 * 装配层守门测试：这条链路最容易在重构时悄无声息断掉的两样东西都在这里锁住。
 *
 * ① **Spring 上下文起得来**。工单 Agent 的工具链会把 ChatClient 要用的 ToolCallback 喂回
 * ChatModel 的解析链，rag-kb 踩过那个环（openAiChatModel → toolCallingManager →
 * toolCallbackResolver → provider → tools → ChatModel）；这里靠 `@Lazy ChatModel` 断开。
 * 上下文能 refresh 就是这条断环策略仍然有效的证据——单元测试全绿也替代不了它。
 * ② **application.yml 的闸门参数真的绑进了 @Value**。工作流单测是手工注入字段跑的，
 * 配置项改名/漏写会让线上用 Java 默认值（阈值 0、权重 0），那是"自动结单一切通过"的事故。
 *
 * 用 `spring.ai.mcp.client.enabled=false` + 内存 H2：不需要 rag-kb 在跑、不需要模型 key，
 * 因此这条测试在任何机器 CI 上都能跑（带 key 的端到端实连另说，见 README 验证状态表）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.mcp.client.enabled=false",
        "ticket.agent.retry-scheduler-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:kbagent-ctx;DB_CLOSE_DELAY=-1"
})
class KbAgentApplicationContextTest {

    @Autowired
    TicketWorkflow workflow;

    @Autowired
    TicketRepository repository;

    @Autowired
    KbMcpGateway gateway;

    @Autowired
    TestRestTemplate rest;

    @Test
    void gateParametersAreBoundFromConfigurationNotJavaDefaults() {
        assertEquals(0.75, (double) ReflectionTestUtils.getField(workflow, "autoResolveThreshold"), 1e-9,
                "阈值若退化成 0，等于所有工单自动结单");
        assertEquals(0.5, (double) ReflectionTestUtils.getField(workflow, "weightEvidence"), 1e-9);
        assertEquals(0.3, (double) ReflectionTestUtils.getField(workflow, "weightCitation"), 1e-9);
        assertEquals(0.2, (double) ReflectionTestUtils.getField(workflow, "weightSelfReport"), 1e-9);
        assertEquals(3, ReflectionTestUtils.getField(workflow, "maxAttempts"));
        assertEquals(2000L, ReflectionTestUtils.getField(workflow, "backoffBaseMs"));
        assertEquals(60000L, ReflectionTestUtils.getField(workflow, "backoffCapMs"));
        assertTrue(((String) ReflectionTestUtils.getField(workflow, "highRiskCategories")).contains("payroll"));
    }

    @Test
    void schemaIsAppliedAndConversationMemoryWired() {
        Ticket t = repository.insert("门禁", "卡丢了", "it_account", "P2");
        assertNotNull(repository.findById(t.id()).orElseThrow().updatedAt());

        // MCP 关掉时状态机仍要能用：这是"没有知识库也能跑"的产品承诺，不是测试取巧
        assertFalse(gateway.available());
        assertEquals(0, gateway.toolNames().length);
    }

    @Test
    @SuppressWarnings("unchecked")
    void selfCheckEndpointsServeTheStateMachineStraightFromTheEnum() {
        Map<String, Object> body = rest.getForObject("/api/tickets/state-machine", Map.class);
        Map<String, Object> transitions = (Map<String, Object>) body.get("transitions");

        assertEquals(List.of("AUTO_RESOLVED", "FAILED", "PENDING_HUMAN"), transitions.get("NEW"),
                "端点打出来的转移表就是代码里那张表，评审不用信文档里手画的图");
        assertEquals(List.of("AUTO_RESOLVED", "PENDING_HUMAN", "RETRYING"), transitions.get("FAILED"));

        Map<String, Object> caps = rest.getForObject("/api/agent/capabilities", Map.class);
        assertEquals(false, caps.get("kbAvailable"));
        assertTrue(caps.get("mcpTools") instanceof List<?>);
    }

    @Test
    void blankTicketDescriptionIsRejectedAtTheBoundary() {
        // 系统边界才做校验：空描述进来后面每个节点都是在猜
        assertEquals(400, rest.postForEntity("/api/tickets",
                Map.of("title", "空单", "description", "  "), String.class).getStatusCode().value());
    }

    /**
     * 人工复核界面最容易触发的一支：重复点批准。
     * 实跑双进程时它返回的是 500「Internal Server Error」——CAS 抛的
     * IllegalTransitionException 是 RuntimeException，而 Controller 只 catch 了
     * IllegalStateException，于是"非法流转→409"这条写在注释里的承诺在 HTTP 层落空，
     * 复核的人分不清是自己抢了还是 Agent 抢了。这里锁回 409 + 可读理由。
     */
    @Test
    @SuppressWarnings("unchecked")
    void secondApproveIsAConflictCarryingTheStateMachineReason() {
        Ticket t = repository.insert("重复批准", "只测状态机边界", "it_support", "P3");
        assertEquals(200, rest.postForEntity("/api/tickets/" + t.id() + "/review",
                Map.of("decision", "APPROVE", "note", "第一次批准"), Map.class)
                .getStatusCode().value());

        var second = rest.postForEntity("/api/tickets/" + t.id() + "/review",
                Map.of("decision", "APPROVE", "note", "再点一次"), Map.class);

        assertEquals(409, second.getStatusCode().value(),
                "撞状态机是并发/重复操作，不是服务端故障");
        String message = String.valueOf(second.getBody().get("message"));
        assertTrue(message.contains("AUTO_RESOLVED"),
                "冲突要把 from→to 和合法目标一起返回，人工才知道下一步能点什么：" + message);
    }

    @Test
    void unknownTicketIs404NotAnEmptyPage() {
        // 复核界面拿 id 查单是常态：查不到要说"不存在"，不能给个空壳页面让人以为这单被清空了
        assertEquals(404, rest.getForEntity("/api/tickets/no-such-ticket", String.class)
                .getStatusCode().value());
    }
}
