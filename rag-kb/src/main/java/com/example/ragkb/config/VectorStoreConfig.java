package com.example.ragkb.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库选择：
 *  - 默认（无 Milvus 基础设施）：内存 SimpleVectorStore，零依赖跑通全链路与评测；
 *  - -Pmilvus 且禁用本 Bean（rag.vectorstore.simple=false）：使用 starter 自动配置的
 *    MilvusVectorStore（collection/index/元数据字段由 starter 管理）。
 * 这样设计的面试叙事：先建立"评测驱动的优化闭环"，再替换存储——基建不是瓶颈，
 * 检索质量才是。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "rag.vectorstore.simple", havingValue = "true", matchIfMissing = true)
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
