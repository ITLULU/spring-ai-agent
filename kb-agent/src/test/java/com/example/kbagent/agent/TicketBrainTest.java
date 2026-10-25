package com.example.kbagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ClassPathResource;

import com.example.kbagent.store.TicketRepository;

/**
 * 模型节点（TicketBrain）的行为锁，用桩 ChatModel 看真实装配出来的 Prompt：
 * ① 分诊/装配走 {@code .entity(Record)} 结构化输出——流水线按字段路由，
 *    自由文本再正则抠字段是最脆的做法，所以这里锁的是"字段真的解析出来了"；
 * ② 提示词来自外置 .st，模板变量已渲染（AGT-01 的口径）；
 * ③ agentic 入口把 **MCP 发现的工具 + 本地工单工具**一起挂给模型，
 *    且本地工具里没有任何改状态的口子——这是 PRO-02 消费侧的契约面；
 * ④ 多轮：有 conversationId 才挂记忆 advisor，空 ID 保持无状态（与 rag-kb 同纪律）。
 */
class TicketBrainTest {

    /** 桩模型：记录 Prompt，按脚本返回文本（.entity() 的解析发生在 ChatClient 侧，不在模型侧） */
    private static final class StubChatModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        String reply = "ok";

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    private static ToolCallback fakeMcpTool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("来自 rag-kb 的 MCP 工具")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }

    private final StubChatModel chatModel = new StubChatModel();
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
    private final KbMcpGateway gateway = mock(KbMcpGateway.class);
    private final TicketBrain brain = newTicketBrain();

    private TicketBrain newTicketBrain() {
        // 裸 mock 的数组返回值是 null，而真实 KbMcpGateway 保证返回空数组——
        // ChatClient 对 null 直接抛 IllegalArgument，所以按真实契约先冻一个"没有工具"的默认值
        when(gateway.discovered()).thenReturn(new ToolCallback[0]);
        return new TicketBrain(ChatClient.create(chatModel), chatMemory, gateway,
                new TicketLocalTools(mock(TicketRepository.class)),
                new ClassPathResource("prompts/classify.st"),
                new ClassPathResource("prompts/draft.st"),
                new ClassPathResource("prompts/agentic-system.st"));
    }

    private static String system(Prompt p) {
        return p.getInstructions().stream().filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .findFirst().orElseThrow().getText();
    }

    @Test
    void classifyRendersExternalTemplateAndParsesStructuredFields() {
        chatModel.reply = """
                {"category":"hr_policy","priority":"P1","searchQuery":"入职满3年的员工享有几天带薪年假"}""";

        TicketBrain.Classification c = brain.classify("年假咨询", "能不能晚点到，入职满3年有几天年假");

        assertEquals("hr_policy", c.category());
        assertEquals("P1", c.priority());
        assertEquals("入职满3年的员工享有几天带薪年假", c.searchQuery(),
                "searchQuery 是'口语诉求→检索问句'的改写产物，流水线拿它去调 kb_retrieve");

        String sent = system(chatModel.prompts.get(0));
        assertTrue(sent.contains("工单标题：年假咨询"), "classify.st 的 {title} 未渲染: " + sent);
        assertTrue(sent.contains("能不能晚点到"), "{description} 未渲染");
        assertTrue(sent.contains("只做分类"), "system 文案应来自外置模板而不是硬编码");
    }

    @Test
    void draftParsesReplyAndSelfConfidence() {
        chatModel.reply = """
                {"reply":"入职满 3 年享有 10 天带薪年假 [1]","citedFaithfully":true,"selfConfidence":0.82}""";

        TicketBrain.Draft d = brain.draft("年假咨询", "入职满3年有几天年假",
                "{\"answer\":\"满 3 年 10 天 [1]\",\"refused\":false}");

        assertEquals("入职满 3 年享有 10 天带薪年假 [1]", d.reply());
        assertTrue(d.citedFaithfully());
        assertEquals(0.82, d.selfConfidence(), 1e-9);

        String sent = system(chatModel.prompts.get(0));
        assertTrue(sent.contains("知识库答复（refused 为 true 表示知识库判定无法回答）：\n{\"answer\""),
                "装配节点必须看到 kb_query 的真实产物原文: " + sent);
    }

    @Test
    void agenticAskAdvertisesMcpToolsPlusLocalTools() {
        when(gateway.discovered()).thenReturn(new ToolCallback[] {
                fakeMcpTool(KbMcpGateway.KB_QUERY), fakeMcpTool(KbMcpGateway.KB_RETRIEVE)});

        brain.ask("加班怎么调休", null);

        ToolCallingChatOptions options = assertInstanceOf(ToolCallingChatOptions.class,
                chatModel.prompts.get(0).getOptions(), "带工具的调用必须把工具交给模型的 tool-calling 通道");
        List<String> names = options.getToolCallbacks().stream()
                .map(cb -> cb.getToolDefinition().name()).toList();
        assertTrue(names.contains("kb_query"), "MCP 发现的知识库工具要挂上: " + names);
        assertTrue(names.contains("ticket_read") && names.contains("ticket_append_note"),
                "本地工单工具也要挂上: " + names);
        assertFalse(names.contains("ticket_transition"),
                "本地工具里绝不能出现改状态的口子——流转只属于确定性闸门");
    }

    @Test
    void followUpInSameConversationSeesHistoryWhileBlankIdStaysStateless() {
        chatModel.reply = "加班按 1:1 调休 [1]";

        brain.ask("加班怎么算", "ticket-1");
        brain.ask("那周末呢", "ticket-1");
        Prompt second = chatModel.prompts.get(1);

        assertTrue(second.getInstructions().stream().anyMatch(m -> m.getText().contains("加班怎么算")),
                "同会话第二轮要带上上一轮，指代才消解得了");
        assertEquals(MessageType.USER, second.getInstructions().get(second.getInstructions().size() - 1)
                .getMessageType(), "本轮问题必须排在最后");

        brain.ask("独立一问", "  ");
        assertTrue(chatMemory.get(ChatMemory.DEFAULT_CONVERSATION_ID).isEmpty(),
                "空 conversationId 不得写进 default 会话");
    }

    @Test
    void sessionIsReadableAndClearableForReviewUI() {
        chatModel.reply = "满 3 年 10 天 [1]";

        brain.ask("年假几天", "conv-9");
        assertEquals(2, brain.session("conv-9").size());

        brain.clearSession("conv-9");
        assertTrue(brain.session("conv-9").isEmpty());
        assertTrue(brain.session(null).isEmpty(), "无会话 ID 读历史返回空而不是炸");
    }
}
