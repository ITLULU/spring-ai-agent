# rag-kb · 生产形态 RAG 知识库（Spring AI + Milvus）

> 定位：不是"调个模型查向量库"的 Demo，而是**带评测闭环、可量化迭代**的 RAG 工程实践项目——
> 具备生产级工程模式（拒答防线/归因分层/幂等入库/断点续跑），但未做鉴权与多实例部署，生产上线前需补齐（见"已知局限"）。
> 技术栈：Java 21 / Spring Boot 3.5 / Spring AI 1.1 / Milvus（可切换）/ 手写 BM25 / LLM-as-Judge 评测。

## 运行前提

| 依赖 | 要求 | 自检命令 |
|---|---|---|
| JDK | **21**（Temurin/Corretto 均可） | `java -version` 显示 21.x |
| Maven | 3.9.x | `mvn -v` |
| LLM API Key | 任一 OpenAI 兼容平台（DashScope / DeepSeek / OpenAI / SiliconFlow） | 见下方环境变量 |
| Docker | **可选**——默认内存向量库零基建；切 Milvus/Postgres/Rerank 时需要：仓库自带最小基建栈 `rag-kb/docker-compose.yml`，或指向你自己的统一托管服务（见下"Docker 基建"节） | — |

## 快速开始

```powershell
# 1. 配置模型（OpenAI 兼容接口均可：DashScope / DeepSeek / OpenAI / Ollama）
#    永久写入（推荐，一次设置全局生效）：setx 后需重启 IDEA/终端才对新进程生效
setx AI_API_KEY "sk-xxx"
#    或仅当前会话临时生效：

$env:AI_API_KEY="sk-xxx"; $env:AI_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode"
$env:AI_CHAT_MODEL="qwen-plus"; $env:AI_EMBED_MODEL="text-embedding-v3"
$env:EVAL_JUDGE_MODEL="qwen-turbo"   # 默认已是独立裁判 qwen-max（不复用 chat，无 self-preference）；
                                     # 换平台时本变量必须同步换成该平台存在的模型，否则裁判调用失败记 ERROR

# 2. 启动——⚠️ 必须在 rag-kb/ 目录下运行（数据路径都是相对工作目录的）
cd rag-kb; mvn spring-boot:run
```

**启动成功判定**：日志出现 `Tomcat started on port 8000`；`GET http://localhost:8000/api/eval/runs` 返回 `[]`；
MCP 端点 `http://localhost:8000/sse` 可建立 SSE 连接。

> ⚠️ **Windows 用户注意**：PowerShell 里 `curl` 是 `Invoke-WebRequest` 别名，本 README 所有示例请写全
> `curl.exe`；且 PowerShell 5.1 默认 GBK 编码会把请求体里的中文变成乱码字节（表现为"检索全不命中"的
> 假象）——先执行 `chcp 65001`，或把 JSON 存成 UTF-8 文件后用 `--data-binary "@req.json"` 发送。

> ⚠️ **存储与重启纪律（两种模式）**：默认 `rag.chunk-store.type=memory` 下向量库(除非切 Milvus)/BM25/ChunkStore 均为内存实现，
> **重启后必须重新 ingest**；切 **postgres 模式**（`-Dspring-boot.run.profiles=persistent` 或 `--rag.chunk-store.type=postgres`，见“持久化部署”一节）后 chunk 语料落库，
> 启动时 warmup 自动重建 BM25，**重启不再需要重灌**（DAT-01 已根治）。
> 入库已幂等（ING-02 确定性 chunk id + 按 source 删旧）：重灌同一文档不会翻倍语料，无需先 reset。

```powershell
# 3. 入库 → 问答 → 评测（评测机：异步 run + 错题重跑 + diff，详见 docs/评测机增强实施方案.md）
curl.exe -X POST localhost:8000/api/ingest   -H "Content-Type: application/json" -d '{\"path\":\"data/docs\"}'   # 支持目录：递归批量入库（pdf/txt/md）
curl.exe -X POST localhost:8000/api/query    -H "Content-Type: application/json" -d '{\"question\":\"入职满3年的员工有几天年假？\"}'
curl.exe -X POST localhost:8000/api/eval/dataset/validate                          # SOP① 锚点校验
curl.exe -X POST localhost:8000/api/eval/runs -H "Content-Type: application/json" -d '{\"tag\":\"baseline\"}'  # 返回 runId，后台跑
curl.exe localhost:8000/api/eval/runs/{runId}                                       # 进度/汇总
curl.exe "localhost:8000/api/eval/runs/{runId}/results?verdict=FAIL"                # 错题清单含归因标签与现场快照
curl.exe -X POST localhost:8000/api/eval/runs -H "Content-Type: application/json" -d '{\"onlyFailedFrom\":\"{runId}\"}'  # 错题本重跑
curl.exe "localhost:8000/api/eval/diff?base={runId}&head={runId2}"                  # 已修复/新增失败（跨 datasetVersion 拒绝）
```

评测结果落 `output/eval-runs/{runId}.jsonl`（逐题存档：三路分数/错因标签/token 记账），
Markdown 报告由 `GET /api/eval/runs/{runId}/report` 从 JSONL 渲染——这就是"优化前 X% → 优化后 Y%"数字的出处。

## 配置参考（环境变量与 rag.* 键）

