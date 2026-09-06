package com.bhukkad.common.config;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class JpaConfig {

    /**
     * Flyway migrations use VARCHAR for enum columns; Hibernate 6 defaults to MySQL ENUM
     * which breaks schema validation unless we keep the string JDBC mapping.
     */
    @Bean
    public HibernatePropertiesCustomizer enumJdbcTypeCustomizer() {
        return properties -> properties.put("hibernate.type.prefer_native_enum_types", false);
    }

    /**
     * Explicit transaction boundary for flows that must commit DB work before
     * performing external I/O (e.g. order creation commits, then the payment
     * gateway is called outside any transaction). Keeps JDBC connections from
     * being held open during network calls.
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
