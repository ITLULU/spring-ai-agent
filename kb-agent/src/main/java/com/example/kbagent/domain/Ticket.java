package com.example.kbagent.domain;

/**
 * 工单读模型（一行一条）。状态之外的字段都是"处理现场"：
 * 建议答复 + 置信度 + 引用快照 + 重试计数，够人工复核时不用回头猜 Agent 当时看到什么。
 *
 * 不可变：流转一律走 {@code TicketRepository#transition}（带 WHERE status=? 的 CAS），
 * 改内存对象再整行写回那种写法在并发下会把别人的流转覆盖掉，这里从类型上不给这个机会。
 *
 * 高风险判定、重试是否到期这类"要看配置的判断"不放进读模型——它们的上限/名单来自
 * ticket.agent.* 配置，唯一判据在 TicketWorkflow，避免同一个问题两处口径。
 */
public record Ticket(
        String id,
        String title,
        String description,
        String category,
        String priority,
        TicketStatus status,
        String suggestedReply,
        Double confidence,
        String citationsJson,
        int attempts,
        String lastError,
        long nextRetryAt,
        String conversationId,
        String resolvedBy,
        long createdAt,
        long updatedAt) {

    /** 一条处理记录：author 区分 AGENT / HUMAN，审核轨迹可回放 */
    public record Note(String id, String ticketId, String author, String body, long createdAt) {
    }
}
