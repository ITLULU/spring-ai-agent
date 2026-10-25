package com.example.ragkb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ragkb.qa.QaService;

/**
 * 整上下文启动测试（排查清单 TST-01 补 rag-kb 这一半）。
 *
 * 为什么这个类比任何单测都重要：AGT-01 把 QaService 从裸调 ChatModel 改成注入 ChatClient
 * 之后，48 个单测全绿、但进程根本起不来——因为单测都是手工 new 出来的对象，只有真正的
 * 容器才会去解 bean 图，把 "qaService → kbChatClient → openAiChatModel →
 * toolCallingManager → toolCallbackResolver → ragKbToolCallbackProvider → ragKbTools
 * → qaService" 这个环暴露成启动失败。以后任何改动装配的代码，跑这一类就能兜住。
 *
 * 零基建：默认 profile 排除了 DataSourceAutoConfiguration、向量库/ChunkStore 都在内存，
 * MCP Server 只是注册路由不需要外部依赖，所以本类不需要任何环境变量或 key。
 */
@SpringBootTest
class RagKbApplicationContextTest {

    @Autowired
    ApplicationContext context;

    @Test
    void agentChatWiringDoesNotReintroduceTheToolCallbackCycle() {
        assertNotNull(context.getBean(QaService.class),
                "QaService 能建出来 = ChatClient 那条依赖没有把环重新闭合");
        assertNotNull(context.getBean("kbChatClient", ChatClient.class));
        assertNotNull(context.getBean(ChatMemory.class));
        assertInstanceOf(InMemoryChatMemoryRepository.class,
                context.getBean(org.springframework.ai.chat.memory.ChatMemoryRepository.class));
    }

    /**
     * 锁"机制"而不是只锁"结果"：注入到 QaService 字段上的必须是延迟代理。
     * 若哪天有人把 @Lazy 摘掉而容器仍能凑巧启动（比如新增了一个不依赖工具的 ChatClient
     * 消费方），这个断言会先把"我们其实是在靠延迟解环"这件事说出来。
     */
    @Test
    void chatClientReachesQaServiceAsALazyProxy() {
        Object injected = ReflectionTestUtils.getField(context.getBean(QaService.class), "chatClient");
        assertNotNull(injected);
        assertInstanceOf(ChatClient.class, injected);
        assertTrue(AopUtils.isAopProxy(injected),
                "注入点必须拿到代理：ChatClient.builder() 构建期就解引用 ChatModel，"
                        + "真实例注入会在 bean 创建期闭合调用环");
    }

    @Test
    void mcpServerExposesTheFullToolList() {
        ToolCallbackProvider provider = context.getBean(ToolCallbackProvider.class);
        List<String> names = Arrays.stream(provider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition).map(d -> d.name()).sorted().toList();
        assertEquals(List.of(
                "eval_dataset_validate", "eval_list_runs", "eval_run_diff", "eval_run_report",
                "eval_run_start", "eval_run_status", "judge_error_record",
                "kb_ingest", "kb_query", "kb_retrieve"), names,
                "10 个工具=阶段2 kb-agent 的能力面，改名/漏挂都会让对端 tools/list 少东西");
    }

    /**
     * 会话窗口按条数裁剪（AGT-02 的成本上界承诺）：配置键没接上时这里会看到 25 条而不是 20 条。
     */
    @Test
    void chatMemoryTrimsToTheConfiguredWindow() {
        ChatMemory memory = context.getBean(ChatMemory.class);
        assertInstanceOf(MessageWindowChatMemory.class, memory);
        String cid = "ctx-window-probe";
        for (int i = 0; i < 25; i++) {
            memory.add(cid, List.of(new UserMessage("第" + i + "问"),
                    new AssistantMessage("第" + i + "答")));
        }
        List<Message> window = memory.get(cid);
        assertEquals(20, window.size(), "rag.memory.max-messages=20：超出即丢最旧，不随对话变长");
        assertEquals("第24问", window.get(window.size() - 2).getText(), "丢的是最旧的，留最近一轮问答");
    }
}
