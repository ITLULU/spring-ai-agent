package com.example.kbagent.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.kbagent.domain.TicketStatus.IllegalTransitionException;

/**
 * 状态机是唯一"业务上允许什么发生"的判据，这张表必须被测试钉住：
 * 文档里的状态转移图与代码不一致时，先红在这里。
 */
class TicketStatusTest {

    @Test
    void newTicketCanGoToTheThreeProcessingOutcomes() {
        assertTrue(TicketStatus.NEW.canTransitionTo(TicketStatus.AUTO_RESOLVED));
        assertTrue(TicketStatus.NEW.canTransitionTo(TicketStatus.PENDING_HUMAN));
        assertTrue(TicketStatus.NEW.canTransitionTo(TicketStatus.FAILED));
        assertFalse(TicketStatus.NEW.canTransitionTo(TicketStatus.RETRYING),
                "RETRYING 只能由 FAILED 经退避进入，新单没失败过");
    }

    @Test
    void failedIsTheOnlyStateThatEntersRetrying() {
        assertTrue(TicketStatus.FAILED.canTransitionTo(TicketStatus.RETRYING));
        assertFalse(TicketStatus.RETRYING.canTransitionTo(TicketStatus.RETRYING),
                "重试中不能再开一轮重试，否则调度器会让同一单并发跑两次");
        assertFalse(TicketStatus.AUTO_RESOLVED.canTransitionTo(TicketStatus.RETRYING));
    }

    @Test
    void resolvedTicketCannotBeRewrittenByTheAgentButCanComeBackToHuman() {
        assertTrue(TicketStatus.AUTO_RESOLVED.canTransitionTo(TicketStatus.PENDING_HUMAN));
        assertFalse(TicketStatus.AUTO_RESOLVED.canTransitionTo(TicketStatus.AUTO_RESOLVED),
                "重复结单必须是非法转移——它正是并发覆盖的表现");
        assertFalse(TicketStatus.AUTO_RESOLVED.canTransitionTo(TicketStatus.NEW));
    }

    @Test
    void humanRejectReopensAsNewNotFailed() {
        assertTrue(TicketStatus.PENDING_HUMAN.canTransitionTo(TicketStatus.NEW),
                "人工驳回带着新线索重跑，走 NEW 而不是 FAILED，不占重试额度");
        assertFalse(TicketStatus.PENDING_HUMAN.canTransitionTo(TicketStatus.FAILED));
    }

    @Test
    void everyStateIsReachableBackToATerminal() {
        for (TicketStatus s : TicketStatus.values()) {
            assertFalse(s.allowedNext().isEmpty(), s + " 不能有死路（否则工单永久卡住）");
        }
    }

    @Test
    void nullTargetIsRejectedAndMessageNamesBothEnds() {
        assertFalse(TicketStatus.NEW.canTransitionTo(null), "目标态为空必须判非法，不能当成漏网放行");
        IllegalTransitionException e = new IllegalTransitionException(TicketStatus.AUTO_RESOLVED,
                TicketStatus.RETRYING);
        assertTrue(e.getMessage().contains("AUTO_RESOLVED"), e.getMessage());
        assertTrue(e.getMessage().contains("RETRYING"), e.getMessage());
        assertTrue(e.getMessage().contains("非法状态转移"), "文案要让运维一眼看出是状态机拦的，不是数据库错");
    }
}
