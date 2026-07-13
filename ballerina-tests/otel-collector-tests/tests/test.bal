// Copyright (c) 2026 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/http;
import ballerina/lang.runtime;
import ballerina/test;

// Scenario selection. Each scenario is a separate `bal test` invocation with
// its own Config.toml (see tests/configs and ballerina-tests/build.gradle):
//   - "export":      export assertions (run twice: OTLP/HTTP and OTLP/gRPC)
//   - "sampler-off": always_off sampler; no spans, but metrics still flow
//   - "unreachable": exporters point at a dead endpoint; app must keep serving
configurable string scenario = "export";
// Each scenario configures a distinct service.name resource attribute; all
// span/metric lookups filter on it so that late or retried exports from a
// previous scenario run (still buffered in the collector) cannot leak into
// the assertions of the current run.
configurable string expectedServiceName = "ballerina-otel-collector-tests";

const string SCENARIO_EXPORT = "export";
const string SCENARIO_SAMPLER_OFF = "sampler-off";
const string SCENARIO_UNREACHABLE = "unreachable";

const string SPAN_RESOURCE_FUNCTION = "get /sum";
const string SPAN_ERROR_RESOURCE_FUNCTION = "get /failure";
const string SPAN_OBSERVABLE_FUNCTION = "ballerinax/otel_collector_tests/ObservableAdder:getSum";
const string SPAN_CALLER_RESPOND = "ballerina/http/Caller:respond";
const string SPAN_CLIENT_GET = "ballerina/http/Client:get";

const string METRIC_REQUESTS_TOTAL = "requests_total";
const string METRIC_INPROGRESS_REQUESTS = "inprogress_requests";

// OTLP/JSON payload shapes (open records; only the fields the tests need).

type OtlpAttribute record {
    string key;
    map<json> value?;
};

type OtlpResource record {
    OtlpAttribute[] attributes?;
};

type OtlpSpan record {
    string traceId;
    string spanId;
    string parentSpanId?;
    string name;
    json kind?;
    json status?;
    OtlpAttribute[] attributes?;
};

type OtlpScopeSpans record {
    OtlpSpan[] spans?;
};

type OtlpResourceSpans record {
    OtlpResource 'resource?;
    OtlpScopeSpans[] scopeSpans?;
};

type OtlpTracePayload record {
    OtlpResourceSpans[] resourceSpans?;
};

type OtlpDataPoint record {
    OtlpAttribute[] attributes?;
    // Per the OTLP/JSON mapping, doubles are JSON numbers and int64 values
    // are JSON strings; keep both as json and normalize in dataPointValue().
    json asDouble?;
    json asInt?;
};

type OtlpSum record {
    json aggregationTemporality?;
    boolean isMonotonic?;
    OtlpDataPoint[] dataPoints?;
};

type OtlpGauge record {
    OtlpDataPoint[] dataPoints?;
};

type OtlpMetric record {
    string name;
    OtlpSum sum?;
    OtlpGauge gauge?;
};

type OtlpScopeMetrics record {
    OtlpMetric[] metrics?;
};

type OtlpResourceMetrics record {
    OtlpResource 'resource?;
    OtlpScopeMetrics[] scopeMetrics?;
};

type OtlpMetricsPayload record {
    OtlpResourceMetrics[] resourceMetrics?;
};

type CollectedSpan record {|
    OtlpSpan span;
    map<string> resourceAttributes;
|};

string sumResponse = "";

@test:BeforeSuite
function setupScenario() returns error? {
    http:Client httpClient = check new ("http://localhost:9091");
    sumResponse = check httpClient->get("/test/sum");

    if scenario == SCENARIO_EXPORT {
        // The /failure resource intentionally responds with a 500, which the
        // client surfaces as an error.
        string|error failureResponse = httpClient->get("/test/failure");
        if failureResponse is string {
            return error("expected GET /test/failure to fail with a 500 status",
                    response = failureResponse);
        }
        return awaitExpectedExports();
    }
    if scenario == SCENARIO_SAMPLER_OFF {
        // Metrics are unaffected by the trace sampler, so they must still
        // arrive; afterwards allow a grace period for any (unexpected) spans
        // to flow through the collector pipeline before asserting there are
        // none.
        check awaitMetric(METRIC_REQUESTS_TOTAL);
        runtime:sleep(5);
        return;
    }
    if scenario == SCENARIO_UNREACHABLE {
        // Give the exporters time to attempt (and fail) both the span batch
        // export and at least one periodic metric export.
        runtime:sleep(8);
        return;
    }
    return error("unknown test scenario: " + scenario);
}