| 环境变量 | 对应配置键 | 默认值 | 何时改 |
|---|---|---|---|
| `AI_API_KEY` | spring.ai.openai.api-key | `sk-not-configured`（**首次调用才报错，不阻启动**） | 必设 |
| `AI_BASE_URL` | spring.ai.openai.base-url | DashScope 兼容端点 | 换平台 |
| `AI_CHAT_MODEL` / `AI_EMBED_MODEL` | chat/embedding options.model | `qwen-plus` / `text-embedding-v3` | 换模型 |
| `EVAL_JUDGE_MODEL` | rag.eval.judge.model | `qwen-max`（独立裁判；显式置空才复用 chat 模型，启动 WARN） | ⚠️ 换非 DashScope 平台（如 DeepSeek）**必须同步覆盖**——否则裁判调用失败全记 ERROR |
| `EVAL_JUDGE_API_KEY` / `EVAL_JUDGE_BASE_URL` | rag.eval.judge.* | 回落主配置 | 裁判用另一家平台时 |
| `RERANK_URL` | rag.rerank.base-url | `http://localhost:8087/rerank` | 配合 `rag.rerank.enabled=true`（默认关）接 TEI/托管 rerank API；仓库 compose 的 CPU 版映射在 8087 直接可用；其他端口/托管 API 用本变量覆盖 |
| `MILVUS_HOST` / `MILVUS_PORT` | vectorstore.milvus.client.* | `localhost` / `19530` | 切 Milvus |
| `CHUNK_STORE_TYPE` | rag.chunk-store.type | `memory` | `postgres` → chunk 语料落库，重启不丢（见“持久化部署”） |
| `PG_URL` / `PG_USER` / `PG_PASSWORD` | rag.postgres.* | `jdbc:postgresql://localhost:5432/ragkb` / `postgres` / `postgres` | 仅 postgres 模式生效 |

| 配置键（application.yml） | 默认 | 说明 |
|---|---|---|
| `rag.vectorstore.simple` | `true` | `false` + `-Pmilvus` → Milvus（persistent profile 已设 false） |
| `rag.rerank.enabled` | `false` | `true` → TEI/托管 rerank；服务不可用时自动降级为融合名次（WARN 留痕，不打挂 /api/query） |
| `rag.chunk-store.type` | `memory` | `postgres` → ChunkStore 落 Postgres（表启动自动建，JdbcTemplate upsert）；向量始终在向量库，PG 只存检索单元原文+父子关系 |
| `rag.chunk.strategy` | `fixed` | `fixed` / `recursive` / `parent-child`，单变量实验开关（实验表里 recursive/parent-child 基线需显式覆盖本键后再跑） |
| `rag.retrieval.top-n` / `final-k` | 20 / 5 | 每路召回数 / 喂 LLM 的上下文块数 |
| `rag.retrieval.hybrid` | `true` | `false` → 纯向量对照组 |
| `rag.retrieval.min-vector-similarity` | 0.30 | 拒答阈值：漏答 vs 幻觉的权衡点，用评测集调 |

## 输出示例（真实字段结构，非虚构格式）

`POST /api/query` 正常回答——每个结论句带 `[n]` 编号引用，citations 可回查：

```json
{
  "answer": "入职满 3 年享有 10 天带薪年假 [1]。年假需在当年 12 月 31 日前使用完毕，未用完不折算工资 [1]。",
  "refused": false,
  "citations": [
    { "no": 1, "chunkId": "3f2a…", "source": "attendance_policy.md",
      "snippet": "员工入职满 1 年享有 5 天带薪年假，满 3 年享有 10 天，满 5 年享有 15 天。年假需在当年…" }
  ],
  "elapsedMs": 2841, "promptTokens": 512, "completionTokens": 63
}
```

超出文档范围的问题触发检索层拒答（不调 LLM，`citations` 为空、token 为 0）：

```json
{ "answer": "无法回答", "refused": true, "citations": [], "elapsedMs": 312, "promptTokens": 0, "completionTokens": 0 }
```

`output/eval-runs/{runId}.jsonl` 单行 case 存档（第一行是 run 头：config+models 快照）：

```json
{"type":"case","caseId":"att-003","category":"fact","heldOut":false,"verdict":"PASS",
 "failureLayer":"NONE","question":"入职满3年的员工有几天年假？","answer":"10 天 [1]","refused":false,
 "citationValid":true,
 "retrieval":[{"chunkId":"3f2a…","source":"attendance_policy.md","vectorScore":0.81,"bm25Score":7.4,"fusedScore":0.032,"evidenceHit":true}],
 "judgeVerdict":"CORRECT","latencyMs":2980,"tokens":{"prompt":512,"completion":63,"judgePrompt":180,"judgeCompletion":4}}
```

## 评测结果（优化前 → 优化后）

> 规则：每个数字必须能追溯到具体 runId；跨 datasetVersion 禁止对比（diff 接口会拒绝）。
> ⬜ **待 baseline 跑完后回填本表**（README 的承诺在这一节兑现）。

| 实验（单变量） | 主集准确率 | held-out 准确率 | 拒答准确率 | 错因 Top1 | datasetVersion | runId |
|---|---|---|---|---|---|---|
| baseline：recursive + hybrid | – | – | – | – | – | *待填* |
| fixed 切块对照 | – | – | – | – | – | *待填* |
| parent-child 切块 | – | – | – | – | – | *待填* |
| 纯向量（hybrid=false） | – | – | – | – | – | *待填* |
| + rerank | – | – | – | – | – | *待填* |
| 拒答阈值 0.30 → 0.45 | – | – | – | – | – | *待填* |

## 验证与测试

```powershell
mvn -pl rag-kb test          # 33 个单元/契约测试（切块含确定性id/BM25+RRF/入库幂等与目录批量/评测存档/失败归因/MCP 工具契约）
pwsh scripts/mcp-probe.ps1   # MCP 最小客户端演练：SSE 握手→initialize→tools/list→tools/call
```

`RagKbToolsTest.toolCallbacksExposeExactContract` 守护对外 10 个工具的 name/description/参数 schema——
改工具契约必先过这条测试（REST 与 MCP 双传输共享同一 Service 层的"契约锚"）。

## MCP 专题：零协议代码把知识库+评测机封装成标准工具（Agent 生态接入点）

> 代码：`mcp/RagKbTools.java`（工具定义）+ `mcp/McpServerConfig.java`（装配）+ `application.yml` 的 `spring.ai.mcp.server` 块。

同一个 Service 层现在有两种传输形态：REST（人/脚本调）+ **MCP**（LLM/Agent 自动发现调用），
两者共享同一套 Service，互不重复。启动后任意 MCP 客户端（Claude Desktop / Cursor / Qoder）连 SSE 端点即可：

```json
{ "mcpServers": { "rag-kb": { "url": "http://localhost:8000/sse" } } }
```

### 1. 封装全景：一次 MCP 调用穿过哪几层

