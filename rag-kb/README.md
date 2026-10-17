# rag-kb · 生产级 RAG 知识库（Spring AI + Milvus）

> 定位：不是"调个模型查向量库"的 Demo，而是**带评测闭环、可量化迭代**的生产级 RAG 系统。
> 技术栈：Java 21 / Spring Boot 3.5 / Spring AI 1.1 / Milvus（可切换）/ 手写 BM25 / LLM-as-Judge 评测。

## 快速开始

```bash
# 1. 配置模型（OpenAI 兼容接口均可：DashScope / DeepSeek / OpenAI / Ollama）
#    永久写入（推荐，一次设置全局生效）：setx 后需重启 IDEA/终端才对新进程生效
setx AI_API_KEY "sk-xxx"
#    或仅当前会话临时生效：
$env:AI_API_KEY="sk-xxx"; $env:AI_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode"
$env:AI_CHAT_MODEL="qwen-plus"; $env:AI_EMBED_MODEL="text-embedding-v3"

# 2. 启动（默认内存向量库，零基建）
mvn spring-boot:run

# 3. 入库 → 问答 → 评测（评测机：异步 run + 错题重跑 + diff，详见 docs/评测机增强实施方案.md）
curl -X POST localhost:8000/api/ingest   -H "Content-Type: application/json" -d '{"path":"data/docs/attendance_policy.md"}'
curl -X POST localhost:8000/api/query    -H "Content-Type: application/json" -d '{"question":"入职满3年的员工有几天年假？"}'
curl -X POST localhost:8000/api/eval/dataset/validate                          # SOP① 锚点校验
curl -X POST localhost:8000/api/eval/runs -H "Content-Type: application/json" -d '{"tag":"baseline"}'  # 返回 runId，后台跑
curl localhost:8000/api/eval/runs/{runId}                                       # 进度/汇总
curl "localhost:8000/api/eval/runs/{runId}/results?verdict=FAIL"                # 错题清单含归因标签与现场快照
curl -X POST localhost:8000/api/eval/runs -H "Content-Type: application/json" -d '{"onlyFailedFrom":"{runId}"}'  # 错题本重跑
curl "localhost:8000/api/eval/diff?base={runId}&head={runId2}"                  # 已修复/新增失败（跨 datasetVersion 拒绝）
```

评测结果落 `output/eval-runs/{runId}.jsonl`（逐题存档：三路分数/错因标签/token 记账），
Markdown 报告由 `GET /api/eval/runs/{runId}/report` 从 JSONL 渲染——这就是"优化前 X% → 优化后 Y%"数字的出处。

## MCP Server：把 RAG + 评测机封装成标准工具（Agent 生态接入点）

同一个 Service 层现在有两种传输形态：REST（人/脚本调）+ **MCP**（LLM/Agent 自动发现调用）。
启动后任意 MCP 客户端（Claude Desktop / Cursor / Qoder）连 SSE 端点即可：

```json
{ "mcpServers": { "rag-kb": { "url": "http://localhost:8000/sse" } } }
```

对外的 10 个工具（契约由 `RagKbToolsTest.toolCallbacksExposeExactContract` 守护）：

| 工具 | 作用 | 背后复用 |
|---|---|---|
| `kb_query` | 带引用溯源的政策问答（完整 RAG 链路） | `QaService` |
| `kb_retrieve` | 纯检索归因，可传 evidence 看逐块命中标记 | `HybridRetrievalService` |
| `kb_ingest` | 文档解析入库（向量+BM25 三路写） | `IngestService` |
| `eval_dataset_validate` | 跑批前锚点守门校验 | `DatasetService` |
| `eval_run_start` | 异步启动评测跑批（错题本/smoke/断点续跑） | `EvalService` |
| `eval_run_status` / `eval_run_report` / `eval_list_runs` | 进度、Markdown 报告、历史 run | `EvalService` |
| `eval_run_diff` | 单变量实验裁决器（跨 datasetVersion 拒绝） | `EvalService` |
| `judge_error_record` | **人在回路**：人工推翻 Judge 误判后记入清单 | `EvalJudgeErrorLog` |

三个可面试的设计点：
1. **零协议代码**——`@Tool` 注解 + 一个 `ToolCallbackProvider` Bean，SSE/JSON-RPC 全部由 starter 装配；工具的 `description` 是给 LLM 看的选工具说明书，这才是真正需要打磨的文案
2. **循环依赖断开**——`openAiChatModel → toolCallbackResolver → ToolCallbackProvider → RagKbTools → QaService → openAiChatModel` 成环，在 `QaService`/`EvalJudge` 的 ChatModel 注入点加 `@Lazy` 解决（构造只存代理，首次 call 才解析）
3. **HITL 的工具层表达**——`judge_error_record` 的 description 明确禁止 Agent 自行判定 Judge 出错并调用：可靠性复利断点上留人工闸门

验证：`pwsh scripts/mcp-probe.ps1`（模拟最小客户端：SSE 握手→initialize→tools/list→tools/call 真执行一次守门校验）。

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
| 切块 | fixed / recursive / parent-child 三策略，配置一键切换 | 评测集是为"单变量实验"设计的：换策略→跑评测→看 contextRecallProxy 归因 |
| 混合检索 | 手写 BM25 + RRF 融合 | BM25 与向量失败模式互补（术语精确 vs 语义改写）；RRF 只依赖名次，免疫两路分数不可比的问题 |
| Rerank | 接口 + TEI 客户端，默认关闭 | cross-encoder 精度高但无法预计算，只对 Top-N 精排；无 GPU 环境用托管 API，代码零改动 |
| 幻觉治理 | 双防线：检索层相似度阈值拒答 + 提示层编号引用约束 | 编号引用可机器校验（忠实度评测的基础），拒答省一次生成成本 |
| 评测 | LLM-as-Judge 二值判定 + 拒答测试题(20%) + 来源命中代理指标 | 二值化抑制 Judge 打分漂移；反向出题法避免测试集贴合系统强项 |

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

## 切到 Milvus（第 3 周）

```bash
docker compose up -d               # 需要 Docker
mvn -Pmilvus spring-boot:run -Dspring-boot.run.arguments=--rag.vectorstore.simple=false
```

## 实验记录规范

每次实验只改一个变量（策略/阈值/topN/rerank），`POST /api/eval/runs {"tag":"变量=值"}`，
用 `GET /api/eval/diff` 对比基线后把 runId 记入 `docs/experiments/`。
跨 datasetVersion 的 run 禁止对比（diff 接口会直接拒绝）。README 的"优化前后数字"必须能追溯到具体 runId。

## TODO（学习路线附带任务）

- [ ] 表格 PDF / 扫描件解析（tabula / OCR，见 `DocumentParserService` 注释）
- [ ] 语义切块（embedding 相似度断句）对比实验
- [ ] ChunkStore 落库（Postgres），当前为内存实现；十万级演进路线见 `docs/评测机增强实施方案.md` §8
- [ ] 评测集扩到 100 条（反向出题法，见 `QaCase` 注释；现 36 题：主集 26 + held-out 10，含 10 道对抗题）
- [ ] RAGAS 指标对照：用 Python RAGAS 跑同一测试集，校准自建 Judge
- [x] 把本系统封装成工具接入 Agent 生态（MCP Server 已完成，见上节；接阶段2工单处理 Agent 待做）
