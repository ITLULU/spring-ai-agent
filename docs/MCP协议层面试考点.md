# MCP 协议层面试考点记录

> 适用场景：大模型 Agent 方向面试中关于 MCP（Model Context Protocol）协议层的考察，
> 重点覆盖：分层架构、tools/resources/prompts 三类原语、stdio vs Streamable HTTP 传输层、常见追问。
> 整理日期：2026-10-17

---

## 一、MCP 定位与分层架构

MCP（Model Context Protocol）是 Anthropic 2024 年 11 月开源的**开放协议**，目标是让 AI 应用
（LLM 宿主 / Client）与外部数据源、工具（Server）之间的连接**标准化**，把碎片化的 N×M 集成
变成 N+M（类比："AI 世界的 USB-C 接口"）。

协议分两层：

```
┌─────────────────────────────────────┐
│ Data Layer（数据层）                  │
│  JSON-RPC 2.0 消息 + 三类原语          │
│  tools / resources / prompts + sampling 等 │
├─────────────────────────────────────┤
│ Transport Layer（传输层）             │
│  stdio | Streamable HTTP（旧 SSE 已废弃）  │
└─────────────────────────────────────┘
```

- 通信本质是 **JSON-RPC 2.0**：request / response / notification 三种消息。
- 初始化时双方做 **capability negotiation**（`initialize` 握手，声明支持 tools/resources/prompts
  中的哪些），之后才能调用对应方法。

角色模型：

| 角色 | 说明 | 示例 |
|---|---|---|
| Host | LLM 应用本身 | Claude Desktop、Qoder、Cursor |
| Client | Host 内部与某个 Server 一一对应的连接器 | MCP 客户端 SDK |
| Server | 暴露能力的外部服务 | 文件服务器、数据库、rag-kb |

---

## 二、三类核心原语（Server → Client 暴露的能力）

### 1. Tools（工具）——"模型控制的"（model-controlled）

- 让 LLM **执行动作**：查数据库、调 API、写文件。
- 协议方法：`tools/list`（发现）、`tools/call`（调用）。
- 每个 tool 有 `name`、`description`、`inputSchema`（JSON Schema 定义参数）。
- **关键特征：调用决策由模型自主做出**（决定调不调、调哪个），属于**高风险原语**。
- 面试加分点：工具需要人类确认（human-in-the-loop）/ 权限控制 / 幂等设计。
- 类比：Postman / 函数调用。

### 2. Resources（资源）——"应用控制的"（application-controlled）

- 向上下文**注入数据**：文件、DB schema、API 文档、配置。
- 协议方法：`resources/list`、`resources/read`，可选 `resources/subscribe`
  （资源变更通知 `notifications/resources/updated`）。
- 每个 resource 有 **URI** 标识（如 `file:///path/doc.md`、`db://tables/expense_policy`）、
  MIME type、可读内容。
- **关键特征：由应用程序（Host）决定何时把资源放进上下文**，而不是模型——通常由用户选择或
  应用策略决定。
- 类比：Google Drive / OSS 里的只读数据。
- 项目映射：rag-kb 的 `data/docs/*.md`（考勤制度、报销政策）就是天然的 resources。

### 3. Prompts（提示模板）——"用户控制的"（user-controlled）

- 服务器预定义的**提示模板**，可带参数，通过 **slash command** 等方式由用户主动触发。
- 协议方法：`prompts/list`、`prompts/get`。
- 内容可以是 messages（多轮对话模板），甚至嵌入 resources。
- **关键特征：选择权在用户**，如 Claude Code 的 `/review-pr`。
- 类比：邮件模板 / 快捷短语。

### 三者对比（必背）

| | Tools | Resources | Prompts |
|---|---|---|---|
| 本质 | 执行动作 | 提供数据 | 模板化输入 |
| 控制方 | **模型**自主调用 | **应用**决定注入 | **用户**主动选择 |
| 副作用 | 有（写操作风险） | 无（只读） | 无 |
| 标识 | name | URI | name + 参数 |
| 典型方法 | tools/call | resources/read | prompts/get |

> 一句话总结：**Tools 是"手"，Resources 是"眼睛/资料库"，Prompts 是"预设话术"**。

### 追问预备：反向原语（Client → Server）

MCP 不只是 Server 提供能力，还有三个**客户端原语**：

- **Sampling**（`sampling/createMessage`）：Server 反过来请求 Client 的 LLM 生成内容
  → Server 无需自带 API Key，实现模型无关性。
- **Elicitation**（2025-06 spec 新增）：Server 向用户追问结构化输入。
- **Roots**：Client 告知 Server 可操作的工作目录边界。

> 这是 MCP 区别于纯 Function Calling 的关键——**双向协议**，而 OpenAI tool calls 是单向的。

---

## 三、传输层：stdio vs Streamable HTTP

### 1. stdio（标准输入输出）

- 原理：Client 把 Server 作为**子进程**启动，stdin 写、stdout 读，换行分隔的 JSON-RPC 消息；
  stderr 走日志。
- 优点：
  - 无网络开销，**延迟极低**、性能最好
  - 天然安全：进程不监听端口，只有父进程能通信
  - 无需认证、无需端口管理，本地桌面客户端（Claude Desktop）首选
- 缺点：
  - **只能本地、一对一**（一个 Client 独占一个 Server 进程），无法远程、无法水平扩展
  - 崩溃恢复需重新拉起进程

### 2. Streamable HTTP（2025-03 spec 引入，替代旧 HTTP+SSE）

