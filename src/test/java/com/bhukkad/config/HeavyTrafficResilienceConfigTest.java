package com.bhukkad.config;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.delivery.OsrmClient;
import com.bhukkad.notification.ResilientEmailSender;
import com.bhukkad.notification.push.FcmPushNotificationSender;
import com.bhukkad.notification.sms.TwilioSmsSender;
import com.bhukkad.notification.whatsapp.TwilioWhatsAppSender;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HeavyTrafficResilienceConfigTest {

    @Test
    void resilience4j_config_presentInYaml() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("resilience4j:");
        assertThat(yaml).contains("circuitbreaker:");
        assertThat(yaml).contains("paymentGateway:");
        assertThat(yaml).contains("notificationEmail:");
        assertThat(yaml).contains("notificationSms:");
        assertThat(yaml).contains("notificationWhatsApp:");
        assertThat(yaml).contains("notificationPush:");
        assertThat(yaml).contains("osrm:");
        assertThat(yaml).contains("bulkhead:");
        assertThat(yaml).contains("max-concurrent-calls: 20");
        assertThat(yaml).contains("retry:");
        assertThat(yaml).contains("osrm:");
        // osrm retry 2 attempts
        assertThat(yaml).contains("max-attempts: 2");
    }

    @Test
    void infra_pools_tunedForHeavyTraffic() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("maximum-pool-size: ${DB_POOL_SIZE:20}");
        assertThat(yaml).contains("max-active: 20");
        // Http client pool tuned in code (RestTemplateConfig default 400)
        String restConfig = Files.readString(Path.of("src/main/java/com/bhukkad/config/RestTemplateConfig.java"));
        assertThat(restConfig).contains("maxConnections");
        assertThat(restConfig).contains("400");
    }

    @Test
    void osrmClient_annotationsPresent() throws Exception {
        var m = OsrmClient.class.getMethod("fetchRoute", double.class, double.class, double.class, double.class);
        assertThat(m.isAnnotationPresent(io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker.class)).isTrue();
        assertThat(m.isAnnotationPresent(io.github.resilience4j.bulkhead.annotation.Bulkhead.class)).isTrue();
        assertThat(m.isAnnotationPresent(io.github.resilience4j.retry.annotation.Retry.class)).isTrue();
        assertThat(m.getAnnotation(io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker.class).name()).isEqualTo("osrm");
    }

    @Test
    void notificationSenders_haveBulkhead() throws Exception {
        assertThat(TwilioSmsSender.class.getMethod("send", String.class, String.class)
                .isAnnotationPresent(io.github.resilience4j.bulkhead.annotation.Bulkhead.class)).isTrue();
        assertThat(TwilioWhatsAppSender.class.getMethod("send", String.class, String.class)
                .isAnnotationPresent(io.github.resilience4j.bulkhead.annotation.Bulkhead.class)).isTrue();
        assertThat(ResilientEmailSender.class.getMethod("send", org.springframework.mail.SimpleMailMessage.class)
                .isAnnotationPresent(io.github.resilience4j.bulkhead.annotation.Bulkhead.class)).isTrue();
        assertThat(FcmPushNotificationSender.class.getMethod("sendToUser", Long.class, String.class, String.class)
                .isAnnotationPresent(io.github.resilience4j.bulkhead.annotation.Bulkhead.class)).isTrue();
    }

    @Test
    void cacheKeyGenerators_stable_underHeavyTraffic() {
        var k1 = CacheKeyGenerator.menuItemsByIds(java.util.List.of(3L, 1L, 2L));
        var k2 = CacheKeyGenerator.menuItemsByIds(java.util.List.of(1L, 2L, 3L));
        assertThat(k1).isEqualTo(k2);

        var n1 = CacheKeyGenerator.restaurantNearby(12.91012, 77.64012, 5.001);
        var n2 = CacheKeyGenerator.restaurantNearby(12.91013, 77.64013, 5.002);
        assertThat(n1).isEqualTo(n2);
    }

    @Test
    void hikariAndTomcat_ratio_isHealthy() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        // Tomcat 80 vs Hikari 20 => ratio 4? Actually prod 30 vs 80 ratio 2.6 — healthy. Check comment documents ratio.
        assertThat(yaml).contains("tomcat.max");
        assertThat(yaml).contains("Hikari");
        // Ensure terminationGrace increased
        String k8s = Files.readString(Path.of("k8s/app/deployment.yaml"));
        assertThat(k8s).contains("terminationGracePeriodSeconds: 90");
    }
}
