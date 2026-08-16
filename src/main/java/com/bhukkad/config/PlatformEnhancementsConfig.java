package com.bhukkad.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        MonitoringProperties.class,
        WalletProperties.class,
        RiderEarningsProperties.class,
        ReferralProperties.class,
        SettlementProperties.class,
        ScheduledOrderProperties.class,
        ExternalEventsProperties.class,
        InventoryProperties.class,
        LocalCacheProperties.class,
        GeoIndexProperties.class,
        StockReservationProperties.class,
        AlertingProperties.class,
        DeliveryTruthProperties.class,
        CommissionTierProperties.class,
        DynamicPricingProperties.class,
        GiftCardProperties.class
})
public class PlatformEnhancementsConfig {
}