// Waits until the collector has forwarded the expected spans and metrics to
// the test sink (app batch export -> collector batch -> sink).
function awaitExpectedExports() returns error? {
    string[] expectedSpanNames = [SPAN_RESOURCE_FUNCTION, SPAN_ERROR_RESOURCE_FUNCTION,
            SPAN_OBSERVABLE_FUNCTION, SPAN_CALLER_RESPOND, SPAN_CLIENT_GET];
    int attempt = 0;
    while attempt < 90 {
        boolean allSpansReceived = expectedSpanNames.every(name => findSpansByName(name).length() > 0);
        if allSpansReceived && metricExists(METRIC_REQUESTS_TOTAL) {
            return;
        }
        runtime:sleep(1);
        attempt += 1;
    }
    string[] receivedSpanNames = collectSpans().map(collectedSpan => collectedSpan.span.name);
    return error("timed out waiting for OTLP trace/metric exports to reach the test sink",
            traceExportCount = getTraceExports().length(), metricExportCount = getMetricExports().length(),
            receivedSpanNames = receivedSpanNames, receivedMetricNames = collectMetricNames());
}

function awaitMetric(string name) returns error? {
    int attempt = 0;
    while attempt < 90 {
        if metricExists(name) {
            return;
        }
        runtime:sleep(1);
        attempt += 1;
    }
    return error(string `timed out waiting for metric "${name}" to reach the test sink`,
            metricExportCount = getMetricExports().length(), receivedMetricNames = collectMetricNames());
}

@test:Config {groups: ["export", "sampler-off", "unreachable"]}
function testServiceResponse() {
    test:assertEquals(sumResponse, "Sum: 53");
}

// --- "export" scenario (OTLP/HTTP and OTLP/gRPC runs) ---

@test:Config {groups: ["export"]}
function testTraceResourceAttributes() {
    CollectedSpan[] spans = findSpansByName(SPAN_RESOURCE_FUNCTION);
    test:assertTrue(spans.length() > 0, string `span "${SPAN_RESOURCE_FUNCTION}" not received by the collector`);
    map<string> resourceAttributes = spans[0].resourceAttributes;
    test:assertEquals(resourceAttributes["service.name"], expectedServiceName);
    test:assertEquals(resourceAttributes["deployment.environment"], "test");
}

@test:Config {groups: ["export"]}
function testExpectedSpansExported() {
    foreach string spanName in [SPAN_RESOURCE_FUNCTION, SPAN_ERROR_RESOURCE_FUNCTION,
            SPAN_OBSERVABLE_FUNCTION, SPAN_CALLER_RESPOND, SPAN_CLIENT_GET] {
        test:assertTrue(findSpansByName(spanName).length() > 0,
                string `span "${spanName}" not received by the collector`);
    }
}

