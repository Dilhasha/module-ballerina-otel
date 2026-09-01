# Code Review Walkthrough: `ballerina/otel` Module

The runtime data flow: config → init → span export → metrics → lifecycle → tests.

---

## 1. Context & Architecture (~5 min)

**What it is:** The `ballerina/otel` observability extension. It publishes Ballerina
traces and metrics to an OpenTelemetry Collector via OTLP, over gRPC or HTTP.

**Two-layer design:**
- **Ballerina layer** (`ballerina/*.bal`, ~230 LOC) — configurable params, validation, module init
- **Java native layer** (`native/`, ~1,750 LOC) — assembles the OTel SDK pipeline via `@java:Method` interop

**Key packaging gotcha (state this up front):**
`opentelemetry-api` and `opentelemetry-context` are *intentionally NOT bundled* — ballerina-rt
shades both and its copies always win classloading (`ballerina/Ballerina.toml:57-62`).
Consequence: the module relies on the runtime's shaded `opentelemetry-api` being compatible
with the bundled exporters. The OTel stack is pinned to **1.32.0** (`gradle.properties`), which
the shaded `opentelemetry-api` in the stock GA `2201.13.4` runtime already satisfies — so
`distribution` in `Ballerina.toml:22` stays at `2201.13.4` with no timestamped-build or
release-gate bump required.

Also note: `kotlin-stdlib` is bundled as a ProGuard-shrunk "slim" jar
(1.7 MB → ~284 KB), keeping only classes reachable from okhttp/okio
(`ballerina/Ballerina.toml:100-106`).

---

## 2. Module Initialization (the happy path)

### 2.1 Entry point
- `ballerina/observability_initializer.bal:17` — module `init()` runs `initializeTracing()` then `initializeMetrics()`. Both are no-ops unless observability is enabled AND the provider/reporter is `"otel"` (`tracer_provider.bal:23`, `metrics_reporter.bal:23`).

### 2.2 What users can configure
- `ballerina/configs.bal:17-28` — traces: endpoint (default gRPC `:4317`), sampler + arg, exporter timeout (10s), batch size (512), trace logging (console/file/level), headers, protocol, resource attributes
- `ballerina/configs.bal:36-44` — metrics: endpoint (default gRPC `:4317`), protocol, headers, resource attrs, service name, export interval (60s), exporter timeout (10s), prefix
- `ballerina/constants.bal:18-25` — the 7 sampler types; default `parentbased_always_on`
- Defaults are aligned with the OTel SDK spec defaults (`OTEL_EXPORTER_OTLP_*`, `OTEL_TRACES_SAMPLER`, `OTEL_BSP_MAX_EXPORT_BATCH_SIZE`)

### 2.3 Validation before crossing into Java
- `tracer_provider.bal:28-39` — invalid sampler → **prints error and falls back to default** (does not fail startup)
- `tracer_provider.bal:42-55` — invalid log level / protocol / endpoint scheme → **returns error** (fails startup)
  - Discussion point: is the asymmetry (fallback vs. hard error) intentional?
- `tracer_provider.bal:57-69` — the `@java:Method` interop call into `OtelTracerProvider.initializeConfigurations`

### 2.4 Building the trace pipeline (Java)
`native/.../OtelTracerProvider.java`
- `:90` `initializeConfigurations()` — `synchronized`; first calls `shutdownCurrentProvider()` (`:99`) so re-initialization is safe (important for tests / re-init)
- `:108-163` — builds `OtlpHttpSpanExporter` or `OtlpGrpcSpanExporter`, adds custom headers, and **conditionally wraps** in `OtelExporter` only when trace logging is enabled (`:127`, `:155`)
- `:179-197` `selectSampler()` — maps the 7 sampler names to OTel `Sampler`s; unknown → `alwaysOn` (already validated Ballerina-side)
- `:175` — startup banner: endpoint + `[gRPC/HTTPS]` + header count

### 2.5 Per-service tracers, one shared pipeline
This is the core design decision. One tracer provider per Ballerina service, but a single shared export pipeline underneath them all.

- `:72-74` — static state: `Map<String, Tracer> tracers`, `List<SdkTracerProvider> tracerProviders`, one shared `BatchSpanProcessor`
- `:204` `getOrCreateTracer()` — `synchronized`; lazily builds the `BatchSpanProcessor` on the *first* tracer request (`:213-217`), then hands ownership over (`spanExporter = null`, `:219`)
- `:221-228` — each Ballerina service gets its **own `SdkTracerProvider`** (so each reports its own `service.name` resource attribute) but they all share the **same processor/exporter pipeline**
- `:232-254` `buildResourceAttributes()` — merges user resource attrs; a user-supplied `service.name` overrides the Ballerina service name
- `:256` `normalizeAttributeKey()` — strips surrounding quotes (Ballerina TOML map keys arrive quoted)

---

## 3. Follow a Span Through Export

### 3.1 The logging wrapper (only present when trace logging is on)
`native/.../OtelExporter.java`
- `:49` `export()` — logs intent + payload at INFO, delegates to the real exporter, then attaches an async `whenComplete` callback (`:55-66`) that logs success or failure
- `:80-93` — maps config strings to JUL levels (`debug` → `Level.CONFIG`)