```
MCP 客户端(Claude/Cursor/Qoder)
   │ SSE 连 /sse，JSON-RPC 消息 POST 到 /mcp/message?sessionId=xxx，响应从 SSE 流推回
   ▼
┌─────────────────────────────────────────────────────┐
│ ① pom 依赖 spring-ai-starter-mcp-server-webmvc      │ ← 传输层全由它装配（SSE/JSON-RPC/会话）
├─────────────────────────────────────────────────────┤
│ ② McpServerConfig：ToolCallbackProvider Bean          │ ← 唯一胶水，一行 builder，声明"对外暴露什么"
├─────────────────────────────────────────────────────┤
│ ③ RagKbTools：@Tool + @ToolParam 注解方法              │ ← 工具说明书写给 LLM 看；只包装，不写业务
├─────────────────────────────────────────────────────┤
│ ④ Service 层（QaService/IngestService/EvalService…）  │ ← 真正的业务，REST 与 MCP 共享
└─────────────────────────────────────────────────────┘
```

### 2. 四个零件逐个拆

**零件① 依赖：starter 一把包办协议层**（pom.xml）

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

自动配置的触发条件就是"容器里出现 `ToolCallbackProvider` Bean"。SSE 端点、`/mcp/message` 回调、
`initialize / tools/list / tools/call` 的 JSON-RPC 分发、会话管理——手写代码量为**零**。

**零件② 装配：一个 Bean 声明能力清单**（`McpServerConfig`，整个类就这一个方法）

```java
@Bean
public ToolCallbackProvider ragKbToolCallbackProvider(RagKbTools ragKbTools) {
    return MethodToolCallbackProvider.builder().toolObjects(ragKbTools).build();
}
```

`MethodToolCallbackProvider` 反射扫描 `ragKbTools` 上所有 `@Tool` 方法，每个转成一个
`ToolCallback`（name + description + JSON Schema 参数描述）。**MCP 对外暴露什么完全由这个 Bean 决定。**

⚠️ 这个 Bean 还藏了一个非平凡的坑——**循环依赖**：

```
openAiChatModel → toolCallingManager → toolCallbackResolver → 本Bean → RagKbTools
     ↑                                                              |
     └────────────────── QaService / EvalJudge ←───────────────────┘   成环！
```

starter 会把所有 `ToolCallbackProvider` 喂给 ChatModel 的工具解析链（为了支持"模型自己调工具"），
而我们的工具又反过来需要 ChatModel 做生成。解法：在 `QaService`/`EvalJudge` 的 ChatModel 注入点加
`@Lazy`——构造时只存代理不调方法，首次 `call` 才解析真实 Bean，环在消费点断开而不是在源头阉割功能。

**零件③ 工具定义：注解三要素 + 一条包装层纪律**（`RagKbTools`）

```java
@Tool(name = "kb_query", description = """
        对企业规章制度知识库提问，返回带编号引用（citations）的答案。
        内部执行完整 RAG 链路：混合检索→重排→LLM 生成。超出文档范围的问题会拒答（refused=true）。
        回答政策类问题优先用本工具而不是 kb_retrieve（后者只召回、不调 LLM）。
        """)
public Map<String, Object> kbQuery(
        @ToolParam(description = "自然语言问题，例如：员工入职满 3 年有多少天年假？") String question) {
    QueryResponse r = qaService.answer(question);   // ← 直接复用 Service，包装层零业务逻辑
    ...
}
```

| 要素 | 作用 | 本项目的讲究 |
|---|---|---|
| `name` | LLM 选工具的标识 | `kb_*` / `eval_*` 前缀分域，客户端可按前缀理解能力边界 |
| `description` | **给 LLM 看的选工具说明书**（真正需要打磨的文案） | 写清何时用/何时不用（kb_query vs kb_retrieve 的分工）、返回什么、甚至行为约束（`judge_error_record` 明确写"Agent 不应自行调用"） |
| `@ToolParam` | 生成 inputSchema（JSON Schema） | 可选参数 `required = false`（如 kb_retrieve 的 evidence） |

包装层纪律：**禁止写业务逻辑**，只做"接口不变、实现替换"的重新表达——这是同一能力能同时以
REST/MCP 两种形态服务、且测试/评测不用维护两套的前提。

**零件④ 配置声明**（application.yml）

```yaml
spring.ai.mcp.server:
  enabled: true
  name: rag-kb-mcp-server
  protocol: SSE          # ⚠️ 规范已废弃 SSE 传输，演进方向 Streamable HTTP（清单 PRO-01）
  sse-endpoint: /sse
  sse-message-endpoint: /mcp/message
  instructions: >-       # 服务端"使用指南"，随 initialize 结果返回给客户端
    rag-kb 企业知识库 MCP Server：用 kb_query 提问制度文档（带引用溯源）...
```

### 3. 对外的 10 个工具（契约由 `RagKbToolsTest.toolCallbacksExposeExactContract` 守护）

| 工具 | 作用 | 背后复用 |
|---|---|---|
| `kb_query` | 带引用溯源的政策问答（完整 RAG 链路） | `QaService` |
| `kb_retrieve` | 纯检索归因，可传 evidence 看逐块命中标记 | `HybridRetrievalService` |
| `kb_ingest` | 文档/目录批量入库（向量+BM25+ChunkStore 三路写，幂等） | `IngestService` |
| `eval_dataset_validate` | 跑批前锚点守门校验 | `DatasetService` |
| `eval_run_start` | 异步启动评测跑批（错题本/smoke/断点续跑） | `EvalService` |
| `eval_run_status` / `eval_run_report` / `eval_list_runs` | 进度、Markdown 报告、历史 run | `EvalService` |
| `eval_run_diff` | 单变量实验裁决器（跨 datasetVersion 拒绝） | `EvalService` |
| `judge_error_record` | **人在回路**：人工推翻 Judge 误判后记入清单 | `EvalJudgeErrorLog` |

契约测试锁的是：名字集合 + description 非空 + inputSchema 含 properties——改任何对外工具签名，
测试先红给你看，这就是"接口不变"的工程执行而非口头约定。

### 4. 新增一个工具 = 三步（动手清单）

