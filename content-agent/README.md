# content-agent

一个基于 **Spring AI** 的内容变现 Agent。把你随手记录的**想法 / 项目实战 markdown**，
自动转换成三样产出：

1. **变现方案**（谁来付费、卖什么、定价、下一步动作）
2. **知乎稿**（搜索长尾 + 技术深度 + 观点钩子）
3. **公众号稿**（手机屏短段落 + 个人叙事 + 引流 CTA）

> 定位：既是一个你每天真会用的生产工具，也是你 Spring AI Agent 学习路径里的第二个实战作品
> （在 rag-kb 之后）。它练的是「多 Agent 编排 + 结构化输出 + prompt 工程」这三项 Agent 核心手艺。

## 架构

```
data/ideas/*.md
      │  MarkdownReader
      ▼
 ContentInput
      │
      ▼  ContentPipeline（确定性顺序编排 4 个 Agent）
 ┌──────────────┬───────────────┬────────────────┬────────────────┐
 │ Analysis     │ Monetization  │ ZhihuWriter    │ WechatWriter   │
 │ 提炼可变现要素│ 生成变现方案   │ 生成知乎稿      │ 生成公众号稿    │
 └──────────────┴───────────────┴────────────────┴────────────────┘
      │  FileOutputWriter
      ▼
data/output/{id}/  ├── result.json   变现方案 + 分析（结构化）
                   ├── zhihu.md      可直接发布的知乎稿
                   └── wechat.md     可直接发布的公众号稿
```

## 设计取舍

- **用 pipeline，不用自主 ReAct 循环**：内容生产步骤固定，pipeline 更稳定、可测、成本可控。
  之后可作为一个改造练习——把它升级成「LLM 自主决定调用哪个 @Tool」的 Agent，对比两种范式的差异。
- **结构化输出**：分析、变现、稿件全部用 Spring AI 的 `.entity(...)`，模型被约束按 Java record 的
  JSON schema 返回，落盘即可被程序消费。
- **prompt 外置**：4 个系统提示词在 `src/main/resources/prompts/`，像调参一样迭代，无需改代码。

## 快速开始

### 1. 配置模型（OpenAI 兼容接口均可）

默认走阿里云 DashScope。用环境变量注入，**不要把 key 写进代码**：

```powershell
# PowerShell
$env:AI_API_KEY = "sk-你的key"
$env:AI_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode"   # 默认
$env:AI_CHAT_MODEL = "qwen-plus"

# 想用 DeepSeek：
# $env:AI_BASE_URL = "https://api.deepseek.com"
# $env:AI_CHAT_MODEL = "deepseek-chat"
```

### 2. 放入你的想法

把你的 markdown 笔记丢进 `data/ideas/`（已附一份示例 `rag-hybrid-retrieval.md`）。

### 3. 运行

```powershell
cd content-agent
mvn spring-boot:run
```

服务起在 `http://localhost:8001`。

### 4. 触发

```powershell
# 跑单篇指定文件
curl -X POST "http://localhost:8001/api/agent/run?file=rag-hybrid-retrieval.md"

# 跑 data/ideas 下全部
curl -X POST "http://localhost:8001/api/agent/run-all"

# 直接贴文本跑（不依赖本地文件）
curl -X POST "http://localhost:8001/api/agent/run-raw" -H "Content-Type: application/json" --data "@payload.json"
```

产出去 `data/output/{文件id}/` 拿，可直接复制到知乎/公众号后台微调发布。

## 目录结构

```
content-agent/
├── pom.xml
├── data/
│   ├── ideas/            输入：你的想法 / 实战 md
│   └── output/           输出：生成的方案与稿件（gitignore）
└── src/main/
    ├── java/com/example/contentagent/
    │   ├── agent/        4 个 Agent + PromptLoader/PromptSupport
    │   ├── config/       ChatClient bean + 配置属性
    │   ├── input/        MarkdownReader
    │   ├── model/        ContentInput / Analysis / Plan / Article / Result
    │   ├── output/       FileOutputWriter
    │   ├── pipeline/     ContentPipeline
    │   └── web/          AgentController
    └── resources/prompts/  4 个系统提示词
```

## 迭代方向（把它做成你真正的生产力）

1. **加一个「选题」Agent**：从公众号后台留言/知乎搜索词反推选题，喂进 pipeline。
2. **接入 rag-kb**：让你过去的实战笔记进向量库，写作 Agent 检索自己的历史素材做「自我引用」。
3. **改成自主 Agent**：把 pipeline 换成 `@Tool` + ChatClient 工具调用，练 ReAct 范式。
4. **加发布环节**：生成后一键推草稿（知乎有第三方工具、公众号有草稿箱 API）。
