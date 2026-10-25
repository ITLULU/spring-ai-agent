package com.example.kbagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阶段2 工单智能处理 Agent 入口（12 周计划 M2 主线）。
 *
 * 与阶段1 的关系：不 import rag-kb 的任何类，只认 MCP 协议。
 * rag-kb 起在 http://localhost:8000/mcp，本进程启动时 tools/list 发现它的 10 个工具，
 * 于是"知识库 + 评测机"成为 Agent 可调用的能力——阶段1 交付的是 RAG 系统，
 * 阶段2 交付的是会用这套系统的 Agent。
 */
@SpringBootApplication
public class KbAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbAgentApplication.class, args);
    }
}