@test:Config {groups: ["export"]}
function testSpanHierarchy() {
    CollectedSpan[] resourceSpans = findSpansByName(SPAN_RESOURCE_FUNCTION);
    test:assertTrue(resourceSpans.length() > 0, string `span "${SPAN_RESOURCE_FUNCTION}" not received by the collector`);
    OtlpSpan resourceSpan = resourceSpans[0].span;

    // The resource function span must be a descendant of the HTTP client span
    // of the test request (trace context propagated over HTTP). The client
    // side emits a chain of spans (Client:get -> HttpCachingClient:get ->
    // HttpClient:get), so walk up the ancestor chain.
    string? parentSpanId = resourceSpan?.parentSpanId;
    test:assertTrue(parentSpanId is string && parentSpanId != "",
            string `span "${SPAN_RESOURCE_FUNCTION}" has no parent span`);
    CollectedSpan? parent = findSpanBySpanId(<string>parentSpanId);
    test:assertTrue(parent is CollectedSpan, string `parent span of "${SPAN_RESOURCE_FUNCTION}" not received by the collector`);
    test:assertEquals((<CollectedSpan>parent).span.traceId, resourceSpan.traceId);
    string[] ancestorNames = getAncestorSpanNames(resourceSpan);
    test:assertTrue(ancestorNames.indexOf(SPAN_CLIENT_GET) !is (),
            string `span "${SPAN_CLIENT_GET}" is not an ancestor of "${SPAN_RESOURCE_FUNCTION}" (ancestors: ${ancestorNames.toString()})`);

    // Spans generated within the resource function must be children of the
    // resource function span, in the same trace.
    foreach string childSpanName in [SPAN_OBSERVABLE_FUNCTION, SPAN_CALLER_RESPOND] {
        CollectedSpan[] children = findSpansByName(childSpanName)
            .filter(collectedSpan => collectedSpan.span.traceId == resourceSpan.traceId);
        test:assertTrue(children.length() > 0,
                string `span "${childSpanName}" not found in trace ${resourceSpan.traceId}`);
        test:assertEquals(children[0].span?.parentSpanId, resourceSpan.spanId,
                string `span "${childSpanName}" is not a child of "${SPAN_RESOURCE_FUNCTION}"`);
    }
}

@test:Config {groups: ["export"]}
function testSpanKinds() {
    CollectedSpan[] resourceSpans = findSpansByName(SPAN_RESOURCE_FUNCTION);
    test:assertTrue(resourceSpans.length() > 0, string `span "${SPAN_RESOURCE_FUNCTION}" not received by the collector`);
    test:assertTrue(isSpanKind(resourceSpans[0].span, 2, "SPAN_KIND_SERVER"),
            string `span "${SPAN_RESOURCE_FUNCTION}" is not a server span (kind: ${resourceSpans[0].span?.kind.toString()})`);

    // The direct parent of the server span is the over-the-wire HTTP client
    // span, which must be a client span.
    string? parentSpanId = resourceSpans[0].span?.parentSpanId;
    test:assertTrue(parentSpanId is string && parentSpanId != "",
            string `span "${SPAN_RESOURCE_FUNCTION}" has no parent span`);
    CollectedSpan? parent = findSpanBySpanId(<string>parentSpanId);
    test:assertTrue(parent is CollectedSpan, string `parent span of "${SPAN_RESOURCE_FUNCTION}" not received by the collector`);
    OtlpSpan parentSpan = (<CollectedSpan>parent).span;
    test:assertTrue(isSpanKind(parentSpan, 3, "SPAN_KIND_CLIENT"),
            string `span "${parentSpan.name}" is not a client span (kind: ${parentSpan?.kind.toString()})`);
}

@test:Config {groups: ["export"]}
function testResourceFunctionSpanAttributes() {
    CollectedSpan[] resourceSpans = findSpansByName(SPAN_RESOURCE_FUNCTION);
    test:assertTrue(resourceSpans.length() > 0, string `span "${SPAN_RESOURCE_FUNCTION}" not received by the collector`);
    map<string> attributes = toAttributeMap(resourceSpans[0].span?.attributes);
    test:assertEquals(attributes["http.method"], "GET");
    test:assertEquals(attributes["http.url"], "/test/sum");
    test:assertEquals(attributes["http.status_code"], "200");
    test:assertEquals(attributes["protocol"], "http");
    test:assertEquals(attributes["src.resource.accessor"], "get");
    test:assertEquals(attributes["src.resource.path"], "/sum");
}

@test:Config {groups: ["export"]}
function testSuccessfulSpanNotMarkedAsError() {
    CollectedSpan[] resourceSpans = findSpansByName(SPAN_RESOURCE_FUNCTION);
    test:assertTrue(resourceSpans.length() > 0, string `span "${SPAN_RESOURCE_FUNCTION}" not received by the collector`);
    test:assertFalse(isErrorSpan(resourceSpans[0].span),
            string `span "${SPAN_RESOURCE_FUNCTION}" is unexpectedly marked as an error (status: ${resourceSpans[0].span?.status.toString()})`);
}

