package com.bhukkad.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        MonitoringProperties.class,
        WalletProperties.class,
        RiderEarningsProperties.class
})
public class PlatformEnhancementsConfig {
}
