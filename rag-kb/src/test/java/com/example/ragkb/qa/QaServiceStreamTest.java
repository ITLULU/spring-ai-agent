package com.example.ragkb.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ragkb.retrieval.HybridRetrievalService;
import com.example.ragkb.retrieval.RetrievedChunk;

import reactor.core.publisher.Flux;

/**
 * API-02 流式问答的行为锁（桩 ChatModel 分片吐字，不起 Spring 上下文）。
 *
 * 锁的是四条容易被"能流就行"糊过去的立场：
 * ① 事件序列固定 meta → delta* → done，引用清单在 delta 之前就到（前端能先挂脚注再渲染正文）；
 * ② 防线 1 在开流前跑完：低置信拒答不产生任何模型调用，也不该让客户端先看到半句真话；
 * ③ 模型侧异常收成一条 error 事件而不是把连接悄悄断掉——SSE 已经发过 200 头，
 *    此后抛异常前端只能看到"流没了"；
 * ④ 会话记忆在流式这条路径上同样生效且只写一轮（否则下一句"那加班呢"指代断链），
 *    conversationId 为空时依旧无状态（评测链路复用同一个 service）。
 */
class QaServiceStreamTest {

    /** 桩模型：按分片吐字，模拟真实供应商的 token 流 */
    private static final class StubChatModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        List<String> chunks = List.of("入职满 3 年", "享有 10 天年假", " [1]");
        RuntimeException failure;

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(String.join("", chunks)))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            if (failure != null) {
                return Flux.error(failure);
            }
            return Flux.fromIterable(chunks)
                    .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
        }
    }

    private final StubChatModel chatModel = new StubChatModel();
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
    private final HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
    private final QaService service = new QaService(retrieval, ChatClient.create(chatModel), chatMemory,
            new ClassPathResource("prompts/qa-system.st"));

    QaServiceStreamTest() {
        ReflectionTestUtils.setField(service, "minVectorSimilarity", 0.30);
    }

    private void givenHit(String content) {
        when(retrieval.retrieve(anyString())).thenReturn(List.of(
                new RetrievedChunk("c-1", content, content, "attendance_policy.md",
                        0.81, 7.4, 0.032, null)));
        when(retrieval.bestVectorScore(any())).thenReturn(0.81);
    }

    private List<ServerSentEvent<String>> stream(String question, String conversationId) {
        return service.answerStream(question, conversationId).collectList().block(Duration.ofSeconds(10));
    }

    private static String dataOf(ServerSentEvent<String> e) {
        return e.data() == null ? "" : e.data();
    }

    @Test
    void metaArrivesBeforeAnyDeltaAndCarriesTheCitationList() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        List<ServerSentEvent<String>> events = stream("入职满3年的员工有几天年假？", null);

        assertNotNull(events);
        assertEquals("meta", events.get(0).event(), "第一个事件必须是 meta：UI 要先拿到引用清单再渲染正文");
        assertTrue(dataOf(events.get(0)).contains("\"citations\""));
        assertTrue(dataOf(events.get(0)).contains("attendance_policy.md"));

        List<String> deltas = events.stream().filter(e -> "delta".equals(e.event()))
                .map(QaServiceStreamTest::dataOf).toList();
        assertEquals(chatModel.chunks.size(), deltas.size(), "每个分片一条 delta，服务端不做二次合并");
        assertEquals("done", events.get(events.size() - 1).event(), "流必须以 done 收尾，客户端才知道结束了");
        assertTrue(dataOf(events.get(events.size() - 1)).contains("\"citationValid\":true"),
                "done 要带引用后验结论：这就是边流式边校验的落点");
    }

    @Test
    void refusalIsDecidedBeforeTheStreamStartsAndSpendsNoToken() {
        when(retrieval.retrieve(anyString())).thenReturn(List.of());
        when(retrieval.bestVectorScore(any())).thenReturn(0.0);

        List<ServerSentEvent<String>> events = stream("公司几点上班？", "conv-refuse");

        assertTrue(dataOf(events.get(0)).contains("\"refused\":true"));
        assertEquals(3, events.size(), "拒答只发 meta + 一条固定拒答语 delta + done");
        assertTrue(dataOf(events.get(1)).contains(QueryResponse.REFUSAL));
        assertTrue(chatModel.prompts.isEmpty(), "防线 1 命中不得调模型（流式路径也一样）");
        assertEquals(2, chatMemory.get("conv-refuse").size(), "被拒答也要入记忆，追问才消解得了指代");
    }

    @Test
    void modelFailureBecomesAnErrorEventInsteadOfASilentlyBrokenStream() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");
        chatModel.failure = new IllegalStateException("HTTP 401 - Incorrect API key");

        List<ServerSentEvent<String>> events = stream("入职满3年的员工有几天年假？", null);

        assertEquals("error", events.get(events.size() - 1).event(),
                "已发出 200 + SSE 头之后再抛异常，前端只会看到连接断了、不知道为什么");
        assertTrue(dataOf(events.get(events.size() - 1)).contains("401"));
    }

    @Test
    void outOfRangeCitationIsReportedByTheDoneEvent() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");
        chatModel.chunks = List.of("满 3 年 10 天", " [9]");   // 只给了 1 条引用，[9] 越界

        List<ServerSentEvent<String>> events = stream("入职满3年的员工有几天年假？", null);
        String done = dataOf(events.get(events.size() - 1));

        assertTrue(done.contains("\"citationValid\":false"), "越界引用必须在 done 里被判为无效：" + done);
    }

    @Test
    void streamedTurnLandsInTheConversationWindowExactlyOnce() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        stream("入职满3年的员工有几天年假？", "conv-stream");

        List<Message> window = chatMemory.get("conv-stream");
        assertEquals(2, window.size(),
                "流式一轮也应落 user+assistant 各一条；0 条=下一句指代断链，4 条=advisor 与手工写入重复");
        assertEquals(MessageType.USER, window.get(0).getMessageType());
        assertEquals("入职满3年的员工有几天年假？", window.get(0).getText());
        assertEquals(MessageType.ASSISTANT, window.get(1).getMessageType());
        assertEquals("入职满 3 年享有 10 天年假 [1]", window.get(1).getText(),
                "窗口里存的是拼好的完整答案，不是分片");
        assertFalse(window.get(1).getText().contains("编号上下文"), "检索上下文不进窗口");
    }

    @Test
    void blankConversationIdKeepsTheStreamStatelessForTheEvalHarness() {
        givenHit("员工入职满 3 年享有 10 天带薪年假。");

        stream("第一题", "  ");
        stream("第二题", null);

        assertTrue(chatMemory.get(ChatMemory.DEFAULT_CONVERSATION_ID).isEmpty(),
                "无会话 ID 不能写进 default 会话");
        assertEquals(1, chatModel.prompts.get(1).getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER).count(),
                "无会话时 Prompt 里只应有本题那一条 user 消息");
    }
}
