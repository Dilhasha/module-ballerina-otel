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
import ballerina/url;

// End-to-end assertions against a real Jaeger backend: the app exports over
// OTLP/gRPC into Jaeger all-in-one (see tests/configs/default.toml and
// resources/jaeger/docker-compose.yml), and the tests read everything back
// through the Jaeger Query API — validating the whole ingest -> store ->
// query path, not just OTLP receipt.
configurable string expectedServiceName = "ballerina-otel-jaeger-tests";
configurable string jaegerQueryEndpoint = "http://localhost:26686";

const string SPAN_RESOURCE_FUNCTION = "get /sum";
const string SPAN_ERROR_RESOURCE_FUNCTION = "get /failure";
const string SPAN_OBSERVABLE_FUNCTION = "ballerinax/otel_jaeger_tests/ObservableAdder:getSum";
const string SPAN_CALLER_RESPOND = "ballerina/http/Caller:respond";
const string SPAN_CLIENT_GET = "ballerina/http/Client:get";

// Jaeger Query API payload shapes (open records; only the fields the tests
// need — extra fields like startTime/duration/logs are absorbed by the rest
// field).

type JaegerTag record {
    string key;
    json value?;
};

type JaegerReference record {
    string refType;
    string traceID;
    string spanID;
};

type JaegerSpan record {
    string traceID;
    string spanID;
    string operationName;
    JaegerReference[]? references?;
    JaegerTag[]? tags?;
    string processID?;
};

type JaegerProcess record {
    string serviceName;
    JaegerTag[]? tags?;
};

type JaegerTrace record {
    string traceID;
    JaegerSpan[] spans;
    map<JaegerProcess> processes?;
};

type JaegerTracesResponse record {
    JaegerTrace[]? data?;
};

type JaegerServicesResponse record {
    string[]? data?;
};

string sumResponse = "";

@test:BeforeSuite
function setup() returns error? {
    http:Client appClient = check new ("http://localhost:9092");
    sumResponse = check appClient->get("/test/sum");

    // The /failure resource intentionally responds with a 500, which the
    // client surfaces as an error.
    string|error failureResponse = appClient->get("/test/failure");
    if failureResponse is string {
        return error("expected GET /test/failure to fail with a 500 status",
                response = failureResponse);
    }
    return awaitExpectedSpans();
}

// Waits until every expected span is queryable through the Jaeger Query API
// (app batch export -> Jaeger ingest -> storage -> query).
function awaitExpectedSpans() returns error? {
    string[] expectedOperations = [SPAN_RESOURCE_FUNCTION, SPAN_ERROR_RESOURCE_FUNCTION,
            SPAN_OBSERVABLE_FUNCTION, SPAN_CALLER_RESPOND, SPAN_CLIENT_GET];
    int attempt = 0;
    while attempt < 90 {
        boolean allSpansQueryable = expectedOperations
            .every(operation => findSpansByOperation(operation).length() > 0);
        if allSpansQueryable {
            return;
        }
        runtime:sleep(1);
        attempt += 1;
    }
    return error("timed out waiting for the expected spans to become queryable in Jaeger",
            queryableOperations = collectOperationNames(), services = fetchServices());
}

@test:Config {}
function testServiceResponse() {
    test:assertEquals(sumResponse, "Sum: 53");
}

@test:Config {}
function testServiceRegisteredInJaeger() {
    string[] services = fetchServices();
    test:assertTrue(services.indexOf(expectedServiceName) !is (),
            string `service "${expectedServiceName}" not registered in Jaeger (services: ${services.toString()})`);
}

@test:Config {}
function testExpectedSpansQueryableInJaeger() {
    foreach string operation in [SPAN_RESOURCE_FUNCTION, SPAN_ERROR_RESOURCE_FUNCTION,
            SPAN_OBSERVABLE_FUNCTION, SPAN_CALLER_RESPOND, SPAN_CLIENT_GET] {
        test:assertTrue(findSpansByOperation(operation).length() > 0,
                string `span "${operation}" not queryable in Jaeger`);
    }
}

@test:Config {}
function testSpanHierarchy() returns error? {
    JaegerTrace sumTrace = check findTraceWithOperation(SPAN_RESOURCE_FUNCTION);
    JaegerSpan resourceSpan = check findSpanInTrace(sumTrace, SPAN_RESOURCE_FUNCTION);

    // The resource function span must be a descendant of the HTTP client span
    // of the test request (trace context propagated over HTTP). The client
    // side emits a chain of spans (Client:get -> HttpCachingClient:get ->
    // HttpClient:get), so walk up the ancestor chain within the trace.
    string[] ancestorNames = ancestorOperationNames(sumTrace, resourceSpan);
    test:assertTrue(ancestorNames.indexOf(SPAN_CLIENT_GET) !is (),
            string `span "${SPAN_CLIENT_GET}" is not an ancestor of "${SPAN_RESOURCE_FUNCTION}" (ancestors: ${ancestorNames.toString()})`);

    // Spans generated within the resource function must be direct children of
    // the resource function span, in the same trace.
    foreach string childOperation in [SPAN_OBSERVABLE_FUNCTION, SPAN_CALLER_RESPOND] {
        JaegerSpan childSpan = check findSpanInTrace(sumTrace, childOperation);
        test:assertEquals(parentSpanId(childSpan), resourceSpan.spanID,
                string `span "${childOperation}" is not a child of "${SPAN_RESOURCE_FUNCTION}"`);
    }
}

