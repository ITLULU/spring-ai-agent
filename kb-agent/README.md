# kb-agent · 工单智能处理 Agent（阶段 2 / 里程碑 M2）

阶段 1 的 `rag-kb` 是一个"被问才答"的知识库；本模块是它的**第一个消费者**：
一张工单进来，Agent 自己分诊、查知识库、装配回复、算置信度，够格的自动结单、
够格不了的带着轨迹转人工，链路失败的按指数退避重来。

rag-kb 侧**零改动**——它不知道自己被谁消费了。两边只有一份协议契约：
Streamable HTTP 的 `/mcp` + `tools/list` 里的工具名与 inputSchema。

```
用户/服务台 ──POST /api/tickets──► kb-agent :8001
                                       │
                    ① 分诊(LLM 结构化)  ② 检索证据  ③ 政策答复  ④ 装配(LLM)  ⑤ 置信闸门
                                       │            │
                                       │      MCP tools/call (Streamable HTTP)
                                       ▼            ▼
                              rag-kb :8000 /mcp  ← 同一份 Service，REST 与 MCP 两种传输
```

---

## 一、为什么长这样（三条设计立场）

### 1. 可控工作流：节点顺序写死在 Java 里，不是让模型自由 ReAct

线上要的是**可复现、可审计、可归因**。`TicketWorkflow` 五个节点顺序固定，
每个节点的产物分别落库（`category`/`confidence`/`citations_json`/`ticket_note`），
出问题时能立刻回答"卡在检索还是卡在判定"——这与 rag-kb 评测机的失败归因分层是同一套方法论。

模型只出现在两个节点（分诊、装配），而且装配节点的输入是上一步**工具的真实产物**
（`kb_query` 的原文 JSON），不是模型的记忆。

`/ask` 端点另留了一条 agentic 路线（模型自主选工具）。两条路都留着是因为它们答的是不同问题：
流水线证明"线上行为可控"，agentic 证明"Agent 会用工具"。

### 2. 自动结单的权力不交给模型自评

第⑤节点是确定性代码：

```
confidence = 0.5 × 证据分 + 0.3 × 引用有效性 + 0.2 × 模型自评
证据分      = kb_retrieve 返回块里的最佳向量相似度（检索层事实，可复算）
引用有效性  = 非拒答且回复里每个 [n] 都落在 citations 范围内（与 rag-kb checkCitations 同判据）
模型自评    = 结构化输出里的 selfConfidence——系统性偏高，所以权重最低
```

`confidence ≥ 0.75` 且引用有效才 `AUTO_RESOLVED`。闸门之上还有一层业务红线：
`payroll/dismissal/compliance` 无论分数多高都转人工。

**口径统一是刻意的**：线上判定与离线评测用两套规则的话，评测分数就没有资格预测线上行为，
所以引用判据逐条对齐 `EvalService#checkCitations`。

### 3. 状态机是审计对象，不是模型的自由输出

`TicketLocalTools` 只提供 `ticket_read` / `ticket_append_note`，**没有任何改状态的工
具**——这条边界还写进了 tool description（HITL 闸门写进协议元数据）。流转只由
`TicketStatus` + `TicketRepository#transition` 决定。

```
NEW ──────────┬─► AUTO_RESOLVED   置信达标、引用有效（resolvedBy=AGENT）
              ├─► PENDING_HUMAN   低置信 / 高风险 / 引用无效 / 拒答
              └─► FAILED          链路异常，带 next_retry_at
FAILED ───────┬─► RETRYING ──► 重跑②③④⑤
              ├─► AUTO_RESOLVED   重试后直接结单
              └─► PENDING_HUMAN   attempts 烧光 → resolvedBy=RETRY_EXHAUSTED
PENDING_HUMAN ─┬─► AUTO_RESOLVED  人工批准（resolvedBy=HUMAN）
               └─► NEW            人工驳回，带线索立刻重跑
AUTO_RESOLVED ──► PENDING_HUMAN   追问/抽检拉回人工；Agent 不能再改写已结单的结论
```

两张并发防线：领域层 `canTransitionTo`（非法转移直接拒）+ SQL 层
`UPDATE ... WHERE id=? AND status=?` 的 CAS（0 行受影响即有人抢先改过 →
`并发让路`，**不计入失败、不烧重试额度**）。

重试：`base × 2^(attempts-1)` 封顶 60s，退避时间点落库（`next_retry_at`）而不是内存里，
重启不丢；`RetryScheduler` 每 5s 扫一次到期单。

---

## 二、MCP 消费侧（PRO-02 的落点）

```yaml
spring.ai.mcp.client:
  enabled: ${MCP_CLIENT_ENABLED:true}
  type: SYNC
  request-timeout: 30s
  streamable-http.connections.rag-kb:
    url: ${RAG_KB_URL:http://localhost:8000}
    endpoint: /mcp
```

starter 启动时完成 `initialize → tools/list`，把 rag-kb 的 10 个工具翻译成进程内
`ToolCallback`。`KbMcpGateway` 的职责被压到最小：**按名字找回调、调用、解析 JSON**，
不含任何 RAG 逻辑；rag-kb 换实现（拒答阈值、检索策略）时这里一行不改。

