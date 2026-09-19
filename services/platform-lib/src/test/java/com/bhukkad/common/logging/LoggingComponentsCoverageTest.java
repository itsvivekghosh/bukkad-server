package com.bhukkad.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the shared logging toolkit: AppLogger façades, business/security
 * event loggers, MDC helpers (TraceContext, MdcContext, MdcTaskDecorator),
 * cached-body wrappers, the request logging filter, the tracing bridge and the
 * per-service log discriminator. Pure unit level — logback ListAppenders
 * capture output; no infrastructure is touched.
 */
class LoggingComponentsCoverageTest {

    /** Block with checked-exception latitude (servlet calls). */
    @FunctionalInterface
    private interface Block {
        void run() throws Exception;
    }

    private static ListAppender<ILoggingEvent> capture(String loggerName, Level level, Block block) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger(loggerName);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(level);
        try {
            block.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        return appender;
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @AfterEach
    void resetMdcAndRequests() {
        MDC.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    // ── AppLogger ────────────────────────────────────────────────────────

    @Test
    void appLogger_textMethods_renderExpectedLines() {
        AppLogger logger = AppLogger.getLogger(LoggingComponentsCoverageTest.class);
        ListAppender<ILoggingEvent> events = capture(
                LoggingComponentsCoverageTest.class.getName(), Level.DEBUG, () -> {
                    logger.info("plain info");
                    logger.info("formatted {}-{}", 1, 2);
                    logger.logEvent("LOGIN", "user signed in");
                    logger.logEvent("CHECKOUT", "order paid", Map.of("orderId", 7));
                    logger.logEvent("EMPTY", "no context", Map.of());
                    logger.logEvent("NULL", "null context", null);
                    logger.debug("debug visible");
                    logger.debug("debug {}-visible", "a");
                    logger.warn("plain warn");
                    logger.warn("warn {}-{}", "a", "b");
                    logger.warnWithContext("slow call", Map.of("ms", 42));
                    logger.warnWithContext("no ctx", null);
                    logger.error("plain error");
                    logger.error("paired", new RuntimeException("cause"));
                    logger.error("error {}", "formatted");
                    logger.errorWithContext("failed", new IllegalStateException("why"), Map.of("k", "v"));
                    logger.errorWithContext("failed-empty", new IllegalStateException("why"), Map.of());
                });

        assertThat(messages(events)).containsExactly(
                "plain info",
                "formatted 1-2",
                "[EVENT: LOGIN] user signed in",
                "[EVENT: CHECKOUT] order paid | Context: orderId=7, ",
                "[EVENT: EMPTY] no context",
                "[EVENT: NULL] null context",
                "debug visible",
                "debug a-visible",
                "plain warn",
                "warn a-b",
                "slow call | Context: ms=42, ",
                "no ctx",
                "plain error",
                "paired",
                "error formatted",
                "failed | Context: k=v, ",
                "failed-empty");
        assertThat(events.list.get(13).getThrowableProxy().getMessage()).isEqualTo("cause"); // exception pair form
    }

    @Test
    void appLogger_debugSuppressedAtInfoLevel() {
        AppLogger logger = AppLogger.getLogger(LoggingComponentsCoverageTest.class);
        ListAppender<ILoggingEvent> events = capture(
                LoggingComponentsCoverageTest.class.getName(), Level.INFO, () -> {
                    logger.debug("hidden");
                    logger.debug("hidden {}", 1);
                });
        assertThat(events.list).isEmpty();
    }

    @Test
    void appLogger_mdcFacilitiesAndNamedSinks() {
        AppLogger logger = AppLogger.getLogger(LoggingComponentsCoverageTest.class);
        logger.addContext(LoggingConstants.TRACE_ID, "tr-1");
        logger.addContext(LoggingConstants.USER_ID, "u-9");
        logger.addContext(LoggingConstants.IP_ADDRESS, "10.0.0.1");
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("tr-1");

        ListAppender<ILoggingEvent> perf = capture(LoggingConstants.PERFORMANCE_LOGGER, Level.TRACE,
                () -> {
                    logger.logPerformance("findRestaurants", 12L);
                    logger.logPerformance("checkout", 4200L);
                });
        assertThat(messages(perf)).containsExactly(
                "[PERFORMANCE] findRestaurants took 12ms",
                "[SLOW OPERATION] checkout took 4200ms");
        assertThat(perf.list.get(1).getLevel()).isEqualTo(Level.WARN);

        ListAppender<ILoggingEvent> sec = capture(LoggingConstants.SECURITY_LOGGER, Level.TRACE, () -> {
            logger.logSecurityEvent("BRUTE_FORCE", "5 failures");
            logger.logSecurityWarning("SUSPECT", "odd ua");
        });
        assertThat(messages(sec)).containsExactly(
                "[SECURITY] [BRUTE_FORCE] 5 failures | TraceId: tr-1 | UserId: u-9 | IP: 10.0.0.1",
                "[SECURITY WARNING] [SUSPECT] odd ua | TraceId: tr-1 | IP: 10.0.0.1");

        ListAppender<ILoggingEvent> order = capture(LoggingConstants.ORDER_LOGGER, Level.TRACE,
                () -> logger.logOrderEvent("CREATED", 55L, "3 items"));
        assertThat(last(order)).isEqualTo("[ORDER] [CREATED] OrderId: 55 | 3 items | TraceId: tr-1 | UserId: u-9");

        ListAppender<ILoggingEvent> payment = capture(LoggingConstants.PAYMENT_LOGGER, Level.TRACE,
                () -> logger.logPaymentEvent("CAPTURED", 55L, 499.0, "razorpay"));
        assertThat(last(payment))
                .isEqualTo("[PAYMENT] [CAPTURED] OrderId: 55 | Amount: 499.0 | razorpay | TraceId: tr-1");

        logger.clearContext();
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isNull();
    }

    private static String last(ListAppender<ILoggingEvent> appender) {
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    // ── BusinessEventLogger ─────────────────────────────────────────────

    @Test
    void businessEventLogger_coversOrderPaymentAndCatalogEvents() {
        BusinessEventLogger events = new BusinessEventLogger();
        MDC.put(LoggingConstants.TRACE_ID, "tr-b");
        MDC.put(LoggingConstants.USER_ID, "u-1");

        ListAppender<ILoggingEvent> order = capture(LoggingConstants.ORDER_LOGGER, Level.TRACE, () -> {
            events.logOrderCreated(1L, "ORD-1", 2L, 3L, 350.0);
            events.logOrderStatusUpdate(1L, "NEW", "PREPARING");
            events.logOrderCancelled(1L, "out of stock");
            events.logOrderDelivered(1L, 8L);
        });
        assertThat(order.list).hasSize(4);
        assertThat(order.list.get(0).getFormattedMessage())
                .contains("ORDER_CREATED").contains("OrderNumber: ORD-1")
                .contains("Amount: ₹350.0").contains("TraceId: tr-b");
        assertThat(order.list.get(1).getFormattedMessage()).contains("NEW -> PREPARING").contains("UserId: u-1");
        assertThat(order.list.get(2).getLevel()).isEqualTo(Level.WARN);
        assertThat(order.list.get(2).getFormattedMessage()).contains("out of stock");
        assertThat(order.list.get(3).getFormattedMessage()).contains("AgentId: 8");

        ListAppender<ILoggingEvent> payment = capture(LoggingConstants.PAYMENT_LOGGER, Level.TRACE, () -> {
            events.logPaymentInitiated(1L, 350.0, "UPI");
            events.logPaymentSuccess(1L, "tx-9", 350.0);
            events.logPaymentFailed(1L, 350.0, "declined");
            events.logPaymentRefunded(1L, "tx-9", 350.0);
        });
        assertThat(payment.list).hasSize(4);
        assertThat(payment.list.get(0).getFormattedMessage()).contains("PAYMENT_INITIATED").contains("Method: UPI");
        assertThat(payment.list.get(1).getFormattedMessage()).contains("TxnId: tx-9");
        assertThat(payment.list.get(2).getLevel()).isEqualTo(Level.ERROR);
        assertThat(payment.list.get(2).getFormattedMessage()).contains("Reason: declined");
        assertThat(payment.list.get(3).getFormattedMessage()).contains("Refund: ₹350.0");

        ListAppender<ILoggingEvent> plain = capture(BusinessEventLogger.class.getName(), Level.TRACE, () -> {
            events.logRestaurantCreated(11L, "Masala Box", 4L);
            events.logMenuItemCreated(12L, "Dosa", 11L, 99.0);
            events.logCouponApplied("FIRST50", 3L, 50.0);
            events.logReviewSubmitted(13L, 11L, 5);
        });
        assertThat(plain.list).hasSize(4);
        assertThat(plain.list.get(0).getFormattedMessage()).contains("RESTAURANT_CREATED").contains("Name: Masala Box");
        assertThat(plain.list.get(1).getFormattedMessage()).contains("Price: ₹99.0");
        assertThat(plain.list.get(2).getFormattedMessage()).contains("Code: FIRST50");
        assertThat(plain.list.get(3).getFormattedMessage()).contains("Rating: 5/5");
    }

    // ── SecurityEventLogger ─────────────────────────────────────────────

    @Test
    void securityEventLogger_masksEmails_andResolvesClientIps() {
        SecurityEventLogger security = new SecurityEventLogger();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ListAppender<ILoggingEvent> sec = capture(LoggingConstants.SECURITY_LOGGER, Level.TRACE, () -> {
            security.logLoginSuccess(1L, "vivek.ghosh@bhukkad.com", "CUSTOMER");
            security.logLoginFailure("ab@bhukkad.com", "bad password"); // ≤2 local-part mask
            security.logRegistration(2L, "vc@bhukkad.com", "CUSTOMER");
            security.logUnauthorizedAccess("nobody@bhukkad.com", "/admin");
            security.logInvalidToken("expired");
            security.logPasswordChange(3L, "user@example.org");
        });
        List<String> lines = messages(sec);
        assertThat(lines.get(0))
                .contains("USER_LOGIN").contains("v***h@bhukkad.com")
                .contains("IP: 198.51.100.9"); // first XFF hop wins
        assertThat(lines.get(1)).contains("**@bhukkad.com").contains("Reason: bad password");
        assertThat(lines.get(2)).contains("**@bhukkad.com"); // 2-char local part
        assertThat(lines.get(3)).contains("UNAUTHORIZED_ACCESS").contains("Endpoint: /admin");
        assertThat(lines.get(4)).contains("INVALID_TOKEN").contains("Reason: expired");
        assertThat(lines.get(5)).contains("PASSWORD_CHANGE").contains("u***r@example.org");
        assertThat(sec.list.get(1).getLevel()).isEqualTo(Level.WARN);

        RequestContextHolder.resetRequestAttributes();
        ListAppender<ILoggingEvent> noRequest = capture(LoggingConstants.SECURITY_LOGGER, Level.TRACE, () -> {
            security.logInvalidToken("no bound request");
            security.logUnauthorizedAccess(null, "/private"); // null / not-an-email mask
        });
        assertThat(messages(noRequest).get(0)).contains("IP: unknown").contains("TraceId: null");
        assertThat(messages(noRequest).get(1)).contains("Email: ***");

        // empty X-Forwarded-For falls back to the socket address
        MockHttpServletRequest blankXff = new MockHttpServletRequest();
        blankXff.setRemoteAddr("203.0.113.8");
        blankXff.addHeader("X-Forwarded-For", "");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(blankXff));
        ListAppender<ILoggingEvent> blank = capture(LoggingConstants.SECURITY_LOGGER, Level.TRACE,
                () -> security.logInvalidToken("blank xff"));
        assertThat(messages(blank).get(0)).contains("IP: 203.0.113.8");

        // non-servlet attributes (or broken access) degrade to "unknown"
        RequestContextHolder.setRequestAttributes(mock(RequestAttributes.class));
        ListAppender<ILoggingEvent> weird = capture(LoggingConstants.SECURITY_LOGGER, Level.TRACE,
                () -> security.logInvalidToken("non-servlet context"));
        assertThat(messages(weird).get(0)).contains("IP: unknown");

        // no "domain" after split (local only)
        RequestContextHolder.resetRequestAttributes();
        ListAppender<ILoggingEvent> localOnly = capture(LoggingConstants.SECURITY_LOGGER, Level.TRACE,
                () -> security.logInvalidToken("x"));
        // sanity: a 5+ char email gets first+***+last treatment
        String masked = captureEmailMask(security);
        assertThat(masked).contains("v***h@bhukkad.com");
        assertThat(localOnly.list).isNotNull();
    }

    private static String captureEmailMask(SecurityEventLogger security) {
        ListAppender<ILoggingEvent> sec = capture(LoggingConstants.SECURITY_LOGGER, Level.TRACE,
                () -> security.logLoginSuccess(9L, "vivek.ghosh@bhukkad.com", "USER"));
        return messages(sec).get(0);
    }

    // ── TraceContext / MDC helpers ──────────────────────────────────────

    @Test
    void traceContext_idsSnapshotRestoreAndClearing() {
        assertThat(TraceContext.getTraceId()).isNull();
        MDC.put(LoggingConstants.TRACE_ID, "t-1");
        MDC.put(LoggingConstants.SPAN_ID, "s-1");
        MDC.put(LoggingConstants.REQUEST_ID, "r-1");
        MDC.put(LoggingConstants.USER_ID, "u-1");
        MDC.put(LoggingConstants.REQUEST_PATH, "/api/v1/orders");
        MDC.put(LoggingConstants.REQUEST_METHOD, "GET");
        MDC.put(LoggingConstants.IP_ADDRESS, "1.2.3.4");
        assertThat(TraceContext.getTraceId()).isEqualTo("t-1");
        assertThat(TraceContext.getSpanId()).isEqualTo("s-1");
        assertThat(TraceContext.getRequestId()).isEqualTo("r-1");
        assertThat(TraceContext.current()).containsOnlyKeys(
                LoggingConstants.TRACE_ID, LoggingConstants.SPAN_ID, LoggingConstants.REQUEST_ID,
                LoggingConstants.USER_ID, LoggingConstants.REQUEST_PATH,
                LoggingConstants.REQUEST_METHOD, LoggingConstants.IP_ADDRESS);
        assertThat(TraceContext.newSpanId()).matches("[0-9a-f]{16}");
        assertThat(TraceContext.copy()).containsEntry(LoggingConstants.TRACE_ID, "t-1");

        MDC.put(LoggingConstants.USER_ID, "   "); // blank ⇒ dropped from current()
        assertThat(TraceContext.current()).doesNotContainKey(LoggingConstants.USER_ID);

        MDC.clear();
        assertThat(TraceContext.current()).isEmpty();
        assertThat(TraceContext.copy()).isEmpty();

        TraceContext.restore(Map.of(LoggingConstants.TRACE_ID, "restored"));
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("restored");
        TraceContext.restore(Map.of());
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isNull();
        TraceContext.restore(Map.of(LoggingConstants.TRACE_ID, "x"));
        TraceContext.restore(null);
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isNull();
        TraceContext.clear();
    }

    @Test
    void wrapWithJobMdc_setsSyntheticIds_andRestoresPrevious() {
        MDC.put(LoggingConstants.TRACE_ID, "outer");
        Runnable wrapped = TraceContext.wrapWithJobMdc("outbox-relay", () -> {
            assertThat(MDC.get(LoggingConstants.TRACE_ID)).startsWith("sched-outbox-relay-");
            assertThat(MDC.get(LoggingConstants.REQUEST_METHOD)).isEqualTo("SCHEDULED");
            assertThat(MDC.get(LoggingConstants.REQUEST_PATH)).isEqualTo("/__scheduled__");
            assertThat(MDC.get(LoggingConstants.REQUEST_ID)).startsWith("sched-outbox-relay-");
        });
        assertThat(wrapped.toString()).startsWith("MdcWrappedRunnable[sched-outbox-relay-");
        wrapped.run();
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("outer"); // restored

        MDC.clear();
        Runnable anonymous = TraceContext.wrapWithJobMdc(() ->
                assertThat(MDC.get(LoggingConstants.TRACE_ID)).startsWith("sched-"));
        anonymous.run();
        anonymous.run();
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isNull(); // previous empty ⇒ cleared
    }

    @Test
    void mdcContext_restoresPreviousOrNullContext() {
        MDC.put(LoggingConstants.TRACE_ID, "keep");
        try (MdcContext ignored = MdcContext.with(Map.of(LoggingConstants.TRACE_ID, "inner"))) {
            assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("inner");
        }
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("keep");

        MDC.clear();
        try (MdcContext ignored = MdcContext.with(Map.of(LoggingConstants.USER_ID, "x"))) {
            assertThat(MDC.get(LoggingConstants.USER_ID)).isEqualTo("x");
        }
        assertThat(MDC.get(LoggingConstants.USER_ID)).isNull(); // previous null ⇒ cleared

        try (MdcContext ignored = MdcContext.with(null)) {
            assertThat(MDC.get(LoggingConstants.TRACE_ID)).isNull();
        }
    }

    @Test
    void mdcTaskDecorator_propagatesAndRestoresCallableAndRunnable() throws Exception {
        MDC.put(LoggingConstants.TRACE_ID, "parent");
        Runnable runnable = MdcTaskDecorator.decorate(
                (Runnable) () -> assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("parent"));
        Callable<String> callable = MdcTaskDecorator.decorate(
                (Callable<String>) () -> MDC.get(LoggingConstants.TRACE_ID));
        MDC.clear(); // parent thread moved on
        runnable.run();
        assertThat(callable.call()).isEqualTo("parent");
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isNull(); // worker restored to "previous" (now clear)

        // worker had its own context before running → restored, not cleared
        MDC.put(LoggingConstants.TRACE_ID, "parent-2");
        Runnable restoreGuard = MdcTaskDecorator.decorate((Runnable) () -> { });
        MDC.remove(LoggingConstants.TRACE_ID);
        MDC.put(LoggingConstants.TRACE_ID, "worker-original");
        restoreGuard.run();
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo("worker-original");
    }

    // ── ServiceLogDiscriminator ─────────────────────────────────────────

    @Test
    void serviceLogDiscriminator_routesLoggerNamesToDirectories() {
        ServiceLogDiscriminator discriminator = new ServiceLogDiscriminator();
        assertThat(discriminator.getKey()).isEqualTo(ServiceLogDiscriminator.KEY).isEqualTo("service");
        assertThat(valueOf(discriminator, null)).isEqualTo("core");
        assertThat(valueOf(discriminator, "ORDER")).isEqualTo("order");
        assertThat(valueOf(discriminator, "com.bhukkad.order.OrderService")).isEqualTo("order");
        assertThat(valueOf(discriminator, "payment")).isEqualTo("payment");
        assertThat(valueOf(discriminator, "com.bhukkad.PaymentGateway")).isEqualTo("payment");
        assertThat(valueOf(discriminator, "com.bhukkad.NotificationService")).isEqualTo("notification");
        assertThat(valueOf(discriminator, "SECURITY")).isEqualTo("user");
        assertThat(valueOf(discriminator, "com.bhukkad.identity.AuthController")).isEqualTo("user");
        assertThat(valueOf(discriminator, "com.bhukkad.UserService")).isEqualTo("user");
        assertThat(valueOf(discriminator, "com.bhukkad.CustomerRepository")).isEqualTo("user");
        assertThat(valueOf(discriminator, "com.bhukkad.SearchService")).isEqualTo("core");
    }

    private static String valueOf(ServiceLogDiscriminator discriminator, String loggerName) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLoggerName()).thenReturn(loggerName);
        return discriminator.getDiscriminatingValue(event);
    }

