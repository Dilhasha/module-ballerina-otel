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

import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.opentelemetry.semconv.ResourceAttributes.SERVICE_NAME;

/**
 * OpenTelemetry SDK based metrics exporter for Ballerina observe metrics.
 */
public final class OtelMetricsProvider {
    private static final String PROTOCOL_HTTP = "http";
    private static final String SCHEME_HTTPS = "https://";
    private static final String COUNTER = "counter";
    private static final String GAUGE = "gauge";
    private static final BString NAME = StringUtils.fromString("name");
    private static final BString DESC = StringUtils.fromString("desc");
    private static final BString TAGS = StringUtils.fromString("tags");
    private static final BString METRIC_TYPE = StringUtils.fromString("metricType");
    private static final BString VALUE = StringUtils.fromString("value");
    private static final PrintStream console = System.out;

    private static final Map<String, AutoCloseable> instruments = new ConcurrentHashMap<>();
    private static volatile Map<String, List<MetricPoint>> counters = Map.of();
    private static volatile Map<String, List<MetricPoint>> gauges = Map.of();
    private static volatile SdkMeterProvider meterProvider;
    private static volatile Meter meter;
    private static volatile String metricPrefix = "";

    private OtelMetricsProvider() {
    }

    public static synchronized void initialize(BString endpoint, BString protocol,
                                               BMap<BString, BString> exporterHeaders,
                                               BMap<BString, BString> resourceAttributes,
                                               int exportIntervalMillis, BString metricPrefix) {
        shutdownCurrentProvider();
        OtelMetricsProvider.metricPrefix = metricPrefix.getValue();

        MetricExporter exporter = buildExporter(endpoint.getValue(), protocol.getValue(),
                exporterHeaders, exportIntervalMillis);
        PeriodicMetricReader metricReader = PeriodicMetricReader.builder(exporter)
                .setInterval(exportIntervalMillis, TimeUnit.MILLISECONDS)
                .build();

        meterProvider = SdkMeterProvider.builder()
                .setResource(Resource.create(buildResourceAttributes(resourceAttributes)))
                .registerMetricReader(metricReader)
                .build();
        meter = meterProvider.get("otel");

        String transportProtocol = PROTOCOL_HTTP.equals(protocol.getValue()) ? "HTTP" : "gRPC";
        String tls = endpoint.getValue().startsWith(SCHEME_HTTPS) ? "S" : "";
        console.println("ballerina: started publishing metrics to Otel on "
                + endpoint.getValue() + " [" + transportProtocol + tls + "]");
    }

    public static void updateMetrics(BArray metrics) {
        Map<String, List<MetricPoint>> nextCounters = new HashMap<>();
        Map<String, List<MetricPoint>> nextGauges = new HashMap<>();

        for (long i = 0; i < metrics.getLength(); i++) {
            Object value = metrics.getRefValue(i);
            if (!(value instanceof BMap<?, ?> metric)) {
                continue;
            }

            MetricPoint point = toMetricPoint(metric);
            if (point == null) {
                continue;
            }

            if (COUNTER.equals(point.type)) {
                nextCounters.computeIfAbsent(point.name, ignored -> new ArrayList<>()).add(point);
                ensureCounter(point);
            } else if (GAUGE.equals(point.type)) {
                nextGauges.computeIfAbsent(point.name, ignored -> new ArrayList<>()).add(point);
                ensureGauge(point);
            }
        }

        counters = Map.copyOf(nextCounters);
        gauges = Map.copyOf(nextGauges);
    }

    private static MetricExporter buildExporter(String endpoint, String protocol,
                                                BMap<BString, BString> exporterHeaders, int exportTimeoutMillis) {
        if (PROTOCOL_HTTP.equals(protocol)) {
            var builder = OtlpHttpMetricExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(exportTimeoutMillis, TimeUnit.MILLISECONDS);
            addHeaders(builder::addHeader, exporterHeaders);
            return builder.build();
        }

        var builder = OtlpGrpcMetricExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(exportTimeoutMillis, TimeUnit.MILLISECONDS);
        addHeaders(builder::addHeader, exporterHeaders);
        return builder.build();
    }

