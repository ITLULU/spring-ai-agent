package com.example.ragkb.eval;

import java.util.Map;

/**
 * run 头（JSONL 第一行）：配置与模型的完整快照。
 * 两次 run 可比的前提——datasetVersion 必须相同（§5.2 指标可比性规则，
 * diff 接口在版本不同时直接拒绝）；Judge 模型版本入快照，
 * self-preference 风险（生成与裁判同模型）在 models.judge 里显式可见。
 */
public record RunHeader(
        String type,
        String runId,
        String datasetVersion,
        String startedAt,
        String parentRun,
        boolean smoke,
        int total,
        Map<String, Object> config,
        Map<String, String> models) {

    public static final String TYPE = "run";
}
