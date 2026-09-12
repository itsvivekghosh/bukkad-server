package com.bhukkad.search.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the P-08 fuzzy-search option gate (P3). Deliberately separate
 * from SearchSchedulingConfig — this is a read-path knob, not scheduling.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchFuzzyProperties.class)
public class SearchFuzzyConfig {
}
