package com.bhukkad.engagement.chat.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for VectorStore implementations.
 * <p>
 * Priority order:
 * 1. PgVectorStore - when {@code app.chatbot.vectorstore=pgvector} is set (requires pgvector extension in Postgres)
 * 2. InMemoryVectorStore - default fallback for dev/test profiles
 */
@Configuration
public class VectorStoreConfig {

    /**
     * PgVectorStore bean - activated when pgvector property is explicitly set.
     * The @ConditionalOnProperty on the class itself handles activation.
     */
    @Bean
    @ConditionalOnProperty(name = "app.chatbot.vectorstore", havingValue = "pgvector", matchIfMissing = false)
    public VectorStore pgVectorStore() {
        // Bean is created by PgVectorStore class @Component annotation
        return null; // Actual bean created by component scan
    }

    /**
     * InMemoryVectorStore bean - default fallback for dev/test/local profiles.
     * Only registered when no other VectorStore bean exists.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(VectorStore.class)
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "dev", matchIfMissing = true)
    public VectorStore inMemoryVectorStoreDev() {
        return new InMemoryVectorStore();
    }

    /**
     * InMemoryVectorStore bean for test profile.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(VectorStore.class)
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "test", matchIfMissing = false)
    public VectorStore inMemoryVectorStoreTest() {
        return new InMemoryVectorStore();
    }
}