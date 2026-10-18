# 企业级底座 DoD 清单

> 每项必须给验证方式（命令/文件/日志），"应该有"不算完成。按节号对应 SKILL.md。

## 1. 仓库工程
- [ ] `.gitignore` 覆盖 `.env`、密钥文件模式、本地数据/output 目录 —— 验证：`git status` 干净且含密钥样例文件被忽略
- [ ] README 四节齐全，快速开始命令逐条真实执行过 —— 验证：干净目录 clone → 按 README 跑通首个接口
- [ ] 父 pom/package 仅管理版本，子模块自选依赖 —— 验证：删除某模块 web 依赖能独立编译

## 2. 配置治理
- [ ] 默认 profile 零外部依赖可启动 —— 验证：不启动任何中间件，`spring-boot:run` 成功 + 冒烟接口 200
- [ ] yml 每个开关注释值 = 代码 @Value 默认 = 文档表格值 —— 验证：逐行对照表（评审时抽查 3 个）
- [ ] 无空 profile / 无死配置 —— 验证：`grep profiles.active` 指向的文件有实际内容或不存在该引用
- [ ] 持久化/生产形态在独立 profile —— 验证：`-Dspring-boot.run.profiles=postgres` 一条命令行切换

## 3. 外部调用
- [ ] 全部 HTTP 客户端有显式超时 —— 验证：grep 无裸 `RestClient.create()`/`WebClient` 默认 builder
- [ ] 每类降级有三异常覆盖单测（拒绝/超时/协议错） —— 验证：mock 故障的单测在 CI 绿
- [ ] 降级响应带可见标志（degraded/fallback 字段或日志 WARN） —— 验证：拔依赖后响应体可断言

## 4. 异常边界
- [ ] 全局 advice 统一错误结构 code/message/traceId —— 验证：故意 400/500，响应为结构体非堆栈
- [ ] 入口参数校验（null/超长/穿越） —— 验证：`?file=../../` 返回 400 而非读文件

## 5. 安全
- [ ] 鉴权过滤器骨架存在且可开关 —— 验证：`security.enabled=true` 时无 token 请求 401
- [ ] 配置文件无真实密钥、无个人绝对路径 —— 验证：grep `sk-`、盘符路径零命中
- [ ] SENSITIVE 端点清单落文档 —— 验证：路由表标注与 README 局限节一致

## 6. 可观测
- [ ] /actuator/health liveness 与 readiness 分离 —— 验证：拔 DB 后 liveness 仍 up、readiness down
- [ ] traceId 贯穿日志与响应头 —— 验证：一次请求的日志行与响应头 traceId 相同
- [ ] 四金指标有定义文档（口径唯一） —— 验证：延迟统计口径在代码注释中可见且全局一致

## 7. 存储
- [ ] 池化连接 + 池×实例 vs max_connections 换算注释 —— 验证：配置文件与注释同址可见
- [ ] 批量写走 batch+事务 —— 验证：单测注入第 N 条失败，断言无半套数据
- [ ] schema 由迁移工具管理（生产） —— 验证：存在 Flyway V1 脚本，initSchema 仅 local profile
- [ ] IN 分批与禁 SELECT * 收敛在 DAO —— 验证：grep 业务层无裸 SQL 拼接

## 8. CI 与测试
- [ ] CI 首跑绿：构建+测试+配置冒烟断言 —— 验证：Actions 页面链接
- [ ] 测试对称：每模块 test 目录非空 —— 验证：`find */src/test -name "*.java" | 按模块计数` 全 >0
- [ ] 对外契约测试锁定 —— 验证：改一个 API 字段名，CI 红

## 9. 部署
- [ ] 仓库自带 compose/manifests 且被 README 引用 —— 验证：`docker compose config` 校验通过
- [ ] graceful shutdown 配置 —— 验证：kill -TERM 时在途请求完成不报 5xx
- [ ] 启动自检日志第一屏 —— 验证：启动日志首行段含 profile/依赖连通/开关值三要素

## 10. 收尾
- [ ] 已知局限清单挂账所有"延后"项 —— 验证：裁剪表与 README 局限节逐条对应
- [ ] 立即跑一轮 `enterprise-project-review` 首飞 —— 验证：评审报告无 🚨（有则回修 2/3 节）