1. `RagKbTools` 加一个 `@Tool` 方法，内部只调已有 Service；
2. `RagKbToolsTest` 的 `EXPECTED_TOOLS` 名字集合加上新工具（不加会红——测试锁集合相等而非包含）；
3. 客户端重连 `/sse` 自动发现，REST 端点如需同步暴露再另加 Controller。

### 5. 验证：两种手段 + 一条启动 WARN 的真相

```powershell
pwsh scripts/mcp-probe.ps1    # 需 PowerShell 7：模拟最小客户端 SSE握手→initialize→tools/list→tools/call 真执行一次守门校验
```

或用 curl 手动握手（理解协议的最好方式）：
1. `GET /sse` 保持连接 → 收到 `event:endpoint` + `data:/mcp/message?sessionId=xxx`；
2. `POST /mcp/message?sessionId=xxx` 发 `initialize`（HTTP 202，JSON-RPC 响应从 SSE 流推回）→ 再发 `notifications/initialized` → `tools/list`；
3. 读 SSE 流里的响应，能看到全部 10 个工具的 name/description/inputSchema。

启动日志的 `WARN SyncMcpToolProvider: No tool methods found in the provided tool objects: []` 是**无害噪音**：
starter 内部另有一条"直接工具对象"注册路径，本项目工具走的是 `ToolCallbackProvider` Bean（零件②），
那条路径永远扫不到东西——实际能力以 `tools/list` 返回为准（FAQ 表也有这条）。

### 6. 面试可讲点速查

- MCP 解决的痛点：M 个工具×N 个模型的 M×N 定制集成 → 标准化为 M+N（工具只需实现一次 MCP Server，任意客户端可接）；
- 与 Function Calling 的关系：不是竞品是分层——FC 是模型厂商的"选工具"能力（私有格式），MCP 是工具供给的开放协议；Agent 客户端把 tools/list 翻译进模型的 FC 菜单，选工具仍是模型的事；
- 一次 tools/call 的完整往返：SSE 长连接下行推响应，消息走 POST 上行（双通道），sessionId 维系会话——为什么不用纯轮询/纯 WebSocket：JSON-RPC 语义 + 防火墙友好；
- description 是 prompt engineering 的一部分：工具选择准确率取决于文案的"何时用/何时不用"边界表达，不是功能罗列；
- 循环依赖的 @Lazy 断环：不阉割"模型自己调工具"能力，只在消费点延迟解析；
- HITL 的工具层表达：`judge_error_record` 的 description 禁止 Agent 自行调用，人在回路闸门写进协议元数据；
- 演进：SSE 传输已被规范废弃（PRO-01），迁 Streamable HTTP 后本项目只改 starter 依赖与 yml，工具代码零改动——接口不变、实现替换的又一次兑现。

## 架构与核心决策（面试对着这节讲）

```
文档 → 解析(PDFBox/txt/md) → 切块(策略可切换) → ┬→ 向量库(语义)      ┐
                                                └→ BM25索引(词法)    ┴→ RRF融合 → Rerank(可选) → Top-K
                                                                                             ↓
                                引用溯源 + 低置信拒答 ← 生成(Chat, 编号上下文约束) ← 上下文装配(parent全文)
                                                     ↓
                                评测闭环：QA测试集 → LLM-as-Judge → 指标报告 → 单变量实验迭代
```

| 决策点 | 选择 | 理由（trade-off） |
|---|---|---|
| 向量库 | 默认内存 SimpleVectorStore，Milvus 走 Maven profile | 先建立评测驱动的优化闭环再上基建——**检索质量不是基建问题**；也保证任何人 clone 后 30 秒可跑 |
| 切块 | fixed / recursive / parent-child 三策略，配置一键切换（企业级演进见下文「切块专题」） | 评测集是为"单变量实验"设计的：换策略→跑评测→看 contextRecallProxy 归因 |
| 混合检索 | 手写 BM25 + RRF 融合（详解见下文「BM25 专题」） | BM25 与向量失败模式互补（术语精确 vs 语义改写）；RRF 只依赖名次，免疫两路分数不可比的问题 |
| Rerank | 接口 + TEI 客户端，默认关闭 | cross-encoder 精度高但无法预计算，只对 Top-N 精排；无 GPU 环境用托管 API，代码零改动 |
| 幻觉治理 | 双防线：检索层相似度阈值拒答 + 提示层编号引用约束 | 编号引用可机器校验（忠实度评测的基础），拒答省一次生成成本 |
| 评测 | LLM-as-Judge 二值判定 + 拒答测试题(20%) + 来源命中代理指标 | 二值化抑制 Judge 打分漂移；反向出题法避免测试集贴合系统强项 |

## BM25 专题：自建实现详解 vs 企业级实践（面试高频考点）

> 代码：`retrieval/Bm25Index.java`（公式注释在类头）+ `retrieval/TextTokenizer.java`（中英混合分词）。

### 1. 为什么混合检索里必须有 BM25（词法路）

向量检索（语义）和 BM25（词法）的**失败模式互补**，这是混合检索成立的前提：

| 查询类型 | 纯向量的问题 | BM25 的表现 |
|---|---|---|
| 精确术语/编号（"第三条第2款"、错误码 `E1042`） | embedding 把编号当普通字符串压进语义空间，近义数字混淆 | 词面精确命中，idf 自动放大稀有词权重 |
| 专有名词/新词（公司内部系统名） | 训练语料没见过，向量表征漂移 | 只要入库时出现过就能召回 |
| 同义改写（"休假"问成"请假"） | ✅ 向量的主场 | ❌ 词面对不上就丢分 |

所以两路各召回 Top-N，用 RRF 只按**名次**融合（两路分数量纲不可比，BM25 分无界、余弦分压在 [-1,1]，归一化都是造假，名次才是唯一公共语言）。

### 2. 本项目自建 BM25 的核心设计

**公式（Okapi BM25 标准形态）：**

```
score(q,d) = Σ_t idf(t) · tf·(k1+1) / (tf + k1·(1-b + b·|d|/avgdl))
idf(t)     = ln(1 + (N - df + 0.5) / (df + 0.5))
```

**三个公式组件的直觉（能被追问的点）：**

