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
 * Verifies that {@link OtelTracerProvider} exports spans over OTLP/gRPC (the extension's
 * default protocol) end-to-end.
 *
 * <p>With opentelemetry-exporter-sender-okhttp (the bundled sender), the gRPC exporter
 * runs gRPC over okhttp's HTTP/2 transport (h2c prior knowledge). This exercises okhttp's
 * HTTP/2 stack (framing, HPACK, flow control) — a different okhttp code path, and hence
 * potentially different kotlin-stdlib reachability, than the OTLP/HTTP tests.</p>
 */
public class TracerProviderGrpcExportTest {

    private static final String SPAN_NAME = "kotlin-stdlib-size-test-grpc-span";
    private static final String SERVICE_NAME = "size-reduction-grpc-service";
    private static final String SPAN_ATTRIBUTE_KEY = "size.test.attribute";
    private static final String SPAN_ATTRIBUTE_VALUE = "kotlin-slim-grpc-span-attribute";
    private static final String ENVIRONMENT_VALUE = "kotlin-slim-grpc-verify-env";

    private MockOtlpGrpcCollector collector;

    @BeforeMethod
    public void setUp() throws Exception {
        collector = MockOtlpGrpcCollector.start();
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
    public void testSpanExportOverGrpcReachesCollector() throws Exception {
        initializeTracerProvider();

        OtelTracerProvider tracerProvider = new OtelTracerProvider();
        Tracer tracer = tracerProvider.getTracer(SERVICE_NAME);
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setAttribute(SPAN_ATTRIBUTE_KEY, SPAN_ATTRIBUTE_VALUE)
                .startSpan();
        span.end();
        forceFlushSpans();

        ReceivedRequest request = collector.awaitRequestContaining(MockOtlpGrpcCollector.TRACES_EXPORT_PATH,
                SPAN_NAME.getBytes(StandardCharsets.UTF_8), 15000);
        assertNotNull(request, "no OTLP/gRPC trace export request containing the span name was received");
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
            ReceivedRequest request = collector.awaitRequestContaining(MockOtlpGrpcCollector.TRACES_EXPORT_PATH,
                    spanName.getBytes(StandardCharsets.UTF_8), 15000);
            assertNotNull(request, "no OTLP/gRPC trace export request received for export cycle " + cycle);
        }
    }

    private void initializeTracerProvider() {
        BMap<BString, BString> exporterHeaders = stringMap();
        exporterHeaders.put(bString("x-size-test"), bString("enabled"));
        BMap<BString, BString> resourceAttributes = stringMap();
        resourceAttributes.put(bString("\"deployment.environment\""), bString(ENVIRONMENT_VALUE));

        OtelTracerProvider.initializeConfigurations(bString(collector.endpointUrl()), bString("always_on"),
                decimal(1), 5000, 512, false, bString(""), bString(""), exporterHeaders, bString("grpc"),
                resourceAttributes);
    }
}
