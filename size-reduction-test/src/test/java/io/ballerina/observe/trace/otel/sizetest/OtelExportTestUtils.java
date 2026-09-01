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
package io.ballerina.observe.trace.otel.sizetest;

import io.ballerina.observe.trace.otel.OtelMetricsProvider;
import io.ballerina.observe.trace.otel.OtelTracerProvider;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Shared helpers for the OTLP export tests: Ballerina runtime value construction and
 * reflective control of the provider singletons under test.
 */
final class OtelExportTestUtils {

    private OtelExportTestUtils() {
    }

    /**
     * Creates an empty Ballerina {@code map<string>} value.
     *
     * @return a new empty string map
     */
    @SuppressWarnings("unchecked")
    static BMap<BString, BString> stringMap() {
        return (BMap<BString, BString>) (BMap<?, ?>) ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_STRING));
    }

    /**
     * Creates a Ballerina string value.
     *
     * @param value the Java string
     * @return the Ballerina string
     */
    static BString bString(String value) {
        return StringUtils.fromString(value);
    }

    /**
     * Creates a Ballerina decimal value.
     *
     * @param value the long value
     * @return the Ballerina decimal
     */
    static BDecimal decimal(long value) {
        return ValueCreator.createDecimalValue(BigDecimal.valueOf(value));
    }

    /**
     * Creates an empty Ballerina {@code map<anydata>[]} value for {@code updateMetrics}.
     *
     * @return a new empty metrics array
     */
    static BArray metricsArray() {
        return ValueCreator.createArrayValue(TypeCreator.createArrayType(
                TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA)));
    }

    /**
     * Creates a single Ballerina metric record as consumed by {@code OtelMetricsProvider.updateMetrics}.
     *
     * @param name        the metric name
     * @param description the metric description
     * @param type        the metric type ({@code counter} or {@code gauge})
     * @param value       the numeric metric value
     * @param tagValue    the value of the {@code component} tag attached to the metric
     * @return the metric record
     */
    @SuppressWarnings("unchecked")
    static BMap<BString, Object> metric(String name, String description, String type, Object value, String tagValue) {
        BMap<BString, Object> metric = (BMap<BString, Object>) (BMap<?, ?>) ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA));
        metric.put(bString("name"), bString(name));
        metric.put(bString("desc"), bString(description));
        metric.put(bString("metricType"), bString(type));
        metric.put(bString("value"), value);
        BMap<BString, BString> tags = stringMap();
        tags.put(bString("component"), bString(tagValue));
        metric.put(bString("tags"), tags);
        return metric;
    }

    /**
     * Force-flushes the shared {@code BatchSpanProcessor} of {@code OtelTracerProvider}
     * so buffered spans are exported immediately.
     *
     * @throws Exception if the processor cannot be accessed
     */
    static void forceFlushSpans() throws Exception {
        Field field = OtelTracerProvider.class.getDeclaredField("spanProcessor");
        field.setAccessible(true);
        BatchSpanProcessor processor = (BatchSpanProcessor) field.get(null);
        assertNotNull(processor, "span processor was not created");
        CompletableResultCode result = processor.forceFlush().join(10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess(), "span processor force flush did not succeed within 10 seconds");
    }

    /**
     * Shuts down the {@code OtelTracerProvider} singleton between tests.
     *
     * @throws Exception if the shutdown method cannot be invoked
     */
    static void shutdownTracerProvider() throws Exception {
        invokeShutdown(OtelTracerProvider.class);
    }

    /**
     * Shuts down the {@code OtelMetricsProvider} singleton between tests.
     *
     * @throws Exception if the shutdown method cannot be invoked
     */
    static void shutdownMetricsProvider() throws Exception {
        invokeShutdown(OtelMetricsProvider.class);
    }

    private static void invokeShutdown(Class<?> providerClass) throws Exception {
        Method method = providerClass.getDeclaredMethod("shutdownCurrentProvider");
        method.setAccessible(true);
        method.invoke(null);
    }
}