| 组件 | 作用 | 调参语义 |
|---|---|---|
| idf | 词的区分度：越稀有权重越高 | 用 `ln(1+…)` 概率型变体**恒非负**——经典形式 df>N/2 的常见词会得负分，反而惩罚含领域高频词的文档 |
| tf 饱和 | 同一词出现 10 次和 20 次差别不大，堆关键词骗不过去 | `k1=1.5`：调大→削弱饱和、更接近线性 tf；调小→更早封顶 |
| 长度归一 | 长块天然词频高，不惩罚会让冗长块挤掉精短命中块 | `b=0.75`：0→完全不管块长，1→全额惩罚；RAG 切块长短不一，折中取 0.75 |

**四个工程决策（代码注释里都有展开，这里给索引）：**

1. **倒排结构三件套**：`term→(docId→tf)` 的 postings + `docId→长度` + 全局 `N`/`avgdl`。检索只遍历查询词的 posting 链，成本随查询覆盖度而非语料量增长；
2. **中文分词用 bigram 折中**（`TextTokenizer`）：不引分词词库，二字滑窗兼顾召回与精度；**入库与查询必须同一分词器口径**——词法检索第一杀手就是两边 tokenizer 不一致；
3. **copy-on-write 并发**：`rebuild` 在新 map 里全量构好后一次性替换 `volatile` 引用；读线程（`search` 不加锁）要么看到完整旧索引要么完整新索引，永远读不到"重建到一半"的脏状态；写线程 `synchronized` 互斥；
4. **全量重建即一致性**：千级 chunk 重建毫秒完成，增量删除/更新的补丁逻辑全部省掉。已知演进边界：十万级时 O(N) 重建不可接受（见下方企业级对照）。

另外两个容易漏的语义点：BM25 是 **OR 语义**（查询词不在倒排表直接跳过，不淘汰文档）；分数是**无界相对值**，只在同 query 内可比——这正是 RRF 只信名次的前提。

### 3. 企业级 BM25：Lucene / Elasticsearch 是怎么做的

**先说结论：公式完全一致。** ES 的 `BM25Similarity`（继承 Lucene）用的就是同一套 Okapi 公式，idf 也是同一个恒非负变体。差别全在**工程形态**上：

| 维度 | 本项目自建 | Lucene/ES |
|---|---|---|
| 公式参数默认值 | k1=1.5（Okapi 原文推荐）、b=0.75 | k1=1.2、b=0.75；可按索引/字段覆盖 `similarity` |
| 索引时机 | 查询时现算（纯内存 Map） | **索引期/查询期分工**：tf、字段长度(norms)落盘在段里，idf 查询时按分片总 df 现算 |
| 更新方式 | 全量 rebuild（重建即一致性） | **增量写入 + 段合并（segment merge）**：新文档进 in-memory buffer→fsync 成段，删除只是打标记，后台线程合并段——这就是为什么 ES 近实时（默认 1s refresh） |
| posting 存储 | HashMap 链 | 有序 + Delta/Frame-of-Reference 压缩 + skip list，支持跨超长 posting 链的归并和 `nextDoc(target)` 跳读 |
| 规模 | 千级 chunk，重启即丢（靠 ChunkStore 同源重建） | 分布式：倒排按 shard 切分，各 shard 独立算自己的 df/idf（注：shard 间 df 不互通，数据分布不均时打分会有偏差，routing 要均匀） |
| 特征能力 | 单字段词袋 | 多字段加权（title^3 + content，tf 按 field 混合 sum/fmax）、短语/邻近查询（需要 position 信息）、function score 任意魔改 |
| 中文 | 自写 bigram | IK/smartcn 分词器插件，同样遵守索引/搜索 analyzer 一致原则 |

**什么时候该从自建切到 ES？三个触发条件：**
1. 语料 ≥ 十万级，全量重建的 O(N) 窗口入库接口不可用；
2. 需要持久化/增量/多副本，不能再靠"重启重灌"；
3. 需要短语、邻近、字段加权这类真倒排引擎才有的检索特征。

**迁移路径已经预埋（《评测机增强实施方案》§8 B3）：** 本项目 `Bm25Index` 对上层只暴露 `search(query, topN) → List<Hit>`，换 ES 就是换一个 bean（客户端把查询翻译成 `match` DSL、把 `_score` 填进 Hit），RRF 融合、归因、评测链路零改动——这也是当初把类职责切窄的回报。

### 4. 面试可讲点速查

- BM25 相对 TF-IDF 的两大改进：tf 饱和曲线 + 按 b 控制的长度归一；
- idf 为什么要用 `ln(1+…)` 变体（恒非负）；k1、b 分别拧什么旋钮；
- 自建与 ES 公式一致，差异在段式增量索引/压缩 posting/多字段特征这三层工程形态；
- 为什么混合检索用 RRF 而不是加权归一化两路分数（量纲不可比）；
- 无锁读 + copy-on-write 重建的并发模型；全量重建的规模边界与演进方案（增量→ES）；
- 中文分词 bigram 的取舍与索引/查询同口径铁律。

### 5. BM25 四问四答（面试追问版）

**Q1：使用 BM25 的核心目的是什么？**

混合检索里"词法召回"这条腿，与向量"语义召回"互补。一句话：向量回答"意思相近的有哪些"，BM25 回答"字面命中的有哪些、按相关性排序"。没有 BM25，检索系统对精确词面就是瞎的。

**Q2：解决了什么问题？（两层）**

- **补向量的失败模式**（它进本项目的原因，对应归因层 `RETRIEVAL_MISS` 的修复手段）：编号/代码（`E1042`、"第三条第2款"）、专有名词/新词这类查询，embedding 会把它们压进语义空间压坏，BM25 靠词面精确命中 + idf 放大稀有词权重兜住；
- **修正朴素关键词检索（TF-IDF）自身的缺陷**（BM25 这个公式存在的原因）：tf 饱和曲线让堆关键词骗不过打分（k1 控制）；长度归一防止冗长块挤掉精短命中块（b 控制）。

**Q3：相对 ES 的相似度（BM25Similarity），最大核心区别是什么？**

