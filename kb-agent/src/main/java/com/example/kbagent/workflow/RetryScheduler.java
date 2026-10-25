package com.example.kbagent.workflow;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.kbagent.domain.Ticket;
import com.example.kbagent.store.TicketRepository;

/**
 * 失败重试的驱动侧（第7周"失败重试 + 人工兜底"的重试那一半）。
 *
 * 退避时间戳落在 ticket.next_retry_at 上，本组件只负责"到点捞回来"：
 * ① 重启进程不丢重试计划——时间在库里，不在内存定时器里（内存任务表是崩溃即失的状态）；
 * ② 捞回后 workflow.retry 先 CAS 占位成 RETRYING，下一轮扫描不会重复消费同一单；
 * ③ ticket.agent.retry-scheduler-enabled=false 就退化成纯人工重试，
 *    排障或压测时可以单独掐掉重试而不影响主链路；
 * ④ 到点但次数已用尽的单也要捞——否则"重试耗尽转人工"永远等不到驱动者（见 abandonExhausted）。
 */
@Component
public class RetryScheduler {

    /** 单轮取数上限：积压时一批只处理这么多张 */
    static final int BATCH_LIMIT = 20;

    private final TicketRepository tickets;
    private final TicketWorkflow workflow;

    @Value("${ticket.agent.retry-scheduler-enabled:true}")
    private boolean enabled;

    @Value("${ticket.agent.max-attempts:3}")
    private int maxAttempts;

    public RetryScheduler(TicketRepository tickets, TicketWorkflow workflow) {
        this.tickets = tickets;
        this.workflow = workflow;
    }

    @Scheduled(fixedDelayString = "${ticket.agent.retry-scan-interval-ms:5000}",
            initialDelayString = "${ticket.agent.retry-scan-interval-ms:5000}")
    public void scan() {
        if (enabled) {
            long now = System.currentTimeMillis();
            retryDue(now, BATCH_LIMIT);
            abandonExhausted(now, BATCH_LIMIT);
        }
    }

    /** 单轮最多处理 BATCH_LIMIT 张：积压时不把模型配额一次烧光 */
    public int retryDue(long now, int limit) {
        List<Ticket> due = tickets.findRetryable(now, maxAttempts, limit);
        for (Ticket t : due) {
            try {
                workflow.retry(t.id());
            } catch (IllegalStateException e) {
                // 状态已被人工改动（比如已被批准结单）：让路，不是故障
                tickets.appendNote(t.id(), "AGENT", "重试让路: " + e.getMessage());
            }
        }
        return due.size();
    }

    /**
     * 耗尽单的兜底交人工。
     *
     * 为什么要有这一轮：findRetryable 只捞 attempts 还没用尽的单，所以"最后一次失败"之后
     * 没有任何驱动者会再碰它——"放弃重试转人工"这条承诺就只剩人工手动点重试才能兑现，
     * 工单会一直在 FAILED 里躺着（2026-10-25 实跑 rag-kb + kb-agent 双进程时抓到的）。
     * 判定与备注仍由 workflow.retry 的耗尽分支产出，本方法只负责"到点把它叫醒"，
     * 状态改成 PENDING_HUMAN 后自然不再被捞，重复扫描是幂等的。
     */
    public int abandonExhausted(long now, int limit) {
        List<Ticket> stranded = tickets.findAbandonable(now, maxAttempts, limit);
        for (Ticket t : stranded) {
            try {
                workflow.retry(t.id());
            } catch (IllegalStateException e) {
                tickets.appendNote(t.id(), "AGENT", "交人工让路: " + e.getMessage());
            }
        }
        return stranded.size();
    }
}
