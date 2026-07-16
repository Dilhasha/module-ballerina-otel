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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A single OTLP export request received by one of the mock collectors
 * ({@link MockOtlpCollector} for OTLP/HTTP, {@link MockOtlpGrpcCollector} for OTLP/gRPC).
 */
public final class ReceivedRequest {

    private final String path;
    private final Map<String, String> headers;
    private final byte[] body;

    ReceivedRequest(String path, Map<String, String> headers, byte[] body) {
        this.path = path;
        if (headers == null) {
            this.headers = Map.of();
        } else {
            // Store keys lowercased so that header() lookups are case-insensitive
            // regardless of the casing the mock collector captured them with.
            Map<String, String> normalized = new HashMap<>();
            headers.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
            this.headers = Map.copyOf(normalized);
        }
        this.body = body.clone();
    }

    /**
     * Waits until a request for the given path whose body contains the given bytes arrives.
     *
     * @param requests      the live collection of received requests to poll
     * @param path          the request path to match (for example {@code /v1/traces})
     * @param needle        the byte sequence that must be present in the request body
     * @param timeoutMillis the maximum time to wait in milliseconds
     * @return the first matching request, or {@code null} if none arrived in time
     * @throws InterruptedException if the waiting thread is interrupted
     */
    static ReceivedRequest awaitContaining(Iterable<ReceivedRequest> requests, String path, byte[] needle,
                                           long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (ReceivedRequest request : requests) {
                if (request.path().equals(path) && request.bodyContains(needle)) {
                    return request;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    /**
     * Returns the request path (HTTP path or full gRPC method path).
     *
     * @return the request path
     */
    public String path() {
        return path;
    }

    /**
     * Returns the first value of the given header (case-insensitive), or {@code null}.
     *
     * @param name the header name
     * @return the header value or {@code null} if not present
     */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the size of the request body in bytes.
     *
     * @return the request body size
     */
    public int bodySize() {
        return body.length;
    }

    /**
     * Returns whether the request body contains the given byte sequence.
     *
     * @param needle the byte sequence to search for
     * @return {@code true} if the body contains the sequence
     */
    public boolean bodyContains(byte[] needle) {
        if (needle.length == 0 || needle.length > body.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= body.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (body[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