先踩住一个常见误区：**打分公式没有任何区别**——ES 用的就是同一套 Okapi 公式和同一个恒非负 idf 变体，k1=1.2 vs 我们的 1.5 只是默认参数，不是本质。

一句话总结最大区别：**本项目的"索引"是一份用完即弃的内存结构，ES 的"索引"是一个可持久增量维护的工程系统。** 拆开看打分所需统计量的来源与维护（详表见上文 §3）：自建版 tf/df/长度全在内存查询时现取，更新靠 O(N) 全量重建，重启即丢；Lucene 把 tf 和字段长度（norms）索引期物化进段、df 查询时按分片总 df 现算，更新靠增量写入+段合并，持久化多副本。

**Q4：为什么企业级要用 ES 的 BM25？**

不是因为"打分更准"（打分一样），而是四个自建给不了的东西：
1. **规模与更新模型**：千万级文档下全量重建 = 入库接口不可用窗口；倒排增量维护这件事自己补就是重造半个 Lucene；
2. **持久化与可用性**："重启必须 reset 重灌"在生产线不可接受；多副本、崩溃恢复、近实时是白送的；
3. **检索特征**：多字段加权（title^3+content）、短语/邻近查询（需 position 信息）、filter 与打分混合、function score；
4. **一致性与生态**：索引/搜索 analyzer 同口径由框架保证（IK/smartcn 现成），团队都认识、运维监控安全体系成熟——自己写的每一行"能解释"，但也每一行"要自己保证正确"。

**30 秒标准答法**："我们用 BM25 是补向量的词法短板——精确术语和编号。它和 ES 的打分公式完全一致，所以迁 ES 不存在指标漂移；企业级选 ES 的本质原因是**更新模型**：段式增量索引 + 持久化多副本，替代我们'全量重建 + 重启重灌'的玩具形态，再加上短语/字段加权这些检索特征。我们的窄接口设计让这次迁移只是换一个 bean。"

## 切块专题：现有三策略的边界与企业级划分方式（RAG 第一瓶颈）

> 代码：`chunking/` 包，策略实现 `ChunkingStrategy` 接口；新增一种切块 = 加一个 bean + 配置开关，评测机直接给单变量实验裁决。

### 1. 已有的三种策略，以及它们共同的天花板

| 策略 | 机制 | 解决什么 | 失败模式 |
|---|---|---|---|
| `fixed` | 字符滑窗 + overlap | 简单可控、吞吐高 | 从句中截断，语义单元被斩断；overlap 只是缓解不是修复 |
| `recursive` | 分隔符阶梯（段落→行→句末标点→空格）逐级回退 | 块边界贴自然语义结构 | 对"内容结构"仍盲：标题、表格、代码块它一视同仁 |
| `parent-child` | 子块检索、父块送生成 | 召回精准度与上下文完整性的矛盾（小块嵌入集中、大块信息全） | 内存放大（父子两份）、父块可能带进噪声 |

三者本质都是**字符级**策略：不知道文档有标题层级、不知道表格被腰斩会毁掉整张表、不知道块里的"该金额"指代着上一个块。**切块是 RAG 的第一瓶颈：chunk 质量塌了，后面检索/生成再强都是徒劳**——garbage in 无法用 prompt 补偿。

### 2. 企业级划分方式（按投入产出比排序）

**A. 结构感知切块（Heading/Table/AST-aware）—— 成本最低、优先级最高**

- **标题层级切块**：按 Markdown/HTML 标题边界切，每块拼上父标题链作面包屑前缀（`第三条 假期 > 3.2 年假 > 计算方式`）。企业文档（制度/wiki/产品手册/合同）天然结构化，这一步不花任何模型调用，块质量立刻上台阶；面包屑同时修复了"孤立块缺文档级先验"的问题；
- **表格感知切块**：表格绝不按字数斩——按行序列化成"列名：值"句式（每行自带表头上下文），附表格标题独立成块。向量化一坨 `|` 分隔符是纯浪费；
- **代码切块**：AST（tree-sitter/JavaParser）以函数/类为最小单元，附 docstring；字符切会把半个方法喂进向量库。

**B. Contextual Retrieval（上下文增强块，Anthropic 提出）—— 效果最大的一项**

入库时让 LLM 看全文后为每块生成一句背景前缀（"本段出自 XX 公司 HR 制度，'满 3 年'指累计工龄…"）拼在块前再 embedding/建索引。根治**指代消解**（块里的"该金额/上述条件"）和跨块依赖；Anthropic 报告检索失败率 -35%（叠加 rerank -49%）。代价：每块一次 LLM 调用（入库时一次性，prompt caching 可降至分毫级），企业知识库离线入库场景下完全值得。

**C. 语义切块（Semantic Chunking）—— TODO 已立项**

相邻句子 embedding 余弦相似度滑窗，骤降点（跌破分位阈值）即主题切换处→在那里断。比固定阶梯更贴"主题边界"；成本是每文档一遍批量 embedding（复用现有模型，便宜）。风险：列表/条款型文档相似度分布平坦甚至退化，必须保留 min/max 长度兜底——所以它是 recursive 的增强而非替代。

**D. Late Chunking（先嵌入后切块，Jina 提出）**

长上下文 embedding 模型先把**整篇**编成 token 级向量，再按切块边界对各段 token 向量 mean-pool 得块向量——每个块的表征都带着全文语境。天花板在于需要开源长上下文模型暴露 token_embeddings（百炼/DashScope 这类托管 API 只给整句向量，用不了）——适合做技术叙事，落地排后。

**E. LLM 切块 / 命题化（Propositions / Agentic Chunking）**

让 LLM 直接把文档改写成"每条=一个自包含事实"的命题集，检索精度最高但最贵；企业知识库是离线入库场景，成本一次性摊销，可接受。变体：**索引问题而非答案**（FAQ/工单库标准做法）：对每块反向生成"用户会怎么问”，把问题空间入索引——本评测集的反向出题法就是同一思想在评测侧的应用。

**F. 多表示/层级索引（Document / Summary Index）**

除了块级索引，再加一层"文档摘要向量"：命中摘要后回文档内部展开细检。适合"答案散布全文"的长报告；与 parent-child 是同一哲学的两种实现（检索粒度与存储粒度解耦）。