@test:Config {groups: ["export"]}
function testErrorSpanExportedWithErrorIndication() {
    CollectedSpan[] errorSpans = findSpansByName(SPAN_ERROR_RESOURCE_FUNCTION);
    test:assertTrue(errorSpans.length() > 0, string `span "${SPAN_ERROR_RESOURCE_FUNCTION}" not received by the collector`);
    OtlpSpan errorSpan = errorSpans[0].span;
    map<string> attributes = toAttributeMap(errorSpan?.attributes);
    test:assertEquals(attributes["http.status_code"], "500");
    test:assertTrue(isErrorSpan(errorSpan),
            string `span "${SPAN_ERROR_RESOURCE_FUNCTION}" carries no error indication ` +
            string `(status: ${errorSpan?.status.toString()}, attributes: ${attributes.toString()})`);
}

@test:Config {groups: ["export"]}
function testMetricsExported() {
    test:assertTrue(metricExists(METRIC_REQUESTS_TOTAL),
            string `metric "${METRIC_REQUESTS_TOTAL}" not received by the collector (received: ${collectMetricNames().toString()})`);
}

@test:Config {groups: ["export"]}
function testRequestsTotalCounterDataPoints() {
    OtlpMetric[] metrics = findMetricsByName(METRIC_REQUESTS_TOTAL);
    test:assertTrue(metrics.length() > 0,
            string `metric "${METRIC_REQUESTS_TOTAL}" not received by the collector (received: ${collectMetricNames().toString()})`);

    // Ballerina counters must be exported as monotonic sums with at least one
    // data point that carries the request tags and a value >= 1 (the /sum
    // request made by the test).
    boolean monotonicSumSeen = false;
    boolean resourceDataPointSeen = false;
    string[] seenDataPoints = [];
    foreach OtlpMetric metric in metrics {
        OtlpSum? sum = metric?.sum;
        if sum is () {
            continue;
        }
        if sum?.isMonotonic == true {
            monotonicSumSeen = true;
        }
        foreach OtlpDataPoint dataPoint in sum?.dataPoints ?: [] {
            decimal? value = dataPointValue(dataPoint);
            map<string> attributes = toAttributeMap(dataPoint?.attributes);
            seenDataPoints.push(string `${attributes.toString()} = ${value.toString()}`);
            if value is decimal && value >= 1d && attributes["src.resource.path"] == "/sum" {
                resourceDataPointSeen = true;
            }
        }
    }
    test:assertTrue(monotonicSumSeen,
            string `metric "${METRIC_REQUESTS_TOTAL}" was not exported as a monotonic sum`);
    test:assertTrue(resourceDataPointSeen,
            string `no "${METRIC_REQUESTS_TOTAL}" data point with src.resource.path="/sum" and value >= 1 ` +
            string `(data points: ${seenDataPoints.toString()})`);
}

@test:Config {groups: ["export"]}
function testInprogressRequestsGaugeExported() {
    OtlpMetric[] metrics = findMetricsByName(METRIC_INPROGRESS_REQUESTS);
    test:assertTrue(metrics.length() > 0,
            string `metric "${METRIC_INPROGRESS_REQUESTS}" not received by the collector (received: ${collectMetricNames().toString()})`);

    boolean gaugeDataPointSeen = false;
    foreach OtlpMetric metric in metrics {
        OtlpGauge? gauge = metric?.gauge;
        if gauge is () {
            continue;
        }
        foreach OtlpDataPoint dataPoint in gauge?.dataPoints ?: [] {
            if dataPointValue(dataPoint) is decimal {
                gaugeDataPointSeen = true;
            }
        }
    }
    test:assertTrue(gaugeDataPointSeen,
            string `metric "${METRIC_INPROGRESS_REQUESTS}" was not exported as a gauge with data points`);
}

