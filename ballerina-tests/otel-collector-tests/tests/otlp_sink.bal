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

// Test sink that receives the OTLP/JSON payloads forwarded by the local
// OpenTelemetry collector (see resources/otel-collector). The collector
// receives the module-under-test's OTLP exports on :4318 and re-exports
// them as OTLP/JSON to this service, so the tests can assert on exactly
// what a real collector received.

isolated json[] traceExports = [];
isolated json[] metricExports = [];

isolated function getTraceExports() returns json[] {
    lock {
        return traceExports.clone();
    }
}

isolated function getMetricExports() returns json[] {
    lock {
        return metricExports.clone();
    }
}

isolated service /v1 on new http:Listener(9095) {

    isolated resource function post traces(@http:Payload json payload) returns json {
        lock {
            traceExports.push(payload.clone());
        }
        return {partialSuccess: {}};
    }

    isolated resource function post metrics(@http:Payload json payload) returns json {
        lock {
            metricExports.push(payload.clone());
        }
        return {partialSuccess: {}};
    }
}
