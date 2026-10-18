# rag-kb 企业级问题排查清单

> **目的**：以"可上线、可工程交付的企业级 Agent"为标尺，对 `rag-kb` 做全量代码审查后形成的问题登记册。
> **基线**：2026-10-17 审查，`mvn -pl rag-kb test` 29 个测试全绿（BUILD SUCCESS）。
> **用法**：每项带唯一编号与复选框，处理完勾选并在commit信息里引用编号（如 `fix(ING-02): 幂等入库`），保持问题→修复可追溯。

**严重度定义**

| 级别 | 含义 |
|---|---|
| 🔴 P0 | 阻断项：启动测试前必须处理，否则测试结果不可信或直接报错 |
| 🟠 P1 | 企业级硬缺口：面试"能不能上线"会被一击穿毙的维度 |
| 🟡 P2 | 增强项：可讲清方案与取舍即可，不必全部实现 |

---

## 〇、已达企业级水位的部分（审计正面结论，面试可直接讲）

- 评测闭环：run 快照（config+models 写入 run 头）、失败归因分层、错题本重跑、held-out 防作弊、datasetVersion 守门跨版本禁比 ✅
- 引用溯源 + 双层拒答防线（检索层阈值 + 提示层编号约束）✅
- `EvalRunStore.file()` 对 runId 做了路径穿越校验（`..`/`/`/`\` 拒绝）✅
- 工具契约测试守护（`RagKbToolsTest.toolCallbacksExposeExactContract`）✅
- REST 与 MCP 双传输共享同一 Service 层，"接口不变、实现替换"的架构纪律 ✅

以下为问题清单。

---

## A. P0 功能缺陷（测试时必踩）

### 🔴 ING-01 · ingest 不支持目录，与对外契约不符 —— ✅ 已修（2026-10-17）
- **位置**：`ingest/DocumentParserService.parseToText()` 只认 `.pdf/.txt/.md` 单文件后缀；但 `mcp/RagKbTools.kb_ingest` 的 description 与 README 快速开始均宣称"单个文件或目录"
- **现象**：传 `data/docs` 直接抛 `暂不支持的格式: data/docs`；对 Agent 而言是工具描述撒谎，会导致编排失败
- **修复**：`IngestService.ingest()` 增加 `Files.isDirectory` 分支 → `Files.walk` 过滤受支持后缀逐个解析合并；或先改 description/README 与实现对齐
- **修复实现**：`IngestService.ingest()` 目录分支（白名单过滤 `DocumentParserService.isSupported`、排序保证稳定、BM25 收尾只重建一次）+ 空目录 fast-fail；单测 `IngestServiceTest` 覆盖；端到端实测 `data/docs` 一次灌 3 文件 5 块
- [x] 已完成

### 🔴 ING-02 · 重复 ingest 同一文档 = 语料翻倍（无幂等） —— ✅ 已修（2026-10-17）
- **位置**：三种切块器均用 `UUID.randomUUID()` 作 chunk id（`FixedSizeChunker:40`、`RecursiveCharacterChunker:42,48`、`ParentChildChunker:60,70`）；`IngestService.ingest()` 写入前不清理同 source 旧块
- **现象**：同一文档 ingest 两次 → 向量库/BM25/ChunkStore 三份重复（含评测中途重启再入库）→ 引用重复、BM25 词频/文档长度统计失真、评测数字不可信
- **修复**（企业数据管道底线是幂等）：
  1. chunk id 改确定性生成：`UUID.nameUUIDFromBytes((source + ":" + index + ":" + content).bytes)` 或 `sha256(source+index+content)` 前 16 位
  2. ingest 前按 metadata `source == 文件名` 先 `vectorStore.delete(旧ids)` + `chunkStore.removeByIds` + BM25 重建（deleteBySource）
- **修复实现**：`Chunk.stableId(source, index, content)`（`UUID.nameUUIDFromBytes`，MD5-based 跨 JVM 稳定）三切块器统一接入；`ChunkStore.removeBySource` 新增接口方法，`IngestService.ingestFile` 写前删旧→向量库同批旧 id 回删；单测：`ChunkingTest.chunkIds_areDeterministicAcrossInvocations` + `IngestServiceTest` 重灌不翻倍；端到端实测：双灌 data/docs 后 PG 与 Milvus 均恒 5 条
- [x] 已完成

### 🔴 QA-01 · 在线链路延迟口径不一致（elapsedMs 不含检索）
- **位置**：`qa/QaService.answer(String)` 第 48 行：`retrieve()` 在进入计时方法**之前**执行，`answer(question, chunks)` 里的 `start` 只圈住生成段
- **现象**：`/api/query` 返回的 `elapsedMs` ≈ 纯生成耗时；评测 run 里 `latencyMs`（`EvalService.runCase` 的 t0 在检索前）= 检索+生成。两套口径，README/面试报数字时必被追问穿帮
- **修复**：把计时起点移到 `answer(String)` 的 retrieve 之前，或统一由 Controller 层计时；QueryResponse javadoc 写明口径
- [ ] 待处理

---

## B. 企业级空白严格排查（九大维度）

### B1. 可观测性（OBS）——当前完全空白，"上线后怎么知道它坏了"无答案

#### 🟠 OBS-01 · 无 Actuator + 业务指标
- **现状**：pom 无 `spring-boot-starter-actuator`；全项目 0 个 Micrometer 指标
- **目标最小集**（约半天工作量）：
  - `Timer`：`rag.query.latency`（含检索段）、`rag.retrieve.latency`
  - `Counter`：`rag.query.total`、`rag.refusal.total`（拒答率是幻觉治理的在线代理指标）、`rag.eval.run.total/cases`
  - `FunctionCounter`：token 消耗（数据源已有：QueryResponse/EvalJudge 都记了 usage，只差暴露）
  - `Gauge`：ChunkStore.size()、BM25 docCount
- [ ] 待处理

#### 🟠 OBS-02 · 无 traceId、无结构化日志
- **现状**：只有零星 `log.warn/error`；一次"检索→生成→Judge"链路无法用同一个 ID 串起来
- **目标**：`micrometer-tracing-bridge-otel` + `management.tracing.sampling.probability`；MDC 注入 conversationId/runId/caseId。至少做到：日志 pattern 带 runId/caseId，错题可回查全链路
- [ ] 待处理

### B2. 韧性与高可用（RES）——一次网络抖动即可打穿

#### 🟠 RES-01 · LLM/Embedding/rerank 调用无超时、无重试、无熔断
- **位置**：`retrieval/TeiReranker:28` 裸 `RestClient.create()`（默认无超时=无限等待）；`QaService`/`EvalJudge` 的 `chatModel.call()` 无任何重试；OpenAI client 未配置 timeout
- **现象**：rerank 服务挂 → 请求线程吊死；LLM 429/502 抖动 → 评测 case 记 ERROR、在线请求 500
- **目标**：
  1. RestClient 显式 connect/read timeout
  2. `spring-boot-starter-aop` + `@Retryable`（指数退避 + jitter，仅重试幂等的检索/embedding/judge 调用）或 Resilience4j CircuitBreaker
  3. Judge 已有 JUDGE_ERROR 兜底（✅），生成侧无对应降级（可讲方案：降级为"仅返回引用摘要"）
- [ ] 待处理

#### 🟠 RES-02 · 无全局异常处理，错误语义混乱
- **现状**：无 `@ControllerAdvice`；`EvalController.status()/results()/report()` 用 `catch (Exception) → 404`，把 IO 错误、序列化错误全部伪装成"run 不存在"；`KbController` 的异常直接白页 500
- **目标**：统一 `ProblemDetail`（RFC 7807）返回体 + 分类异常映射（404/400/409/500），前端/Agent 才能程序化消费错误
- [ ] 待处理

#### 🟡 RES-03 · 评测 run 无优雅停机与并发守卫漏洞
- **位置**：`EvalService:67` 单线程 executor 为 daemon 线程，进程退出时 run 直接腰斩（断点续跑部分缓解 ✅）；`startRun:99` 守卫条件 `activeRunId != null && store.exists(activeRunId)`——run 文件被删/改名时可放行第二个并发 run
- **目标**：实现 `SmartLifecycle`/`@PreDestroy` 先停止提交+等待当前 case 落盘；守卫改为只看 `activeRunId != null`
- [ ] 待处理

### B3. 安全（SEC）——面试"敢直接部署吗"的第一个击穿点

#### 🟠 SEC-01 · /api/ingest 与 kb_ingest = 任意文件读取通道
- **位置**：`KbController.ingest()`、`RagKbTools.kbIngest()` 接受任意路径直传 `Files.readString`/PDFBox
- **风险**：把服务器任意文本文件（配置、源码、凭据文件改后缀 .txt）灌进知识库并经 `/api/query` 回显——真实的数据泄露路径；MCP 化后该能力进一步暴露给任何连 `/mcp` 端点的 Agent
- **目标**：入库路径白名单 base-dir（canonicalPath 前缀校验）+ 后缀白名单集中定义；这也是评测锚点回读 `docsDir` 的同一套约束
- [ ] 待处理

#### 🟠 SEC-02 · 全部端点无认证授权（REST + MCP 均裸奔）
- **现状**：无 Spring Security；MCP SSE 端点、`/api/*` 全部匿名可调，`POST /api/ingest/reset` 匿名可清空知识库
- **目标（本地项目务实版）**：轻量 API-key filter（`X-API-Key`）护住写操作（ingest/reset/eval runs）即可；README"技术决策"表补一行认证 trade-off（学习期零鉴权换可跑性，生产接 OAuth2/Gateway）
- [ ] 待处理

#### 🟡 SEC-03 · 检索无权限维度（企业知识库必考题）
- **现状**：chunk metadata 只有 source/chunkIndex/strategy/parentId，无 tenant/acl/visibility；`SearchRequest.filterExpression` 从未使用
- **目标**：面试话术必备 + 最小 demo：metadata 加 `visibility: public|dept:HR`，查询带身份 → `filterExpression` 过滤。"部门 A 的文档部门 B 能不能检索到"是 RagKbToolsTest 之外最可能被深挖的生产问题
- [ ] 待处理

### B4. 数据持久化与生命周期（DAT）

#### 🟠 DAT-01 · 三处内存态，重启全丢且无恢复入口 —— ✅ 已修（2026-10-17，采纳目标 3 的 Postgres 方案并额外交付 warmup）
- **现状**：`ChunkStore`(ConcurrentHashMap)、`SimpleVectorStore`（starter 版支持 `save(File)/load(File)` 未使用）、`Bm25Index`（重启即空）
- **连锁风险（切 Milvus 时的隐形炸弹）**：`HybridRetrievalService:76-79` 中 `chunkStore.get()` 返回 null 时**静默 continue**——Milvus profile 下重启服务，向量召回有结果但 ChunkStore 为空 → 融合候选为空 → 系统表现为纯向量拒答/低分，**评测分数莫名下降且归因系统查不出原因**
- **目标**：
  1. 内存版：`SimpleVectorStore.save/load` 挂 `ApplicationReadyEvent`/`@PreDestroy`
  2. 启动时 ChunkStore 为空而向量库非空 → WARN + 提供 `POST /api/ingest/reindex`（从向量库全量回灌 BM25/ChunkStore）
  3. 生产叙事：落 Postgres（chunk 元数据表 + BM25 换 ES/Lucene），README TODO 已有此项，把它升级为讲得出的迁移方案
- **修复实现**（按目标 3 落地，优于原计划的 1/2 过渡方案）：`ChunkStore` 接口化 + `@ConditionalOnProperty(rag.chunk-store.type)` 双实现（memory 默认零基建 / postgres 落库：chunk 表启动幂等建表，ON CONFLICT upsert）；`StoreWarmupRunner` 监听 `ApplicationReadyEvent` 从 ChunkStore 重建 BM25，postgres 模式下重启混合检索无损恢复；内存模式保留原纪律（重启重灌），但 warmup 日志会明示语料为空需入库，不再静默退化。端到端实测：重启后 `backend=postgres, chunks=5`，`/api/retrieve` bm25Score>0
- [x] 已完成（遗留：SimpleVectorStore 文件持久化不再需要——生产路径已指向 Milvus+PG；BM25 换 ES 仍按触发条件演进）

#### 🟡 DAT-02 · 无文档台账（doc registry）
- **现状**：已入库了哪些文档、各自 hash/时间/chunk 数无处可查；`reset` 只能全清
- **目标**：`Map<String, DocMeta>`（source→hash/chunks/ingestedAt）暴露 `GET /api/docs`；文档改版检测（hash 变化提示重入库+题目校验）——与 datasetVersion 守门是同一思想的数据侧版本
- [ ] 待处理

#### 🟡 DAT-03 · BM25 全量重建，无增量索引
- **位置**：`IngestService:66` 每次 ingest 后 `bm25Index.rebuildFrom(chunkStore)` O(N)
- **现状定级**：千级 chunk 可接受（README 已声明规模前提），但要能讲十万级方案（倒排增删 + 段式合并，对标 Lucene）——放"追问清单"
- [ ] 待处理

### B5. 配置与环境工程（CFG）

#### 🟠 CFG-01 · 散落 20+ 个 `@Value`，无类型安全配置
- **现状**：`rag.*` 全配置以字段注入散布 8 个类；`EvalService` 一个人复制 7 个 @Value 仅为写 run 快照；字段注入导致构造不可变测试困难（DatasetService/EvalRunStore 被迫开 `setXxx` 测试后门）
- **目标**：`@ConfigurationProperties(prefix="rag")` record 分层（chunk/retrieval/rerank/eval），一次替换，顺带消灭测试注入 hack
- [ ] 待处理

#### 🟡 CFG-02 · 无 profile 分层与关键参数校验
- **现状**：单 application.yml；`AI_API_KEY` 缺省回落 `sk-not-configured` 直到首次调用才炸
- **目标**：`application-dev/prod.yml`；启动期 `@PostConstruct` 或 `ApplicationValidator` fail-fast 检查 key≠占位值；`temperature` 等生成参数进配置而非硬编码（QaService system prompt 目前硬编码在代码里——也属于"配置外置"范畴，P2）
- [ ] 待处理

### B6. API 工程与契约（API）

#### 🟠 API-01 · MCP 工具层错误契约不统一
- **位置**：`RagKbTools` 中 `evalRunStart/evalRunDiff` catch 后返回 `{status:rejected}` ✅，但 `kbIngest/evalDatasetValidate/evalRunStatus/evalListRuns/judgeErrorRecord` 直接 `throws IOException`——异常进 MCP 协议层后 Agent 拿到的是裸错误串，无法自我修正
- **目标**：所有 @Tool 方法统一返回结构化 `{ok|error|reason}`，把"给 LLM 的错误信息"当 API 设计做（description 是给机器的文档，error 也是）
- [ ] 待处理

#### 🟡 API-02 · 无流式输出端点
- **现状**：全部同步阻塞；`ChatModel.stream()`/SSE 业务端点缺失（MCP 传输的 SSE ≠ 回答流式）
- **目标**：`GET/POST /api/query/stream` 返回 `Flux<String>`；企业聊天 UI 标配，也是"为什么 RAG 要流式+引用如何边流式边校验"的面试素材
- [ ] 待处理

#### 🟡 API-03 · 无 API 版本与查询参数校验
- **现状**：`/api/*` 无版本前缀；question/path 空值未校验（`@Valid`/`@NotBlank` 零使用）
- **目标**：`/api/v1` 或 README 声明版本策略；请求 record 加 jakarta validation
- [ ] 待处理

### B7. 测试与 CI/CD 交付（TST）

#### 🟠 TST-01 · web 层与全链路零测试
- **现状**：29 个测试全在纯逻辑层（chunking/BM25/eval store/mcp 契约），无一个 `@SpringBootTest`；Controller 路由/序列化/错误码、启动装配（@Lazy 断环）全部裸奔——`mvn spring-boot:run` 起不来这类问题现在只能靠人肉发现
- **目标**：
  1. `@SpringBootTest` contextLoads（用 MockChatModel/MockEmbeddingModel bean 顶替，不需要真 key）
  2. `MockMvc` 打通 `/api/ingest→/api/query→/api/eval` 关键路径（外部 LLM 用 `MockWebServer` stub OpenAI 兼容响应）
  3. Judge 解析边界用例（`out` 含多行/小写/correct 混排）
- [ ] 待处理

#### 🟠 TST-02 · 无 CI，README"30 秒可跑"没有机器背书
- **现状**：无 `.github/workflows`；无 jacoco
- **目标**：GitHub Actions 三步（checkout/setup-java 21/`mvn -pl rag-kb test`）+ README 徽章；挂 GitHub 前完成
- [ ] 待处理

#### 🟡 TST-03 · 无应用容器化
- **现状**：docker-compose 只有 Milvus；应用本身无 Dockerfile（multistage `mvn package` + JRE 21）
- [ ] 待处理

### B8. 可扩展性与性能（SCA）

#### 🟡 SCA-01 · 无 embedding/语义缓存
- **现状**：同一 question 重复查询重复算 embedding；评测侧已通过"检索结果复用给生成"省了一次（✅ `answer(question, chunks)` 重载），在线侧无缓存
- **目标叙事**：embedding LRU 缓存（查询侧成本大头）→ 语义缓存（相似度>0.95 直接命中历史答案）是"效果-成本"表上最便宜的一列
- [ ] 待处理

#### 🟡 SCA-02 · 单线程评测器扩展上限
- **现状**：`Executors.newSingleThreadExecutor`，36 题×5-10s ≈ 3-6 分钟可接受；测试集扩到 100+ 且 Judge 独立后线性变慢
- **目标**：固定小并发（如 3 并发+限流护住 API quota），JSONL 追加写已 synchronized 天然安全——讲清为什么可以并发（case 间无共享状态）与为什么不能开太大（429）
- [ ] 待处理

### B9. MCP 协议层（PRO）

#### ✅ PRO-01 · SSE 传输已被 MCP 规范废弃（2026-10 已切 Streamable HTTP）
- **位置**：`application.yml` `protocol: STREAMABLE` + `streamable-http.mcp-endpoint: /mcp`
- **修复实现**：① 配置键不靠记忆——从 starter 1.1.3 的 `spring-configuration-metadata.json` 核实枚举 `SSE | STREAMABLE | STATELESS` 与 `streamable-http.*` 键块；② 实测切换成本：仅 yml 三行 + `mcp-probe.ps1` 握手流程（旧双通道 SSE 收流 → 单端点 POST /mcp + `Mcp-Session-Id` 头），`RagKbTools`/`McpServerConfig` 零改动——"接口不变、实现替换"再次兑现；③ 副产品：新探针逐请求 POST，PowerShell 5.1 即可跑（旧版需 pwsh 7）；④ E2E 实测：initialize 拿到会话头 → tools/list 10 工具 → tools/call `eval_dataset_validate` 真执行（36 题 stale=0）
- **SSE 对比叙事保留在**：README"MCP 专题"§6（双通道→单端点演进、STATELESS 为下一可选形态）
- [x] 已修复（实测验证）

#### 🟡 PRO-02 · 只做了 Server，没有 Client 消费侧
- **现状**：rag-kb 是工具提供方；"阶段2 工单 Agent 通过 MCP Client 连 rag-kb"未起步（README:111 已自标 TODO）
- **目标**：`spring-ai-starter-mcp-client` 写一个最小 Agent（ChatClient + ToolCallback 从 MCP 发现），这条链路通了，rag-kb 才从"问答系统"变成"Agent 生态里的检索工具"——正是阶段 2 第 6-7 周的主线
- [ ] 待处理

---

## C. Agent 能力维度欠缺（高级 Java → Agent 岗的核心差距）

### 🟠 AGT-01 · 全程裸用 `ChatModel.call()`，Spring AI 抽象层零使用
- **位置**：`QaService:83`、`EvalJudge:83`
- **问题**：ChatClient fluent API、Advisor 链（`SimpleLoggerAdvisor`/`MessageChatMemoryAdvisor`/`QuestionAnswerAdvisor`）、`.entity()` 结构化输出一个未用——这是 Spring AI Agent 岗面试的第一批问题，也说明当前代码是"SDK 直调"而非"框架工程"
- **修复**：QaService 改 `ChatClient.builder(chatModel).defaultAdvisors(...)`；system prompt 外置模板；(answer, refused, citations[]) 用 `.entity(Record)` 一次生成 + 机器校验
- [ ] 待处理

### 🟠 AGT-02 · 无会话记忆（多轮零支持）
- **问题**：每次提问独立无状态；`ChatMemory`/`MessageWindowChatMemory`/conversationId 全无。阶段 2"多轮会话可追溯"是硬验收，这里是最自然的落点
- **修复**：`/api/query` 加可选 `conversationId` → `MessageWindowChatMemory` + advisor；引用与记忆并存怎么裁剪（窗口只留 answer 不留 context 全文）——面试好素材
- [ ] 待处理

### 🟡 AGT-03 · 检索是固定管道，不是 Agent 策略（Agentic RAG 缺失）
- **缺项**：查询改写（同义/缩写/指代消解）、HyDE、多路召回变体、Router（判断该不该查库/查哪个库）、检索质量自评后重试（self-RAG 雏形）
- **目标**：至少做"查询改写"或"低置信→改写重检"一条，配评测 diff 验证增益——单变量实验框架现成，做这个成本最低、面试叙事最强
- [ ] 待处理

### 🟡 AGT-04 · 在线忠实度无机器校验
- **现状**：引用越界校验（`checkCitations`）只在评测链路，线上 `/api/query` 返回的答案不验 `[n]` 是否越界/引用块是否真含结论关键词
- **目标**：把 checkCitations 下沉到 QaService 出口（越界→降级/重生成一次），形成"在线防线 + 离线评测"双保险叙事
- [ ] 待处理

---

## D. 次级代码细节（随手修，不必单独立项）

| # | 问题 | 位置 |
|---|---|---|
| D1 | `docCache` 为普通 `HashMap` 且文档改版后永不失效：线程不安全 + validate 可能读旧文 | `DatasetService:39,157` |
| D2 | Judge 复用 chat 模型时 temperature=0.1（独立构建才 0.0）：裁判应恒定 0 | `EvalJudge:40-43` |
| D3 | `startRun` 并发守卫漏洞（见 RES-03）；`activeRunId` 置 null 的 finally 依赖 execute 不抛 Error | `EvalService:99` |
| D4 | 拒答识别 `startsWith("无法回答")`：模型输出"无法回答。经核查…"或前后缀变体时误判；Judge 的 `contains("CORRECT")` 解析同样脆 | `QaService:90`、`EvalJudge:88` |
| D5 | token 缺语义哨兵值 `-1` 与 `0` 混用（usage 缺失记 -1，NONE 记 0，`Math.max(0,·)` 吞掉差异） | `QaService:87-88`、`CaseResult:68` |
| D6 | `FixedSizeChunker` 的 `replaceAll("\\s{2,}"," ")` 抹掉段落边界，fixed 策略对结构损伤比看起来大——对比实验时要意识到这个混杂变量 | `FixedSizeChunker:33` |
| D7 | README"实验记录规范"节引用 `docs/experiments/` 尚不存在（文档已统一在 `rag-kb/docs/`，仅缺 experiments 子目录），第一次跑评测时建上 | README 实验记录规范节 |
| D8 | `kb_ingest` 与 `/api/ingest` 对 IO 错误的响应形态不同（一个 throws 一个 500 白页），统一归入 API-01/RES-02 | — |

---

## E. 建议处理顺序（对齐 12 周计划，防止在 rag-kb 跑偏）

> 原则：rag-kb 是阶段 1 资产，主线是阶段 2 工单 Agent。**只修"不修测试不可信"和"面试一击穿毙"的，其余做成"讲得出的方案"。**

| 批次 | 项 | 预估 | 时点 |
|---|---|---|---|
| ① 测试前 | ING-01 ✅、ING-02 ✅、QA-01 | ~2h | ING-01/02 已完成（含单测+端到端实测），剩 QA-01 |
| ② 本周 | TST-01(至少 contextLoads+ingest 回归 ✅ ingest 已有单测)、DAT-01 ✅(启动 warmup+ChunkStore 落 PG 已根治)、CFG-01 | ~ 天 | DAT-01 已提前完成 |
| ③ 第 4 周 M1 收尾 | OBS-01、RES-01、RES-02、SEC-01、TST-02、PRO-01、README/D7 | ~1.5 天 | 挂 GitHub 前 |
| ④ 与阶段 2 合并做 | AGT-01、AGT-02、API-02、PRO-02（工单 Agent 顺势作为 MCP Client 消费方）、AGT-03 | 融入第 5-7 周 | ChatClient/ChatMemory 是工单 Agent 的必经之路，一次投入两处收益 |
| ⑤ 话术层（不实现） | SEC-03、SCA-01/02、DAT-02/03、AGT-04 | 笔记 | 第 11-12 周系统设计准备 |

## F. 启动测试注意事项（2026-10-17 更新：ING-01/02 与 DAT-01 已修复，原绕行条款废弃）

1. 环境变量四件套：`AI_API_KEY` / `AI_BASE_URL` / `AI_CHAT_MODEL` / `AI_EMBED_MODEL`；建议另设 `EVAL_JUDGE_MODEL` 独立裁判（否则启动 WARN self-preference，且见 D2）
2. ~~ING-02 修复前：每次 ingest 先 reset~~ 已幂等：确定性 chunk id + 写前按 source 删旧，重灌任意次数不翻倍（有单测锁行为）
3. **内存模式：每次重启服务 = 全内存清空**，需重新 ingest；**postgres 模式（`--rag.chunk-store.type=postgres` + Milvus）：重启后 warmup 自动从库重建 BM25，无需重灌**（DAT-01 已根治，启动日志看 `BM25 warmup 完成: backend=postgres, chunks=N` 核对）
4. 顺序：`/api/eval/dataset/validate`（SOP①）→ smoke run（20 题验方向）→ 全量 baseline → 单变量实验 + diff
5. 在线延迟与评测延迟当前口径不同（QA-01），记录数字时注明来源
