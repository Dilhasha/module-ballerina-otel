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

import ballerina/io;
import ballerina/jballerina.java;
import ballerina/observe;
import ballerina/task;

function initializeMetrics() returns error? {
    if !observe:isMetricsEnabled() || observe:getMetricsReporter() != PROVIDER_NAME {
        return;
    }

    string lowerProtocol = metricsProtocol.toLowerAscii();
    if lowerProtocol != "http" && lowerProtocol != "grpc" {
        return error("Invalid metrics protocol. Must be 'http' or 'grpc'");
    }

    if !hasSupportedEndpointScheme(metricsEndpoint) {
        return error("Invalid metrics endpoint. Endpoint must start with http:// or https://");
    }

    externInitializeMetrics(metricsEndpoint, lowerProtocol,
            metricsExporterHeaders, metricsResourceAttributes, metricsServiceName, metricsExportIntervalMillis,
            metricsExporterTimeoutMillis, metricsPrefix);

    check updateMetricsSnapshot();
    _ = check task:scheduleJobRecurByFrequency(new MetricsSnapshotJob(), <decimal>metricsExportIntervalMillis / 1000.0);

    io:println(string `[OTEL Metrics] Started publishing metrics to Otel on ${metricsEndpoint}`);
}

class MetricsSnapshotJob {

    *task:Job;

    public function execute() {
        error? result = trap updateMetricsSnapshot();
        if result is error {
            io:println("[OTEL Metrics] Error updating metrics snapshot: ", result.message());
        }
    }
}

function updateMetricsSnapshot() returns error? {
    observe:Metric[] metrics = observe:getAllMetrics();
    externUpdateMetrics(metrics);
}

function externInitializeMetrics(string endpoint, string protocol,
        map<string> exporterHeaders, map<string> resourceAttributes, string serviceName, int exportIntervalMillis,
        int exporterTimeoutMillis, string metricPrefix) = @java:Method {
    'class: "io.ballerina.observe.trace.otel.OtelMetricsProvider",
    name: "initialize"
} external;

function externUpdateMetrics(observe:Metric[] metrics) = @java:Method {
    'class: "io.ballerina.observe.trace.otel.OtelMetricsProvider",
    name: "updateMetrics"
} external;
