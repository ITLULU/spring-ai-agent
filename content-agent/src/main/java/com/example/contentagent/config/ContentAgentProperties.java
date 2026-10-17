package com.example.contentagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * content-agent.* 配置项。
 */
@Component
@ConfigurationProperties(prefix = "content-agent")
public class ContentAgentProperties {

    /** 输入 markdown 目录 */
    private String inputDir = "data/ideas";

    /** 输出落盘目录 */
    private String outputDir = "data/output";

    public String getInputDir() {
        return inputDir;
    }

    public void setInputDir(String inputDir) {
        this.inputDir = inputDir;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }
}
