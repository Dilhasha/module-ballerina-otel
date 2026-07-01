/*
 * Copyright (c) 2026 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerina.observe.trace.otel;

import io.ballerina.observe.trace.otel.sampler.RateLimitingSampler;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.observability.tracer.spi.TracerProvider;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import static io.opentelemetry.semconv.ResourceAttributes.SERVICE_NAME;

/**
 * This is the Otel tracing extension class for {@link TracerProvider}.
 */
public class OtelTracerProvider implements TracerProvider {
    // Tracer constants
    private static final String TRACER_NAME = "otel";
    private static final PrintStream console = System.out;

    // Protocol constants
    private static final String PROTOCOL_HTTP = "http";
    private static final String SCHEME_HTTPS = "https://";

    // Sampler type constants
    private static final String SAMPLER_ALWAYS_OFF = "always_off";
    private static final String SAMPLER_TRACEIDRATIO = "traceidratio";
    private static final String SAMPLER_PARENTBASED_ALWAYS_ON = "parentbased_always_on";
    private static final String SAMPLER_PARENTBASED_ALWAYS_OFF = "parentbased_always_off";
    private static final String SAMPLER_PARENTBASED_TRACEIDRATIO = "parentbased_traceidratio";
    private static final String SAMPLER_ALWAYS_ON = "always_on";

    private static SdkTracerProvider tracerProvider;
    private static SpanExporter spanExporter;
    private static Sampler sampler;
    private static int pendingExporterTimeoutMillis;
    private static int pendingMaxExportBatchSize;
    private static BMap<BString, BString> resourceAttributes;

    @Override
    public String getName() {
        return TRACER_NAME;
    }

    @Override
    public void init() {    // Do Nothing
    }

    public static synchronized void initializeConfigurations(BString endpoint,
                                                             BString samplerType, BDecimal samplerArg,
                                                             int exporterTimeoutMillis, int maxExportBatchSize,
                                                             boolean traceLogConsole, BString traceLogFile,
                                                             BString traceLogLevel,
                                                             BMap<BString, BString> exporterHeaders,
                                                             BString protocol,
                                                             BMap<BString, BString> resourceAttributes) {

        shutdownCurrentProvider();
        // Store resource attributes for use in getTracer()
        OtelTracerProvider.resourceAttributes = resourceAttributes;

        String protocolValue = protocol.getValue();
        boolean isHttpProtocol = PROTOCOL_HTTP.equals(protocolValue);
        String reporterEndpoint = endpoint.getValue();

        // Create exporter based on protocol. The tracer provider is built lazily when a tracer is requested.
        if (isHttpProtocol) {
            // HTTP/HTTPS transport
            var httpExporterBuilder = OtlpHttpSpanExporter.builder()
                    .setEndpoint(reporterEndpoint);

            // Add custom headers if provided
            if (exporterHeaders != null && !exporterHeaders.isEmpty()) {
                for (BString key : exporterHeaders.getKeys()) {
                    BString value = exporterHeaders.get(key);
                    if (value != null) {
                        httpExporterBuilder.addHeader(key.getValue(), value.getValue());
                    }
                }
            }

            OtlpHttpSpanExporter httpExporter = httpExporterBuilder.build();

            // Conditionally wrap with OtelExporter for trace logging (like Jaeger extension)
            SpanExporter finalExporter;
            if (traceLogConsole || !traceLogFile.getValue().isEmpty()) {
                finalExporter = new OtelExporter(httpExporter, reporterEndpoint, traceLogConsole,
                        traceLogFile.getValue(), traceLogLevel.getValue());
            } else {
                finalExporter = httpExporter;
            }

            spanExporter = finalExporter;

        } else {
            // gRPC transport (default)
            var grpcExporterBuilder = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(reporterEndpoint);

            // Add custom headers if provided
            if (exporterHeaders != null && !exporterHeaders.isEmpty()) {
                for (BString key : exporterHeaders.getKeys()) {
                    BString value = exporterHeaders.get(key);
                    if (value != null) {
                        grpcExporterBuilder.addHeader(key.getValue(), value.getValue());
                    }
                }
            }

            SpanExporter grpcExporter = grpcExporterBuilder.build();

            // Conditionally wrap with OtelExporter for trace logging (like Jaeger extension)
            SpanExporter finalGrpcExporter;
            if (traceLogConsole || !traceLogFile.getValue().isEmpty()) {
                finalGrpcExporter = new OtelExporter(grpcExporter, reporterEndpoint, traceLogConsole,
                        traceLogFile.getValue(), traceLogLevel.getValue());
            } else {
                finalGrpcExporter = grpcExporter;
            }

            spanExporter = finalGrpcExporter;
        }

        sampler = selectSampler(samplerType, samplerArg);
        pendingExporterTimeoutMillis = exporterTimeoutMillis;
        pendingMaxExportBatchSize = maxExportBatchSize;

        String transportProtocol = isHttpProtocol ? "HTTP" : "gRPC";
        String tls = reporterEndpoint.startsWith(SCHEME_HTTPS) ? "S" : "";
        String authInfo = (exporterHeaders != null && !exporterHeaders.isEmpty())
            ? " (with " + exporterHeaders.size() + " custom header(s))"
            : "";

        console.println("ballerina: started publishing traces to Otel on " + reporterEndpoint +
                       " [" + transportProtocol + tls + "]" + authInfo);
    }

