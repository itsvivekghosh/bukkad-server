package com.bhukkad.metrics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SloProperties.class)
public class MetricsConfig {

}