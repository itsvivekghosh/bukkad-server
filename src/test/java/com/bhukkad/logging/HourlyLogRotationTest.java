package com.bhukkad.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.rolling.DefaultTimeBasedFileNamingAndTriggeringPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the hourly file-rotation contract implemented in
 * logback-spring.xml:
 *
 *   • files named exactly server_DD-MM-YYYY-HH.log
 *   • hourly grouping by the log entry's timestamp
 *   • the midnight transition 23:59:59 → 00:00:00 rolls to the new date's
 *     server_DD-MM-YYYY-00.log (the 00 hour belongs to the new calendar date)
 *   • a restart within the same hour reuses the same file (no duplicate)
 *   • a start at a new hour writes to that hour's file
 *
 * The test drives Logback's internal clock via the public test hook
 * DefaultTimeBasedFileNamingAndTriggeringPolicy#setCurrentTime, mirroring the
 * production fileNamePattern (server_%d{dd-MM-yyyy-HH}.log).
 *
 * The {@code %d{dd-MM-yyyy-HH}} pattern renders using the JVM default timezone
 * (the server timezone). CI runners default to UTC, so this test pins the
 * default timezone to the same zone the asserted filenames are expressed in —
 * otherwise a UTC runner would render a different hour token and fail.
 */
class HourlyLogRotationTest {

    private static final String PATTERN = "payment/server_%d{dd-MM-yyyy-HH}.log";
    private static final ZoneId TEST_ZONE = ZoneId.of("Asia/Kolkata");
    private static TimeZone originalTimeZone;