    private static Attributes buildResourceAttributes(BMap<BString, BString> resourceAttributes) {
        AttributesBuilder builder = Attributes.builder();
        String serviceName = "ballerina-service";

        if (resourceAttributes != null && !resourceAttributes.isEmpty()) {
            for (BString key : resourceAttributes.getKeys()) {
                BString value = resourceAttributes.get(key);
                String attributeKey = normalizeAttributeKey(key.getValue());
                if (value == null) {
                    continue;
                }
                if ("service.name".equals(attributeKey)) {
                    serviceName = value.getValue();
                } else {
                    builder.put(attributeKey, value.getValue());
                }
            }
        }

        builder.put(SERVICE_NAME, serviceName);
        builder.put("telemetry.sdk.name", "ballerina-opentelemetry");
        builder.put("telemetry.sdk.language", "ballerina");
        return builder.build();
    }

    private static MetricPoint toMetricPoint(BMap<?, ?> metric) {
        BString rawName = getBString(metric, NAME);
        BString rawType = getBString(metric, METRIC_TYPE);
        Object rawValue = metric.get(VALUE);
        if (rawName == null || rawType == null || rawValue == null) {
            return null;
        }

        String name = getMetricName(rawName.getValue());
        String description = "";
        BString desc = getBString(metric, DESC);
        if (desc != null) {
            description = desc.getValue();
        }

        Number number;
        if (rawValue instanceof Number n) {
            number = n;
        } else {
            return null;
        }

        Attributes attributes = Attributes.empty();
        Object rawTags = metric.get(TAGS);
        if (rawTags instanceof BMap<?, ?> tags) {
            attributes = buildAttributes(tags);
        }
        return new MetricPoint(name, description, rawType.getValue(), number, attributes);
    }

    private static Attributes buildAttributes(BMap<?, ?> tags) {
        AttributesBuilder builder = Attributes.builder();
        for (Object key : tags.getKeys()) {
            Object value = tags.get(key);
            if (key instanceof BString tagKey && value instanceof BString tagValue) {
                builder.put(normalizeAttributeKey(tagKey.getValue()), tagValue.getValue());
            }
        }
        return builder.build();
    }

    private static String normalizeAttributeKey(String key) {
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static String getMetricName(String name) {
        if (metricPrefix == null || metricPrefix.isEmpty()) {
            return name;
        }
        return metricPrefix + "_" + name;
    }

    private static BString getBString(BMap<?, ?> map, BString key) {
        Object value = map.get(key);
        return value instanceof BString bString ? bString : null;
    }

    private static synchronized void ensureCounter(MetricPoint point) {
        String key = COUNTER + ":" + point.name;
        if (meter == null || instruments.containsKey(key)) {
            return;
        }
        instruments.put(key, meter.counterBuilder(point.name)
                .setDescription(point.description)
                .buildWithCallback(measurement -> recordCounters(point.name, measurement)));
    }

    private static synchronized void ensureGauge(MetricPoint point) {
        String key = GAUGE + ":" + point.name;
        if (meter == null || instruments.containsKey(key)) {
            return;
        }
        instruments.put(key, meter.gaugeBuilder(point.name)
                .setDescription(point.description)
                .buildWithCallback(measurement -> recordGauges(point.name, measurement)));
    }

    private static void recordCounters(String name, ObservableLongMeasurement measurement) {
        List<MetricPoint> points = counters.get(name);
        if (points == null) {
            return;
        }
        for (MetricPoint point : points) {
            measurement.record(point.value.longValue(), point.attributes);
        }
    }

    private static void recordGauges(String name, ObservableDoubleMeasurement measurement) {
        List<MetricPoint> points = gauges.get(name);
        if (points == null) {
            return;
        }
        for (MetricPoint point : points) {
            measurement.record(point.value.doubleValue(), point.attributes);
        }
    }

    private static void addHeaders(HeaderConsumer consumer, BMap<BString, BString> exporterHeaders) {
        if (exporterHeaders == null || exporterHeaders.isEmpty()) {
            return;
        }
        for (BString key : exporterHeaders.getKeys()) {
            BString value = exporterHeaders.get(key);
            if (value != null) {
                consumer.addHeader(key.getValue(), value.getValue());
            }
        }
    }

    private static synchronized void shutdownCurrentProvider() {
        for (AutoCloseable instrument : instruments.values()) {
            try {
                instrument.close();
            } catch (Exception e) {
                console.println("ballerina: failed to close OTEL metric instrument: " + e.getMessage());
            }
        }
        instruments.clear();
        counters = Map.of();
        gauges = Map.of();

        if (meterProvider != null) {
            meterProvider.shutdown().join(10, TimeUnit.SECONDS);
            meterProvider = null;
            meter = null;
        }
    }

    @FunctionalInterface
    private interface HeaderConsumer {
        void addHeader(String key, String value);
    }

    private record MetricPoint(String name, String description, String type, Number value, Attributes attributes) {
    }
}
