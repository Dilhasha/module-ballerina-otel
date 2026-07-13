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
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.bString;
import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.metric;
import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.metricsArray;
import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.shutdownMetricsProvider;
import static io.ballerina.observe.trace.otel.sizetest.OtelExportTestUtils.stringMap;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Verifies that {@link OtelMetricsProvider} exports metrics over OTLP/gRPC (the extension's
 * default protocol) end-to-end.
 *
 * <p>With opentelemetry-exporter-sender-okhttp (the bundled sender), the gRPC exporter
 * runs gRPC over okhttp's HTTP/2 transport (h2c prior knowledge). This exercises okhttp's
 * HTTP/2 stack (framing, HPACK, flow control) — a different okhttp code path, and hence
 * potentially different kotlin-stdlib reachability, than the OTLP/HTTP tests.</p>
 */
public class MetricsProviderGrpcExportTest {

    private static final String METRIC_PREFIX = "sizetestgrpc";
    private static final String COUNTER_NAME = "requests_total";
    private static final String GAUGE_NAME = "inflight_requests";
    private static final String SERVICE_NAME = "size-reduction-grpc-service";
    private static final String TAG_VALUE = "kotlin-slim-grpc-tag-value";

    private MockOtlpGrpcCollector collector;

    @BeforeMethod
    public void setUp() throws Exception {
        collector = MockOtlpGrpcCollector.start();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        try {
            shutdownMetricsProvider();
        } finally {
            collector.close();
        }
    }

    @Test
    public void testMetricExportOverGrpcReachesCollector() throws Exception {
        BMap<BString, BString> exporterHeaders = stringMap();
        exporterHeaders.put(bString("x-size-test"), bString("enabled"));
        BMap<BString, BString> resourceAttributes = stringMap();

        OtelMetricsProvider.initialize(bString(collector.endpointUrl()), bString("grpc"), exporterHeaders,
                resourceAttributes, bString(SERVICE_NAME), 200, 5000, bString(METRIC_PREFIX));

        BArray metrics = metricsArray();
        metrics.append(metric(COUNTER_NAME, "Total requests", "counter", 7L, TAG_VALUE));
        metrics.append(metric(GAUGE_NAME, "In-flight requests", "gauge", 1.5d, TAG_VALUE));
        OtelMetricsProvider.updateMetrics(metrics);

        String exportedCounterName = METRIC_PREFIX + "_" + COUNTER_NAME;
        ReceivedRequest request = collector.awaitRequestContaining(MockOtlpGrpcCollector.METRICS_EXPORT_PATH,
                exportedCounterName.getBytes(StandardCharsets.UTF_8), 15000);
        assertNotNull(request, "no OTLP/gRPC metric export request containing the counter name was received");
        assertEquals(request.header("x-size-test"), "enabled");
        assertTrue(request.bodyContains(SERVICE_NAME.getBytes(StandardCharsets.UTF_8)),
                "exported payload does not carry the service.name resource attribute");
        assertTrue(request.bodyContains(TAG_VALUE.getBytes(StandardCharsets.UTF_8)),
                "exported payload does not carry the metric tag value");

        String exportedGaugeName = METRIC_PREFIX + "_" + GAUGE_NAME;
        ReceivedRequest gaugeRequest = collector.awaitRequestContaining(MockOtlpGrpcCollector.METRICS_EXPORT_PATH,
                exportedGaugeName.getBytes(StandardCharsets.UTF_8), 15000);
        assertNotNull(gaugeRequest, "no OTLP/gRPC metric export request containing the gauge name was received");
    }
}
