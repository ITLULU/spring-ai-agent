package com.example.kbagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.kbagent.domain.Ticket;
import com.example.kbagent.domain.TicketStatus;
import com.example.kbagent.store.TicketRepository;
import com.example.kbagent.testsupport.FreshH2;

/**
 * 重试驱动侧的取数与让路逻辑。这里<b>不</b>跑真流水线（那是 TicketWorkflowTest 的职责），
 * 只锁调度器自己的三条纪律：
 * ① 取数分两路且互不越权：还能试的走 retryDue（到期 + attempts<上限），烧光额度的走
 *    abandonExhausted（到期 + attempts≥上限）；单轮各自限量（积压时不一次把模型配额烧光）；
 * ② 状态被人工改过 → 记一条"重试让路"就完事，绝不让异常冒出 @Scheduled（一次抛出会让后续调度被抑制）；
 * ③ 开关关掉后完全不取数——排障/压测时要能单独掐掉重试而不影响主链路。
 */
class RetrySchedulerTest {

    private TicketRepository repo;
    private TicketWorkflow workflow;
    private RetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        repo = new TicketRepository(FreshH2.dataSource());
        workflow = mock(TicketWorkflow.class);
        scheduler = new RetryScheduler(repo, workflow);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "maxAttempts", 3);
    }

    private Ticket failed(long nextRetryAt, int times) {
        Ticket t = repo.insert("打印机", "脱机", "it_hardware", "P2");
        for (int i = 0; i < times; i++) {
            repo.recordFailure(t.id(), "mcp down", nextRetryAt);
        }
        return t;
    }

    @Test
    void picksUpOnlyDueTicketsWithinAttemptBudget() {
        Ticket due = failed(System.currentTimeMillis() - 1, 1);
        Ticket notDue = failed(System.currentTimeMillis() + 600_000, 1);
        Ticket exhausted = failed(System.currentTimeMillis() - 1, 3);

        int picked = scheduler.retryDue(System.currentTimeMillis(), 20);

        assertEquals(1, picked, "到期的、且还有额度的才算一轮工作");
        verify(workflow).retry(due.id());
        verify(workflow, never()).retry(notDue.id());
        verify(workflow, never()).retry(exhausted.id());
        assertEquals(TicketStatus.FAILED, repo.findById(exhausted.id()).orElseThrow().status(),
                "retryDue 这一路不碰烧光额度的单——交人工由 abandonExhausted 那一轮负责，"
                        + "两路各自取数互不越权，判据仍只在 workflow.retry 里");
    }

    @Test
    void exhaustionSweepPicksUpOnlyDueTicketsWithNoRetryBudgetLeft() {
        Ticket stranded = failed(System.currentTimeMillis() - 1, 3);
        Ticket stillHasBudget = failed(System.currentTimeMillis() - 1, 2);
        Ticket notDueYet = failed(System.currentTimeMillis() + 600_000, 3);

        int handedOver = scheduler.abandonExhausted(System.currentTimeMillis(), 20);

        assertEquals(1, handedOver, "只有到期且额度用尽的才该转人工，其余交给重试那一轮");
        verify(workflow).retry(stranded.id());
        verify(workflow, never()).retry(stillHasBudget.id());
        verify(workflow, never()).retry(notDueYet.id());
    }

    @Test
    void limitCapsOneSweepSoBacklogDoesNotBurnModelQuota() {
        for (int i = 0; i < 4; i++) {
            failed(System.currentTimeMillis() - 1 - i, 1);
        }

        int picked = scheduler.retryDue(System.currentTimeMillis(), 2);

        assertEquals(2, picked, "单轮限量：积压 4 张先跑 2 张，剩下的下一轮");
    }

    @Test
    void humanChangedStateFirstSoRetryStepsAsideWithoutThrowing() {
        Ticket t = failed(System.currentTimeMillis() - 1, 1);
        when(workflow.retry(anyString()))
                .thenThrow(new IllegalStateException("只有 FAILED 工单能重试，当前 AUTO_RESOLVED"));

        int picked = scheduler.retryDue(System.currentTimeMillis(), 20);

        assertEquals(1, picked);
        List<Ticket.Note> notes = repo.notes(t.id());
        assertTrue(notes.stream().anyMatch(n -> n.body().contains("重试让路")
                && n.body().contains("AUTO_RESOLVED")),
                "让路要留痕到人能看懂的一句话，否则监控只会看到一个重试次数不再增长的诡异单");
    }

    @Test
    void scanRunsTheSweepOnlyWhenTheSwitchIsOn() {
        Ticket t = failed(System.currentTimeMillis() - 1, 1);

        scheduler.scan();

        verify(workflow).retry(t.id());
    }

    @Test
    void switchedOffSweepDoesNotEvenQueryTheDatabase() {
        TicketRepository untouched = mock(TicketRepository.class);
        RetryScheduler off = new RetryScheduler(untouched, workflow);
        ReflectionTestUtils.setField(off, "enabled", false);
        ReflectionTestUtils.setField(off, "maxAttempts", 3);

        off.scan();

        verifyNoInteractions(untouched, workflow);
    }
}
