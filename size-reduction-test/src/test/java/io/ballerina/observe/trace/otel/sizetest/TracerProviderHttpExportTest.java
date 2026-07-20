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

import io.ballerina.observe.trace.otel.OtelTracerProvider;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.bString;
import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.decimal;
import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.forceFlushSpans;
import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.shutdownTracerProvider;
import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.stringMap;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Verifies that {@link OtelTracerProvider} exports spans over OTLP/HTTP end-to-end.
 *
 * <p>The OTLP HTTP transport is implemented on okhttp and okio, which are written in
 * Kotlin; a successful export therefore proves that the kotlin-stdlib jar on the
 * classpath (original or shrunk) provides everything the HTTP export path needs.</p>
 */
public class TracerProviderHttpExportTest {

    private static final String SPAN_NAME = "kotlin-stdlib-size-test-span";
    private static final String SERVICE_NAME = "size-reduction-service";
    private static final String SPAN_ATTRIBUTE_KEY = "size.test.attribute";
    private static final String SPAN_ATTRIBUTE_VALUE = "kotlin-slim-span-attribute";
    private static final String ENVIRONMENT_VALUE = "kotlin-slim-verify-env";

    private MockOtlpCollector collector;

    @BeforeMethod
    public void setUp() throws Exception {
        collector = MockOtlpCollector.start();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        try {
            shutdownTracerProvider();
        } finally {
            collector.close();
        }
    }

    @Test
    public void testSpanExportOverHttpReachesCollector() throws Exception {
        initializeTracerProvider();

        OtelTracerProvider tracerProvider = new OtelTracerProvider();
        Tracer tracer = tracerProvider.getTracer(SERVICE_NAME);
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setAttribute(SPAN_ATTRIBUTE_KEY, SPAN_ATTRIBUTE_VALUE)
                .startSpan();
        span.end();
        forceFlushSpans();

        ReceivedRequest request = collector.awaitRequestContaining("/v1/traces",
                SPAN_NAME.getBytes(StandardCharsets.UTF_8), 15000);
        assertNotNull(request, "no OTLP/HTTP trace export request containing the span name was received");
        assertTrue(request.header("content-type").startsWith("application/x-protobuf"),
                "unexpected content type: " + request.header("content-type"));
        assertEquals(request.header("x-size-test"), "enabled");
        assertTrue(request.bodyContains(SERVICE_NAME.getBytes(StandardCharsets.UTF_8)),
                "exported payload does not carry the service.name resource attribute");
        assertTrue(request.bodyContains(ENVIRONMENT_VALUE.getBytes(StandardCharsets.UTF_8)),
                "exported payload does not carry the deployment.environment resource attribute");
        assertTrue(request.bodyContains(SPAN_ATTRIBUTE_VALUE.getBytes(StandardCharsets.UTF_8)),
                "exported payload does not carry the span attribute value");
    }

    @Test
    public void testConsecutiveExportCyclesOverSameTransport() throws Exception {
        initializeTracerProvider();

        OtelTracerProvider tracerProvider = new OtelTracerProvider();
        Tracer tracer = tracerProvider.getTracer(SERVICE_NAME);
        for (int cycle = 0; cycle < 2; cycle++) {
            String spanName = SPAN_NAME + "-cycle-" + cycle;
            tracer.spanBuilder(spanName).startSpan().end();
            forceFlushSpans();
            ReceivedRequest request = collector.awaitRequestContaining("/v1/traces",
                    spanName.getBytes(StandardCharsets.UTF_8), 15000);
            assertNotNull(request, "no OTLP/HTTP trace export request received for export cycle " + cycle);
        }
    }

    private void initializeTracerProvider() {
        String endpoint = collector.baseUrl() + "/v1/traces";
        BMap<BString, BString> exporterHeaders = stringMap();
        exporterHeaders.put(bString("x-size-test"), bString("enabled"));
        BMap<BString, BString> resourceAttributes = stringMap();
        resourceAttributes.put(bString("\"deployment.environment\""), bString(ENVIRONMENT_VALUE));

        OtelTracerProvider.initializeConfigurations(bString(endpoint), bString("always_on"), decimal(1),
                5000, 512, false, bString(""), bString(""), exporterHeaders, bString("http/protobuf"),
                resourceAttributes);
    }
}
