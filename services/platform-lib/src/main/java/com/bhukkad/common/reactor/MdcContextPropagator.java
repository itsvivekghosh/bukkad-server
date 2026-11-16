package com.bhukkad.common.reactor;

import com.bhukkad.common.logging.TraceContext;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * MDC propagation across reactive operator boundaries.
 *
 * <p>SLF4J MDC is {@code ThreadLocal}, so {@code traceId}/{@code spanId} are
 * lost when a Reactor signal hops to a different thread (for example across
 * {@code flatMap}, {@code publishOn} or {@code subscribeOn}). This helper
 * captures the MDC at subscription time and restores it on every signal before
 * it reaches downstream user code, keeping request context correlated across
 * operator boundaries.
 *
 * <p>Usage options:
 * <ul>
 *   <li>{@link #propagate(Publisher)} — wrap a specific reactive chain. Apply it
 *       outermost (including over {@code subscribeOn}) so the MDC captured on
 *       the subscribing thread is restored on every signal, even after a thread
 *       hop.</li>
 *   <li>{@link #installReactorHook()} — install the same behaviour globally via
 *       {@link Hooks#onEachOperator} so every WebFlux reactive chain propagates
 *       MDC without per-chain wrapping. Uninstall with {@link #removeReactorHook()}.
 *       Note: with the global hook, {@code subscribeOn} re-subscribes upstream on
 *       the scheduler thread, where that thread's MDC is captured; for
 *       cross-thread {@code subscribeOn}, prefer {@link #propagate} applied
 *       outermost or re-seed MDC on the scheduler thread.</li>
 * </ul>
 *
 * <p>The capture happens on the subscribing thread, so MDC must be populated
 * there (e.g. by the request tracing filter) before the chain is subscribed.
 */
public final class MdcContextPropagator {

    private static final String HOOK_NAME = "bhukkad-mdc-propagation";

    private MdcContextPropagator() {
    }

    /** Snapshot the current SLF4J MDC (traceId, spanId and any other keys). */
    public static Map<String, String> captureMdc() {
        return TraceContext.copy();
    }

    /** Restore MDC to a previously captured snapshot. */
    public static void restoreMdc(Map<String, String> context) {
        TraceContext.restore(context);
    }

    /**
     * Run {@code action} with {@code context} installed as the MDC, restoring
     * the previous MDC on completion (even on exception).
     */
    public static void withMdc(Map<String, String> context, Runnable action) {
        Map<String, String> previous = TraceContext.copy();
        try {
            TraceContext.restore(context);
            action.run();
        } finally {
            TraceContext.restore(previous);
        }
    }

    /**
     * Wrap {@code source} so the MDC captured at subscription is restored on
     * every signal before it reaches downstream user code. The returned type
     * preserves {@link Mono} vs {@link Flux} so the result keeps the full
     * Reactor API.
     */
    @SuppressWarnings("unchecked")
    public static <T> Publisher<T> propagate(Publisher<T> source) {
        if (source instanceof Mono) {
            return (Publisher<T>) new MdcPropagatingMono<>((Mono<T>) source);
        }
        if (source instanceof Flux) {
            return (Publisher<T>) new MdcPropagatingFlux<>((Flux<T>) source);
        }
        return new MdcPropagatingPublisher<>(source);
    }

    /**
     * Install MDC propagation globally via {@link Hooks#onEachOperator}. Every
     * reactive chain assembled after this call restores the subscription-time
     * MDC on each signal.
     */
    public static void installReactorHook() {
        removeReactorHook();
        Hooks.onEachOperator(HOOK_NAME, source -> propagate((Publisher<Object>) source));
    }

    /** Remove the reactor hook installed by {@link #installReactorHook()}. */
    public static void removeReactorHook() {
        Hooks.resetOnEachOperator(HOOK_NAME);
    }

    private static final class MdcPropagatingFlux<T> extends Flux<T> {

        private final Flux<T> source;

        MdcPropagatingFlux(Flux<T> source) {
            this.source = source;
        }

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            Map<String, String> captured = TraceContext.copy();
            source.subscribe(new MdcCoreSubscriber<>(actual, captured));
        }
    }

    private static final class MdcPropagatingMono<T> extends Mono<T> {

        private final Mono<T> source;

        MdcPropagatingMono(Mono<T> source) {
            this.source = source;
        }

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            Map<String, String> captured = TraceContext.copy();
            source.subscribe(new MdcCoreSubscriber<>(actual, captured));
        }
    }

    private static final class MdcPropagatingPublisher<T> implements Publisher<T> {

        private final Publisher<T> source;

        MdcPropagatingPublisher(Publisher<T> source) {
            this.source = source;
        }

        @Override
        public void subscribe(Subscriber<? super T> s) {
            Map<String, String> captured = TraceContext.copy();
            source.subscribe(new MdcCoreSubscriber<>(toCoreSubscriber(s), captured));
        }

        @SuppressWarnings("unchecked")
        private static <T> CoreSubscriber<? super T> toCoreSubscriber(Subscriber<? super T> s) {
            if (s instanceof CoreSubscriber) {
                return (CoreSubscriber<? super T>) s;
            }
            return new CoreSubscriber<T>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    s.onSubscribe(subscription);
                }

                @Override
                public void onNext(T t) {
                    s.onNext(t);
                }

                @Override
                public void onError(Throwable t) {
                    s.onError(t);
                }

                @Override
                public void onComplete() {
                    s.onComplete();
                }
            };
        }
    }

    private static final class MdcCoreSubscriber<T> implements CoreSubscriber<T> {

        private final CoreSubscriber<? super T> actual;
        private final Map<String, String> captured;

        MdcCoreSubscriber(CoreSubscriber<? super T> actual, Map<String, String> captured) {
            this.actual = actual;
            this.captured = captured;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            actual.onSubscribe(subscription);
        }

        @Override
        public void onNext(T t) {
            Map<String, String> previous = TraceContext.copy();
            try {
                TraceContext.restore(captured);
                actual.onNext(t);
            } finally {
                TraceContext.restore(previous);
            }
        }

        @Override
        public void onError(Throwable t) {
            Map<String, String> previous = TraceContext.copy();
            try {
                TraceContext.restore(captured);
                actual.onError(t);
            } finally {
                TraceContext.restore(previous);
            }
        }

        @Override
        public void onComplete() {
            Map<String, String> previous = TraceContext.copy();
            try {
                TraceContext.restore(captured);
                actual.onComplete();
            } finally {
                TraceContext.restore(previous);
            }
        }
    }
}