为什么不直接注入 `ToolCallback[]`：① `MCP_CLIENT_ENABLED=false` 时容器里没有这些 Bean，
`ObjectProvider` 让"没有知识库也能跑状态机"成立（本机验证与单测都靠它）；② 工具名拼错要在
**调用点**给出可读错误，而不是让 NPE 冒进工作流被当成一次普通失败白烧三轮退避。

---

## 三、启动与验证

```bash
export JAVA_HOME=<JDK 21>
# 1) 先起阶段 1（需要真实模型 key）
cd rag-kb && mvn -B spring-boot:run
# 2) 再起本模块
cd ../kb-agent && mvn -B spring-boot:run     # :8001，工单落 ./data/kb-agent/tickets.mv.db
```

演示脚本：[http/ticket-agent.http](http/ticket-agent.http)（受理 → 看轨迹 → 审核 → 追问 → 自检）。

自检端点就是给评审看的自证：

```bash
curl -s localhost:8001/api/tickets/state-machine   # 状态转移表直接从枚举打出来
curl -s localhost:8001/api/agent/capabilities      # MCP 侧真的发现了哪些工具
curl -s localhost:8001/api/tickets/<id>            # 工单现场 + 逐节点审核轨迹
```

### 验证状态（2026-10-25）

| 面 | 怎么验的 | 结论 |
| --- | --- | --- |
| 状态机与转移表 | `TicketStatusTest` 6 项 | ✅ 已过 |
| H2 存储、CAS、退避取数、轨迹排序 | `TicketRepositoryTest` 8 项（真 H2 + 真 schema.sql，不用 mock） | ✅ 已过 |
| 五节点流水线、置信闸门、红线、引用判据、重试/退避、HITL、并发让路 | `TicketWorkflowTest` 16 项（真 H2 + 桩模型/桩 MCP） | ✅ 已过 |
| 结构化输出解析、提示词渲染、工具挂载、多轮隔离 | `TicketBrainTest` 5 项（桩 ChatModel） | ✅ 已过 |
| 进程启动、REST 契约、失败记账 | `MCP_CLIENT_ENABLED=false` 实启：`Started in 1.7s`；受理单后 `FAILED/attempts=1/nextRetryAt>0` 且轨迹含"处理失败"；`/retry` 后 attempts=2；REJECT 用在 FAILED 上 409；decision 非法 400；未知工单 404 | ✅ 已实测 |
| 全仓回归 | `mvn -B test`：content-agent 4 + rag-kb 48 + kb-agent 35 = **87 项全绿** | ✅ 已过 |
| **真实 LLM 下的分诊/装配质量** | 本机 agent 环境无 `AI_API_KEY`（上面那次 401 就是证据） | ⚠️ **未验证**，需带 key 跑 `http/ticket-agent.http` |
| **kb-agent → rag-kb 的 MCP 实连** | 需要 rag-kb 同时在跑 | ⚠️ **未验证**，`capabilities` 里 `kbAvailable=true` 且 `mcpTools` 非空即通 |
| 自动结单率 / 拒答率等统计 | 需要 ≥30 张真实工单 | ⚠️ 未测 |

---

## 四、已知局限（不藏）

1. **同步流水线**：`accept()` 里一路跑完，单次响应时间 = 两次模型调用 + 两次 MCP 调用。
   并发上去要换队列 + 异步 worker（`next_retry_at` 那套取数已经能复用）。
2. **H2 file 单实例**：多副本要换 MySQL/PG；DAO 写的是标准 SQL，只动 datasource url。
3. **置信权重是拍的**：0.5/0.3/0.2 与阈值 0.75 没有经过标注集标定。正确做法是拿阶段 1 的
   评测机跑一批带人工标注的工单，用混淆矩阵调阈值——这是下一步，不是已完成。
4. **证据分只取最佳向量分**：没 fused/rerank 分参与，多块证据的"覆盖度"未建模。
5. **`kb_ingest` 侧的写操作没挂到 agentic 工具里**：自由问答允许模型读、不允许它改库。
6. **重试只覆盖工具/模型层异常**：业务判定不通过（低置信）走 PENDING_HUMAN，不进重试通道——
   这是设计，不是遗漏。

---

## 五、包结构

```
com.example.kbagent
├── KbAgentApplication
├── config/AgentChatConfig        ChatClient + 会话窗口；@Lazy ChatModel 断环（同 rag-kb 踩过的环）
├── domain/                       Ticket（不可变读模型）、TicketStatus（转移表 + IllegalTransition）
├── store/TicketRepository        JdbcTemplate 直写 SQL、CAS 流转、窄更新
├── agent/
│   ├── KbMcpGateway              MCP 消费侧网关（PRO-02）
│   ├── TicketBrain               模型节点：classify / draft / ask（.st 外置 + .entity 结构化）
│   └── TicketLocalTools          只读 + 留痕，无改状态工具
├── workflow/
│   ├── TicketWorkflow            五节点流水线 + 置信闸门 + 退避 + HITL
│   └── RetryScheduler            @Scheduled 扫 next_retry_at 到期单
└── web/TicketController          受理/列表/详情/审核/重试/追问/自检端点
```
