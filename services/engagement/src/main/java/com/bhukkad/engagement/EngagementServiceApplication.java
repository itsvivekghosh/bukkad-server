package com.bhukkad.engagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.bhukkad.engagement.growth.config.GrowthProperties;
import com.bhukkad.engagement.notification.config.NotificationProperties;
import com.bhukkad.engagement.realtime.config.LiveProperties;
import com.bhukkad.engagement.referral.config.ReferralAbuseProperties;
import com.bhukkad.engagement.referral.config.ReferralServiceProperties;

/**
 * Engagement service — consolidated user-facing features.
 * Owns social (posts, likes, comments, feed), growth (promotions, loyalty, referrals),
 * survey (delivery surveys, trending), notification (email/SMS/push),
 * and realtime (SSE live updates, rider tracking).
 * Consolidated from social + growth + referral + survey + notification + realtime services.
 */
@SpringBootApplication(scanBasePackages = {"com.bhukkad.engagement", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.engagement", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.engagement", "com.bhukkad.common"})
@EnableJpaAuditing
@EnableConfigurationProperties({
        GrowthProperties.class,
        LiveProperties.class,
        NotificationProperties.class,
        ReferralAbuseProperties.class,
        ReferralServiceProperties.class
})
public class EngagementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngagementServiceApplication.class, args);
    }
}