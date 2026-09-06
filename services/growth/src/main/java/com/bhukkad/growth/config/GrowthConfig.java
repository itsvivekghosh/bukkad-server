package com.bhukkad.growth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GrowthProperties.class)
public class GrowthConfig {
}
