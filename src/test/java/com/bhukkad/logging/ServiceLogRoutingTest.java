package com.bhukkad.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the production SiftingAppender configuration routes each
 * service's log events to its own directory under /tmp/logs/bhukkad/:
 *
 *   order        → /tmp/logs/bhukkad/order/
 *   payment      → /tmp/logs/bhukkad/payment/
 *   user         → /tmp/logs/bhukkad/user/
 *   notification → /tmp/logs/bhukkad/notification/
 *   core         → /tmp/logs/bhukkad/core/
 *
 * The test boots Logback with an XML config that mirrors the <appender name="SIFT">
 * block from logback-spring.xml, pointed at a temp directory. It exercises the
 * real ServiceLogDiscriminator and verifies isolation between services.
 */
class ServiceLogRoutingTest {

    @TempDir
    Path tempDir;

    private Path configureAndRun(Path logPath) throws Exception {
        String xml = """
                <configuration>
                  <appender name="SIFT" class="ch.qos.logback.classic.sift.SiftingAppender">
                    <discriminator class="com.bhukkad.logging.ServiceLogDiscriminator"/>
                    <sift>
                      <appender name="FILE_${service}" class="ch.qos.logback.core.rolling.RollingFileAppender">
                        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                          <fileNamePattern>%s/${service}/server_%%d{dd-MM-yyyy-HH}.log</fileNamePattern>
                          <maxHistory>24</maxHistory>
                        </rollingPolicy>
                        <encoder>
                          <pattern>%%msg%%n</pattern>
                        </encoder>
                      </appender>
                    </sift>
                  </appender>
                  <appender name="ASYNC_SIFT" class="ch.qos.logback.classic.AsyncAppender">
                    <queueSize>128</queueSize>
                    <discardingThreshold>0</discardingThreshold>
                    <appender-ref ref="SIFT"/>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="ASYNC_SIFT"/>
                  </root>
                </configuration>
                """.formatted(logPath.toString().replace("\\", "/"));

        Path config = tempDir.resolve("logback-routing.xml");
        Files.writeString(config, xml);

        // Use an isolated LoggerContext — never touch the shared SLF4J
        // singleton (resetting/stopping it would break other tests' logging).
        LoggerContext context = new LoggerContext();
        context.setName("routing-" + System.nanoTime());
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        JoranConfigurator joran = new JoranConfigurator();
        joran.setContext(context);
        joran.doConfigure(config.toFile());

        Logger order = context.getLogger("com.bhukkad.serviceImpl.OrderServiceImpl");
        Logger payment = context.getLogger("com.bhukkad.serviceImpl.PaymentServiceImpl");
        Logger user = context.getLogger("com.bhukkad.security.JwtAuthenticationFilter");
        Logger notification = context.getLogger("com.bhukkad.serviceImpl.NotificationServiceImpl");
        Logger core = context.getLogger("org.springframework.web");

        order.info("ORDER_EVENT");
        payment.info("PAYMENT_EVENT");
        user.info("USER_EVENT");
        notification.info("NOTIFICATION_EVENT");
        core.info("CORE_EVENT");

        // AsyncAppender: give the worker time to flush, then stop.
        Thread.sleep(1500);
        context.stop();
        return logPath;
    }

    private Set<String> hourlyFiles(Path root) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".log"))
                    .map(p -> root.relativize(p).toString())
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void servicesRouteToTheirOwnDirectories() throws Exception {
        Path logPath = tempDir.resolve("logs");
        configureAndRun(logPath);

        Set<String> files = hourlyFiles(logPath);
        assertTrue(files.stream().anyMatch(f -> f.startsWith("order/")), "order dir missing: " + files);
        assertTrue(files.stream().anyMatch(f -> f.startsWith("payment/")), "payment dir missing: " + files);
        assertTrue(files.stream().anyMatch(f -> f.startsWith("user/")), "user dir missing: " + files);
        assertTrue(files.stream().anyMatch(f -> f.startsWith("notification/")), "notification dir missing: " + files);
        assertTrue(files.stream().anyMatch(f -> f.startsWith("core/")), "core dir missing: " + files);

        // Each service has exactly one hourly file.
        assertEquals(5, files.size(), "expected one hourly file per service: " + files);
    }

    @Test
    void serviceLogsAreIsolated() throws Exception {
        Path logPath = tempDir.resolve("logs2");
        configureAndRun(logPath);

        Set<String> files = hourlyFiles(logPath);
        String paymentFile = files.stream().filter(f -> f.startsWith("payment/")).findFirst().orElseThrow();
        String orderFile = files.stream().filter(f -> f.startsWith("order/")).findFirst().orElseThrow();

        String paymentContent = Files.readString(logPath.resolve(paymentFile));
        String orderContent = Files.readString(logPath.resolve(orderFile));

        // Payment dir contains only payment events; order dir only order events.
        assertTrue(paymentContent.contains("PAYMENT_EVENT"));
        assertTrue(!paymentContent.contains("ORDER_EVENT"));
        assertTrue(orderContent.contains("ORDER_EVENT"));
        assertTrue(!orderContent.contains("PAYMENT_EVENT"));
        assertTrue(!orderContent.contains("USER_EVENT"));
    }

    @Test
    void directoriesAreCreatedAutomatically() throws Exception {
        Path logPath = tempDir.resolve("auto-created");
        configureAndRun(logPath);
        assertTrue(Files.isDirectory(logPath.resolve("payment")), "payment dir must be auto-created");
        assertTrue(Files.isDirectory(logPath.resolve("order")), "order dir must be auto-created");
        assertTrue(Files.isDirectory(logPath.resolve("user")), "user dir must be auto-created");
        assertTrue(Files.isDirectory(logPath.resolve("notification")), "notification dir must be auto-created");
    }
}