    @BeforeAll
    static void pinTimeZone() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(TEST_ZONE));
    }

    @AfterAll
    static void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @TempDir
    Path tempDir;

    /** A fully wired, started appender chain for one "process" lifetime. */
    private static final class Chain {
        final LoggerContext context;
        final RollingFileAppender<ILoggingEvent> appender;
        final DefaultTimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent> triggering;
        final Logger logger;

        Chain(Path dir, ZonedDateTime initialTime) {
            context = new LoggerContext();
            context.setName("rotation-" + System.nanoTime());
            context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
            appender = new RollingFileAppender<>();
            appender.setContext(context);

            TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<>();
            policy.setContext(context);
            policy.setFileNamePattern(dir + "/" + PATTERN);
            policy.setParent(appender);

            triggering = new DefaultTimeBasedFileNamingAndTriggeringPolicy<>();
            triggering.setContext(context);
            triggering.setTimeBasedRollingPolicy(policy);
            // Set the artificial time BEFORE start so the active file name is
            // derived from the test time, not the real system clock.
            triggering.setCurrentTime(initialTime.toInstant().toEpochMilli());
            policy.setTimeBasedFileNamingAndTriggeringPolicy(triggering);

            policy.start();
            triggering.start();
            appender.setRollingPolicy(policy);
            appender.setTriggeringPolicy(triggering);

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [trace:%X{traceId:--}] %msg%n");
            encoder.start();
            appender.setEncoder(encoder);
            appender.start();

            logger = context.getLogger("ROTATION_TEST");
            logger.setLevel(ch.qos.logback.classic.Level.INFO);
            logger.addAppender(appender);
            logger.setAdditive(false);
        }

        /** Logs one entry at the given absolute time (Asia/Kolkata). */
        void logAt(ZonedDateTime at) throws IOException {
            triggering.setCurrentTime(at.toInstant().toEpochMilli());
            logger.info("entry at " + at);
            appender.getOutputStream().flush();
        }

        void stop() {
            appender.stop();
            context.stop();
        }
    }

    @BeforeEach
    void setUp() {
        // no-op; each Chain owns an isolated context
    }

    private List<Path> files() throws IOException {
        Path serviceDir = tempDir.resolve("payment");
        if (!Files.exists(serviceDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(serviceDir)) {
            return stream.sorted().toList();
        }
    }

    private String content(Path file) throws IOException {
        return Files.readString(file);
    }

    @Test
    void hourlyGrouping_andFileNaming() throws Exception {
        Chain chain = new Chain(tempDir, ZonedDateTime.of(2026, 8, 22, 14, 0, 0, 0, TEST_ZONE));
        ZonedDateTime base = ZonedDateTime.of(2026, 8, 22, 14, 0, 0, 0, TEST_ZONE);
        chain.logAt(base);
        chain.logAt(base.plusMinutes(30));
        chain.logAt(base.plusMinutes(59).plusSeconds(59));
        chain.stop();

        List<Path> files = files();
        assertEquals(1, files.size(), "all 14:xx entries must land in one file");
        assertEquals("server_22-08-2026-14.log", files.get(0).getFileName().toString());
        assertEquals(3, content(files.get(0)).lines().count());
    }

    @Test
    void newHour_createsNewFile() throws Exception {
        Chain chain = new Chain(tempDir, ZonedDateTime.of(2026, 8, 22, 14, 30, 0, 0, TEST_ZONE));
        chain.logAt(ZonedDateTime.of(2026, 8, 22, 14, 30, 0, 0, TEST_ZONE));
        chain.logAt(ZonedDateTime.of(2026, 8, 22, 15, 5, 0, 0, TEST_ZONE));
        chain.stop();

        List<Path> files = files();
        assertEquals(2, files.size());
        assertEquals("server_22-08-2026-14.log", files.get(0).getFileName().toString());
        assertEquals("server_22-08-2026-15.log", files.get(1).getFileName().toString());
        assertEquals(1, content(files.get(0)).lines().count());
        assertEquals(1, content(files.get(1)).lines().count());
    }

    @Test
    void midnightTransition_00hourBelongsToNewDate() throws Exception {
        Chain chain = new Chain(tempDir, ZonedDateTime.of(2026, 8, 22, 23, 59, 59, 0, TEST_ZONE));
        // 23:59:59 on 22-08-2026
        chain.logAt(ZonedDateTime.of(2026, 8, 22, 23, 59, 59, 0, TEST_ZONE));
        // 00:00:00 on 23-08-2026 — must NOT continue the 22nd's 23h file.
        chain.logAt(ZonedDateTime.of(2026, 8, 23, 0, 0, 0, 0, TEST_ZONE));
        chain.stop();

        List<Path> files = files();
        assertEquals(2, files.size(), "midnight must start a new file for the new date");
        assertEquals("server_22-08-2026-23.log", files.get(0).getFileName().toString());
        assertEquals("server_23-08-2026-00.log", files.get(1).getFileName().toString());
        assertEquals(1, content(files.get(0)).lines().count());
        assertEquals(1, content(files.get(1)).lines().count());
    }

    @Test
    void restartWithinSameHour_reusesFile() throws Exception {
        // First process writes at 15:10.
        Chain first = new Chain(tempDir, ZonedDateTime.of(2026, 8, 22, 15, 10, 0, 0, TEST_ZONE));
        first.logAt(ZonedDateTime.of(2026, 8, 22, 15, 10, 0, 0, TEST_ZONE));
        first.stop();

        // "Restart": a brand-new appender chain, same hour (15:20) → same file, append.
        Chain restarted = new Chain(tempDir, ZonedDateTime.of(2026, 8, 22, 15, 20, 0, 0, TEST_ZONE));
        restarted.logAt(ZonedDateTime.of(2026, 8, 22, 15, 20, 0, 0, TEST_ZONE));
        restarted.stop();

        List<Path> files = files();
        assertEquals(1, files.size(), "restart within the same hour must NOT create a duplicate file");
        assertEquals("server_22-08-2026-15.log", files.get(0).getFileName().toString());
        assertEquals(2, content(files.get(0)).lines().count());
    }

    @Test
    void restartAtNewHour_writesToNewHourFile() throws Exception {
        Chain first = new Chain(tempDir, ZonedDateTime.of(2026, 8, 22, 16, 5, 0, 0, TEST_ZONE));
        first.logAt(ZonedDateTime.of(2026, 8, 22, 16, 5, 0, 0, TEST_ZONE));
        first.stop();

        // Restart at 17:00 → a new hour's file.
        Chain restarted = new Chain(tempDir, ZonedDateTime.of(2026, 8, 22, 17, 0, 0, 0, TEST_ZONE));
        restarted.logAt(ZonedDateTime.of(2026, 8, 22, 17, 0, 0, 0, TEST_ZONE));
        restarted.stop();

        List<Path> files = files();
        assertEquals(2, files.size());
        assertEquals("server_22-08-2026-16.log", files.get(0).getFileName().toString());
        assertEquals("server_22-08-2026-17.log", files.get(1).getFileName().toString());
    }
}