**历史背景（体现跟进 spec）**：旧版"HTTP + SSE"双端点传输已被 Streamable HTTP 取代，
原因是旧方案要求常开 SSE 通道、必须依赖两个端点、不利于无状态服务器部署。

设计要点：**单一端点**（通常 `/mcp`），基于 POST + 可选 SSE 流：

- Client → Server：**POST** JSON-RPC 消息到 `/mcp`，
  请求头 `Accept: application/json, text/event-stream`。
- Server 响应的两种方式（核心考点）：
  1. **普通交互**：直接返回 `application/json`（一问一答，等价于 REST）
  2. **需要流式**：返回 `text/event-stream`，同一 HTTP 连接上通过 SSE 持续推送多条
     JSON-RPC 消息（进度通知 `notifications/progress`、心跳、批量结果），完成后关闭流
- **会话管理**：initialize 响应头返回 `Mcp-Session-Id`，后续请求携带该 header；
  Session ID 应高熵不可猜测。
- **断线恢复**：SSE 支持 `Last-Event-ID` 重连；Server 可选择性缓存允许恢复的流。
- **协议头校验**：`MCP-Protocol-Version` header，防止版本混淆。
- **鉴权**：走标准 HTTP 体系，**OAuth 2.1**（2025-03/06 spec 明确授权框架）。
- 优点：**可远程、可云端部署、支持多 Client**（无状态 Server 可负载均衡）。
- 缺点：延迟与运维复杂度高于 stdio；有状态流破坏简单水平扩展（需 sticky session
  或共享会话存储）。

### 选型对比（必背）

| 维度 | stdio | Streamable HTTP |
|---|---|---|
| 部署位置 | 本地子进程 | 本地或远程云端 |
| 连接拓扑 | 1 Client : 1 Server | 1 Server : N Clients |
| 消息形式 | 换行分隔 JSON-RPC | POST JSON + 按需 SSE 流 |
| 会话/状态 | 进程即会话 | Mcp-Session-Id |
| 鉴权 | 进程隔离，无需 | OAuth 2.1 / Bearer Token |
| 流式 | 天然支持（双向管道） | 响应升级为 SSE，支持双向通知 |
| 扩展性 | 不能水平扩展 | 可负载均衡（需处理会话粘性） |
| 适用场景 | 桌面工具、本地 IDE 集成 | 企业级、跨网络、SaaS 化 MCP 服务 |

### 结合项目的表述（加分回答模板）

> "我把 rag-kb 封装成 MCP Server 时，本地开发接 Spring AI 的
> `spring-ai-starter-mcp-server-webmvc` 走 Streamable HTTP，因为要支持多个客户端
> （Claude、Qoder、Cursor）远程连；如果只是 Claude Desktop 本机用，stdio 更简单安全。"

---

## 四、常见追问及答题要点

### 1. "MCP 和 Function Calling 什么区别？"

Function Calling 是 LLM 层的"调用外部函数"能力，各家 API 格式不一、静态注册；
MCP 是**协议层**标准，统一了工具的发现（`tools/list` 动态）、调用、鉴权、传输，
且支持反向能力（sampling）。
类比：Function Calling ≈ 函数指针，MCP ≈ 整个操作系统 ABI。

### 2. "MCP 和 A2A 什么区别？"

MCP 解决 **Agent ↔ 工具/数据** 的连接；A2A（Agent2Agent）解决 **Agent ↔ Agent**
的对等协作（任务生命周期、Agent Card 发现）。两者互补，一个纵向、一个横向。

### 3. "安全性怎么考虑？"（2025 年 spec 重点）

- **提示词注入**：tool description 和 resource 内容都不可信，敏感操作需用户确认
- **Tool Poisoning / Rug Pull**：Server 在 tools/list 中的描述与 call 时行为不一致
  → 锁定 Server 版本、审查 description diff
- **Confused Deputy**：代理 OAuth 时需 PKCE
- **最小权限原则**：只挂载需要的 Server；stdio 的进程隔离本身就是安全边界

### 4. "initialize 握手流程？"

1. Client 发 `initialize`（带 protocolVersion + 自己的 capabilities）
2. Server 响应（自己的 capabilities，如 `tools: {}`、`resources: {}`）
3. Client 发 `notifications/initialized`
4. 进入正常消息期

能力协商保证双方只调用对方支持的方法。

### 5. "一次 tools/call 的完整链路？"

用户提问 → Host 调 `tools/list` 获取 schema 放入上下文 → 模型决定调用，产出 tool_use
→ Client 发 JSON-RPC `tools/call` 给 Server → Server 执行返回 `content`
（text/image/resource_link）→ 结果回填模型上下文 → 模型生成最终回答。
长任务可穿插 `notifications/progress`。

---

## 五、面试速记版

> MCP = **JSON-RPC 双层协议**；
> Server 侧三原语 **Tools(模型控) / Resources(应用控) / Prompts(用户控)**，
> Client 侧三原语 **Sampling / Elicitation / Roots**；
> 传输层本地用 **stdio**（子进程、快、安全、一对一），
> 远程用 **Streamable HTTP**（单端点 `/mcp`，POST 为主、按需 SSE 流、
> Session-Id + OAuth 2.1，取代旧双端点 HTTP+SSE）。

## 附：后续行动项

- [ ] 基于 `spring-ai-starter-mcp-server-webmvc` 落地 rag-kb 的 MCP Server Demo：
  `/api/query` 封装为 tool，`data/docs/*.md` 政策文档封装为 resource，
  常用问答模板封装为 prompt——面试时"能跑通的 demo"比背概念更有说服力。
