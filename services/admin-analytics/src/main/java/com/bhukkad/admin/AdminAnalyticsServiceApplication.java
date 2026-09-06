package com.bhukkad.admin;

import com.bhukkad.admin.audit.AuditedAspect;
import com.bhukkad.admin.audit.AuditService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.bhukkad.admin", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.admin", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.admin", "com.bhukkad.common"})
@EnableJpaAuditing
public class AdminAnalyticsServiceApplication {

    @Bean
    public AuditService auditService(com.bhukkad.admin.domain.AuditEventRepository auditEventRepository) {
        return new AuditService(auditEventRepository);
    }

    @Bean
    public AuditedAspect auditedAspect(AuditService auditService) {
        return new AuditedAspect(auditService);
    }

    public static void main(String[] args) { SpringApplication.run(AdminAnalyticsServiceApplication.class, args); }
}
