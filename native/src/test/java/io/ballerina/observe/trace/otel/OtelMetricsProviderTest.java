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

import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Test class for OtelMetricsProvider.
 */
public class OtelMetricsProviderTest {

    private static final String COUNTERS = "counters";
    private static final String GAUGES = "gauges";
    private static final String METRIC_PREFIX = "metricPrefix";

    @BeforeMethod
    public void resetMetricsState() throws Exception {
        setStaticField(COUNTERS, Map.of());
        setStaticField(GAUGES, Map.of());
        setStaticField(METRIC_PREFIX, "");
    }

    @Test
    public void testUpdateMetricsSnapshotsCountersAndGauges() throws Exception {
        setStaticField(METRIC_PREFIX, "svc");

        BArray metrics = metricsArray();
        metrics.append(metric("requests_total", "HTTP requests", "counter", 7L, tags(
                "\"http.method\"", "GET",
                "route", "/hello"
        )));
        metrics.append(metric("memory_usage", "Memory usage", "gauge", 42.5d, tags(
                "area", "heap"
        )));

        OtelMetricsProvider.updateMetrics(metrics);

        Map<?, ?> counters = getStaticField(COUNTERS);
        Map<?, ?> gauges = getStaticField(GAUGES);
        assertTrue(counters.containsKey("svc_requests_total"));
        assertTrue(gauges.containsKey("svc_memory_usage"));

        Object counterPoint = ((List<?>) counters.get("svc_requests_total")).get(0);
        assertEquals(recordValue(counterPoint, "name"), "svc_requests_total");
        assertEquals(recordValue(counterPoint, "description"), "HTTP requests");
        assertEquals(recordValue(counterPoint, "type"), "counter");
        assertEquals(((Number) recordValue(counterPoint, "value")).longValue(), 7L);

        Attributes attributes = recordValue(counterPoint, "attributes");
        assertEquals(attributes.get(AttributeKey.stringKey("http.method")), "GET");
        assertEquals(attributes.get(AttributeKey.stringKey("route")), "/hello");

        Object gaugePoint = ((List<?>) gauges.get("svc_memory_usage")).get(0);
        assertEquals(((Number) recordValue(gaugePoint, "value")).doubleValue(), 42.5d);
    }

    @Test
    public void testUpdateMetricsIgnoresMalformedMetrics() throws Exception {
        BArray metrics = metricsArray();
        BMap<BString, Object> missingValue = map();
        missingValue.put(bString("name"), bString("broken_metric"));
        missingValue.put(bString("metricType"), bString("counter"));
        metrics.append(missingValue);
        metrics.append(bString("not-a-metric-record"));

        OtelMetricsProvider.updateMetrics(metrics);

        assertTrue(((Map<?, ?>) getStaticField(COUNTERS)).isEmpty());
        assertTrue(((Map<?, ?>) getStaticField(GAUGES)).isEmpty());
    }

    @Test
    public void testBuildResourceAttributesUsesConfiguredServiceName() throws Exception {
        BMap<BString, Object> resourceAttributes = map();
        resourceAttributes.put(bString("service.name"), bString("orders"));
        resourceAttributes.put(bString("deployment.environment"), bString("dev"));
        resourceAttributes.put(bString("\"quoted.key\""), bString("quoted-value"));

        Attributes attributes = invokeBuildResourceAttributes("runtime-service", resourceAttributes);

        assertEquals(attributes.get(AttributeKey.stringKey("service.name")), "orders");
        assertEquals(attributes.get(AttributeKey.stringKey("deployment.environment")), "dev");
        assertEquals(attributes.get(AttributeKey.stringKey("quoted.key")), "quoted-value");
        assertEquals(attributes.get(AttributeKey.stringKey("telemetry.sdk.language")), null);
    }

    @Test
    public void testBuildResourceAttributesUsesRuntimeServiceName() throws Exception {
        Attributes attributes = invokeBuildResourceAttributes("runtime-service", null);

        assertEquals(attributes.get(AttributeKey.stringKey("service.name")), "runtime-service");
    }

    private static BArray metricsArray() {
        return ValueCreator.createArrayValue(TypeCreator.createArrayType(PredefinedTypes.TYPE_ANY));
    }

    private static BMap<BString, Object> metric(String name, String description, String metricType,
                                                Number value, BMap<BString, Object> tags) {
        BMap<BString, Object> metric = map();
        metric.put(bString("name"), bString(name));
        metric.put(bString("desc"), bString(description));
        metric.put(bString("metricType"), bString(metricType));
        metric.put(bString("value"), value);
        metric.put(bString("tags"), tags);
        return metric;
    }

    private static BMap<BString, Object> tags(String... entries) {
        BMap<BString, Object> tags = map();
        for (int i = 0; i < entries.length; i += 2) {
            tags.put(bString(entries[i]), bString(entries[i + 1]));
        }
        return tags;
    }

    private static BMap<BString, Object> map() {
        return ValueCreator.createMapValue(TypeCreator.createMapType(PredefinedTypes.TYPE_ANY));
    }

    private static BString bString(String value) {
        return StringUtils.fromString(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getStaticField(String fieldName) throws Exception {
        Field field = OtelMetricsProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    private static void setStaticField(String fieldName, Object value) throws Exception {
        Field field = OtelMetricsProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T recordValue(Object record, String methodName) throws Exception {
        Method method = record.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (T) method.invoke(record);
    }

    private static Attributes invokeBuildResourceAttributes(String serviceName,
                                                            BMap<BString, Object> resourceAttributes) throws Exception {
        Method method = OtelMetricsProvider.class.getDeclaredMethod(
                "buildResourceAttributes", String.class, BMap.class);
        method.setAccessible(true);
        return (Attributes) method.invoke(null, serviceName, resourceAttributes);
    }

}
