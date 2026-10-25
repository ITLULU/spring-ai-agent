package com.example.kbagent.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.kbagent.agent.KbMcpGateway;
import com.example.kbagent.agent.TicketBrain;
import com.example.kbagent.domain.Ticket;
import com.example.kbagent.domain.TicketStatus;
import com.example.kbagent.store.TicketRepository;
import com.example.kbagent.workflow.TicketWorkflow;

/**
 * 工单 Agent 的对外端点。三条能力线各自的入口：
 * 流水线（/api/tickets 受理即全自动跑完）、人在回路（/review）、
 * Agentic 自由问答（/ask，模型自己选 MCP 工具）；
 * 另外两条是给评审/面试看的自证端点：状态转移表与 MCP 能力清单。
 */
@RestController
@RequestMapping("/api")
public class TicketController {

    private final TicketWorkflow workflow;
    private final TicketRepository tickets;
    private final TicketBrain brain;
    private final KbMcpGateway kb;

    public TicketController(TicketWorkflow workflow, TicketRepository tickets,
                            TicketBrain brain, KbMcpGateway kb) {
        this.workflow = workflow;
        this.tickets = tickets;
        this.brain = brain;
        this.kb = kb;
    }

    public record AcceptRequest(String title, String description, String category, String priority) {
    }

    public record ReviewRequest(String decision, String note) {
    }

    public record AskRequest(String question, String conversationId) {
    }

    @PostMapping("/tickets")
    public Map<String, Object> accept(@RequestBody AcceptRequest req) {
        if (req == null || req.description() == null || req.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description 不能为空");
        }
        return workflow.view(workflow.accept(req.title(), req.description(), req.category(), req.priority()));
    }

    @GetMapping("/tickets")
    public List<Map<String, Object>> list(@RequestParam(required = false) TicketStatus status,
                                          @RequestParam(defaultValue = "50") int limit) {
        return tickets.list(status, Math.min(limit, 200)).stream().map(workflow::view).toList();
    }

    @GetMapping("/tickets/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Ticket t = tickets.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "工单不存在: " + id));
        return Map.of("ticket", workflow.view(t), "notes", workflow.notes(id));
    }

    /** 人工审核：APPROVE 结单；REJECT 带着人的线索重开并立刻重跑流水线 */
    @PostMapping("/tickets/{id}/review")
    public Map<String, Object> review(@PathVariable String id, @RequestBody ReviewRequest req) {
        if (req == null || req.decision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision 必须是 APPROVE/REJECT");
        }
        TicketWorkflow.Decision decision;
        try {
            decision = TicketWorkflow.Decision.valueOf(req.decision().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision 必须是 APPROVE/REJECT");
        }
        try {
            return workflow.view(workflow.review(id, decision, req.note()));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 手动重试一次（不等调度器），排障时最常用 */
    @PostMapping("/tickets/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) {
        try {
            return workflow.view(workflow.retry(id));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Agentic 模式：模型自主决定调哪些 MCP/本地工具，支持多轮（同 conversationId） */
    @PostMapping("/tickets/{id}/ask")
    public Map<String, Object> ask(@PathVariable String id, @RequestBody AskRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        String cid = req.conversationId() == null || req.conversationId().isBlank()
                ? "ticket-" + id : req.conversationId().trim();
        return Map.of("answer", brain.ask(req.question(), cid), "conversationId", cid);
    }

    @GetMapping("/session/{conversationId}")
    public List<Map<String, Object>> session(@PathVariable String conversationId) {
        return brain.session(conversationId).stream()
                .map(m -> Map.<String, Object>of(
                        "role", m.getMessageType().name(), "text", m.getText()))
                .toList();
    }

    @PostMapping("/session/{conversationId}/reset")
    public Map<String, Object> resetSession(@PathVariable String conversationId) {
        brain.clearSession(conversationId);
        return Map.of("conversationId", conversationId, "status", "cleared");
    }

    /** 自检：MCP 侧发现了哪些工具（rag-kb 的 tools/list 是否真的接通） */
    @GetMapping("/agent/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("kbAvailable", kb.available(), "mcpTools", List.of(kb.toolNames()));
    }

    /** 状态转移图：评审要的那张图，直接从代码里的枚举打出来，不是文档里手画的 */
    @GetMapping("/tickets/state-machine")
    public Map<String, Object> stateMachine() {
        return Map.of("transitions", java.util.Arrays.stream(TicketStatus.values())
                .collect(java.util.stream.Collectors.toMap(
                        TicketStatus::name, s -> s.allowedNext().stream().map(Enum::name).sorted().toList(),
                        (a, b) -> a, java.util.LinkedHashMap::new)));
    }
}
