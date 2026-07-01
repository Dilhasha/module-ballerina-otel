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

configurable string tracesEndpoint = "http://localhost:4317";
configurable string tracesSampler = "always_on";
configurable decimal tracesSamplerArg = 1;
configurable int tracesExporterTimeoutMillis = 1000;
configurable int tracesMaxExportBatchSize = 10000;
configurable boolean tracesLogConsole = false;
configurable string tracesLogFile = "";
configurable string tracesLogLevel = "info";

// Traces OTLP exporter configuration
configurable map<string> tracesExporterHeaders = {};
configurable string tracesProtocol = "grpc"; // "grpc" or "http"
configurable map<string> tracesResourceAttributes = {};

// Metrics configuration
configurable boolean metricsEnabled = false;
configurable string metricsEndpoint = "http://localhost:4318/v1/metrics";
configurable string metricsProtocol = "http";
configurable map<string> metricsExporterHeaders = {};
configurable map<string> metricsResourceAttributes = {};
configurable string metricsServiceName = "";
configurable int metricsExportIntervalMillis = 60000;
configurable int metricsExporterTimeoutMillis = 1000;
configurable string metricsPrefix = "";

function hasSupportedEndpointScheme(string endpoint) returns boolean {
    return endpoint.startsWith("http://") || endpoint.startsWith("https://");
}
