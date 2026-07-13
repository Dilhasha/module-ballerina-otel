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
 * Verifies that {@link OtelMetricsProvider} exports metrics over OTLP/HTTP end-to-end.
 *
 * <p>The OTLP HTTP transport is implemented on okhttp and okio, which are written in
 * Kotlin; a successful export therefore proves that the kotlin-stdlib jar on the
 * classpath (original or shrunk) provides everything the HTTP export path needs.</p>
 */
public class MetricsProviderHttpExportTest {

    private static final String METRIC_PREFIX = "sizetest";
    private static final String COUNTER_NAME = "requests_total";
    private static final String GAUGE_NAME = "inflight_requests";
    private static final String SERVICE_NAME = "size-reduction-service";
    private static final String TAG_VALUE = "kotlin-slim-tag-value";

    private MockOtlpCollector collector;

    @BeforeMethod
    public void setUp() throws Exception {
        collector = MockOtlpCollector.start();
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
    public void testMetricExportOverHttpReachesCollector() throws Exception {
        String endpoint = collector.baseUrl() + "/v1/metrics";
        BMap<BString, BString> exporterHeaders = stringMap();
        exporterHeaders.put(bString("x-size-test"), bString("enabled"));
        BMap<BString, BString> resourceAttributes = stringMap();

        OtelMetricsProvider.initialize(bString(endpoint), bString("http"), exporterHeaders, resourceAttributes,
                bString(SERVICE_NAME), 200, 5000, bString(METRIC_PREFIX));

        BArray metrics = metricsArray();
        metrics.append(metric(COUNTER_NAME, "Total requests", "counter", 7L, TAG_VALUE));
        metrics.append(metric(GAUGE_NAME, "In-flight requests", "gauge", 1.5d, TAG_VALUE));
        OtelMetricsProvider.updateMetrics(metrics);

        String exportedCounterName = METRIC_PREFIX + "_" + COUNTER_NAME;
        ReceivedRequest request = collector.awaitRequestContaining("/v1/metrics",
                exportedCounterName.getBytes(StandardCharsets.UTF_8), 15000);
        assertNotNull(request, "no OTLP/HTTP metric export request containing the counter name was received");
        assertTrue(request.header("content-type").startsWith("application/x-protobuf"),
                "unexpected content type: " + request.header("content-type"));
        assertEquals(request.header("x-size-test"), "enabled");
        assertTrue(request.bodyContains(SERVICE_NAME.getBytes(StandardCharsets.UTF_8)),
                "exported payload does not carry the service.name resource attribute");
        assertTrue(request.bodyContains(TAG_VALUE.getBytes(StandardCharsets.UTF_8)),
                "exported payload does not carry the metric tag value");

        String exportedGaugeName = METRIC_PREFIX + "_" + GAUGE_NAME;
        ReceivedRequest gaugeRequest = collector.awaitRequestContaining("/v1/metrics",
                exportedGaugeName.getBytes(StandardCharsets.UTF_8), 15000);
        assertNotNull(gaugeRequest, "no OTLP/HTTP metric export request containing the gauge name was received");
    }
}
