package com.example.contentagent.pipeline;

import com.example.contentagent.agent.ContentAnalysisAgent;
import com.example.contentagent.agent.MonetizationAgent;
import com.example.contentagent.agent.WechatWriterAgent;
import com.example.contentagent.agent.ZhihuWriterAgent;
import com.example.contentagent.model.ContentAnalysis;
import com.example.contentagent.model.ContentInput;
import com.example.contentagent.model.MonetizationPlan;
import com.example.contentagent.model.PipelineResult;
import com.example.contentagent.model.PlatformArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 编排 4 个 Agent 顺序执行：分析 → 变现 → 知乎 → 公众号。
 *
 * 设计取舍：这里用「确定性 pipeline」而非「让 LLM 自主决定调用哪个工具」的 ReAct 循环。
 * 原因：内容生产流程步骤固定，pipeline 更稳定、可测、可控成本；
 * 这也是一个很好的对比实验——你之后可以在阶段2把这里改造成带工具调用的自主 Agent。
 */
@Service
public class ContentPipeline {

    private static final Logger log = LoggerFactory.getLogger(ContentPipeline.class);

    private final ContentAnalysisAgent analysisAgent;
    private final MonetizationAgent monetizationAgent;
    private final ZhihuWriterAgent zhihuWriterAgent;
    private final WechatWriterAgent wechatWriterAgent;

    public ContentPipeline(ContentAnalysisAgent analysisAgent,
                           MonetizationAgent monetizationAgent,
                           ZhihuWriterAgent zhihuWriterAgent,
                           WechatWriterAgent wechatWriterAgent) {
        this.analysisAgent = analysisAgent;
        this.monetizationAgent = monetizationAgent;
        this.zhihuWriterAgent = zhihuWriterAgent;
        this.wechatWriterAgent = wechatWriterAgent;
    }

    public PipelineResult run(ContentInput input) {
        log.info("[pipeline] 开始处理: {}", input.title());

        // 结构化输出可能因模型不合规返回 null：在这里就fail-fast报清楚"哪个环节没产出"，
        // 不留到下一环节拿 null 调方法时暴 NPE，调用方从错误信息里看到的是环节名而非堆栈猜测
        ContentAnalysis analysis = require(analysisAgent.analyze(input), "分析 Agent(ContentAnalysisAgent)");
        log.info("[pipeline] 分析完成: coreTopic={}", analysis.coreTopic());

        MonetizationPlan plan = require(monetizationAgent.plan(analysis), "变现 Agent(MonetizationAgent)");
        log.info("[pipeline] 变现方案完成: {}", plan.planTitle());

        PlatformArticle zhihu = require(zhihuWriterAgent.write(input, analysis), "知乎 Agent(ZhihuWriterAgent)");
        log.info("[pipeline] 知乎稿完成: {}", zhihu.title());

        PlatformArticle wechat = require(wechatWriterAgent.write(input, analysis), "公众号 Agent(WechatWriterAgent)");
        log.info("[pipeline] 公众号稿完成: {}", wechat.title());

        return new PipelineResult(input, analysis, plan, zhihu, wechat, Instant.now());
    }

    private static <T> T require(T value, String stage) {
        if (value == null) {
            throw new IllegalStateException("Pipeline 环节无产出：" + stage
                    + " 返回 null（多为模型未按结构化 schema 输出），检查其 prompt 与 -schema 约束");
        }
        return value;
    }
}
