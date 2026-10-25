package com.example.kbagent.agent;

import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.example.kbagent.domain.Ticket;
import com.example.kbagent.store.TicketRepository;

/**
 * Agent 的本地工具（与 MCP 发现的知识库工具挂在同一个 ChatClient 上）。
 *
 * 一条硬纪律：**这里没有任何能改工单状态的工具**。
 * 模型可以读工单、可以追加处理记录，但 AUTO_RESOLVED / PENDING_HUMAN 的流转
 * 只由 TicketWorkflow 的确定性闸门决定——状态机是审计对象，不是模型的自由输出。
 * 这条边界就写在 description 里（HITL 闸门写进协议元数据，与 rag-kb 的
 * judge_error_record 同一手法）。
 */
@Component
public class TicketLocalTools {

    private final TicketRepository tickets;

    public TicketLocalTools(TicketRepository tickets) {
        this.tickets = tickets;
    }

    @Tool(name = "ticket_read", description = """
            读取一张工单的现场：标题、诉求、当前状态、上一轮 Agent 建议与置信度、重试次数。
            需要引用工单原文或判断进展到哪一步时用本工具；它只读，不改状态。
            """)
    public String ticketRead(@ToolParam(description = "工单 ID") String ticketId) {
        Optional<Ticket> t = tickets.findById(ticketId);
        if (t.isEmpty()) {
            return "{\"error\":\"ticket not found: " + ticketId + "\"}";
        }
        Ticket x = t.get();
        return """
                {"id":"%s","title":"%s","description":"%s","category":"%s","priority":"%s",
                 "status":"%s","confidence":%s,"attempts":%d,"suggestedReply":%s}"""
                .formatted(x.id(), esc(x.title()), esc(x.description()), esc(x.category()),
                        esc(x.priority()), x.status(),
                        x.confidence() == null ? "null" : String.format("%.3f", x.confidence()),
                        x.attempts(), x.suggestedReply() == null ? "null" : "\"" + esc(x.suggestedReply()) + "\"");
    }

    @Tool(name = "ticket_append_note", description = """
            给工单追加一条处理记录（AGENTS 视角的判断依据、需要人工注意的点）。
            仅用于留痕；结单、转人工、重开都不属于本工具能力，由服务端状态机与人工审核决定。
            """)
    public String ticketAppendNote(
            @ToolParam(description = "工单 ID") String ticketId,
            @ToolParam(description = "记录内容，简明一句话") String body) {
        if (tickets.findById(ticketId).isEmpty()) {
            return "failed: ticket not found " + ticketId;
        }
        tickets.appendNote(ticketId, "AGENT", body == null ? "" : body);
        return "ok: note appended";
    }

    /** 手搓 JSON 转义足够用：这些字段只进模型上下文，不进机器解析 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
