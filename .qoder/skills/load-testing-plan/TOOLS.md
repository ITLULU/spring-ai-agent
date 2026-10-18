# 压测工具与场景细则

## 流量配比方法（S2 峰值场景）

1. 从线上现状文档取读写比与接口分布；无现状数据时用二八假设：Top3 读接口占 80% 流量、
   写接口占 15%、后台任务接口 5%，并标注 [假设] 待核实；
2. 每个接口独立设定目标 RPS，脚本内按权重合成一个场景（不要 N 个脚本各跑各的——
   那测不出接口间资源争抢）；
3. 数据倾斜模拟：热点 key 按 zipf 分布取参（缓存击穿与分片倾斜病灶主要来自倾斜，均匀分布会掩盖它）。

## k6 骨架（默认工具）

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  // S1 基线：阶梯找拐点
  stages: [
    { duration: '2m', target: 50 },    // VU 递增
    { duration: '5m', target: 200 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(99)<300'],   // P99 达标线
    http_req_failed: ['rate<0.001'],    // 错误率 <0.1%
    // 到达率塌陷预警：stalled 请求计数
    'iterations{status:timeout}': ['count<10'],
  },
  scenarios: { mix: { executor: 'constant-arrival-rate', rate: 300, timeUnit: '1s',
    preAllocatedVUs: 500, maxVUs: 2000 } }, // S2 用恒定到达率，压真实 QPS 而非 VU 数
};

const READ = JSON.open('payloads/read.json');   // 参数化数据集，禁止每次同参命中缓存假高
export default function () {
  if (Math.random() < 0.8) {
    const p = READ[Math.floor(Math.random() * READ.length)];
    check(http.post('http://svc/api/query', JSON.stringify(p),
      { headers: { 'Content-Type': 'application/json' } }),
      { 'p<400': r => r.status === 200 });
  } else {
    // 写路径 → 影子表/克隆库目标
  }
  sleep(1);
}
```

要点：容量测试用 `constant-arrival-rate`（QPS 是因，VU 是果）；只有测并发极限才用 VU 模型。
`--out experimental-prometheus-rw` 直接出 Grafana 板。

## JMeter / wrk2 / 分布式

- JMeter 必须非 GUI：`jmeter -n -t plan.jmx -l res.jtl -e -o report/`；
  线程组勾选 steady duty cycle 防 ramp 假象；监听器全部关闭（GUI 监听器自身吃满 CPU）。
- wrk2 恒定到达率：`wrk2 -t8 -c200 -R3000 -d300s --latency script.lua`（-R 是目标 QPS）。
- 分布式：k6 无原生集群，按接口/数据分片多机跑（每机一份参数化切片，互不重叠）；
  汇总时**各压测机 CPU<60% 才有效**，否则数据作废重压。

## S5 故障注入清单（按项目实际依赖裁剪）

| 注入 | 手段 | 预期验证 |
|------|------|----------|
| 下游依赖不可达 | iptables drop / 停容器 | 降级标志 + 超时生效（不是线程堆积） |
| 下游慢化（不失败） | toxiproxy 加延迟 > 客户端超时 | 熔断开启、RT 有上界 |
| 缓存整体清空 | S3 突增前置步骤 flushall | miss 放大后 DB 池饱和速度 = 击穿烈度 |
| DB 主从切换 | 停主库 | 连接池重建成活时间、无脏写 |
| LLM API 限流 429 | mock 服务按比例返 429 | 重试+退避不放大流量（重试风暴检查） |

## 常见坑速查

| 坑 | 症状 | 预防 |
|----|------|------|
| 压测机自身瓶颈 | 拐点出现但被测系统 CPU/池全空闲 | 压测机指标同板，核数×2000 连接上限预算 |
| coordinated omission | 延迟分布漂亮但长尾致命 | 用恒定到达率工具（k6 CAR/wrk2），闭模型结论打折 |
| 同参数循环 | QPS 很高、DB 零压力 | 参数化数据集 + zipf 热点 |
| 连接复用假象 | 压测端 keep-alive 全复用，测不出建连风暴 | 混合 10% 新建连接（模拟扩容冷启动） |
| 监控采样太粗 | 拐点毛刺被 1min 均值抹平 | 秒级采样，拐点区间加密 |
| 日志同步刷盘 | 吞吐被磁盘拖死误判业务瓶颈 | 压测 profile 关 access log/异步 appender |
| 压测数据污染 | 生产统计被测试流量污染 | 影子表/流量打标（header）+ 数据清理预案写进方案第 6 节 |

## 报告"预测 vs 实测"对照表模板

```markdown
| 组件 | 预测断裂QPS | 实测断裂QPS | 偏差 | 归因（Step 7 四类） | 预测修正 |
|------|-------------|-------------|------|---------------------|----------|
| rerank HTTP | 1200（无超时假设） | 150（连接堆积） | -87% | 排队/锁 | 补：下游RT进算式 |
```
偏差 >±30% 的行必须写"预测为什么错"——这是推算模型迭代的机会，比压测数字本身更值钱。