@test:Config {}
function testSpanKinds() returns error? {
    JaegerTrace sumTrace = check findTraceWithOperation(SPAN_RESOURCE_FUNCTION);
    JaegerSpan resourceSpan = check findSpanInTrace(sumTrace, SPAN_RESOURCE_FUNCTION);

    // The OTLP -> Jaeger translation stores the span kind as a "span.kind"
    // tag ("server"/"client").
    map<json> resourceSpanTags = toTagMap(resourceSpan?.tags);
    test:assertEquals(resourceSpanTags["span.kind"], "server",
            string `span "${SPAN_RESOURCE_FUNCTION}" is not a server span (tags: ${resourceSpanTags.toString()})`);

    // The direct parent of the server span is the over-the-wire HTTP client
    // span, which must be a client span.
    string? parentId = parentSpanId(resourceSpan);
    test:assertTrue(parentId is string && parentId != "",
            string `span "${SPAN_RESOURCE_FUNCTION}" has no parent span`);
    JaegerSpan? parentSpan = findSpanInTraceBySpanId(sumTrace, <string>parentId);
    test:assertTrue(parentSpan is JaegerSpan,
            string `parent span of "${SPAN_RESOURCE_FUNCTION}" not found in trace ${sumTrace.traceID}`);
    map<json> parentSpanTags = toTagMap((<JaegerSpan>parentSpan)?.tags);
    test:assertEquals(parentSpanTags["span.kind"], "client",
            string `span "${(<JaegerSpan>parentSpan).operationName}" is not a client span (tags: ${parentSpanTags.toString()})`);
}

@test:Config {}
function testResourceFunctionSpanTags() returns error? {
    JaegerTrace sumTrace = check findTraceWithOperation(SPAN_RESOURCE_FUNCTION);
    JaegerSpan resourceSpan = check findSpanInTrace(sumTrace, SPAN_RESOURCE_FUNCTION);
    map<json> tags = toTagMap(resourceSpan?.tags);
    test:assertEquals(tags["http.method"], "GET");
    test:assertEquals(tags["http.url"], "/test/sum");
    test:assertEquals(tags["http.status_code"], "200");
    test:assertEquals(tags["protocol"], "http");
    test:assertEquals(tags["src.resource.accessor"], "get");
    test:assertEquals(tags["src.resource.path"], "/sum");
}

@test:Config {}
function testSuccessfulSpanNotMarkedAsError() returns error? {
    JaegerTrace sumTrace = check findTraceWithOperation(SPAN_RESOURCE_FUNCTION);
    JaegerSpan resourceSpan = check findSpanInTrace(sumTrace, SPAN_RESOURCE_FUNCTION);
    test:assertFalse(isErrorSpan(resourceSpan),
            string `span "${SPAN_RESOURCE_FUNCTION}" is unexpectedly marked as an error (tags: ${toTagMap(resourceSpan?.tags).toString()})`);
}

@test:Config {}
function testErrorSpanStoredWithErrorIndication() returns error? {
    JaegerTrace failureTrace = check findTraceWithOperation(SPAN_ERROR_RESOURCE_FUNCTION);
    JaegerSpan errorSpan = check findSpanInTrace(failureTrace, SPAN_ERROR_RESOURCE_FUNCTION);
    map<json> tags = toTagMap(errorSpan?.tags);
    test:assertEquals(tags["http.status_code"], "500");
    test:assertTrue(isErrorSpan(errorSpan),
            string `span "${SPAN_ERROR_RESOURCE_FUNCTION}" carries no error indication (tags: ${tags.toString()})`);
}

@test:Config {}
function testProcessResourceAttributes() returns error? {
    JaegerTrace sumTrace = check findTraceWithOperation(SPAN_RESOURCE_FUNCTION);
    // The OTLP resource becomes the Jaeger "process": service.name is the
    // process serviceName and the remaining resource attributes its tags.
    boolean found = false;
    string[] seenProcesses = [];
    foreach JaegerProcess process in sumTrace?.processes ?: {} {
        map<json> processTags = toTagMap(process?.tags);
        seenProcesses.push(string `${process.serviceName} ${processTags.toString()}`);
        if process.serviceName == expectedServiceName
                && processTags["deployment.environment"] == "test" {
            found = true;
        }
    }
    test:assertTrue(found,
            "no Jaeger process with the configured service.name and deployment.environment " +
            string `resource attributes (processes: ${seenProcesses.toString()})`);
}

