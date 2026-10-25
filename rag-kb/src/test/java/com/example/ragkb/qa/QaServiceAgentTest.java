package com.example.ragkb.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ragkb.retrieval.HybridRetrievalService;
import com.example.ragkb.retrieval.RetrievedChunk;

/**
 * AGT-01/AGT-02/QA-01 的行为锁（不起 Spring 上下文，用桩 ChatModel 看真实装配出来的 Prompt）：
 * ① 检索上下文只进 system，会话窗口只存 question/answer（否则历史上下文会被模型当成本轮依据）；
 * ② 同 conversationId 的第二轮能看到第一轮问答，不同 ID 互相看不到；
 * ③ conversationId 为空即无状态——评测链路必须走这条分支，否则 36 题共享一个会话，数字失真；
 * ④ 防线 1 拒答不调模型但仍入记忆（下一句追问要能知道被拒过），且 elapsedMs==retrievalMs；
 * ⑤ QA-01：线上入口的 elapsedMs 含检索段。
 */
class QaServiceAgentTest {

    /** 桩模型：记录每次真正发给模型的 Prompt，返回固定答案 */
    private static final class StubChatModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        String reply = "入职满 3 年享有 10 天年假 [1]";

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    private final StubChatModel chatModel = new StubChatModel();
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
    private final HybridRetrievalService retrieval = mock(HybridRetrievalService.class);

    private final ChatClient chatClient = ChatClient.create(chatModel);
    private final QaService service = newService();

    private QaService newService() {
        QaService s = new QaService(retrieval, chatClient, chatMemory,
                new ClassPathResource("prompts/qa-system.st"));
        ReflectionTestUtils.setField(s, "minVectorSimilarity", 0.30);
        return s;
    }

    private void givenHit(String content) {
        when(retrieval.retrieve(anyString())).thenReturn(List.of(
                new RetrievedChunk("c-1", content, content, "attendance_policy.md",
                        0.81, 7.4, 0.032, null)));
        when(retrieval.bestVectorScore(any())).thenReturn(0.81);
    }

    private static String textOf(Message m) {
        return m.getText();
    }

    @Test
    void systemTemplateCarriesContextAndIsRendered() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        QueryResponse r = service.answer("入职满3年的员工有几天年假？", null);

        assertFalse(r.refused());
        Prompt sent = chatModel.prompts.get(0);
        String system = sent.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .findFirst().orElseThrow().getText();
        assertTrue(system.contains("[1] (来源: attendance_policy.md)"), "模板变量 {context} 未渲染进 system");
        assertTrue(system.contains("只输出四个字：无法回答"), "system 文案应来自外置模板 qa-system.st");
        assertEquals(1, r.citations().size());
    }

    @Test
    void conversationMemoryKeepsOnlyQuestionAndAnswerNotContext() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        service.answer("入职满3年的员工有几天年假？", "conv-1");

        List<Message> history = chatMemory.get("conv-1");
        assertEquals(2, history.size(), "一轮问答应落 2 条（user + assistant）");
        assertEquals(MessageType.USER, history.get(0).getMessageType());
        assertEquals("入职满3年的员工有几天年假？", textOf(history.get(0)));
        assertEquals(MessageType.ASSISTANT, history.get(1).getMessageType());
        for (Message m : history) {
            assertFalse(textOf(m).contains("编号上下文"), "检索上下文不得进会话窗口（窗口会被撑爆且污染依据）");
            assertFalse(textOf(m).contains("来源: attendance_policy.md"), "同上");
        }
    }

    @Test
    void followUpSeesHistoryButOtherConversationDoesNot() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        service.answer("入职满3年的员工有几天年假？", "conv-1");
        service.answer("那加班呢？", "conv-1");
        Prompt second = chatModel.prompts.get(1);

        boolean historyInjected = second.getInstructions().stream()
                .anyMatch(m -> textOf(m).contains("10 天年假"));
        assertTrue(historyInjected, "同会话第二轮必须把上一轮答案注入进来，指代才可能消解");
        assertEquals(MessageType.USER, second.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER).reduce((a, b) -> b).orElseThrow().getMessageType());

        service.answer("那加班呢？", "conv-2");
        Prompt isolated = chatModel.prompts.get(2);
        assertFalse(isolated.getInstructions().stream().anyMatch(m -> textOf(m).contains("10 天年假")),
                "不同 conversationId 之间必须隔离");
    }

    @Test
    void blankConversationIdStaysStatelessForEvalHarness() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        service.answer("第一题", "  ");
        service.answer("第二题", null);

        assertTrue(chatMemory.get(ChatMemory.DEFAULT_CONVERSATION_ID).isEmpty(),
                "无会话 ID 不能写进 default 会话——评测每题都得是无状态的");
        assertEquals(1, chatModel.prompts.get(1).getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER).count(),
                "无会话时 Prompt 里只应有本题那一条 user 消息");
    }

    @Test
    void retrievalLayerRefusalSkipsModelButIsRememberedAndTimed() {
        when(retrieval.retrieve(anyString())).thenReturn(List.of());
        when(retrieval.bestVectorScore(any())).thenReturn(0.0);

        long t0 = System.currentTimeMillis();
        QueryResponse r = service.answer("公司几点上班？", "conv-refuse");

        assertTrue(r.refused());
        assertEquals(QueryResponse.REFUSAL, r.answer());
        assertEquals(0, r.citations().size());
        assertEquals(0, r.promptTokens(), "拒答省一次生成：token 记账必须是 0 而非 -1");
        assertTrue(chatModel.prompts.isEmpty(), "防线 1 命中不得调模型");
        assertTrue(r.retrievalMs() >= 0, "QA-01：检索段耗时需单独暴露");
        assertEquals(r.retrievalMs(), r.elapsedMs(), "拒答没有生成段，总时延=检索段");
        assertTrue(System.currentTimeMillis() - t0 >= 0);

        List<Message> history = chatMemory.get("conv-refuse");
        assertEquals(2, history.size(), "被拒答也是一轮线上行为，追问要靠它消解指代");
        assertEquals(QueryResponse.REFUSAL, textOf(history.get(1)));
    }

    @Test
    void onlinePathElapsedTimeIncludesRetrievalSegment() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        QueryResponse r = service.answer("入职满3年的员工有几天年假？", null);

        assertTrue(r.elapsedMs() >= r.retrievalMs(), "elapsedMs 必须含检索段（QA-01 修复点）");
        assertTrue(r.retrievalMs() >= 0);
    }

    @Test
    void windowTrimsOldestTurnsBeyondMaxMessages() {
        ChatMemory small = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(2).build();
        QaService svc = new QaService(retrieval, chatClient, small,
                new ClassPathResource("prompts/qa-system.st"));
        ReflectionTestUtils.setField(svc, "minVectorSimilarity", 0.30);
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        svc.answer("第一问", "conv-win");
        svc.answer("第二问", "conv-win");

        List<Message> window = small.get("conv-win");
        assertEquals(2, window.size(), "窗口按配置条数裁剪，旧轮次被挤出");
        assertEquals("第二问", textOf(window.get(0)), "保留的应是最近一轮");
    }
}