@test:Config {groups: ["export"]}
function testMetricsResourceAttributes() {
    boolean found = false;
    foreach map<string> resourceAttributes in getMetricResourceAttributeSets() {
        if resourceAttributes["service.name"] == expectedServiceName
                && resourceAttributes["deployment.environment"] == "test" {
            found = true;
            break;
        }
    }
    test:assertTrue(found, "metrics with the configured resource attributes not received by the collector");
}

// --- "sampler-off" scenario ---

@test:Config {groups: ["sampler-off"]}
function testNoSpansExportedWhenSamplerAlwaysOff() {
    CollectedSpan[] spans = collectSpans();
    test:assertEquals(spans.length(), 0,
            string `expected no spans with the always_off sampler, but received: ${spans.map(collectedSpan => collectedSpan.span.name).toString()}`);
}

@test:Config {groups: ["sampler-off"]}
function testMetricsStillExportedWhenSamplerAlwaysOff() {
    test:assertTrue(metricExists(METRIC_REQUESTS_TOTAL),
            string `metric "${METRIC_REQUESTS_TOTAL}" not received by the collector although the trace sampler must not affect metrics`);
}

// --- "unreachable" scenario ---

@test:Config {groups: ["unreachable"]}
function testServiceUnaffectedByUnreachableEndpoint() returns error? {
    // The BeforeSuite request already succeeded (testServiceResponse); the
    // app must also keep serving further requests while every export
    // attempt keeps failing in the background.
    http:Client httpClient = check new ("http://localhost:9091");
    string secondResponse = check httpClient->get("/test/sum");
    test:assertEquals(secondResponse, "Sum: 53");
}

@test:Config {groups: ["unreachable"]}
function testNoExportsReachSinkWhenEndpointUnreachable() {
    CollectedSpan[] spans = collectSpans();
    test:assertEquals(spans.length(), 0,
            string `expected no spans in the sink for the unreachable endpoint, but received: ${spans.map(collectedSpan => collectedSpan.span.name).toString()}`);
    test:assertFalse(metricExists(METRIC_REQUESTS_TOTAL),
            "expected no metrics in the sink for the unreachable endpoint");
}

// Helper functions

// Only spans/metrics whose resource carries this scenario's service.name are
// considered (see the expectedServiceName documentation above).

