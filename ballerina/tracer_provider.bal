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

import ballerina/jballerina.java;
import ballerina/log as _;
import ballerina/observe;

function initializeTracing() returns error? {
    if (!observe:isTracingEnabled() || observe:getTracingProvider() != PROVIDER_NAME) {
        return;
    }

    // Validate sampler type
    string[] validSamplerTypes = [SAMPLER_TYPE_ALWAYS_ON, SAMPLER_TYPE_ALWAYS_OFF, SAMPLER_TYPE_TRACE_ID_RATIO,
            SAMPLER_TYPE_PARENT_BASED_ALWAYS_ON, SAMPLER_TYPE_PARENT_BASED_ALWAYS_OFF,
            SAMPLER_TYPE_PARENT_BASED_TRACE_ID_RATIO, SAMPLER_TYPE_RATE_LIMITING];
    if validSamplerTypes.indexOf(tracesSampler) is () {
        return error(string `invalid Otel configuration tracesSampler: ${tracesSampler}. Valid values are: always_on, always_off, traceidratio, parentbased_always_on, parentbased_always_off, parentbased_traceidratio, ratelimiting`);
    }

    // Validate trace log level
    string lowerLogLevel = tracesLogLevel.toLowerAscii();
    if lowerLogLevel != "info" && lowerLogLevel != "debug" && lowerLogLevel != "warn" && lowerLogLevel != "error" {
        return error(string `invalid Otel configuration tracesLogLevel: ${tracesLogLevel}. Valid values are: info, INFO, debug, DEBUG, warn, WARN, error, ERROR`);
    }

    // Validate protocol
    string lowerProtocol = tracesProtocol.toLowerAscii();
    if lowerProtocol != "grpc" && lowerProtocol != "http" {
        return error(string `invalid Otel configuration tracesProtocol: ${tracesProtocol}. Valid values are: grpc, GRPC, http, HTTP`);
    }

    if !hasSupportedEndpointScheme(tracesEndpoint) {
        return error(string `invalid Otel configuration tracesEndpoint: ${tracesEndpoint}. Endpoint must start with http:// or https://`);
    }

    map<string> exporterHeaders = check parseOtlpHeaders(tracesExporterHeaders, "tracesExporterHeaders");

    externInitializeConfigurations(tracesEndpoint, tracesSampler, tracesSamplerArg,
        tracesExporterTimeoutMillis, tracesMaxExportBatchSize, tracesLogConsole, tracesLogFile, lowerLogLevel,
        exporterHeaders, lowerProtocol, tracesResourceAttributes);
}

function externInitializeConfigurations(string endpoint, string samplerType,
        decimal samplerArg, int exporterTimeoutMillis, int maxExportBatchSize,
        boolean tracesLogConsole, string tracesLogFile, string tracesLogLevel,
        map<string> exporterHeaders, string protocol,
        map<string> resourceAttributes) = @java:Method {
    'class: "io.ballerina.observe.trace.otel.OtelTracerProvider",
    name: "initializeConfigurations"
} external;
