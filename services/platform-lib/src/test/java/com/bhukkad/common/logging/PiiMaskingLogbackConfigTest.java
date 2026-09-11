package com.bhukkad.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 PII log-masking registration: the canonical logback template
 * ({@code logback/bhukkad-logback-base.xml} in platform-lib, the file every
 * service's {@code logback-spring.xml} includes) must register the
 * {@code %pii} conversion rule without errors, mount a CONSOLE appender on an
 * INFO root, and mask emails and phone numbers through the real pattern
 * pipeline.
 */
class PiiMaskingLogbackConfigTest {

    private static final String SERVICE_STYLE_CONFIG =
            "<configuration><include resource=\"logback/bhukkad-logback-base.xml\"/></configuration>";

    private LoggerContext context;
    private ListAppender<ILoggingEvent> capture;

    @BeforeEach
    void configureFromCanonicalInclude() throws Exception {
        // Isolated context, but configured EXACTLY like every service's
        // logback-spring.xml (include the shared base fragment). The isolated
        // context needs its own MDC adapter — the template's %X{traceId}
        // converter uses it.
        context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new org.xml.sax.InputSource(new StringReader(SERVICE_STYLE_CONFIG)));

        List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
        assertThat(statuses)
                .as("the canonical template must configure cleanly (conversionRule, appender, root)")
                .noneMatch(status -> status.getLevel() == Status.ERROR);

        Logger probe = context.getLogger("PiiMaskingProbe");
        capture = new ListAppender<>();
        capture.start();
        probe.addAppender(capture);
    }

    @Test
    void template_mountsConsoleAppenderOnInfoRoot() {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(root.getLevel()).isEqualTo(Level.INFO);
        assertThat(root.getAppender("CONSOLE"))
                .isInstanceOf(ConsoleAppender.class);
    }

    @Test
    void conversionRule_resolves_andPatternWrapsMessageWithPii() {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        ConsoleAppender<?> console = (ConsoleAppender<?>) root.getAppender("CONSOLE");
        PatternLayoutEncoder encoder = (PatternLayoutEncoder) console.getEncoder();

        assertThat(encoder.getPattern())
                .as("the pattern must route messages through the masking converter")
                .contains("%pii");

        // The rule must be resolvable in THIS context: a layout using the
        // template's pattern formats without the unknown-converter fallback.
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(encoder.getPattern());
        layout.start();

        assertThat(context.getStatusManager().getCopyOfStatusList())
                .noneMatch(status -> status.getLevel() == Status.ERROR);
    }

    @Test
    void loggedPhoneAndEmail_areMasked_inFormattedOutput() {
        Logger probe = context.getLogger("PiiMaskingProbe");
        probe.info("otp sent to phone=9876543210 email=customer@bhukkad.in");

        assertThat(capture.list).hasSize(1);

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        PatternLayoutEncoder encoder = (PatternLayoutEncoder) ((ConsoleAppender<?>) root.getAppender("CONSOLE")).getEncoder();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(encoder.getPattern());
        layout.start();

        String formatted = layout.doLayout(capture.list.get(0));

        assertThat(formatted)
                .contains("***@***")
                .contains("**********")
                .doesNotContain("customer@bhukkad.in")
                .doesNotContain("9876543210");
    }
}