function collectSpans() returns CollectedSpan[] {
    CollectedSpan[] collected = [];
    foreach json payload in getTraceExports() {
        OtlpTracePayload|error tracePayload = payload.fromJsonWithType();
        if tracePayload is error {
            continue;
        }
        foreach OtlpResourceSpans resourceSpans in tracePayload.resourceSpans ?: [] {
            map<string> resourceAttributes = toAttributeMap(resourceSpans?.'resource?.attributes);
            if resourceAttributes["service.name"] != expectedServiceName {
                continue;
            }
            foreach OtlpScopeSpans scopeSpans in resourceSpans.scopeSpans ?: [] {
                foreach OtlpSpan span in scopeSpans.spans ?: [] {
                    collected.push({span, resourceAttributes});
                }
            }
        }
    }
    return collected;
}

function findSpansByName(string name) returns CollectedSpan[] {
    return collectSpans().filter(collectedSpan => collectedSpan.span.name == name);
}

function getAncestorSpanNames(OtlpSpan span) returns string[] {
    string[] ancestorNames = [];
    OtlpSpan currentSpan = span;
    while ancestorNames.length() < 10 {
        string? parentSpanId = currentSpan?.parentSpanId;
        if parentSpanId is () || parentSpanId == "" {
            break;
        }
        CollectedSpan? parent = findSpanBySpanId(parentSpanId);
        if parent is () {
            break;
        }
        currentSpan = parent.span;
        ancestorNames.push(currentSpan.name);
    }
    return ancestorNames;
}

function findSpanBySpanId(string spanId) returns CollectedSpan? {
    foreach CollectedSpan collectedSpan in collectSpans() {
        if collectedSpan.span.spanId == spanId {
            return collectedSpan;
        }
    }
    return ();
}

function collectMetrics() returns OtlpMetric[] {
    OtlpMetric[] collected = [];
    foreach json payload in getMetricExports() {
        OtlpMetricsPayload|error metricsPayload = payload.fromJsonWithType();
        if metricsPayload is error {
            continue;
        }
        foreach OtlpResourceMetrics resourceMetrics in metricsPayload.resourceMetrics ?: [] {
            map<string> resourceAttributes = toAttributeMap(resourceMetrics?.'resource?.attributes);
            if resourceAttributes["service.name"] != expectedServiceName {
                continue;
            }
            foreach OtlpScopeMetrics scopeMetrics in resourceMetrics.scopeMetrics ?: [] {
                foreach OtlpMetric metric in scopeMetrics.metrics ?: [] {
                    collected.push(metric);
                }
            }
        }
    }
    return collected;
}

function findMetricsByName(string name) returns OtlpMetric[] {
    return collectMetrics().filter(metric => metric.name == name);
}

function metricExists(string name) returns boolean {
    return findMetricsByName(name).length() > 0;
}

function collectMetricNames() returns string[] {
    string[] names = [];
    foreach OtlpMetric metric in collectMetrics() {
        if names.indexOf(metric.name) is () {
            names.push(metric.name);
        }
    }
    return names;
}

function getMetricResourceAttributeSets() returns map<string>[] {
    map<string>[] attributeSets = [];
    foreach json payload in getMetricExports() {
        OtlpMetricsPayload|error metricsPayload = payload.fromJsonWithType();
        if metricsPayload is error {
            continue;
        }
        foreach OtlpResourceMetrics resourceMetrics in metricsPayload.resourceMetrics ?: [] {
            attributeSets.push(toAttributeMap(resourceMetrics?.'resource?.attributes));
        }
    }
    return attributeSets;
}

function toAttributeMap(OtlpAttribute[]? attributes) returns map<string> {
    map<string> attributeMap = {};
    foreach OtlpAttribute attribute in attributes ?: [] {
        map<json>? attributeValue = attribute?.value;
        if attributeValue is () {
            continue;
        }
        json extractedValue = ();
        if attributeValue.hasKey("stringValue") {
            extractedValue = attributeValue.get("stringValue");
        } else if attributeValue.hasKey("intValue") {
            extractedValue = attributeValue.get("intValue");
        } else if attributeValue.hasKey("boolValue") {
            extractedValue = attributeValue.get("boolValue");
        }
        if extractedValue is string {
            attributeMap[attribute.key] = extractedValue;
        } else if extractedValue != () {
            attributeMap[attribute.key] = extractedValue.toString();
        }
    }
    return attributeMap;
}

// OTLP/JSON may encode enums as integers (per the OTLP spec) or as enum
// names, depending on the marshaler; accept either representation.
function isSpanKind(OtlpSpan span, int kindNumber, string kindName) returns boolean {
    json kind = span?.kind;
    return kind == kindNumber || kind == kindNumber.toString() || kind == kindName;
}

// A span counts as an error span if its OTLP status code is ERROR, or if it
// carries the Ballerina observability "error" attribute (the runtime tags
// failed observations with error=true).
function isErrorSpan(OtlpSpan span) returns boolean {
    json status = span?.status;
    if status is map<json> {
        json code = status["code"];
        if code == 2 || code == "2" || code == "STATUS_CODE_ERROR" {
            return true;
        }
    }
    map<string> attributes = toAttributeMap(span?.attributes);
    return attributes["error"] == "true";
}

// Per the OTLP/JSON mapping, double values are JSON numbers and int64 values
// are JSON strings; normalize both to decimal.
function dataPointValue(OtlpDataPoint dataPoint) returns decimal? {
    json doubleValue = dataPoint?.asDouble;
    if doubleValue is int|float|decimal {
        return <decimal>doubleValue;
    }
    if doubleValue is string {
        decimal|error parsed = decimal:fromString(doubleValue);
        if parsed is decimal {
            return parsed;
        }
    }
    json intValue = dataPoint?.asInt;
    if intValue is int {
        return <decimal>intValue;
    }
    if intValue is string {
        int|error parsed = int:fromString(intValue);
        if parsed is int {
            return <decimal>parsed;
        }
    }
    return ();
}
