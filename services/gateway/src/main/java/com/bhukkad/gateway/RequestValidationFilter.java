package com.bhukkad.gateway;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Strict JSON request validation at the gateway edge.
 *
 * <p>JSON bodies on POST, PUT and PATCH requests must use
 * {@code application/json}. The body is parsed incrementally with Jackson's
 * non-blocking parser: a configured nesting-depth constraint rejects deeply
 * nested documents, strict duplicate detection rejects ambiguous objects, and
 * a streaming field-name counter bounds the number of keys. Only the current
 * data-buffer is copied for parsing; the request body is not assembled into a
 * second whole-body buffer.</p>
 *
 * <p>The existing {@link EdgeSizeLimitFilter} remains the sole request-size
 * limit. This filter runs immediately after it so an oversized request is
 * rejected with 413 before JSON parsing begins.</p>
 */
@Component
public class RequestValidationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestValidationFilter.class);

    /** After the edge size guard (-25), before cache/hedging/kill-switch filters. */
    static final int ORDER = EdgeSizeLimitFilter.ORDER + 1;

    private final int maxKeyCount;
    private final JsonFactory jsonFactory;
    private final List<PathPattern> excludedPathPatterns;

    public RequestValidationFilter(GatewayValidationProperties properties) {
        this.maxKeyCount = properties.getMaxKeyCount();
        StreamReadConstraints constraints = StreamReadConstraints.defaults()
                .rebuild()
                .maxNestingDepth(properties.getMaxDepth())
                .build();
        this.jsonFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(constraints)
                .build();

        PathPatternParser parser = new PathPatternParser();
        this.excludedPathPatterns = GatewayConfig.REQUEST_VALIDATION_EXCLUDED_PATHS.stream()
                .map(parser::parse)
                .toList();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!isJsonBodyMethod(request.getMethod())
                || isExcluded(request)
                || isExplicitlyEmpty(request)) {
            return chain.filter(exchange);
        }

        boolean jsonContentType = isJsonContentType(request.getHeaders().getContentType());
        if (!jsonContentType && hasDeclaredBody(request)) {
            return writeValidationError(exchange, unsupportedMediaType());
        }

        Flux<DataBuffer> validatingBody = validatingBody(request.getBody(), jsonContentType);
        ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return validatingBody;
            }
        };
        ServerWebExchange decoratedExchange = exchange.mutate()
                .request(decoratedRequest)
                .build();

        return chain.filter(decoratedExchange)
                .onErrorResume(RequestValidationException.class,
                        error -> writeValidationError(decoratedExchange, error));
    }

    private static boolean isJsonBodyMethod(HttpMethod method) {
        return HttpMethod.POST.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method);
    }

    private boolean isExcluded(ServerHttpRequest request) {
        String path = request.getPath().pathWithinApplication().value();
        PathContainer pathContainer = PathContainer.parsePath(path);
        return excludedPathPatterns.stream().anyMatch(pattern -> pattern.matches(pathContainer));
    }

    private static boolean isJsonContentType(MediaType contentType) {
        return contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }

    private static boolean isExplicitlyEmpty(ServerHttpRequest request) {
        String contentLength = request.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
        if (contentLength == null) {
            return false;
        }
        try {
            return Long.parseLong(contentLength) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean hasDeclaredBody(ServerHttpRequest request) {
        String contentLength = request.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
        if (contentLength != null) {
            try {
                return Long.parseLong(contentLength) > 0;
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        return request.getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING) != null;
    }

    private Flux<DataBuffer> validatingBody(Flux<DataBuffer> body, boolean jsonContentType) {
        return Flux.defer(() -> {
            JsonStreamValidator validator = new JsonStreamValidator(jsonFactory, maxKeyCount);
            PendingBody pending = new PendingBody();

            Flux<DataBuffer> validated = body.handle((buffer, sink) -> {
                if (!jsonContentType) {
                    pending.release();
                    DataBufferUtils.release(buffer);
                    throw unsupportedMediaType();
                }

                try {
                    validator.accept(buffer);
                } catch (RequestValidationException error) {
                    pending.release();
                    DataBufferUtils.release(buffer);
                    throw error;
                } catch (IOException error) {
                    pending.release();
                    DataBufferUtils.release(buffer);
                    throw invalidJson(error);
                }

                if (pending.hasBody()) {
                    sink.next(pending.take());
                }
                pending.set(buffer);
            });

            return validated
                    .concatWith(Flux.defer(() -> {
                        validator.finish();
                        DataBuffer last = pending.take();
                        return last == null ? Flux.empty() : Flux.just(last);
                    }))
                    .doFinally(signal -> {
                        pending.release();
                        validator.close();
                    });
        });
    }

    private static Mono<Void> writeValidationError(ServerWebExchange exchange,
                                                    RequestValidationException error) {
        log.warn("EDGE_REQUEST_VALIDATION_REJECTED path={} code={}",
                exchange.getRequest().getURI().getPath(), error.code);
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        return EdgeApiErrors.write(exchange, error.status, error.code, error.getMessage());
    }

    private static RequestValidationException unsupportedMediaType() {
        return new RequestValidationException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
    }

    private static RequestValidationException invalidJson(Throwable cause) {
        return new RequestValidationException(HttpStatus.BAD_REQUEST, "INVALID_JSON",
                "Request body must contain exactly one valid JSON value", cause);
    }

    private static final class PendingBody {

        private DataBuffer buffer;

        boolean hasBody() {
            return buffer != null;
        }

        void set(DataBuffer buffer) {
            this.buffer = buffer;
        }

        DataBuffer take() {
            DataBuffer result = buffer;
            buffer = null;
            return result;
        }

        void release() {
            if (buffer != null) {
                DataBufferUtils.release(buffer);
                buffer = null;
            }
        }
    }

    /**
     * One-request Jackson parser. The non-blocking parser consumes each copied
     * buffer as it arrives and counts field names without building a JSON tree.
     */
    private static final class JsonStreamValidator {

        private final JsonParser parser;
        private final ByteArrayFeeder feeder;
        private final int maxKeyCount;
        private int keyCount;
        private boolean sawInput;
        private boolean rootValueSeen;
        private boolean rootComplete;

        JsonStreamValidator(JsonFactory jsonFactory, int maxKeyCount) {
            try {
                this.parser = jsonFactory.createNonBlockingByteArrayParser();
                this.feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
            } catch (IOException error) {
                throw invalidJson(error);
            }
            this.maxKeyCount = maxKeyCount;
        }

        void accept(DataBuffer dataBuffer) throws IOException {
            byte[] bytes = copyReadableBytes(dataBuffer);
            if (bytes.length == 0) {
                return;
            }

            sawInput = true;
            try {
                feeder.feedInput(bytes, 0, bytes.length);
                drainAvailableTokens();
            } catch (StreamConstraintsException error) {
                throw new RequestValidationException(HttpStatus.BAD_REQUEST,
                        "JSON_DEPTH_EXCEEDED", "JSON nesting depth exceeds the configured limit", error);
            } catch (IOException error) {
                throw invalidJson(error);
            }
        }

        void finish() {
            if (!sawInput) {
                return;
            }

            try {
                feeder.endOfInput();
                if (!drainAvailableTokens() || !rootValueSeen || !rootComplete) {
                    throw invalidJson(null);
                }
            } catch (RequestValidationException error) {
                throw error;
            } catch (StreamConstraintsException error) {
                throw new RequestValidationException(HttpStatus.BAD_REQUEST,
                        "JSON_DEPTH_EXCEEDED", "JSON nesting depth exceeds the configured limit", error);
            } catch (IOException error) {
                throw invalidJson(error);
            }
        }

        private boolean drainAvailableTokens() throws IOException {
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == null) {
                    return true;
                }
                if (token == JsonToken.NOT_AVAILABLE) {
                    return false;
                }
                processToken(token);
            }
        }

        private void processToken(JsonToken token) {
            if (rootComplete) {
                throw invalidJson(null);
            }

            if (token == JsonToken.FIELD_NAME) {
                keyCount++;
                if (keyCount > maxKeyCount) {
                    throw new RequestValidationException(HttpStatus.BAD_REQUEST,
                            "JSON_KEY_LIMIT_EXCEEDED",
                            "JSON key count exceeds the configured limit");
                }
                return;
            }

            rootValueSeen = true;
            if (parser.getParsingContext().inRoot()) {
                rootComplete = true;
            }
        }

        void close() {
            try {
                parser.close();
            } catch (IOException ignored) {
                // The parser is request-scoped and already unwinding on failure.
            }
        }

        private static byte[] copyReadableBytes(DataBuffer dataBuffer) {
            int size = dataBuffer.readableByteCount();
            byte[] bytes = new byte[size];
            int offset = 0;
            try (DataBuffer.ByteBufferIterator iterator = dataBuffer.readableByteBuffers()) {
                while (iterator.hasNext()) {
                    ByteBuffer source = iterator.next();
                    int length = source.remaining();
                    source.get(bytes, offset, length);
                    offset += length;
                }
            }
            if (offset != size) {
                return java.util.Arrays.copyOf(bytes, offset);
            }
            return bytes;
        }
    }

    private static final class RequestValidationException extends RuntimeException {

        private final HttpStatus status;
        private final String code;

        RequestValidationException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        RequestValidationException(HttpStatus status, String code, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = code;
        }
    }
}
