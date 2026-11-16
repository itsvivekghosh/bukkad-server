package com.bhukkad.common.reactor;

import com.bhukkad.common.logging.LoggingConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies MDC ({@code traceId}/{@code spanId}) survives across Reactor operator
 * boundaries ({@code flatMap}, {@code subscribeOn}) when chains are wrapped with
 * {@link MdcContextPropagator#propagate} or the global {@link
 * MdcContextPropagator#installReactorHook()} hook.
 */
class MdcPropagationTest {

    @AfterEach
    void tearDown() {
        MdcContextPropagator.removeReactorHook();
        MDC.clear();
    }

    @Test
    void propagatePreservesMdcAcrossFlatMap() {
        MDC.put(LoggingConstants.TRACE_ID, "trace-1");
        MDC.put(LoggingConstants.SPAN_ID, "span-1");

        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenSpan = new AtomicReference<>();

        Flux.from(MdcContextPropagator.<String>propagate(
                Mono.just("a").flatMap(v -> Mono.just(v + "-b"))))
                .doOnNext(v -> {
                    seenTrace.set(MDC.get(LoggingConstants.TRACE_ID));
                    seenSpan.set(MDC.get(LoggingConstants.SPAN_ID));
                })
                .blockLast();

        assertThat(seenTrace.get()).isEqualTo("trace-1");
        assertThat(seenSpan.get()).isEqualTo("span-1");
    }

    @Test
    void propagatePreservesMdcAcrossSubscribeOn() {
        MDC.put(LoggingConstants.TRACE_ID, "trace-1");
        MDC.put(LoggingConstants.SPAN_ID, "span-1");

        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenSpan = new AtomicReference<>();

        // propagate applied outermost over subscribeOn: MDC captured on the
        // subscribing thread is restored on the scheduler thread.
        Flux.from(MdcContextPropagator.<String>propagate(
                Mono.just("a").subscribeOn(Schedulers.boundedElastic())))
                .doOnNext(v -> {
                    seenTrace.set(MDC.get(LoggingConstants.TRACE_ID));
                    seenSpan.set(MDC.get(LoggingConstants.SPAN_ID));
                })
                .blockLast();

        assertThat(seenTrace.get()).isEqualTo("trace-1");
        assertThat(seenSpan.get()).isEqualTo("span-1");
    }

    @Test
    void installReactorHookPropagatesAcrossFlatMap() {
        MdcContextPropagator.installReactorHook();
        try {
            MDC.put(LoggingConstants.TRACE_ID, "trace-1");
            AtomicReference<String> seen = new AtomicReference<>();

            Mono.just("a").flatMap(v -> Mono.just(v))
                    .subscribe(new Subscriber<String>() {
                        @Override
                        public void onSubscribe(Subscription s) {
                            s.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(String t) {
                            seen.set(MDC.get(LoggingConstants.TRACE_ID));
                        }

                        @Override
                        public void onError(Throwable t) {
                        }

                        @Override
                        public void onComplete() {
                        }
                    });

            assertThat(seen.get()).isEqualTo("trace-1");
        } finally {
            MdcContextPropagator.removeReactorHook();
        }
    }
}
