package com.example.kbagent.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工单状态机（阶段2 第5周的领域骨架）。
 *
 * 转移表写成枚举里的显式集合，而不是散落在 if/else 里——理由：
 * ① 状态转移图能直接打印出来（{@link #allowedNext()}），面试/评审要的就是这张图；
 * ② 非法转移在领域层就被拒绝，DAO 的 CAS（WHERE status=?）只是第二道防线；
 * ③ 加一个状态时编译器会强制我逐个 case 补齐，不会出现"漏了一个分支没人发现"。
 *
 * <pre>
 *   NEW ─────────────┬──────────────► AUTO_RESOLVED   （置信度达标，自动结单）
 *                    ├──────────────► PENDING_HUMAN   （低置信/高风险，转人工）
 *                    └──────────────► FAILED          （链路异常，待重试）
 *   FAILED ──────────┬──────────────► RETRYING        （退避到期，重新处理）
 *                    ├──────────────► AUTO_RESOLVED   （重试成功后直接结单）
 *                    └──────────────► PENDING_HUMAN   （重试额度烧光，转人工兜底）
 *   RETRYING ────────┬──────────────► AUTO_RESOLVED / PENDING_HUMAN / FAILED
 *   PENDING_HUMAN ───┬──────────────► AUTO_RESOLVED   （人工批准 Agent 口径）
 *                    └──────────────► NEW             （人工驳回，带补充线索重跑）
 *   AUTO_RESOLVED ───┴──────────────► PENDING_HUMAN   （用户追问/抽检不通过，拉回人工）
 * </pre>
 */
public enum TicketStatus {

    /** 已受理，尚未处理 */
    NEW,
    /** Agent 自动给出可结单答复（有引用且置信度过闸门） */
    AUTO_RESOLVED,
    /** 人在回路：等人工批准/驳回 */
    PENDING_HUMAN,
    /** 处理链路失败，可能还会重试 */
    FAILED,
    /** 退避到期后的重试执行中 */
    RETRYING;

    private static final Set<TicketStatus> PROCESS_OUTCOMES =
            Set.of(AUTO_RESOLVED, PENDING_HUMAN, FAILED);

    /** 从本状态出发唯一合法的目标状态集合（顺序固定，便于打印状态图） */
    public Set<TicketStatus> allowedNext() {
        Set<TicketStatus> next = new LinkedHashSet<>();
        switch (this) {
            case NEW, RETRYING -> next.addAll(PROCESS_OUTCOMES);
            // 重试成功后没经过 NEW，允许它直接结单，省一次无意义的状态绕行
            // 重试烧光后必须有人接：FAILED → PENDING_HUMAN 是"人工兜底"那条边，
            // 没有它就会出现的终态是"重试到上限、然后永远躺在 FAILED 里没人知道"
            case FAILED -> {
                next.add(RETRYING);
                next.add(AUTO_RESOLVED);
                next.add(PENDING_HUMAN);
            }
            // 人工驳回不是"失败"，是带着新线索重跑：回 NEW 而不是 FAILED，
            // 否则会把人的判断记进错误重试通道，attempts 语义被污染
            case PENDING_HUMAN -> {
                next.add(AUTO_RESOLVED);
                next.add(NEW);
            }
            // 已结单仍可被追问或抽检拉回人工，但不能再被 Agent 直接改写结论
            case AUTO_RESOLVED -> next.add(PENDING_HUMAN);
        }
        return Set.copyOf(next);
    }

    public boolean canTransitionTo(TicketStatus target) {
        return target != null && allowedNext().contains(target);
    }

    /** 非法转移统一抛这一种异常，Controller 层据此返回 409 而不是 500 */
    public static class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(TicketStatus from, TicketStatus to) {
            super("非法状态转移: %s → %s（合法目标: %s）".formatted(from, to, from.allowedNext()));
        }
    }
}