    private static Sampler selectSampler(BString samplerType, BDecimal samplerArg) {
        switch (samplerType.getValue()) {
            case SAMPLER_ALWAYS_OFF:
                return Sampler.alwaysOff();
            case SAMPLER_TRACEIDRATIO:
                return Sampler.traceIdRatioBased(samplerArg.value().doubleValue());
            case SAMPLER_PARENTBASED_ALWAYS_ON:
                return Sampler.parentBased(Sampler.alwaysOn());
            case SAMPLER_PARENTBASED_ALWAYS_OFF:
                return Sampler.parentBased(Sampler.alwaysOff());
            case SAMPLER_PARENTBASED_TRACEIDRATIO:
                return Sampler.parentBased(Sampler.traceIdRatioBased(samplerArg.value().doubleValue()));
            case RateLimitingSampler.TYPE:
                return new RateLimitingSampler(samplerArg.value().intValue());
            case SAMPLER_ALWAYS_ON:
            default:
                return Sampler.alwaysOn();
        }
    }

    @Override
    public Tracer getTracer(String serviceName) {
        return getOrCreateTracer(serviceName);
    }

    private static synchronized Tracer getOrCreateTracer(String serviceName) {
        if (tracerProvider == null) {
            if (spanExporter == null) {
                throw new IllegalStateException("Otel tracer provider is not initialized");
            }
            tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor
                            .builder(spanExporter)
                            .setMaxExportBatchSize(pendingMaxExportBatchSize)
                            .setExporterTimeout(pendingExporterTimeoutMillis, TimeUnit.MILLISECONDS)
                            .build())
                    .setSampler(sampler)
                    .setResource(Resource.create(buildResourceAttributes(serviceName)))
                    .build();
            spanExporter = null;
        }
        return tracerProvider.get(TRACER_NAME);
    }

    private static Attributes buildResourceAttributes(String serviceName) {
        AttributesBuilder builder = Attributes.builder();
        String resolvedServiceName = serviceName;

        if (resourceAttributes != null && !resourceAttributes.isEmpty()) {
            for (BString key : resourceAttributes.getKeys()) {
                BString value = resourceAttributes.get(key);
                if (value == null) {
                    continue;
                }

                String attributeKey = normalizeAttributeKey(key.getValue());
                if ("service.name".equals(attributeKey)) {
                    resolvedServiceName = value.getValue();
                } else {
                    builder.put(attributeKey, value.getValue());
                }
            }
        }

        builder.put(SERVICE_NAME, resolvedServiceName);
        return builder.build();
    }

    private static String normalizeAttributeKey(String key) {
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static synchronized void shutdownCurrentProvider() {
        if (tracerProvider != null) {
            tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
            tracerProvider = null;
        } else if (spanExporter != null) {
            spanExporter.shutdown().join(10, TimeUnit.SECONDS);
        }
        spanExporter = null;
        sampler = null;
        pendingExporterTimeoutMillis = 0;
        pendingMaxExportBatchSize = 0;
    }

    @Override
    public ContextPropagators getPropagators() {

        return ContextPropagators.create(W3CTraceContextPropagator.getInstance());
    }
}