### 3.2 Trace logger plumbing
`native/.../logging/OtelTraceLogger.java`
- `:31-36` — two JUL loggers: `otel.tracelog` and, importantly, `io.opentelemetry` — **the OTel SDK's own warnings (e.g. why an export failed) are routed to the same console/file handlers** so users can see failure reasons even when the runtime suppresses default JUL output
- `:38-67` — handler setup: removes old handlers (re-init safe), console and/or `FileHandler` (append mode), `setUseParentHandlers(false)`
- `OtelTraceLogFormatter.java` — JSON-ish line formatting

### 3.3 The custom rate-limiting sampler
`native/.../sampler/`
- `RateLimitingSampler.java:25-27` — **copied from upstream opentelemetry-java** (Jaeger remote sampler, v1.32.0); leaky-bucket, constant traces/sec
- `RateLimitingSampler.java:44-52` — precomputes the on/off `SamplingResult`s with `sampler.type` / `sampler.param` attributes; `maxBalance` floor of 1.0
- `RateLimiter.java:28-46` — the interesting part: **lock-free CAS loop** on a single `AtomicLong debit`
  - `debit` = "last operation nano time minus remaining balance"
  - Each call computes balance = now − debit (capped at `maxBalance`), subtracts cost; if negative → drop (no CAS needed); otherwise CAS to commit. Retry only on contention.
  - Why lock-free: this runs on **every span start** — a `synchronized` block here would serialize all request handling

---

## 4. Follow a Metric

### 4.1 Ballerina side: snapshot scheduler
`ballerina/metrics_reporter.bal`
- `:36-38` — init Java side, then `:40` push an immediate first snapshot
- `:44-45` — schedules `MetricsSnapshotJob` at **half the export interval (min 1s)**. Rationale in the comment: the OTel SDK exports on its own periodic reader; refreshing at half the interval bounds snapshot staleness instead of racing two unsynchronized same-interval timers. Good discussion point.
- `:48-58` — job `trap`s errors and prints, so a failing snapshot can't kill the scheduler

### 4.2 Java side: observable instruments
`native/.../OtelMetricsProvider.java`
- `:73` `initialize()` — `synchronized`, shutdown-first (like the tracer); builds `PeriodicMetricReader` (`:83-85`) + `SdkMeterProvider` (`:87-91`)
- `:99-125` `updateMetrics()` — converts the Ballerina metrics array into fresh `nextCounters`/`nextGauges` maps, then **atomically swaps** them into the `volatile` fields via immutable `Map.copyOf` (`:123-124`). This is the copy-on-write pattern: writers build a new map; the OTel export thread only ever sees a complete, immutable snapshot.
- `:228-249` `ensureCounter()`/`ensureGauge()` — `synchronized` lazy registration in the `instruments` ConcurrentHashMap; uses **`buildWithCallback`** (observable/async instruments) — the SDK *pulls* values at export time via `recordCounters`/`recordGauges` (`:251-269`) reading the volatile snapshot
- `:233-235` — counters are double-valued so float counters aren't truncated (ints exact up to 2^53)
- Concurrency summary for the team: `synchronized` guards init/registration; `volatile` + immutable-swap guards the data path. No locks on the hot read path.

---

## 5. Lifecycle & Edge Cases

- **Trace shutdown:** `OtelTracerProvider.java:263-281` — shuts down every provider with a 10s join; the shared `BatchSpanProcessor` shutdown is idempotent so pending spans flush once. If no tracer was ever created, shuts the bare exporter down (`:271-273`).
- **Metrics shutdown:** `OtelMetricsProvider.java:283-300` — closes each observable instrument, clears snapshots, then 10s join on the meter provider.
- **Unreachable collector:** exporters fail asynchronously; the app stays responsive (covered by E2E scenario 4). Failure reasons surface through the `io.opentelemetry` logger routing (§3.2).
- **Re-initialization:** both providers do shutdown-first init, so state can't leak between configs.

---

## 6. How We Verify It

### Unit tests (TestNG) — `native/src/test/java/`
- `OtelTracerProviderTest`, `OtelMetricsProviderTest`, `OtelExporterTest`, `RateLimitingSamplerTest`, `RateLimiterTest`
- Use OTel's `InMemorySpanExporter`; static state is reset **via reflection** between tests — worth flagging as a brittleness cost of the static-state design

### E2E tests — `ballerina-tests/otel-collector-tests/`
- `main.bal` — instrumented HTTP service under test
- `tests/otlp_sink.bal` — mock OTLP sink capturing what a **real Dockerized OTel Collector** (contrib 0.120.0) forwards
- 4 scenarios: OTLP/HTTP, OTLP/gRPC, `always_off` sampler (metrics still flow), unreachable endpoint (app stays up)

### Size-reduction tests — `size-reduction-test/`
- Guards the ProGuard-shrunk kotlin-stdlib: exports over both protocols against mock collectors must still work with the slim jar

---

## 7. Reviewer Focus Areas (discussion after the walkthrough)

Priority 1:
- [ ] `OtelTracerProvider` static state + `synchronized` init/getOrCreate/shutdown — races, shutdown completeness, 10s joins
- [ ] `RateLimiter.checkCredit` CAS loop — correctness, contention behavior
- [ ] `OtelMetricsProvider` — volatile snapshot swap vs. callback reads; `metricPrefix` visibility during `updateMetrics`
- [ ] E2E timing assumptions, port conflicts, Docker cleanup

Priority 2:
- [ ] Error asymmetry in `tracer_provider.bal` (sampler fallback vs. hard errors)
- [ ] ProGuard keep-rules coverage
- [ ] All 7 sampler types exercised in tests

Priority 3:
- [ ] Checkstyle/SpotBugs findings, Jacoco coverage gaps, javadoc
