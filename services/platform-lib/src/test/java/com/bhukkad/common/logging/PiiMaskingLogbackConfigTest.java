package com.bhukkad.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 PII-LOGS: the fleet-wide console configuration
 * (src/main/resources/logback-bhukkad.xml, included by every service's
 * logback-spring.xml) must actually REDACT phone numbers and emails from
 * rendered log lines — {@link PiiMaskingConverter} existed but was dead code
 * while no logback config registered or used it.
 *
 * <p>The test boots the config the way every service's logback-spring.xml
 * does — a {@code <configuration>} that {@code <include>}s it (plain Joran;
 * the file must stay Spring-tag-free and use logback's {@code <included>}
 * fragment root, so this proof is possible) — emits a PII-bearing log line,
 * and renders it through the CONSOLE appender's OWN encoder/layout to assert
 * what would hit stdout.</p>
 */
class PiiMaskingLogbackConfigTest {

    /**
     * Regression guard for the silent-exit debug blackout: Joran splices an
     * included resource as nested content, so a {@code <configuration>} ROOT
     * in logback-bhukkad.xml would be dropped ("Ignoring unknown property
     * [configuration]") and every service would boot with zero appenders.
     * Loading through this exact include wrapper proves the appender mounts.
     */
    private static final String SERVICE_INCLUDE_WRAPPER =
            "<configuration><include resource=\"logback-bhukkad.xml\"/></configuration>";

    @Test
    void platformConfig_rendersConsoleLinesWithPiiMasked() throws Exception {
        LoggerContext context = new LoggerContext();
        context.setName("bhukkad-pii-proof");
        // Standalone contexts must wire the MDC adapter Boot installs for real
        // services (the pattern renders %X{traceId}; no adapter = NPE there).
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        assertThat(PiiMaskingLogbackConfigTest.class.getClassLoader()
                .getResource("logback-bhukkad.xml"))
                .as("logback-bhukkad.xml must ship on the platform-lib classpath")
                .isNotNull();
        configurator.doConfigure(new java.io.ByteArrayInputStream(
                SERVICE_INCLUDE_WRAPPER.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        ch.qos.logback.classic.Logger root =
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ConsoleAppender<ILoggingEvent> console =
                (ConsoleAppender<ILoggingEvent>) root.getAppender("CONSOLE");
        assertThat(console).as("shared config must mount the CONSOLE appender on root").isNotNull();
        assertThat(console.isStarted()).isTrue();
        Layout<ILoggingEvent> layout =
                ((ch.qos.logback.core.encoder.LayoutWrappingEncoder<ILoggingEvent>)
                        console.getEncoder()).getLayout();

        // The config's console layout must reach the appender — capture events
        // with an extra ListAppender, then RENDER them through the file's own
        // layout to prove what would hit stdout.
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        root.addAppender(capture);

        org.slf4j.Logger logger = context.getLogger("com.bhukkad.proof");
        logger.info("login attempt for phone=9876543210 email=vivek.ghosh+pay@bhukkad.com ok");
        logger.info("plain operational line untouched");

        assertThat(capture.list).hasSizeGreaterThanOrEqualTo(2);
        String maskedLine = layout.doLayout(capture.list.get(0));

        assertThat(maskedLine)
                .contains("***@***")                  // email redacted
                .contains("**********")                // phone redacted
                .doesNotContain("9876543210")
                .doesNotContain("vivek.ghosh+pay@bhukkad.com");
        assertThat(layout.doLayout(capture.list.get(1))).contains("plain operational line untouched");
    }
}
