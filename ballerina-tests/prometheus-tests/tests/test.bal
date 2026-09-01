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
import ballerina/time;

// End-to-end assertions against a real, self-hosted Prometheus backend: the app
// exports metrics over OTLP/HTTP into Prometheus' native OTLP receiver (stable
// since Prometheus 3.0; see tests/configs/default.toml and
// resources/prometheus/docker-compose.yml), Prometheus stores them, and the
// tests read them back through its query API (/api/v1/query_range) — validating
// the whole ingest -> store -> query path, not just OTLP receipt.
configurable string expectedServiceName = "ballerina-otel-prometheus-tests";
configurable string prometheusEndpoint = "http://localhost:39090";

// The two metrics the Ballerina observability runtime emits for an
// instrumented HTTP service: a monotonic request counter and an in-progress
// requests gauge. With the `UnderscoreEscapingWithSuffixes` OTLP translation
// strategy (see resources/prometheus/prometheus.yml) these already-Prometheus-
// style names surface unchanged, since they carry no unit and the counter name
// already ends in `_total`.
const string METRIC_REQUESTS_TOTAL = "requests_total";
const string METRIC_INPROGRESS_REQUESTS = "inprogress_requests";

// OTLP resource attributes have their dots replaced with underscores when
// surfaced as Prometheus labels, so `service.name` becomes `service_name`. It
// is promoted onto every series via `promote_resource_attributes` in
// resources/prometheus/prometheus.yml (Prometheus otherwise maps service.name
// only to the `job` label).
const string SERVICE_NAME_LABEL = "service_name";

// Prometheus query API payload shapes (open records; only the fields the
// tests need). A range query returns resultType "matrix" with each result
// carrying its label set (`metric`) and `[timestamp, "value"]` samples.
type PromResult record {
    map<json> metric?;
    // Each entry is [<unix-seconds>, "<value>"]; kept as json and normalized
    // in sampleValue().
    json[][] values?;
};

type PromData record {
    string resultType?;
    PromResult[] result?;
};

type PromResponse record {
    string status?;
    PromData data?;
};

string sumResponse = "";

@test:BeforeSuite
function setup() returns error? {
    http:Client appClient = check new ("http://localhost:9093");
    sumResponse = check appClient->get("/test/sum");

    // The /failure resource intentionally responds with a 500, which the
    // client surfaces as an error; it makes the request counter record a
    // failed observation as well.
    string|error failureResponse = appClient->get("/test/failure");
    if failureResponse is string {
        return error("expected GET /test/failure to fail with a 500 status",
                response = failureResponse);
    }

    // Prometheus needs a moment to accept the first OTLP export and make it
    // queryable; poll until both metrics appear.
    return awaitExpectedMetrics();
}

// Waits until both metrics are queryable through the Prometheus query API (app
// periodic export -> Prometheus ingest -> query).
function awaitExpectedMetrics() returns error? {
    int attempt = 0;
    while attempt < 120 {
        if queryMetricSeries(METRIC_REQUESTS_TOTAL).length() > 0
                && queryMetricSeries(METRIC_INPROGRESS_REQUESTS).length() > 0 {
            return;
        }
        runtime:sleep(2);
        attempt += 1;
    }
    return error("timed out waiting for the expected metrics to become queryable in Prometheus",
            requestsTotalSeries = queryMetricSeries(METRIC_REQUESTS_TOTAL).length(),
            inprogressSeries = queryMetricSeries(METRIC_INPROGRESS_REQUESTS).length());
}

@test:Config {}
function testServiceResponse() {
    test:assertEquals(sumResponse, "Sum: 53");
}

@test:Config {}
function testRequestsTotalCounterQueryable() {
    PromResult[] series = queryMetricSeries(METRIC_REQUESTS_TOTAL);
    test:assertTrue(series.length() > 0,
            string `metric "${METRIC_REQUESTS_TOTAL}" not queryable in Prometheus`);

    // A positive sample value confirms the counter's data points actually
    // reached Prometheus' store.
    test:assertTrue(maxSeriesValue(series) >= 1d,
            string `metric "${METRIC_REQUESTS_TOTAL}" has no data points with a value >= 1 in Prometheus`);
}

@test:Config {}
function testInprogressRequestsGaugeQueryable() {
    PromResult[] series = queryMetricSeries(METRIC_INPROGRESS_REQUESTS);
    test:assertTrue(series.length() > 0,
            string `metric "${METRIC_INPROGRESS_REQUESTS}" not queryable in Prometheus`);
}

@test:Config {}
function testMetricsCarryServiceNameResourceAttribute() {
    // service.name is exported as an OTLP resource attribute and surfaces as
    // the Prometheus label `service_name`; at least one series must carry the
    // configured service name.
    boolean found = false;
    string[] seenServiceNames = [];
    foreach PromResult series in queryMetricSeries(METRIC_REQUESTS_TOTAL) {
        json serviceName = (series?.metric ?: {})[SERVICE_NAME_LABEL];
        if serviceName is string {
            seenServiceNames.push(serviceName);
            if serviceName == expectedServiceName {
                found = true;
            }
        }
    }
    test:assertTrue(found,
            string `no "${METRIC_REQUESTS_TOTAL}" series labelled ${SERVICE_NAME_LABEL}="${expectedServiceName}" ` +
            string `in Prometheus (seen: ${seenServiceNames.toString()})`);
}

// Helper functions

// Runs a Prometheus range query for the given metric over the last 15 minutes
// and returns the resulting series. Any transport/parse error is swallowed into
// an empty result so the polling helpers can retry.
function queryMetricSeries(string metricName) returns PromResult[] {
    http:Client|error queryClient = new (prometheusEndpoint);
    if queryClient is error {
        return [];
    }

    int nowSeconds = currentTimeSeconds();
    string path = string `/api/v1/query_range` +
            string `?query=${metricName}&start=${nowSeconds - (15 * 60)}&end=${nowSeconds}&step=60`;
    PromResponse|error queryResponse = queryClient->get(path);
    if queryResponse is error {
        return [];
    }
    return queryResponse?.data?.result ?: [];
}

function maxSeriesValue(PromResult[] series) returns decimal {
    decimal max = 0d;
    foreach PromResult result in series {
        foreach json[] sample in result?.values ?: [] {
            decimal? value = sampleValue(sample);
            if value is decimal && value > max {
                max = value;
            }
        }
    }
    return max;
}

// A Prometheus range sample is [<unix-seconds>, "<value>"]; the value is the
// second element, encoded as a string.
function sampleValue(json[] sample) returns decimal? {
    if sample.length() < 2 {
        return ();
    }
    json value = sample[1];
    if value is int|float|decimal {
        return <decimal>value;
    }
    if value is string {
        decimal|error parsed = decimal:fromString(value);
        if parsed is decimal {
            return parsed;
        }
    }
    return ();
}

function currentTimeSeconds() returns int {
    [int, decimal] [seconds, _] = time:utcNow();
    return seconds;
}
