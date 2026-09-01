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
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for OtelTracerProvider.
 */
public class OtelTracerProviderTest {

    private OtelTracerProvider tracerProvider;

    @BeforeMethod
    public void setUp() throws Exception {
        tracerProvider = new OtelTracerProvider();
        // Reset shared static state so tests do not leak providers into each other
        OtelTracerProviderTest.<Map<?, ?>>getStaticField("tracers").clear();
        OtelTracerProviderTest.<List<?>>getStaticField("tracerProviders").clear();
        setStaticField("spanProcessor", null);
        setStaticField("spanExporter", null);
        setStaticField("sampler", null);
        setStaticField("resourceAttributes", null);
    }

    @Test
    public void testGetName() {
        assertEquals(tracerProvider.getName(), "otel");
    }

    @Test
    public void testInit() {
        // init() does nothing, just ensure it doesn't throw an exception
        tracerProvider.init();
    }


    @Test
    public void testGetPropagators() {
        ContextPropagators propagators = tracerProvider.getPropagators();
        assertNotNull(propagators);
    }

    @Test
    public void testBuildResourceAttributesUsesConfiguredServiceName() throws Exception {
        BMap<BString, BString> resourceAttributes = map();
        resourceAttributes.put(bString("\"service.name\""), bString("orders"));
        resourceAttributes.put(bString("\"deployment.environment\""), bString("dev"));

        setStaticField("resourceAttributes", resourceAttributes);

        Attributes attributes = invokeBuildResourceAttributes("default-service");

        assertEquals(attributes.get(AttributeKey.stringKey("service.name")), "orders");
        assertEquals(attributes.get(AttributeKey.stringKey("deployment.environment")), "dev");
    }

    @Test
    public void testShutdownClosesPendingExporter() throws Exception {
        SpanExporter exporter = mock(SpanExporter.class);
        when(exporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
        setStaticField("spanExporter", exporter);

        invokeShutdownCurrentProvider();

        verify(exporter, times(1)).shutdown();
        assertNull(getStaticField("spanExporter"));
    }

    @Test
    public void testGetTracerUsesPerServiceResourceServiceName() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        setStaticField("spanExporter", exporter);
        setStaticField("sampler", Sampler.alwaysOn());
        setStaticField("resourceAttributes", null);
        setStaticIntField("pendingExporterTimeoutMillis", 1000);
        setStaticIntField("pendingMaxExportBatchSize", 512);

        try {
            Tracer ordersTracer = tracerProvider.getTracer("orders");
            Tracer paymentsTracer = tracerProvider.getTracer("payments");
            // Requesting the same service again must return the cached tracer
            assertSame(tracerProvider.getTracer("orders"), ordersTracer);

            ordersTracer.spanBuilder("orders-span").startSpan().end();
            paymentsTracer.spanBuilder("payments-span").startSpan().end();

            // Flush the shared batch processor before shutdown (shutdown clears the
            // InMemorySpanExporter's recorded spans)
            BatchSpanProcessor processor = getStaticField("spanProcessor");
            processor.forceFlush().join(5, TimeUnit.SECONDS);

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertEquals(spans.size(), 2);
            Map<String, String> serviceNamesBySpan = new HashMap<>();
            for (SpanData span : spans) {
                serviceNamesBySpan.put(span.getName(),
                        span.getResource().getAttribute(AttributeKey.stringKey("service.name")));
            }
            assertEquals(serviceNamesBySpan.get("orders-span"), "orders");
            assertEquals(serviceNamesBySpan.get("payments-span"), "payments");
        } finally {
            invokeShutdownCurrentProvider();
        }
    }

    @SuppressWarnings("unchecked")
    private static BMap<BString, BString> map() {
        return (BMap<BString, BString>) (BMap<?, ?>) ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_STRING));
    }

    private static BString bString(String value) {
        return StringUtils.fromString(value);
    }

    private static void setStaticField(String fieldName, Object value) throws Exception {
        Field field = OtelTracerProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void setStaticIntField(String fieldName, int value) throws Exception {
        Field field = OtelTracerProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(null, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getStaticField(String fieldName) throws Exception {
        Field field = OtelTracerProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    private static Attributes invokeBuildResourceAttributes(String serviceName) throws Exception {
        Method method = OtelTracerProvider.class.getDeclaredMethod("buildResourceAttributes", String.class);
        method.setAccessible(true);
        return (Attributes) method.invoke(null, serviceName);
    }

    private static void invokeShutdownCurrentProvider() throws Exception {
        Method method = OtelTracerProvider.class.getDeclaredMethod("shutdownCurrentProvider");
        method.setAccessible(true);
        method.invoke(null);
    }
}
