package com.example.ragkb.eval;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * LLM-as-Judge 二值判定（抑制打分漂移）。
 * 关键决策（《方案》§3.3）：Judge 默认复用生成模型——这是 self-preference 风险
 * （被告的律师当法官），所以配置了 rag.eval.judge.model 时构建独立 ChatModel，
 * 且 Judge 模型名写入 run 快照，让"这个数字是谁打的"可追溯。
 * 返回：CORRECT | INCORRECT | JUDGE_ERROR（调用失败，verdict 记 ERROR 不算系统失败）。
 */
@Component
public class EvalJudge {

    private static final Logger log = LoggerFactory.getLogger(EvalJudge.class);

    private final ChatModel model;
    private final String modelName;
    private final boolean sameAsGenerator;

    public EvalJudge(@Lazy ChatModel chatModel,
                     @Value("${spring.ai.openai.chat.options.model:chat}") String chatModelName,
                     @Value("${rag.eval.judge.model:}") String judgeModel,
                     @Value("${rag.eval.judge.api-key:${spring.ai.openai.api-key:sk-not-configured}}") String apiKey,
                     @Value("${rag.eval.judge.base-url:${spring.ai.openai.base-url:https://api.openai.com}}") String baseUrl) {
        if (judgeModel == null || judgeModel.isBlank()) {
            this.model = chatModel;
            this.modelName = chatModelName;
            this.sameAsGenerator = true;
        } else {
            OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
            this.model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(judgeModel)
                            .temperature(0.0)
                            .build())
                    .build();
            this.modelName = judgeModel;
            this.sameAsGenerator = false;
        }
        if (sameAsGenerator) {
            log.warn("评测 Judge 与生成使用同一模型（{}），存在 self-preference 偏差风险；" +
                    "建议设置 rag.eval.judge.model 指向不同模型", chatModelName);
        }
    }

    /** Judge 判定结果 + 裁判自身消耗（成本记账要连裁判一起算） */
    public record Outcome(String verdict, int promptTokens, int completionTokens) {
        public Outcome(String verdict) {
            this(verdict, 0, 0);
        }
    }

    /** 对照参考答案判正确性；空答案/拒答在可答题上直接判 INCORRECT（不调 LLM，省一次调用） */
    public Outcome judge(String question, String expected, String actual) {
        if (actual == null || actual.isBlank() || actual.startsWith("无法回答")) {
            return new Outcome("INCORRECT");
        }
        String prompt = """
                你是评测裁判。对照参考答案，判断系统回答是否在事实层面正确回答了问题。
                只输出一行：正确输出 CORRECT，错误或不完整输出 INCORRECT，不要任何解释。

                问题：%s
                参考答案：%s
                系统回答：%s
                """.formatted(question, expected, actual);
        try {
            ChatResponse resp = model.call(new Prompt(List.of(new UserMessage(prompt))));
            String out = resp.getResult().getOutput().getText();
            Usage usage = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
            int pt = usage == null || usage.getPromptTokens() == null ? -1 : usage.getPromptTokens();
            int ct = usage == null || usage.getCompletionTokens() == null ? -1 : usage.getCompletionTokens();
            String verdict = out != null && out.contains("CORRECT") && !out.contains("INCORRECT")
                    ? "CORRECT" : "INCORRECT";
            if (out == null) {
                verdict = "JUDGE_ERROR";
            }
            return new Outcome(verdict, pt, ct);
        } catch (Exception e) {
            log.error("Judge 调用失败: {}", e.getMessage());
            return new Outcome("JUDGE_ERROR");
        }
    }

    /** run 快照里显示的裁判身份 */
    public String describe() {
        return modelName + (sameAsGenerator ? " (same-as-generator ⚠self-preference)" : " (independent)");
    }
}
