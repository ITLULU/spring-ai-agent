package com.example.kbagent.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.kbagent.domain.Ticket;
import com.example.kbagent.domain.TicketStatus;
import com.example.kbagent.domain.TicketStatus.IllegalTransitionException;

/**
 * 工单存储（JdbcTemplate 直写 SQL，不引 ORM）。
 *
 * 两个刻意的选择：
 * ① **状态流转是 CAS**：{@code UPDATE ... WHERE id=? AND status=?}，
 *    0 行受影响即说明这单被别人抢先改过——抛 IllegalTransition 而不是静默覆盖。
 *    这是"Agent 与人工可能同时操作同一单"这条真实并发路径下唯一便宜的防线。
 * ② **窄更新而不是整行回写**：每个业务动作只更新它该改的列，
 *    读模型（可能已过期）永远不可能把别的列写回旧值。
 */
@Repository
public class TicketRepository {

    private final JdbcTemplate jdbc;

    public TicketRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 受理即落库：NEW 状态、attempts=0，后续每个节点各自窄更新 */
    public Ticket insert(String title, String description, String category, String priority) {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ticket (id, title, description, category, priority, status,
                                    attempts, next_retry_at, conversation_id, created_at, updated_at)
                VALUES (?,?,?,?,?, 'NEW', 0, 0, ?, ?, ?)""",
                id, title, description, category, priority, id, now, now);
        return findById(id).orElseThrow();
    }

    public Optional<Ticket> findById(String id) {
        return jdbc.query("SELECT * FROM ticket WHERE id = ?", TICKET_MAPPER, id).stream().findFirst();
    }

    public List<Ticket> list(TicketStatus status, int limit) {
        return status == null
                ? jdbc.query("SELECT * FROM ticket ORDER BY updated_at DESC FETCH FIRST ? ROWS ONLY",
                        TICKET_MAPPER, limit)
                : jdbc.query("SELECT * FROM ticket WHERE status = ? ORDER BY updated_at DESC FETCH FIRST ? ROWS ONLY",
                        TICKET_MAPPER, status.name(), limit);
    }

    /** 退避到期、还能再试的失败单（调度器的唯一取数入口，走 idx_ticket_retry） */
    public List<Ticket> findRetryable(long now, int maxAttempts, int limit) {
        return jdbc.query("""
                SELECT * FROM ticket WHERE status = 'FAILED' AND next_retry_at > 0
                  AND next_retry_at <= ? AND attempts < ? ORDER BY next_retry_at
                FETCH FIRST ? ROWS ONLY""",
                TICKET_MAPPER, now, maxAttempts, limit);
    }

    /**
     * 状态流转（CAS）。legalFrom 由调用方给出"我基于哪个状态做的决策"，
     * 与目标态一起过状态机——两个执行体同时把 NEW 单结掉时，后一个在这里撞墙。
     */
    public Ticket transition(String id, TicketStatus legalFrom, TicketStatus to, String resolvedBy) {
        if (!legalFrom.canTransitionTo(to)) {
            throw new IllegalTransitionException(legalFrom, to);
        }
        long now = System.currentTimeMillis();
        int updated = jdbc.update("""
                UPDATE ticket SET status = ?, resolved_by = COALESCE(?, resolved_by), updated_at = ?
                WHERE id = ? AND status = ?""",
                to.name(), resolvedBy, now, id, legalFrom.name());
        if (updated == 0) {
            Ticket current = findById(id).orElseThrow();
            throw new IllegalTransitionException(current.status(), to);
        }
        return findById(id).orElseThrow();
    }

    /** 处理产物：建议答复 + 置信度 + 引用快照（不改状态，状态由 transition 单独负责） */
    public void saveDraft(String id, String reply, double confidence, String citationsJson) {
        jdbc.update("""
                UPDATE ticket SET suggested_reply = ?, confidence = ?, citations_json = ?, updated_at = ?
                WHERE id = ?""",
                reply, confidence, citationsJson, System.currentTimeMillis(), id);
    }

    /** 分类结果回填（检索节点之前跑，一次写清 category/priority） */
    public void saveClassification(String id, String category, String priority) {
        jdbc.update("""
                UPDATE ticket SET category = COALESCE(?, category), priority = COALESCE(?, priority),
                                  updated_at = ? WHERE id = ?""",
                category, priority, System.currentTimeMillis(), id);
    }

    /** 失败记账：attempts++、错误原因、下次可重试时间点（退避由调用方算） */
    public void recordFailure(String id, String error, long nextRetryAt) {
        jdbc.update("""
                UPDATE ticket SET status = 'FAILED', attempts = attempts + 1, last_error = ?,
                                  next_retry_at = ?, updated_at = ? WHERE id = ?""",
                truncate(error), nextRetryAt, System.currentTimeMillis(), id);
    }

    /** 重试开始：先占位成 RETRYING，避免调度器下一轮把同一单再捞一次 */
    public Ticket markRetrying(String id) {
        return transition(id, TicketStatus.FAILED, TicketStatus.RETRYING, null);
    }

    /** 人工驳回后重开：清掉上次的建议与置信度，避免复核界面把旧结论当新结论 */
    public void resetForRerun(String id) {
        jdbc.update("""
                UPDATE ticket SET suggested_reply = NULL, confidence = NULL, citations_json = NULL,
                                  next_retry_at = 0, last_error = NULL, updated_at = ? WHERE id = ?""",
                System.currentTimeMillis(), id);
    }

    public void appendNote(String ticketId, String author, String body) {
        jdbc.update("INSERT INTO ticket_note (id, ticket_id, author, body, created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), ticketId, author, body, System.currentTimeMillis());
    }

    public List<Ticket.Note> notes(String ticketId) {
        return jdbc.query("SELECT * FROM ticket_note WHERE ticket_id = ? ORDER BY created_at",
                NOTE_MAPPER, ticketId);
    }

    private static final RowMapper<Ticket> TICKET_MAPPER = (rs, i) -> new Ticket(
            rs.getString("id"), rs.getString("title"), rs.getString("description"),
            rs.getString("category"), rs.getString("priority"),
            TicketStatus.valueOf(rs.getString("status")),
            rs.getString("suggested_reply"), nullableDouble(rs, "confidence"),
            rs.getString("citations_json"), rs.getInt("attempts"), rs.getString("last_error"),
            rs.getLong("next_retry_at"), rs.getString("conversation_id"),
            rs.getString("resolved_by"), rs.getLong("created_at"), rs.getLong("updated_at"));

    private static final RowMapper<Ticket.Note> NOTE_MAPPER = (rs, i) -> new Ticket.Note(
            rs.getString("id"), rs.getString("ticket_id"), rs.getString("author"),
            rs.getString("body"), rs.getLong("created_at"));

    /** 可空 DOUBLE 列：不同驱动可能回 Double 也可能回 BigDecimal，统一按 Number 收口 */
    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return v == null ? null : ((Number) v).doubleValue();
    }

    /** 错误原文可能是整段堆栈/上游响应，落库截断——列是 CLOB 但展示要能看 */
    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
    }
}