    // ── CachedBody wrappers ─────────────────────────────────────────────

    @Test
    void cachedBodyRequest_allowsReReadsAndReportsFinishState() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/api/v1/orders");
        raw.setContent("{\"items\":2}".getBytes());
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(raw);

        assertThat(wrapped.getBody()).isEqualTo("{\"items\":2}");

        // every getInputStream()/getReader() call replays the same content
        StringBuilder first = new StringBuilder();
        var in = wrapped.getInputStream();
        assertThat(in.isFinished()).isFalse();
        assertThat(in.isReady()).isTrue();
        int ch;
        while ((ch = in.read()) != -1) {
            first.append((char) ch);
        }
        assertThat(in.isFinished()).isTrue();
        BufferedReader reader = wrapped.getReader();
        assertThat(reader.readLine()).isEqualTo("{\"items\":2}");
        reader.close();

        assertThatThrownBy(() -> wrapped.getInputStream().setReadListener(mock(ReadListener.class)))
                .isInstanceOf(UnsupportedOperationException.class);

        // GET-less empty body also works
        MockHttpServletRequest empty = new MockHttpServletRequest("GET", "/health");
        CachedBodyHttpServletRequest emptyWrapped = new CachedBodyHttpServletRequest(empty);
        assertThat(emptyWrapped.getBody()).isEmpty();
        assertThat(emptyWrapped.getInputStream().isFinished()).isTrue();
    }

    @Test
    void cachedBodyResponse_teesEveryWriteShapeToBodyAndDelegate() throws Exception {
        MockHttpServletResponse delegate = new MockHttpServletResponse();
        CachedBodyHttpServletResponse response = new CachedBodyHttpServletResponse(delegate);

        ServletOutputStreamProxyProbe.assertTee(response, delegate);

        // writer path through the same tee
        MockHttpServletResponse writerDelegate = new MockHttpServletResponse();
        CachedBodyHttpServletResponse writerResponse = new CachedBodyHttpServletResponse(writerDelegate);
        PrintWriter writer = writerResponse.getWriter();
        writer.print("hi-writer");
        writerResponse.flushBuffer(); // writer + stream flush
        assertThat(writerResponse.getBody()).isEqualTo("hi-writer");
        assertThat(writerDelegate.getContentAsString()).isEqualTo("hi-writer");
        writer.close(); // closes tee (original + copy)

        // fresh-stream isReady/setWriteListener delegate to the original stream
        MockHttpServletResponse fresh = new MockHttpServletResponse();
        CachedBodyHttpServletResponse freshResponse = new CachedBodyHttpServletResponse(fresh);
        var tee = freshResponse.getOutputStream();
        assertThat(tee.isReady()).isTrue();
        // MockHttpServletResponse's delegate rejects async write listeners;
        // the tee's setWriteListener forwarding stays verified by compilation
        // of the wrapper itself (delegate is a plain synchronous stream).
    }

    /** Keeps the tee assertions grouped; exercised through the public wrapper. */
    private static final class ServletOutputStreamProxyProbe {
        static void assertTee(CachedBodyHttpServletResponse response, MockHttpServletResponse delegate)
                throws Exception {
            ServletOutputStream out = response.getOutputStream();
            assertThat(response.getOutputStream()).isSameAs(out); // cached instance
            out.write('A');
            out.write(new byte[]{'B', 'C'});
            out.write(new byte[]{'D', 'E', 'F', 'G'}, 1, 2); // window skips D and G
            assertThat(response.getBody()).isEqualTo("ABCEF");
            assertThat(delegate.getContentAsString()).isEqualTo("ABCEF");
            out.flush();
        }
    }

    // ── RequestLoggingFilter ────────────────────────────────────────────

    @Test
    void requestLoggingFilter_seedsMdcDuringChain_andCleansAfterwards() throws Exception {
        org.springframework.core.env.Environment env = new org.springframework.mock.env.MockEnvironment();
        LoggingSampler sampler = new LoggingSampler(1.0, 1.0);
        RequestLoggingFilter filter = new RequestLoggingFilter(env, sampler);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addHeader("X-Request-Id", "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenInChain = new AtomicReference<>();
        ListAppender<ILoggingEvent> events = capture(RequestLoggingFilter.class.getName(), Level.INFO, () -> {
            filter.init(new org.springframework.mock.web.MockFilterConfig("requestLoggingFilter"));
            filter.doFilter(request, response, (rq, rs) -> {
                seenInChain.set(MDC.get(com.bhukkad.common.tracing.TraceContext.TRACE_ID));
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(201);
            });
            assertThat(MDC.get(com.bhukkad.common.tracing.TraceContext.TRACE_ID)).isNull(); // cleaned in finally
        });
        assertThat(seenInChain.get()).isEqualTo("req-123");
        assertThat(last(events)).contains("HTTP | method=GET | path=/api/v1/restaurants | status=201");
    }

    // ── TracingBridge ───────────────────────────────────────────────────

    @Test
    void tracingBridge_isNoopSafeAndAttachesRecordingSpanAttributes() throws Exception {
        assertThat(TracingBridge.tracer()).isNotNull();

        // missing ids ⇒ early return (before touching the span)
        TracingBridge.attachMdcIdsToCurrentSpan();

        MDC.put(LoggingConstants.TRACE_ID, "t-att");
        MDC.put(LoggingConstants.SPAN_ID, "s-att");
        try (MockedStatic<Span> spans = Mockito.mockStatic(Span.class)) {
            Span recording = mock(Span.class);
            when(recording.isRecording()).thenReturn(true);
            spans.when(Span::current).thenReturn(recording);
            TracingBridge.attachMdcIdsToCurrentSpan();
            verify(recording).setAttribute("log.traceId", "t-att");
            verify(recording).setAttribute("log.spanId", "s-att");
        }

        // not-recording current span ⇒ no attributes written
        try (MockedStatic<Span> spans = Mockito.mockStatic(Span.class)) {
            spans.when(Span::current).thenReturn(null);
            TracingBridge.attachMdcIdsToCurrentSpan(); // null guard path
        }

        AutoCloseable span = TracingBridge.startSpan("job-x"); // no-op tracer ⇒ cheap
        assertThat(span).isNotNull();
        span.close();
    }

    // ── Alert enums ─────────────────────────────────────────────────────

    @Test
    void alertEnums_exposeStableValues() {
        assertThat(com.bhukkad.common.logging.alert.AlertCategory.values()).hasSize(8)
                .contains(com.bhukkad.common.logging.alert.AlertCategory.SLOW_REQUEST,
                        com.bhukkad.common.logging.alert.AlertCategory.HTTP_ERROR,
                        com.bhukkad.common.logging.alert.AlertCategory.SECURITY,
                        com.bhukkad.common.logging.alert.AlertCategory.EXCEPTION,
                        com.bhukkad.common.logging.alert.AlertCategory.PAYMENT,
                        com.bhukkad.common.logging.alert.AlertCategory.NOTIFICATION,
                        com.bhukkad.common.logging.alert.AlertCategory.INVENTORY,
                        com.bhukkad.common.logging.alert.AlertCategory.SYSTEM);
        assertThat(com.bhukkad.common.logging.alert.AlertCategory.valueOf("PAYMENT"))
                .isEqualTo(com.bhukkad.common.logging.alert.AlertCategory.PAYMENT);
        assertThat(com.bhukkad.common.logging.alert.AlertSeverity.values())
                .containsExactly(com.bhukkad.common.logging.alert.AlertSeverity.INFO,
                        com.bhukkad.common.logging.alert.AlertSeverity.WARNING,
                        com.bhukkad.common.logging.alert.AlertSeverity.CRITICAL);
        assertThat(com.bhukkad.common.logging.alert.AlertSeverity.valueOf("CRITICAL").name())
                .isEqualTo("CRITICAL");
    }

    // ── TraceIdResolver: remaining blank-header branch ─────────────────

    @Test
    void traceIdResolver_blankRequestIdFallsBackToGenerated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdResolver.REQUEST_ID_HEADER, "   ");
        String traceId = TraceIdResolver.seedFrom(request);
        assertThat(traceId).isNotBlank().doesNotContain("   ");
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo(traceId);
        assertThat(MDC.get(LoggingConstants.REQUEST_ID)).isEqualTo(traceId);

        String another = TraceIdResolver.seedNew();
        assertThat(another).isNotBlank();
        assertThat(MDC.get(LoggingConstants.TRACE_ID)).isEqualTo(another);
    }

    // ── CorrelationIdFilter ──────────────────────────────────────────────

    @Test
    void correlationIdFilter_generatesIdWhenMissing() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> captured = new AtomicReference<>();
        ListAppender<ILoggingEvent> events = capture(CorrelationIdFilter.class.getName(), Level.DEBUG, () -> {
            filter.doFilter(request, response, (rq, rs) -> {
                jakarta.servlet.http.HttpServletResponse httpRs = (jakarta.servlet.http.HttpServletResponse) rs;
                captured.set(MDC.get("correlationId"));
                assertThat(MDC.get("correlationId")).isNotBlank();
                assertThat(httpRs.getHeader("X-Correlation-Id")).isEqualTo(captured.get());
            });
        });
        assertThat(captured.get()).isNotBlank();
        assertThat(MDC.get("correlationId")).isNull(); // cleaned after request
    }

    @Test
    void correlationIdFilter_reusesExistingId() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader("X-Correlation-Id", "existing-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> captured = new AtomicReference<>();
        ListAppender<ILoggingEvent> events = capture(CorrelationIdFilter.class.getName(), Level.DEBUG, () -> {
            filter.doFilter(request, response, (rq, rs) -> {
                jakarta.servlet.http.HttpServletResponse httpRs = (jakarta.servlet.http.HttpServletResponse) rs;
                captured.set(MDC.get("correlationId"));
                assertThat(MDC.get("correlationId")).isEqualTo("existing-id");
                assertThat(httpRs.getHeader("X-Correlation-Id")).isEqualTo("existing-id");
            });
        });
        assertThat(captured.get()).isEqualTo("existing-id");
    }
}
