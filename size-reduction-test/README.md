# kotlin-stdlib size reduction test

The Ballerina OTEL package bundles `kotlin-stdlib` (~1.7 MB) solely because the OTLP/HTTP
exporter transport is built on **okhttp** and **okio**, which are written in Kotlin. Almost
none of the standard library is actually reachable from those two libraries.

This subproject does two things:

1. **Shrinks** `kotlin-stdlib` down to only the classes/members reachable from
   okhttp + okio, using a ProGuard *shrink-only* pass (no optimization, no obfuscation, so
   surviving bytecode is identical to the original).
2. **Verifies** the shrunk jar by running the same functional test suite twice: once against
   the original `kotlin-stdlib` and once against the slim jar. The tests drive both real OTLP
   export paths of `OtelTracerProvider` / `OtelMetricsProvider` — OTLP/HTTP (HTTP/1.1) and
   OTLP/gRPC (gRPC over okhttp's HTTP/2 transport) — against in-JVM mock OTLP collectors,
   so a missing stdlib class/method surfaces as a test failure.

Current result: **1,718,956 → 284,233 bytes (~83.5 % reduction)** against
`kotlin-stdlib` 1.9.23 (bundled with okhttp 4.12.0 / okio-jvm 3.9.0).

## Layout

| Path | Purpose |
|------|---------|
| `proguard/kotlin-stdlib-slim.pro` | Shrink-only ProGuard keep rules |
| `src/test/java/.../MockOtlpCollector.java` | In-JVM OTLP/HTTP collector stand-in |
| `src/test/java/.../MockOtlpGrpcCollector.java` | In-JVM OTLP/gRPC collector stand-in (grpc-java on shaded Netty) |
| `src/test/java/.../ReceivedRequest.java` | Request capture (path/headers/body) shared by both collectors |
| `src/test/java/.../OtelExportTestUtils.java` | Shared Ballerina-value + provider-reflection test helpers |
| `src/test/java/.../TracerProviderHttpExportTest.java` | Exports spans over OTLP/HTTP, asserts they reach the collector |
| `src/test/java/.../MetricsProviderHttpExportTest.java` | Exports counter/gauge metrics over OTLP/HTTP |
| `src/test/java/.../TracerProviderGrpcExportTest.java` | Exports spans over OTLP/gRPC (okhttp HTTP/2 sender) |
| `src/test/java/.../MetricsProviderGrpcExportTest.java` | Exports counter/gauge metrics over OTLP/gRPC |
| `src/test/java/.../KotlinStdlibVariantTest.java` | Confirms which stdlib jar is loaded + exercises okhttp/okio primitives |
| `src/test/resources/testng.xml` | TestNG suite shared by both variants |

## Test suite

The same TestNG suite (`src/test/resources/testng.xml`) runs against both kotlin-stdlib
variants. The `kotlin.stdlib.variant` system property (`original` or `slim`, set by the
Gradle test tasks) tells the suite which jar it is expected to be running on.

### `MockOtlpCollector` / `MockOtlpGrpcCollector` (test infrastructure)

Two in-JVM collector stand-ins, one per OTLP transport. Both record every request as a
`ReceivedRequest` (path, headers, protobuf body) and offer `awaitRequestContaining(path,
bytes, timeout)` to poll for an export whose payload contains a given byte sequence.
Neither contains Kotlin code, so nothing on the server side can mask a missing
kotlin-stdlib member on the client (exporter) side:

- `MockOtlpCollector` — OTLP/HTTP, built on the JDK's `com.sun.net.httpserver.HttpServer`
  (no extra dependencies); transparently gunzips `Content-Encoding: gzip` bodies.
- `MockOtlpGrpcCollector` — OTLP/gRPC, built on test-only grpc-java + shaded Netty (pure
  Java). It serves the `TraceService/Export` and `MetricsService/Export` RPCs with raw
  byte-array marshallers (no protobuf codegen) and captures per-call ASCII metadata so
  tests can assert custom exporter headers. The JDK `HttpServer` cannot speak HTTP/2,
  which is why gRPC needs its own collector.

### `KotlinStdlibVariantTest`

Fast sanity checks that fail early and with a clear message before the heavier end-to-end
tests run:

- `testExpectedKotlinStdlibVariantIsLoaded` — resolves the jar that `kotlin.Unit` was loaded
  from and asserts it matches the variant under test (guards against the wrong jar silently
  ending up on the classpath, which would invalidate the whole verification).
- `testOkioBufferRoundTrip` — okio `Buffer` UTF-8 write/read round trip.
- `testOkioByteStringHashing` — okio `ByteString` UTF-8 encode + SHA-256 hashing.
- `testOkioGzipRoundTrip` — `GzipSink`/`GzipSource` compress/decompress round trip; this is
  the code path the OTLP exporters use when gzip compression is enabled.
- `testOkhttpUrlParsing` — okhttp `HttpUrl` parsing (path, port, query parameters).
- `testOkhttpHeadersAndRequestConstruction` — okhttp `Headers.Builder`/`Request.Builder`
  construction and case-insensitive header lookup.

### `TracerProviderHttpExportTest`

Drives the real `OtelTracerProvider` (the extension's native code) end-to-end over
OTLP/HTTP against the mock collector:

- `testSpanExportOverHttpReachesCollector` — initializes the provider with a custom exporter
  header and a `deployment.environment` resource attribute, starts/ends a span carrying a
  span attribute, force-flushes the `BatchSpanProcessor`, and asserts that the export request
  arrives with the protobuf content type, the custom header, and a payload containing the
  span name, the span attribute value, and the `service.name` + `deployment.environment`
  resource attributes.
- `testConsecutiveExportCyclesOverSameTransport` — performs two export/flush cycles through
  the same exporter, exercising okhttp connection reuse across requests.

### `MetricsProviderHttpExportTest`

Drives the real `OtelMetricsProvider` end-to-end over OTLP/HTTP:

- `testMetricExportOverHttpReachesCollector` — initializes the provider with a custom
  exporter header and a metric prefix, publishes a counter and a gauge (with tags) through
  `updateMetrics`, and asserts that the periodic reader's export requests arrive with the
  protobuf content type, the custom header, and a payload containing the prefixed counter
  and gauge names, the metric tag value, and the `service.name` resource attribute.

### `TracerProviderGrpcExportTest` / `MetricsProviderGrpcExportTest`

Same end-to-end scenarios as the HTTP tests, but with the providers initialized with
`protocol = "grpc"` (the extension's **default** protocol). With
`opentelemetry-exporter-sender-okhttp` — the sender bundled in the Ballerina package — the
gRPC exporters run gRPC over **okhttp's HTTP/2 transport** (h2c prior knowledge), not
grpc-java. This exercises okhttp's HTTP/2 stack (framing, HPACK, flow control, connection
management), a different okhttp code path — and hence potentially different kotlin-stdlib
reachability — than the HTTP/1.1 tests:

- `TracerProviderGrpcExportTest.testSpanExportOverGrpcReachesCollector` — mirrors the HTTP
  span test: asserts the custom header arrives as gRPC metadata and that the exported
  message carries the span name, span attribute value, and `service.name` +
  `deployment.environment` resource attributes.
- `TracerProviderGrpcExportTest.testConsecutiveExportCyclesOverSameTransport` — two
  export/flush cycles over the same HTTP/2 connection.
- `MetricsProviderGrpcExportTest.testMetricExportOverGrpcReachesCollector` — mirrors the
  HTTP metrics test (prefixed counter/gauge names, tag value, `service.name`, custom
  header as gRPC metadata).

## Gradle tasks

```bash
# Produce the slim jar at build/libs/kotlin-stdlib-<ver>-slim.jar
./gradlew :size-reduction-test:shrinkKotlinStdlib

# Functional suite against the ORIGINAL kotlin-stdlib
./gradlew :size-reduction-test:test

# Functional suite against the SLIM kotlin-stdlib
./gradlew :size-reduction-test:testOnSlimKotlinStdlib

# Shrink + both suites + a printed size-reduction report
./gradlew :size-reduction-test:verifySizeReduction
```

## Important: keep okhttp/okio pinned to the bundled versions

The bundled okhttp / okio / kotlin-stdlib versions are aligned with what
`opentelemetry-exporter-sender-okhttp` declares (okhttp **4.12.0**, okio-jvm **3.9.0**,
kotlin-stdlib **1.9.23**). Because the exact okhttp/okio version determines which
`kotlin-stdlib` members are reachable, this subproject forces okhttp/okio to the *bundled*
versions (from `gradle.properties`) on both the shrink input and the test runtime classpath so
the shrink and verification always reflect what actually ships — even if a future
`opentelemetry-exporter-sender-okhttp` bump would otherwise pull a different okhttp/okio.
If the bundled versions in `gradle.properties` change, re-run `verifySizeReduction`.

## Extending coverage

The shrink is only as safe as the code paths the tests exercise. If new native code reaches a
new okhttp/okio feature, add a test that drives it; a `NoSuchMethodError` /
`NoClassDefFoundError` in `testOnSlimKotlinStdlib` names the exact stdlib member to pin with a
targeted `-keep`/`-keepclassmembers` rule in `kotlin-stdlib-slim.pro`.
