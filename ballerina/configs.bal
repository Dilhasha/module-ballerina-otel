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
// Trace sampling strategy: always_on, always_off, traceidratio, parentbased_always_on,
// parentbased_always_off, parentbased_traceidratio, or ratelimiting
configurable string tracesSampler = DEFAULT_SAMPLER_TYPE;
// Sampler-specific argument: sampling probability (0.0-1.0) for traceidratio samplers,
// max traces per second for ratelimiting; ignored by the other samplers
configurable decimal tracesSamplerArg = 1;
configurable int tracesExporterTimeoutMillis = 10000;
configurable int tracesMaxExportBatchSize = 512;
configurable boolean tracesLogConsole = false;
configurable string tracesLogFile = "";
configurable string tracesLogLevel = "info";

// Traces OTLP exporter configuration
// Comma-separated key=value pairs, the same format as the
// OTEL_EXPORTER_OTLP_TRACES_HEADERS environment variable, e.g.
// "api-key=key,other-header=value". Values may be percent-encoded.
configurable string tracesExporterHeaders = "";
// Exporter protocol: "grpc" (OTLP/gRPC), "http/protobuf" (OTLP/HTTP + protobuf)
// or "http/json" (OTLP/HTTP + JSON). Note: "http/json" is not yet supported.
configurable string tracesProtocol = "grpc";
configurable map<string> tracesResourceAttributes = {};

// Metrics configuration
configurable string metricsEndpoint = "http://localhost:4317";
// Exporter protocol: "grpc" (OTLP/gRPC), "http/protobuf" (OTLP/HTTP + protobuf)
// or "http/json" (OTLP/HTTP + JSON). Note: "http/json" is not yet supported.
configurable string metricsProtocol = "grpc";
// Comma-separated key=value pairs, the same format as the
// OTEL_EXPORTER_OTLP_METRICS_HEADERS environment variable. Values may be
// percent-encoded.
configurable string metricsExporterHeaders = "";
configurable map<string> metricsResourceAttributes = {};
configurable string metricsServiceName = "";
configurable int metricsExportIntervalMillis = 60000;
configurable int metricsExporterTimeoutMillis = 10000;
configurable string metricsPrefix = "";

function hasSupportedEndpointScheme(string endpoint) returns boolean {
    return endpoint.startsWith("http://") || endpoint.startsWith("https://");
}

// Parses OTLP exporter headers given as comma-separated key=value pairs — the
// format of the OTEL_EXPORTER_OTLP_*_HEADERS environment variables (the OTel
// exporter builders themselves only accept individual key/value pairs, so the
// list format must be parsed here). Values may be percent-encoded per the
// OTel specification (W3C Baggage style).
function parseOtlpHeaders(string headers, string configName) returns map<string>|error {
    map<string> parsedHeaders = {};
    foreach string entry in re `,`.split(headers) {
        string pair = entry.trim();
        if pair.length() == 0 {
            continue;
        }
        int? separatorIndex = pair.indexOf("=");
        if separatorIndex is () || separatorIndex == 0 {
            return error(string `invalid Otel configuration ${configName}: "${pair}" is not a key=value pair. ` +
                    string `Expected comma-separated key=value pairs, e.g. "api-key=key,other-header=value"`);
        }
        string key = pair.substring(0, separatorIndex).trim();
        string|error value = percentDecode(pair.substring(separatorIndex + 1).trim());
        if value is error {
            return error(string `invalid Otel configuration ${configName}: ${value.message()}`);
        }
        parsedHeaders[key] = value;
    }
    return parsedHeaders;
}

// Strict RFC 3986 percent-decoding: only %HH sequences are decoded (the
// decoded bytes are interpreted as UTF-8). Unlike form-urlencoded decoders,
// '+' stays a literal plus so that e.g. base64 header values are not
// corrupted.
function percentDecode(string value) returns string|error {
    if !value.includes("%") {
        return value;
    }
    byte[] rawBytes = value.toBytes();
    byte[] decodedBytes = [];
    int i = 0;
    while i < rawBytes.length() {
        byte currentByte = rawBytes[i];
        if currentByte == 0x25 { // '%'
            if i + 2 >= rawBytes.length() {
                return error(string `truncated percent-encoding in "${value}"`);
            }
            int high = check hexDigitValue(rawBytes[i + 1], value);
            int low = check hexDigitValue(rawBytes[i + 2], value);
            decodedBytes.push(<byte>(high * 16 + low));
            i += 3;
        } else {
            decodedBytes.push(currentByte);
            i += 1;
        }
    }
    return string:fromBytes(decodedBytes);
}

function hexDigitValue(byte digit, string value) returns int|error {
    if digit >= 0x30 && digit <= 0x39 { // '0'-'9'
        return digit - 0x30;
    }
    if digit >= 0x41 && digit <= 0x46 { // 'A'-'F'
        return digit - 0x41 + 10;
    }
    if digit >= 0x61 && digit <= 0x66 { // 'a'-'f'
        return digit - 0x61 + 10;
    }
    return error(string `invalid percent-encoding in "${value}"`);
}
