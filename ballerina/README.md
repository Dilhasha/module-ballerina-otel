## Overview

The Otel Observability Extension provides an implementation for tracing and publishing traces to a [Otel](https://www.oteltracing.io/) Agent.

### Key Features

- Publish distributed traces to a Otel Agent via OpenTelemetry
- Configurable sampler type and parameters
- Support for trace logging to console and file
- Configurable reporter flush interval and buffer size

## Enabling Otel Extension

To package the Otel extension into the Jar, follow the following steps.
1. Add the following import to your program.
```ballerina
import ballerina/otel as _;
```

2. Add the following to the `Ballerina.toml` when building your program.
```toml
[package]
org = "my_org"
name = "my_package"
version = "1.0.0"

[build-options]
observabilityIncluded=true
```

To enable the extension and publish traces to Otel, add the following to the `Config.toml` when running your program.
```toml
[ballerina.observe]
tracingEnabled=true
tracingProvider="otel"

[ballerina.otel]
tracesEndpoint = "http://localhost:4317" # Optional Configuration. Default value is http://localhost:4317
tracesSampler = "parentbased_always_on" # Optional Configuration. Default value is parentbased_always_on
tracesSamplerArg = 1                    # Optional Configuration. Default value is 1
tracesExporterTimeoutMillis = 10000     # Optional Configuration. Default value is 10000
tracesMaxExportBatchSize = 512          # Optional Configuration. Default value is 512
tracesProtocol = "grpc"                 # Optional Configuration. Default value is grpc. Possible values are grpc, http/protobuf, http/json (http/json is not yet supported)
tracesLogConsole = false                # Optional Configuration. Default value is false
tracesLogFile = ""                      # Optional Configuration. Default value is empty string
tracesLogLevel = "info"                 # Optional Configuration. Default value is info. Possible values are debug, info, warn, error
tracesExporterHeaders = "api-key=key"   # Optional. Comma-separated key=value pairs, same format as OTEL_EXPORTER_OTLP_TRACES_HEADERS. Values may be percent-encoded (e.g. value%20with%20spaces)

[ballerina.otel.tracesResourceAttributes]
"service.name" = "my-service"
```

For OTLP/HTTP traces, configure the complete traces endpoint URL.
```toml
[ballerina.otel]
tracesProtocol = "http/protobuf"
tracesEndpoint = "http://localhost:4318/v1/traces"
```

To enable the extension and publish metrics to Otel, add the following to the `Config.toml` when running your program.
```toml
[ballerina.observe]
metricsEnabled = true
metricsReporter = "otel"

[ballerina.otel]
metricsEndpoint = "http://localhost:4317" # Optional Configuration. Default value is http://localhost:4317
metricsProtocol = "grpc"                 # Optional Configuration. Default value is grpc. Possible values are grpc, http/protobuf, http/json (http/json is not yet supported)
metricsServiceName = ""                  # Optional Configuration. Default value is empty string
metricsExportIntervalMillis = 60000      # Optional Configuration. Default value is 60000
metricsExporterTimeoutMillis = 10000     # Optional Configuration. Default value is 10000
metricsPrefix = ""                       # Optional Configuration. Default value is empty string
metricsExporterHeaders = "api-key=key"   # Optional. Comma-separated key=value pairs, same format as OTEL_EXPORTER_OTLP_METRICS_HEADERS. Values may be percent-encoded (e.g. value%20with%20spaces)

[ballerina.otel.metricsResourceAttributes]
"service.name" = "my-service"
```

For OTLP/HTTP metrics, configure the complete metrics endpoint URL.
```toml
[ballerina.otel]
metricsProtocol = "http/protobuf"
metricsEndpoint = "http://localhost:4318/v1/metrics"
```

## Third-party dependencies and licenses

This package bundles the following third-party Java libraries, each redistributed under the Apache License, Version 2.0:

- OpenTelemetry Java SDK and OTLP exporters (`io.opentelemetry:*`) — © The OpenTelemetry Authors
- OkHttp (`com.squareup.okhttp3:okhttp`) — © Square, Inc.
- Okio (`com.squareup.okio:okio-jvm`) — © Square, Inc.
- Kotlin Standard Library (`org.jetbrains.kotlin:kotlin-stdlib`) — © JetBrains s.r.o. and Kotlin contributors

The bundled `kotlin-stdlib` is a size-reduced ("slim") variant produced with ProGuard: only the classes reachable from OkHttp/Okio are retained, and no class is optimized or obfuscated, so every surviving class is byte-identical to the original artifact. The corresponding Apache 2.0 license, attribution and modification notice are included in the jar's `META-INF/LICENSE.txt` and `META-INF/NOTICE.txt`. See the `NOTICE` file in the source repository for full attributions.
