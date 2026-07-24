# Ballerina OTel end-to-end tests

Three end-to-end test packages verify the `ballerina/otel` module against real
observability backends (unlike the JVM-level tests in `size-reduction-test`, which use
in-JVM mock collectors):

- **`otel-collector-tests`** — exports traces and metrics through a real
  `opentelemetry-collector-contrib` instance (over both **OTLP/HTTP and OTLP/gRPC**) and
  asserts on the raw OTLP payloads the collector forwards to an in-test sink.
- **`jaeger-tests`** — exports traces over **OTLP/gRPC** straight into a real **Jaeger
  all-in-one** backend and asserts through the **Jaeger Query API**, validating the whole
  ingest → store → query path (see [Jaeger end-to-end tests](#jaeger-end-to-end-tests)).
- **`prometheus-tests`** — exports **metrics** over **OTLP/HTTP** into a real, self-hosted
  **Prometheus** backend via its **native OTLP receiver** (stable since Prometheus 3.0) and
  asserts through the **Prometheus query API** (`/api/v1/query_range`), validating the whole
  metric ingest → store → query path (see
  [Prometheus end-to-end tests](#prometheus-end-to-end-tests)).

## Self-contained test setup

No manual setup is required — the tests are **self-contained**:

- The Gradle build (`build.gradle` → `startOtelCollector` / `stopOtelCollector`,
  `startJaeger` / `stopJaeger`, and `startPrometheus` / `stopPrometheus`) starts an
  `otel/opentelemetry-collector-contrib:0.120.0` container named `otel-collector-test`, a
  `jaegertracing/all-in-one:1.62.0` container named `jaeger-test`, and a single
  `prom/prometheus:v3.5.0` container named `prometheus-test` via Docker Compose
  before the tests run, and tears them down afterwards (`finalizedBy`).
- If a container with the expected name is already running, it is reused instead of
  restarted. Prometheus is a single Go binary that boots in seconds, so
  `startPrometheus` only needs a short warm-up before the tests begin.
- **Docker is therefore a hard requirement.** On Windows the collector tasks (and the
  Ballerina tests) are skipped; on any platform they can be skipped with `-PskipBalTests`.
- Everything the collector forwards is captured by a mock OTLP sink implemented *inside the
  test package itself* (`tests/otlp_sink.bal`), so no other external service is involved.
- The collector's receivers are published on the **non-default host ports 14318 (HTTP) and
  14317 (gRPC)**: local observability agents (e.g. the Datadog Agent) often already listen
  on the standard OTLP ports 4317/4318 on developer machines. The non-default ports make the tests
  independent of any local agent.

## Architecture

```
Ballerina app under test (main.bal, :9091)
        │  OTLP export (per-scenario Config.toml:
        │  HTTP → localhost:14318, gRPC → localhost:14317,
        │  or a dead endpoint in the "unreachable" scenario)
        ▼
OpenTelemetry Collector (Docker, otel/opentelemetry-collector-contrib:0.120.0)
  receivers: otlp http :4318 / grpc :4317 (host :14318 / :14317)
  → processors: batch → exporters: otlphttp (OTLP/JSON)
        │  http://host.docker.internal:9095
        ▼
Test sink service (tests/otlp_sink.bal, :9095) — stores raw OTLP/JSON payloads in memory
        │
        ▼
Test suite (tests/test.bal) — deserializes payloads and asserts on spans/metrics
```

The collector re-exports as **OTLP/JSON** (`encoding: json`, no compression) so the test
suite can assert on the payloads with plain Ballerina `json` handling — the assertions run
against exactly what a real collector received and processed.

### Ports

| Port | Owner | Purpose |
|------|-------|---------|
| 9091 | `main.bal` | HTTP service under test (`GET /test/sum`, `GET /test/failure`) |
| 14318 | Docker collector | OTLP/HTTP receiver (container port 4318) |
| 14317 | Docker collector | OTLP/gRPC receiver (container port 4317) |
| 9095 | `tests/otlp_sink.bal` | OTLP/JSON sink the collector forwards to |
| 4390 | nobody (intentionally) | Dead endpoint used by the "unreachable" scenario |
| 9092 | `jaeger-tests/main.bal` | HTTP service under test of the Jaeger package |
| 24317 | Docker Jaeger | OTLP/gRPC receiver (container port 4317) |
| 24318 | Docker Jaeger | OTLP/HTTP receiver (container port 4318, currently unused) |
| 26686 | Docker Jaeger | Jaeger Query API / UI (container port 16686) |
| 9093 | `prometheus-tests/main.bal` | HTTP service under test of the Prometheus package |
| 39090 | Docker Prometheus | HTTP API: OTLP/HTTP metrics ingest via the native receiver (`/api/v1/otlp/v1/metrics`) **and** the Prometheus query API (`/api/v1/query_range`) (container port 9090) |

## Scenarios

The exporter configuration is fixed for the lifetime of a Ballerina process, so the Gradle
`ballerinaTest` task runs `bal test` **once per scenario**. For each run it copies the
scenario config from `otel-collector-tests/tests/configs/` to
`otel-collector-tests/tests/Config.toml` (a **generated, gitignored** file — `bal test`
ignores a caller-provided `BAL_CONFIG_FILES` environment variable, but always picks up
`tests/Config.toml`) and selects the matching test cases with `--groups`.

| Run | Config | Groups | Exercises |
|-----|--------|--------|-----------|
| OTLP/HTTP export (with code coverage) | `configs/default.toml` | `export` | Full trace/metric export over OTLP/HTTP (:14318), `always_on` sampler |
| OTLP/gRPC export | `configs/grpc.toml` | `export` | Same assertions over OTLP/gRPC (:14317) |
| always_off sampler | `configs/sampler_off.toml` | `sampler-off` | No spans exported; metrics unaffected by the trace sampler |
| parentbased_always_on sampler | `configs/parentbased.toml` | `parent-based` | The remote parent's `traceparent` sampled flag is honored (module default sampler): sampled parent → exported, unsampled parent → dropped, no parent → sampled |
| Unreachable endpoint | `configs/unreachable.toml` | `unreachable` | Exporters point at a dead port (:4390); app keeps serving while every export fails |

After the collector scenarios, the Gradle build runs the separate **`jaeger-tests`**
package once (config `jaeger-tests/tests/configs/default.toml`, no groups — see
[Jaeger end-to-end tests](#jaeger-end-to-end-tests)), and then the separate
**`prometheus-tests`** package once (config `prometheus-tests/tests/configs/default.toml`,
no groups — see [Prometheus end-to-end tests](#prometheus-end-to-end-tests)).

Each scenario configures a **distinct `service.name`** resource attribute, and every
span/metric lookup in the test suite filters on it — late or retried exports from a
previous scenario run (still buffered in the collector) cannot leak into the assertions of
the current run.

## Layout

| Path | Purpose |
|------|---------|
| `build.gradle` | Unpacks the configured Ballerina distribution, publishes the module to the local repo, manages the collector container, runs `bal test` once per scenario |
| `otel-collector-tests/main.bal` | Instrumented HTTP service: `GET /test/sum`, `GET /test/failure` (intentionally returns an error → 500, for error-span coverage), plus an `@observe:Observable` class method |
| `otel-collector-tests/tests/configs/*.toml` | Per-scenario configs (`default`, `grpc`, `sampler_off`, `parentbased`, `unreachable`): scenario/service-name selectors plus `[ballerina.otel]` endpoints, protocols, sampler, 3 s metric export interval, resource attributes |
| `otel-collector-tests/tests/Config.toml` | **Generated** per run from `tests/configs/` by the Gradle build (gitignored) |
| `otel-collector-tests/tests/otlp_sink.bal` | Mock OTLP sink on `:9095` (`POST /v1/traces`, `POST /v1/metrics`), thread-safe in-memory capture |
| `otel-collector-tests/tests/test.bal` | Test cases (grouped per scenario) + OTLP/JSON record types and helpers |
| `resources/otel-collector/docker-compose.yml` | Collector container definition (host ports `:14318`/`:14317`) |
| `resources/otel-collector/docker-compose.linux.yml` | Linux-only override mapping `host.docker.internal` to the host gateway (needed on plain Linux/CI; would break Docker Desktop if applied unconditionally) |
| `resources/otel-collector/otel-collector-config.yaml` | Collector pipelines: `otlp` receiver (HTTP + gRPC) → `batch` processor → `otlphttp` (JSON) + `debug` exporters |
| `jaeger-tests/main.bal` | Same instrumented HTTP service as the collector package, on `:9092` |
| `jaeger-tests/tests/configs/default.toml` | Jaeger scenario config: OTLP/gRPC → `:24317`, `always_on` sampler, gRPC exporter headers, tracing only |
| `jaeger-tests/tests/Config.toml` | **Generated** per run from `tests/configs/` by the Gradle build (gitignored) |
| `jaeger-tests/tests/test.bal` | Jaeger Query API record types, helpers, and test cases |
| `resources/jaeger/docker-compose.yml` | Jaeger all-in-one container definition (host ports `:24317`/`:24318`/`:26686`) |
| `prometheus-tests/main.bal` | Same instrumented HTTP service as the collector package, on `:9093` |
| `prometheus-tests/tests/configs/default.toml` | Prometheus scenario config: OTLP/HTTP → `:39090/api/v1/otlp/v1/metrics`, metrics only, 3 s export interval, resource attributes, plus the query endpoint (no auth — Prometheus has none) |
| `prometheus-tests/tests/Config.toml` | **Generated** per run from `tests/configs/` by the Gradle build (gitignored) |
| `prometheus-tests/tests/test.bal` | Prometheus query API record types, helpers, and metrics test cases |
| `resources/prometheus/docker-compose.yml` | Single-container Prometheus definition (`prom/prometheus:v3.5.0`, `--web.enable-otlp-receiver`; host port `:39090`) |
| `resources/prometheus/prometheus.yml` | Prometheus config: OTLP `promote_resource_attributes` (surfaces `service.name` as a label) + `UnderscoreEscapingWithSuffixes` translation, out-of-order ingestion window |

## Test cases (`tests/test.bal`)

### Setup — `setupScenario` (`@test:BeforeSuite`)

Always sends `GET http://localhost:9091/test/sum`, then per scenario:

- **export**: also sends `GET /test/failure` (must fail with a 500), then polls the sink
  for up to 90 s (1 s interval) until all five expected spans **and** the `requests_total`
  metric have arrived (app batch export → collector batch → sink). On timeout it fails
  with a diagnostic error listing the span/metric names actually received.
- **sampler-off**: waits for the `requests_total` metric, then a 5 s grace period so any
  (unexpected) spans would have time to flow through the pipeline before asserting none
  arrived.
- **parent-based**: sends two additional `GET /test/sum` requests as **raw HTTP over a
  plain TCP socket** (the instrumented `http:Client` would overwrite a user-supplied
  `traceparent` header with its own span context), each with a fabricated W3C
  `traceparent`: one with a sampled remote parent (flag `01`), one with an unsampled
  remote parent (flag `00`). Waits for the sampled trace to arrive, then a 5 s grace
  period before asserting the unsampled trace never does.
- **unreachable**: sleeps 8 s so the exporters attempt (and fail) the span batch export
  and at least one periodic metric export.

Expected spans (export scenario):

- `get /sum` — resource function (server span)
- `get /failure` — error resource function (server span, 500)
- `ballerinax/otel_collector_tests/ObservableAdder:getSum` — `@observe:Observable` user function
- `ballerina/http/Caller:respond` — caller respond client call
- `ballerina/http/Client:get` — the test's own outbound HTTP client call

### Assertions

| Test | Groups | Verifies |
|------|--------|----------|
| `testServiceResponse` | all | The service responded `"Sum: 53"` (the instrumented request itself succeeded) |
| `testTraceResourceAttributes` | export | Trace resource carries the configured `service.name` and `deployment.environment` from `[ballerina.otel.tracesResourceAttributes]` |
| `testExpectedSpansExported` | export | All five expected spans reached the collector |
| `testSpanHierarchy` | export | Trace-context propagation over HTTP: the server span is a descendant of the test's `Client:get` span (walking the client-side span chain), and `ObservableAdder:getSum` / `Caller:respond` are direct children of the server span within the same trace |
| `testSpanKinds` | export | The resource function span is `SPAN_KIND_SERVER` and its direct parent (the over-the-wire client span) is `SPAN_KIND_CLIENT` (accepts int or enum-name OTLP/JSON encodings) |
| `testResourceFunctionSpanAttributes` | export | Server span attributes: `http.method=GET`, `http.url=/test/sum`, `http.status_code=200`, `protocol=http`, `src.resource.accessor=get`, `src.resource.path=/sum` |
| `testSuccessfulSpanNotMarkedAsError` | export | The successful `get /sum` span carries no error indication (OTLP status or `error=true` attribute) |
| `testErrorSpanExportedWithErrorIndication` | export | The `get /failure` span has `http.status_code=500` and an error indication (OTLP `STATUS_CODE_ERROR` or the runtime's `error=true` attribute) |
| `testMetricsExported` | export | The `requests_total` metric reached the collector |
| `testRequestsTotalCounterDataPoints` | export | `requests_total` is exported as a **monotonic sum** with a data point tagged `src.resource.path="/sum"` and value ≥ 1 |
| `testInprogressRequestsGaugeExported` | export | `inprogress_requests` is exported as a **gauge** with at least one numeric data point |
| `testMetricsResourceAttributes` | export | Metric resource carries the configured `service.name` and `deployment.environment` from `[ballerina.otel.metricsResourceAttributes]` |
| `testNoSpansExportedWhenSamplerAlwaysOff` | sampler-off | With `tracesSampler = "always_off"` no spans reach the sink |
| `testMetricsStillExportedWhenSamplerAlwaysOff` | sampler-off | The trace sampler does not affect metrics — `requests_total` still arrives |
| `testRemoteParentSampledDecisionHonored` | parent-based | The request with a sampled remote parent is exported: the server span continues the fabricated trace ID with the fabricated span ID as its direct parent |
| `testRemoteParentUnsampledDecisionHonored` | parent-based | No spans of the fabricated unsampled trace reach the sink — the upstream "don't sample" decision is honored |
| `testRootSpanSampledWhenNoRemoteParent` | parent-based | A request without an externally fabricated `traceparent` starts a root trace and is sampled (always_on fallback of the parent-based sampler) |
| `testServiceUnaffectedByUnreachableEndpoint` | unreachable | The app keeps serving requests while every export attempt fails in the background |
| `testNoExportsReachSinkWhenEndpointUnreachable` | unreachable | Nothing reaches the sink when the exporters point at a dead endpoint |

Helper utilities in `test.bal` deserialize the OTLP/JSON payloads into open records
(`OtlpTracePayload`, `OtlpMetricsPayload`, `OtlpSum`, `OtlpGauge`, …), flatten spans with
their resource attributes, walk parent-span chains, normalize OTLP attribute encodings
(`stringValue`/`intValue`/`boolValue`), normalize data-point values (OTLP/JSON encodes
doubles as JSON numbers but int64 as JSON strings), and detect error spans (OTLP status
code or `error=true` attribute).

## Jaeger end-to-end tests

The `jaeger-tests` package complements the collector tests with a **real tracing
backend**: instead of asserting on raw OTLP payloads captured by a sink, it exports over
**OTLP/gRPC** into a `jaegertracing/all-in-one:1.62.0` container and asserts on what the
**Jaeger Query API** (`/api/services`, `/api/traces`) returns — so ingestion, the
OTLP → Jaeger model translation, storage, and querying are all exercised.

```
Ballerina app under test (jaeger-tests/main.bal, :9092)
        │  OTLP/gRPC export (tests/Config.toml → localhost:24317)
        ▼
Jaeger all-in-one (Docker, jaegertracing/all-in-one:1.62.0)
        ▲
        │  GET /api/traces?service=…&operation=…  (host :26686)
Test suite (jaeger-tests/tests/test.bal)
```

Notes on the design:

- **Traces only** — Jaeger does not ingest metrics, so `metricsEnabled` stays off in the
  scenario config.
- The scenario exports with `tracesSampler = "always_on"` and exercises the
  **`tracesExporterHeaders` string parsing on the gRPC path** (Jaeger ignores the unknown
  header).
- The test suite's own polling requests to the Query API are instrumented too and create
  additional traces under the same `service.name`; all lookups therefore use
  **operation-scoped queries** (`&operation=…`) so this noise cannot crowd out the traces
  the assertions need.
- In the OTLP → Jaeger translation the span kind becomes a `span.kind` tag, the OTLP
  resource becomes the Jaeger *process* (with `service.name` as the process service name
  and the remaining resource attributes as process tags), and an OTLP ERROR status becomes
  `otel.status_code="ERROR"` / `error=true` tags — the assertions work on these
  translated representations.

### Setup — `setup` (`@test:BeforeSuite`)

Sends `GET http://localhost:9092/test/sum` and `GET /test/failure` (must fail with a
500), then polls the Query API for up to 90 s (1 s interval) until all five expected
spans (same set as the collector export scenario, with the
`ballerinax/otel_jaeger_tests/…` observable-function name) are queryable.

### Assertions

| Test | Verifies |
|------|----------|
| `testServiceResponse` | The service responded `"Sum: 53"` |
| `testServiceRegisteredInJaeger` | `/api/services` lists the configured `service.name` |
| `testExpectedSpansQueryableInJaeger` | All five expected spans are queryable via operation-scoped trace searches |
| `testSpanHierarchy` | Within a single returned trace: the server span is a descendant of the test's `Client:get` span, and `ObservableAdder:getSum` / `Caller:respond` are its direct children (`CHILD_OF` references) |
| `testSpanKinds` | `span.kind` tag is `server` on the resource span and `client` on its direct parent |
| `testResourceFunctionSpanTags` | Server span tags: `http.method=GET`, `http.url=/test/sum`, `http.status_code=200`, `protocol=http`, `src.resource.accessor=get`, `src.resource.path=/sum` |
| `testSuccessfulSpanNotMarkedAsError` | The successful span carries no error indication |
| `testErrorSpanStoredWithErrorIndication` | The `get /failure` span has `http.status_code=500` and an error indication (`error` tag or `otel.status_code=ERROR`) |
| `testProcessResourceAttributes` | The Jaeger process carries the configured `service.name` and the `deployment.environment=test` resource attribute as a process tag |

## Prometheus end-to-end tests

The `prometheus-tests` package is the **metrics** counterpart of the Jaeger tracing
suite: it exports over **OTLP/HTTP** into a real, self-hosted **Prometheus** backend
through its **native OTLP receiver** and asserts on what the **Prometheus query API**
(`/api/v1/query_range`) returns — so OTLP receipt, storage, and querying are all exercised
end to end. Prometheus 3.0 ingests OTLP natively (the receiver is stable since 3.0), so —
unlike a multi-container stack — no separate collector or push gateway is required.

```text
Ballerina app under test (prometheus-tests/main.bal, :9093)
        │  OTLP/HTTP export (tests/Config.toml → localhost:39090/api/v1/otlp/v1/metrics)
        ▼
Prometheus (Docker, prom/prometheus:v3.5.0, --web.enable-otlp-receiver) — ingest + store + query
        ▲
        │  GET /api/v1/query_range?query=…  (host :39090, no auth)
Test suite (prometheus-tests/tests/test.bal)
```

Notes on the design:

- **Metrics only** — the suite validates the two metrics the Ballerina observability
  runtime emits for an instrumented HTTP service: the `requests_total` monotonic counter
  and the `inprogress_requests` gauge. `metricsEnabled` is on and tracing is left at its
  default in the scenario config.
- **No authentication** — Prometheus ships without an auth layer, so the OTLP receiver
  (`--web.enable-otlp-receiver`, off by default) and the query API are both open on the
  host port (`:39090`). No credentials or exporter `Authorization` header are needed.
- **Native OTLP + query API on one port** — Prometheus accepts OTLP/HTTP metrics at
  `/api/v1/otlp/v1/metrics` and serves the query API at `/api/v1/query_range` on the same
  host port (`:39090`, container `9090`).
- **Resource-attribute promotion** — by default Prometheus maps `service.name` only to the
  `job` label and drops other resource attributes onto a `target_info` metric. The
  `resources/prometheus/prometheus.yml` config sets `otlp.promote_resource_attributes`
  (`service.name`, `deployment.environment`) so they surface as labels on every series.
- **Label mapping** — with the default `UnderscoreEscapingWithSuffixes` translation
  strategy the OTLP `service.name` resource attribute surfaces as the Prometheus label
  `service_name` (dots become underscores). The metric names `requests_total` and
  `inprogress_requests` carry no unit and the counter already ends in `_total`, so they
  pass through unchanged. The assertions query by metric name and read the `service_name`
  label off the returned series. The suite's own polling requests do not pollute the
  metrics, so no scoping is needed.

### Setup — `setup` (`@test:BeforeSuite`)

Sends `GET http://localhost:9093/test/sum` and `GET /test/failure` (must fail with a
500), then polls the Prometheus query API for up to ~240 s (2 s interval) until both
metrics are queryable (app periodic export → Prometheus ingest → query).

### Assertions

| Test | Verifies |
|------|----------|
| `testServiceResponse` | The service responded `"Sum: 53"` |
| `testRequestsTotalCounterQueryable` | The `requests_total` metric is queryable in Prometheus and has at least one data point with a value ≥ 1 (i.e. the counter's data points reached the store) |
| `testInprogressRequestsGaugeQueryable` | The `inprogress_requests` gauge metric is queryable in Prometheus |
| `testMetricsCarryServiceNameResourceAttribute` | A `requests_total` series carries the `service_name` label set to the configured service name — confirming the OTLP resource attribute survived as a promoted metric label |

## Running

```bash
# Full run (builds the module, publishes it locally, starts the collector,
# Jaeger and Prometheus, runs bal test once per scenario plus the Jaeger and
# Prometheus packages)
./gradlew :otel-extension-ballerina-tests:test

# Skip these tests
./gradlew build -PskipBalTests

# Attach a debugger to the Ballerina test run (runs only the OTLP/HTTP export scenario)
./gradlew :otel-extension-ballerina-tests:test -Pdebug=5005
```

To run a single scenario manually, unpack the configured Ballerina distribution,
copy its config into place, and run `bal` with the matching group:

```bash
./gradlew unpackJballerinaTools
cd ballerina-tests/otel-collector-tests
cp tests/configs/grpc.toml tests/Config.toml
JAVA_OPTS="-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true" \
    ../../build/extracted-distributions/jballerina-tools-*/bin/bal test --groups export
```

The Jaeger package works the same way (start the Jaeger container from
`resources/jaeger/docker-compose.yml` first; the package has a single scenario and no
groups):

```bash
cd ballerina-tests/jaeger-tests
cp tests/configs/default.toml tests/Config.toml
JAVA_OPTS="-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true" \
    ../../build/extracted-distributions/jballerina-tools-*/bin/bal test
```

The Prometheus package works the same way. Start the Prometheus container from
`resources/prometheus/docker-compose.yml` first (it boots in seconds; the package has a
single scenario and no groups):

```bash
docker compose -f ballerina-tests/resources/prometheus/docker-compose.yml up -d
cd ballerina-tests/prometheus-tests
cp tests/configs/default.toml tests/Config.toml
JAVA_OPTS="-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true" \
    ../../build/extracted-distributions/jballerina-tools-*/bin/bal test
```

Notes:

- The tests use the Ballerina distribution declared by `ballerinaLangVersion`,
  because the module's OTel SDK jars require the `opentelemetry-api` version
  shaded into that distribution's `ballerina-rt`.
- `otel-collector-tests/Ballerina.toml` is regenerated from
  `build-config/resources/tests/Ballerina.toml` with the current module version
  (`updateTomlVersions`), and the module bala is published to the local repository first.
- When debugging export problems, add `tracesLogConsole = true` (or point
  `tracesLogFile` at a file) under `[ballerina.otel]` in the scenario config to surface
  the OTel SDK's own exporter logs.

## Requirements

- Docker with Compose (collector, Jaeger, and the single-container Prometheus backend) —
  tests are skipped on Windows
- JDK 21 (module build)
- Free local ports: 9091, 9092, 9093, 9095, 14317, 14318, 24317, 24318, 26686, 39090
  (and nothing listening on 4390)
- Network access to pull `otel/opentelemetry-collector-contrib:0.120.0`,
  `jaegertracing/all-in-one:1.62.0`, `prom/prometheus:v3.5.0`, and the Ballerina
  distribution on first run