### 3. 落地路线建议（针对本项目）

1. **HeadingChunker**（md 文档免费午餐，下一个单变量实验的最佳候选）；
2. **Contextual 前缀生成接入 IngestService**（效果最大；token 记账已有，成本可见可控）；
3. 语义切块（TODO 项，批量 embedding 实现）；
4. 纪律红线：换切块策略 = 换语料快照，必须 reset 全量重灌 + 重跑全量评测建新基线再对比（datasetVersion 只指纹测试集，不会替你发现这件事；evidenceHit 锚点比对的是源文档归一化文本，所以题目不用重出）。

### 4. 面试可讲点速查

- 切块三层递进：字符级（fixed/recursive）→ 结构级（标题/表格/AST）→ 语义级（semantic/contextual/late/propositions）；
- 为什么表格必须行序列化、面包屑前缀修复什么；
- Contextual Retrieval 原理（指代消解 + 文档级先验进嵌入）与成本结构（一次性入库摊销）；
- Late chunking 为什么大模型 API 用不了（token 级嵌入不可得）；
- 策略接口化 + 评测机单变量裁决，才是"切块方案选型"的工程答案，而非拍脑袋站队。

## 模块地图（每一行都能解释的设计）

| 包 | 职责 | 对应深度点 |
|---|---|---|
| `chunking` | 3 种切块策略实现 | 固定 vs 递归 vs parent-child 对比实验 |
| `retrieval` | BM25 索引 / 中英混合分词 / RRF / Reranker / 混合编排 | 混合检索与精排原理 |
| `qa` | 上下文装配、引用溯源、拒答、token 记账 | 幻觉治理 |
| `eval` | 评测机：caseId/锚点测试集、异步 run+JSONL 存档、失败归因分层、错题重跑与 diff、Judge 独立与失误清单 | 量化评测 |
| `ingest` | PDF/文本解析、入库编排 | 文档解析天花板 |
| `web` | 端点含纯检索调试（可传 evidence 参数看命中标记） | 归因分析方法论 |
| `mcp` | @Tool 包装 Service 为 MCP 工具（REST/MCP 双传输同一实现） | Agent 生态接入、HITL 工具设计 |

## Docker 基建（仓库内置最小 compose，也可接统一托管服务）

仓库自带 `rag-kb/docker-compose.yml`（Milvus standalone + Postgres + 可选 TEI），端口与
application.yml 默认值对齐，clone 后即可拉起：

```powershell
cd rag-kb
docker compose up -d                      # Milvus(19530) + Postgres(5432/ragkb)
docker compose --profile rerank up -d     # 追加 CPU 版 rerank（8087）
```

| 服务 | compose 端口 | 关键配置 |
|---|---|---|
| Milvus standalone（v2.4.9，连带 etcd/minio） | 19530 / 9091 | 向量持久化：`-Pmilvus` 构建 + persistent profile（`simple=false`） |
| Postgres（pgvector/pg16） | 5432 | ChunkStore 落库；连接串指向 **ragkb 库**（表由应用启动自建），凭据 postgres/postgres 与 yml 默认值一致 |
| Rerank TEI CPU 版（BAAI/bge-reranker-v2-m3） | **8087**（对齐 yml 默认） | `rag.rerank.enabled=true`（persistent profile 已开）；服务未起时自动降级，不阻断主链路 |

> 已有统一托管基建的机器（按服务拆目录的自建 compose）可不启本文件，用
> `MILVUS_HOST`/`PG_URL`/`RERANK_URL` 环境变量指向你的服务即可（端口不同也没关系，覆盖默认值）。
> 首次拉 rerank 模型约 2GB 到命名卷，TEI 健康检查宽限 120s，属正常。
> 注：旧版 TEI 镜像（cpu-1.5）因 HF 改返相对 307 Location 会无限重启循环——用 cpu-1.9+ 镜像（compose 已锁）。

## 切到 Milvus（向量持久化）

前提：Milvus standalone 运行中（`localhost:19530`，启停见上节 compose）。

```bash
mvn -Pmilvus spring-boot:run -Dspring-boot.run.arguments=--rag.vectorstore.simple=false
```

切换前逐条核对（都是会静默改变评测结论的坑）：

1. ⚠️ **维度对齐**：`text-embedding-v3` 默认输出 **1024 维**，Spring AI Milvus starter 建 collection 默认
   **1536 维**——须设 `spring.ai.vectorstore.milvus.embedding-dimension: 1024`（注意是单数，已在 application.yml 配好），
   否则首次入库 dimension mismatch
2. **profile 是编译期行为**：命令行 `-Pmilvus` 自带重编译；IDEA 需勾选 milvus profile 并 Reload，
   否则 `simple=false` 时找不到 VectorStore Bean 启动失败
3. **重启纪律**：仅切 Milvus 时只有向量持久化，ChunkStore/BM25 仍为内存——请连带启用下节的 postgres 模式，
   否则重启后融合候选静默为空（DAT-01，warmup 已实现，见下节）
4. 换 embedding 模型 = 换向量空间，必须换 `collectionName` 或清库重灌

## 持久化部署（ChunkStore 落 Postgres，DAT-01 根治）

背景：切 Milvus 后向量虽持久，但 `ChunkStore`（BM25 语料 + 融合后回取原文）仍是内存——
重启后向量召回正常、融合候选却全空，系统表现为"低分拒答"的静默退化。解法是三层各司其职：

| 存储 | 内容 | 持久性 |
|---|---|---|
| Milvus | 向量 + 轻量 metadata | ✅ collection 落盘 |
| Postgres `chunk` 表 | 检索单元原文 + 父子关系（metadata JSON 列） | ✅ 启动自动建表（幂等 IF NOT EXISTS） |
| BM25 索引 | 进程内倒排 | 重启时由 `StoreWarmupRunner` 从 ChunkStore 自动重建 |

