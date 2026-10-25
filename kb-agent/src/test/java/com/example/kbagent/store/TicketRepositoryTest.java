package com.example.kbagent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.kbagent.domain.Ticket;
import com.example.kbagent.domain.TicketStatus;
import com.example.kbagent.domain.TicketStatus.IllegalTransitionException;
import com.example.kbagent.testsupport.FreshH2;

/**
 * 存储层用**真 H2** 跑（内存库 + 真 schema.sql），不用 mock：
 * 这层的价值就在于 SQL 与 schema 对得上、CAS 条件真的生效，
 * 换成 mock 等于把要验证的东西假装验证了。
 */
class TicketRepositoryTest {

    private TicketRepository repo;

    @BeforeEach
    void setUp() {
        repo = new TicketRepository(FreshH2.dataSource());
    }

    @Test
    void insertLandsInNewWithOwnConversationId() {
        Ticket t = repo.insert("VPN 上不了", "今早连不上 VPN，提示认证失败", null, null);

        assertEquals(TicketStatus.NEW, t.status());
        assertEquals(0, t.attempts());
        assertEquals(t.id(), t.conversationId(), "工单默认用自己的会话 ID，追问自然接在同一条线上");
        assertEquals(0, t.nextRetryAt());
        assertNotNull(t.updatedAt());
    }

    @Test
    void transitionIsCasSoLoserOfRaceIsRejected() {
        Ticket t = repo.insert("报销", "打车票怎么贴", "expense", "P2");

        repo.transition(t.id(), TicketStatus.NEW, TicketStatus.AUTO_RESOLVED, "AGENT");

        IllegalTransitionException e = assertThrows(IllegalTransitionException.class,
                () -> repo.transition(t.id(), TicketStatus.NEW, TicketStatus.AUTO_RESOLVED, "AGENT"));
        assertTrue(e.getMessage().contains("AUTO_RESOLVED"),
                "并发让路时必须报出'当前真实状态'，而不是调用方以为的那个: " + e.getMessage());
    }

    @Test
    void illegalTransitionIsRejectedBeforeTouchingDb() {
        Ticket t = repo.insert("门禁卡", "卡丢了", null, null);

        assertThrows(IllegalTransitionException.class,
                () -> repo.transition(t.id(), TicketStatus.NEW, TicketStatus.RETRYING, null));
        assertEquals(TicketStatus.NEW, repo.findById(t.id()).orElseThrow().status());
    }

    @Test
    void failureRecordBumpsAttemptsAndKeepsRetryPlan() {
        Ticket t = repo.insert("打印机", "离线", null, null);
        long planned = System.currentTimeMillis() + 8000;

        repo.recordFailure(t.id(), "MCP 超时", planned);
        Ticket after = repo.findById(t.id()).orElseThrow();

        assertEquals(TicketStatus.FAILED, after.status());
        assertEquals(1, after.attempts());
        assertEquals(planned, after.nextRetryAt());
        assertTrue(after.lastError().contains("MCP 超时"));

        assertEquals(TicketStatus.RETRYING, repo.markRetrying(t.id()).status());
        assertEquals(1, repo.findById(t.id()).orElseThrow().attempts(), "markRetrying 不动 attempts，计数只属于失败");
    }

    @Test
    void retryableQueryHonoursDueTimeAndAttemptCap() {
        Ticket due = repo.insert("a", "到期了", null, null);
        repo.recordFailure(due.id(), "x", 1000L);
        Ticket future = repo.insert("b", "还没到点", null, null);
        repo.recordFailure(future.id(), "x", 9_999_999_999_999L);
        Ticket exhausted = repo.insert("c", "额度用光", null, null);
        repo.recordFailure(exhausted.id(), "x", 1000L);
        repo.recordFailure(exhausted.id(), "x", 1000L);
        repo.recordFailure(exhausted.id(), "x", 1000L);

        List<Ticket> due1 = repo.findRetryable(2000L, 3, 10);

        assertEquals(1, due1.size(), "只捞到期的、且还有重试额度的单");
        assertEquals(due.id(), due1.get(0).id());
        assertEquals(3, repo.findById(exhausted.id()).orElseThrow().attempts(),
                "attempts 到 3 后不再被调度器捞走，改走人工兜底（读模型不可变，计数要回库里看）");
        assertTrue(repo.findRetryable(500L, 3, 10).isEmpty(), "时间没到就不该重试");
    }

    @Test
    void draftAndClassificationAreNarrowUpdates() {
        Ticket t = repo.insert("考勤", "忘打卡", "hr_policy", "P2");

        repo.saveDraft(t.id(), "按制度补卡即可 [1]", 0.91, "[{\"no\":1}]");
        repo.saveClassification(t.id(), null, "P1");

        Ticket after = repo.findById(t.id()).orElseThrow();
        assertEquals(TicketStatus.NEW, after.status(), "写草稿不许顺手改状态");
        assertEquals(0.91, after.confidence(), 1e-9);
        assertEquals("P1", after.priority(), "COALESCE 语义：传 null 保留原值");
        assertEquals("hr_policy", after.category());
        assertNotNull(repo.list(TicketStatus.NEW, 10).size());
    }

    @Test
    void resetForRerunClearsStaleDraftSoReviewSeesFreshResult() {
        Ticket t = repo.insert("报销", "旧结论", null, null);
        repo.saveDraft(t.id(), "旧答复", 0.8, "[{\"no\":1}]");

        repo.resetForRerun(t.id());

        Ticket after = repo.findById(t.id()).orElseThrow();
        assertNull(after.suggestedReply());
        assertNull(after.confidence());
        assertEquals(0, after.nextRetryAt(), "重开的单不能被上一轮的退避时间戳继续拖着");
    }

    @Test
    void notesKeepInsertionOrderAndAuthor() {
        Ticket t = repo.insert("账号", "锁定", null, null);
        repo.appendNote(t.id(), "AGENT", "分诊: it_account");
        repo.appendNote(t.id(), "HUMAN", "驳回: 还缺截图");

        List<Ticket.Note> notes = repo.notes(t.id());
        assertEquals(2, notes.size());
        assertEquals("AGENT", notes.get(0).author());
        assertEquals("HUMAN", notes.get(1).author(), "审核轨迹按时间正序，人读起来才是流水账而不是倒序");
    }
}
