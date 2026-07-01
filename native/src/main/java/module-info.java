module io.ballerina.observe.trace.extension.otel {
    requires io.ballerina.runtime;
    requires io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires io.opentelemetry.sdk.trace;
    requires io.opentelemetry.sdk.metrics;
    requires io.opentelemetry.sdk.common;
    requires io.opentelemetry.semconv;
    requires io.opentelemetry.exporter.otlp;
    requires java.logging;

    provides io.ballerina.runtime.observability.tracer.spi.TracerProvider
            with io.ballerina.observe.trace.otel.OtelTracerProvider;
}
