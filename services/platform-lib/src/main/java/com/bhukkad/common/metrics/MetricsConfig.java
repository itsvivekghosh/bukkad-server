package com.bhukkad.common.metrics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SloProperties.class, CostProperties.class})
public class MetricsConfig {

}