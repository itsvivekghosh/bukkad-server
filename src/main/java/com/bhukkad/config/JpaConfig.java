package com.bhukkad.config;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