// Helper functions

// All lookups go through operation-scoped Jaeger queries: the test suite's
// own (instrumented) polling requests to the Query API create additional
// traces under the same service, so unscoped trace searches could crowd out
// the traces the assertions need.

function fetchTraces(string? operation = ()) returns JaegerTrace[] {
    string path = string `/api/traces?service=${encodeQueryValue(expectedServiceName)}&limit=200`;
    if operation is string {
        path += string `&operation=${encodeQueryValue(operation)}`;
    }
    http:Client|error jaegerClient = new (jaegerQueryEndpoint);
    if jaegerClient is error {
        return [];
    }
    JaegerTracesResponse|error tracesResponse = jaegerClient->get(path);
    if tracesResponse is error {
        return [];
    }
    return tracesResponse?.data ?: [];
}

function fetchServices() returns string[] {
    http:Client|error jaegerClient = new (jaegerQueryEndpoint);
    if jaegerClient is error {
        return [];
    }
    JaegerServicesResponse|error servicesResponse = jaegerClient->get("/api/services");
    if servicesResponse is error {
        return [];
    }
    return servicesResponse?.data ?: [];
}

function findSpansByOperation(string operation) returns JaegerSpan[] {
    JaegerSpan[] spans = [];
    foreach JaegerTrace jaegerTrace in fetchTraces(operation) {
        foreach JaegerSpan span in jaegerTrace.spans {
            if span.operationName == operation {
                spans.push(span);
            }
        }
    }
    return spans;
}

// Returns a full trace containing the given operation; Jaeger returns
// complete traces for operation-scoped searches, so parent/child assertions
// can run within a single trace.
function findTraceWithOperation(string operation) returns JaegerTrace|error {
    JaegerTrace[] traces = fetchTraces(operation);
    if traces.length() == 0 {
        return error(string `no trace containing span "${operation}" is queryable in Jaeger`,
                queryableOperations = collectOperationNames());
    }
    return traces[0];
}

function findSpanInTrace(JaegerTrace jaegerTrace, string operation) returns JaegerSpan|error {
    foreach JaegerSpan span in jaegerTrace.spans {
        if span.operationName == operation {
            return span;
        }
    }
    return error(string `span "${operation}" not found in trace ${jaegerTrace.traceID} ` +
            string `(spans: ${jaegerTrace.spans.map(span => span.operationName).toString()})`);
}

function findSpanInTraceBySpanId(JaegerTrace jaegerTrace, string spanId) returns JaegerSpan? {
    foreach JaegerSpan span in jaegerTrace.spans {
        if span.spanID == spanId {
            return span;
        }
    }
    return ();
}

function parentSpanId(JaegerSpan span) returns string? {
    foreach JaegerReference reference in span?.references ?: [] {
        if reference.refType == "CHILD_OF" {
            return reference.spanID;
        }
    }
    return ();
}

function ancestorOperationNames(JaegerTrace jaegerTrace, JaegerSpan span) returns string[] {
    string[] ancestorNames = [];
    JaegerSpan currentSpan = span;
    while ancestorNames.length() < 10 {
        string? parentId = parentSpanId(currentSpan);
        if parentId is () || parentId == "" {
            break;
        }
        JaegerSpan? parent = findSpanInTraceBySpanId(jaegerTrace, parentId);
        if parent is () {
            break;
        }
        currentSpan = parent;
        ancestorNames.push(currentSpan.operationName);
    }
    return ancestorNames;
}

function collectOperationNames() returns string[] {
    string[] names = [];
    foreach JaegerTrace jaegerTrace in fetchTraces() {
        foreach JaegerSpan span in jaegerTrace.spans {
            if names.indexOf(span.operationName) is () {
                names.push(span.operationName);
            }
        }
    }
    return names;
}

function toTagMap(JaegerTag[]? tags) returns map<json> {
    map<json> tagMap = {};
    foreach JaegerTag tag in tags ?: [] {
        tagMap[tag.key] = tag?.value;
    }
    return tagMap;
}

// A span counts as an error span if the OTLP -> Jaeger translation tagged it
// as one (OTLP status ERROR becomes otel.status_code="ERROR" plus a boolean
// error=true tag) or if it carries the Ballerina observability "error"
// attribute (the runtime tags failed observations with the string "true").
function isErrorSpan(JaegerSpan span) returns boolean {
    map<json> tags = toTagMap(span?.tags);
    json errorTag = tags["error"];
    return errorTag == true || errorTag == "true" || tags["otel.status_code"] == "ERROR";
}

function encodeQueryValue(string value) returns string {
    string|error encoded = url:encode(value, "UTF-8");
    return encoded is string ? encoded : value;
}
