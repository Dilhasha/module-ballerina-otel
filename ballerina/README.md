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
tracesSampler = "always_on"             # Optional Configuration. Default value is always_on
tracesSamplerArg = 1                    # Optional Configuration. Default value is 1
tracesExporterTimeoutMillis = 1000      # Optional Configuration. Default value is 1000
tracesMaxExportBatchSize = 10000        # Optional Configuration. Default value is 10000
tracesProtocol = "grpc"                 # Optional Configuration. Default value is grpc. Possible values are grpc, http
tracesLogConsole = false                # Optional Configuration. Default value is false
tracesLogFile = ""                      # Optional Configuration. Default value is empty string
tracesLogLevel = "info"                 # Optional Configuration. Default value is info. Possible values are debug, info, warn, error

[ballerina.otel.tracesExporterHeaders]   # Optional. Similar to OTEL_EXPORTER_OTLP_TRACES_HEADERS.
"api-key" = "key"

[ballerina.otel.tracesResourceAttributes]
"service.name" = "my-service"
```

For OTLP/HTTP traces, configure the complete traces endpoint URL.
```toml
[ballerina.otel]
tracesProtocol = "http"
tracesEndpoint = "http://localhost:4318/v1/traces"
```

To enable the extension and publish metrics to Otel, add the following to the `Config.toml` when running your program.
```toml
[ballerina.observe]
metricsEnabled = true
metricsReporter = "otel"

[ballerina.otel]
metricsEndpoint = "http://localhost:4318/v1/metrics" # Optional Configuration. Default value is http://localhost:4318/v1/metrics
metricsProtocol = "http"                 # Optional Configuration. Default value is http. Possible values are grpc, http
metricsServiceName = ""                  # Optional Configuration. Default value is empty string
metricsExportIntervalMillis = 60000      # Optional Configuration. Default value is 60000
metricsExporterTimeoutMillis = 1000      # Optional Configuration. Default value is 1000
metricsPrefix = ""                       # Optional Configuration. Default value is empty string

[ballerina.otel.metricsExporterHeaders]  # Optional. Similar to OTEL_EXPORTER_OTLP_METRICS_HEADERS.
"api-key" = "key"

[ballerina.otel.metricsResourceAttributes]
"service.name" = "my-service"
```
