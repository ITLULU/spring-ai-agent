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
 *    排障或压测时可以单独掐掉重试而不影响主链路。
 */
@Component
public class RetryScheduler {

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
            retryDue(System.currentTimeMillis(), 20);
        }
    }

    /** 单轮最多处理 limit 张：积压时不把模型配额一次烧光 */
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
}
