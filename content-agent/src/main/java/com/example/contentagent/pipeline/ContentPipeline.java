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

        ContentAnalysis analysis = analysisAgent.analyze(input);
        log.info("[pipeline] 分析完成: coreTopic={}", analysis.coreTopic());

        MonetizationPlan plan = monetizationAgent.plan(analysis);
        log.info("[pipeline] 变现方案完成: {}", plan.planTitle());

        PlatformArticle zhihu = zhihuWriterAgent.write(input, analysis);
        log.info("[pipeline] 知乎稿完成: {}", zhihu.title());

        PlatformArticle wechat = wechatWriterAgent.write(input, analysis);
        log.info("[pipeline] 公众号稿完成: {}", wechat.title());

        return new PipelineResult(input, analysis, plan, zhihu, wechat, Instant.now());
    }
}
