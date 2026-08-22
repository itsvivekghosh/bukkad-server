# Monitoring & Observability

## Prometheus metrics

The app exposes Micrometer metrics at `/actuator/prometheus`. The following
custom metrics are available:

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `bhukkad_http_requests_seconds` | Timer (histogram) | `method`, `uri` | Per-endpoint latency; publishes p50/p95/p99 |
| `bhukkad_http_errors_total` | Counter | `method`, `uri` | HTTP 5xx count per endpoint |
| `bhukkad_orders_created_total` | Counter | - | Total orders placed |
| `bhukkad_orders_cancelled_total` | Counter | - | Total orders cancelled |
| `bhukkad_orders_delivered_total` | Counter | - | Total orders delivered |
| `bhukkad_funnel_search_total` | Counter | - | Search events |
| `bhukkad_funnel_menu_view_total` | Counter | - | Menu view events |
| `bhukkad_funnel_cart_add_total` | Counter | - | Cart add events |
| `bhukkad_funnel_checkout_total` | Counter | - | Checkout events |
| `bhukkad_funnel_payment_total` | Counter | - | Payment events |
| `bhukkad_funnel_delivered_total` | Counter | - | Delivered events |
| `sse_active_connections` | Gauge | - | Active SSE connections |

## SLO / SLI

Dashboard: `monitoring/grafana/bhukkad-slo-sli.json` (import into Grafana).

### SLO targets

| Indicator | Target | Measurement |
|-----------|--------|-------------|
| Availability | 99.9% | `1 - (5xx / total)` over 30d |
| Latency (p95) | < 1s | `bhukkad_http_requests_seconds` histogram |
| Latency (p99) | < 3s | `bhukkad_http_requests_seconds` histogram |
| Error Budget | 0.1% | `bhukkad_http_errors_total` / `bhukkad_http_requests_total` |

## Log correlation

Every request receives a `traceId`, `spanId`, and `requestId` written to MDC
and propagated to API responses (`X-Trace-Id`, `X-Span-Id`, `X-Request-Id`
headers). Logs are structured JSON. A Grafana Loki or Elasticsearch index
can correlate by `traceId` and `spanId`.

## OpenTelemetry tracing

The app is ready for OTel distributed tracing. To enable:

1. Add the `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`
   dependencies to `pom.xml`.
2. Set `MANAGEMENT_TRACING_ENABLED=true` and configure the OTLP exporter
   endpoint e.g. `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://jaeger:4318/v1/traces`.
3. Restart the app. `spanId` is already in MDC logs; the bridge adds the
   full W3C `traceparent` propagation.