```powershell
# 前提：Postgres 就绪（启停见上节 compose；库需存在，表由应用启动时自建）
docker compose up -d postgres   # 或你自己的实例，用 PG_URL/PG_USER/PG_PASSWORD 覆盖
# 方式一（推荐）：persistent profile 一次带齐 postgres+rerank+simple=false（切 Milvus 需连带 -Pmilvus 构建）
mvn -Pmilvus spring-boot:run "-Dspring-boot.run.profiles=persistent"
# 方式二：不改 profile，命令行单点覆盖
mvn -Pmilvus spring-boot:run "-Dspring-boot.run.arguments=--rag.vectorstore.simple=false --rag.chunk-store.type=postgres"
```

启动日志看到 `BM25 warmup 完成: backend=postgres, chunks=N（混合检索重启即恢复，无需重新入库）`即生效；
配合幂等入库（确定性 chunk id + 写前按 source 删旧），重跑/重启/重灌任意顺序都不会语料翻倍。
面试可讲的 trade-off：用 `DriverManagerDataSource` 无连接池（读写只在入库/warmup，非在线高频路径）、
不用 Flyway（单表幂等建表替代）、metadata 存 JSON 文本列而非拆 parent 表——都是"落库演进第一步"的克制取舍，
上量时的演进方向（连接池/表规范化/ES 倒排）在排查清单里挂账。

## 项目结构

```
rag-kb/
├── data/
│   ├── docs/            # 知识库源文档（md/txt/pdf），ingest 与锚点校验的基准目录
│   └── eval/            # 评测集：qa_v2/qa_v2.json（36 题，含 held-out 与对抗题）+ judge-errors.md
├── output/eval-runs/    # 每次评测 run 的 JSONL 存档（自动生成，报告由此渲染）
├── docs/                # 12周执行计划 / 评测机实施方案 / MCP 面试考点 / 环境清单 / 企业级排查清单
└── scripts/mcp-probe.ps1# MCP 端到端探针
```

## 实验记录规范

每次实验只改一个变量（策略/阈值/topN/rerank），`POST /api/eval/runs {"tag":"变量=值"}`，
用 `GET /api/eval/diff` 对比基线后把 runId 记入 `docs/experiments/`（首次跑评测时创建）。
跨 datasetVersion 的 run 禁止对比（diff 接口会直接拒绝）。README 的"优化前后数字"必须能追溯到具体 runId。

## 已知局限（Local-first 学习系统的诚实边界）

- **存储模式二选一**：默认全内存（重启即失、clone 即跑）；postgres 模式 chunk 落库 + warmup 重建 BM25（DAT-01 已根治）。
  无鉴权：REST 与 MCP 端点均匿名可调，ingest 接受任意本机路径——定位为本地学习环境，
  生产化路径见排查清单 SEC-01/02（base-dir 白名单 + API-key filter）
- ~~ingest 非幂等、kb_ingest 目录支持未实现~~ 已修复（ING-01 目录批量入库 / ING-02 确定性 id + 按 source 删旧，均有单测）
- **评测单 run 串行**：单线程 executor，千级 case 内是正确成本（方案 §0"不做清单"），万级需并发+限流
- **解析天花板**：txt/md/pdf 文本层；表格 PDF/扫描件（tabula/OCR）在未做清单内（下方 TODO）
- **在线延迟口径**：`/api/query` 的 elapsedMs 暂不含检索段（清单 QA-01，修复前两套数字不要混用）

## FAQ

| 现象 | 原因与解法 |
|---|---|
| 启动正常，一查询就报 401/invalid api key | `sk-not-configured` 占位值不阻启动、**首次调用才炸**——检查 `AI_API_KEY` 是否在当前会话设置 |
| 所有问题都返回"无法回答" | ① 内存模式下服务重启后没重新 ingest；postgres 模式查 warmup 日志 chunks 是否为 0；② `min-vector-similarity` 阈值过高；先用 `/api/retrieve` 看三路分数 |
| 中文查询莫名不命中、分数极低 | PowerShell GBK 编码把请求体打成了乱码字节——`chcp 65001` 或 `--data-binary "@req.json"` |
| 评测分数比上一轮莫名下降 | 先查 warmup 日志 BM25 是否空（内存模式重启未重灌）；语料翻倍已不可能（ING-02 幂等已修），再看三路分数归因 |
| 切 Milvus 后启动失败 | Maven profile 没生效（IDEA 未勾选/未 Reload）或维度未对齐（见"切到 Milvus"第 1 条） |
| postgres 模式启动报连不上库 | Postgres 实例/库不存在（表会自建、库不会）；检查 PG_URL 与容器状态 |
| 启动日志 WARN `SyncMcpToolProvider: No tool methods found in the provided tool objects: []` | 无害噪音：starter 内部还有一条"直接工具对象"注册路径，本项目工具走的是 `ToolCallbackProvider` Bean（McpServerConfig），那条路径扫到空才打 WARN；实测 `tools/list` 返回全部 10 个工具（可用 scripts/mcp-probe.ps1 验证，需 PowerShell 7 的 `pwsh`） |
| 评测中途要改配置重跑 | run 由守护线程执行、无优雅停机——kill 后用 `{"resumeRunId":"…"}` 断点续跑 |

## TODO（学习路线附带任务，编号关联 docs/rag-kb企业级问题排查清单.md）

- [ ] 🔴 修 QA-01（延迟口径统一）——ING-01/ING-02 已完成 ✅
- [ ] 表格 PDF / 扫描件解析（tabula / OCR，见 `DocumentParserService` 注释）
- [ ] 语义切块（embedding 相似度断句）对比实验
- [x] ChunkStore 落库（Postgres）+ 启动 warmup（DAT-01 已根治，见"持久化部署"节）；十万级演进路线见 `docs/评测机增强实施方案.md` §8
- [ ] 评测集扩到 100 条（反向出题法，见 `QaCase` 注释；现 36 题：主集 26 + held-out 10，含 10 道对抗题）
- [ ] RAGAS 指标对照：用 Python RAGAS 跑同一测试集，校准自建 Judge
- [ ] MCP 传输切 Streamable HTTP（SSE 已被规范废弃，PRO-01）；ChatClient/ChatMemory 化（AGT-01/02）
- [x] 把本系统封装成工具接入 Agent 生态（MCP Server 已完成，见"MCP 专题"节；接阶段2工单处理 Agent 待做）
