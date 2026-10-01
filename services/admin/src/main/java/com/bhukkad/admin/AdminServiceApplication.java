package com.bhukkad.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.bhukkad.admin.analytics.config.ComplianceProperties;
import com.bhukkad.admin.analytics.config.ExperimentProperties;
import com.bhukkad.admin.analytics.config.FeatureFlagProperties;
import com.bhukkad.admin.analytics.config.FraudProperties;
import com.bhukkad.admin.analytics.config.FraudScoringProperties;
import com.bhukkad.admin.analytics.config.OrderArchiveProperties;

/**
 * Admin service — consolidated internal tools.
 * Owns admin analytics (audit, fraud, churn, read models) and support tickets.
 * Consolidated from admin-analytics + supportticket services.
 */
@SpringBootApplication(
    scanBasePackages = {"com.bhukkad.admin"},
    exclude = {KafkaAutoConfiguration.class}
)
@ComponentScan(basePackages = {"com.bhukkad.admin", "com.bhukkad.common"})
// Scoped to the platform-lib slices admin actually persists. Listing all of
// "com.bhukkad.common" here also registered every platform-lib component into
// @DataJpaTest slices, which provide no MeterRegistry/RestClient and then
// failed to start.
@EntityScan(basePackages = {"com.bhukkad.admin", "com.bhukkad.common.outbox",
        "com.bhukkad.common.idempotency"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.admin", "com.bhukkad.common.outbox",
        "com.bhukkad.common.idempotency"})
@EnableJpaAuditing
@EnableConfigurationProperties({
        ComplianceProperties.class,
        ExperimentProperties.class,
        FeatureFlagProperties.class,
        FraudProperties.class,
        FraudScoringProperties.class,
        OrderArchiveProperties.class
})
public class AdminServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminServiceApplication.class, args);
    }
}