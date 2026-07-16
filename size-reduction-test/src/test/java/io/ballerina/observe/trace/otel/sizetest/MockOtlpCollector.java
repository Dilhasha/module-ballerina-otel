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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Minimal in-JVM OTLP/HTTP collector stand-in used to verify that the OTLP HTTP
 * exporters (okhttp + okio + kotlin-stdlib code path) complete real export requests.
 */
public final class MockOtlpCollector implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

    private MockOtlpCollector(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Starts a collector on an ephemeral loopback port.
     *
     * @return the started collector
     * @throws IOException if the server socket cannot be opened
     */
    public static MockOtlpCollector start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        MockOtlpCollector collector = new MockOtlpCollector(server, executor);
        server.createContext("/", collector::handle);
        server.setExecutor(executor);
        server.start();
        return collector;
    }

    /**
     * Returns the base URL (scheme://host:port) of this collector.
     *
     * @return the base URL of the collector
     */
    public String baseUrl() {
        InetSocketAddress address = server.getAddress();
        return "http://" + address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    /**
     * Waits until a request for the given path whose body contains the given bytes arrives.
     *
     * @param path          the request path to match (for example {@code /v1/traces})
     * @param needle        the byte sequence that must be present in the request body
     * @param timeoutMillis the maximum time to wait in milliseconds
     * @return the first matching request, or {@code null} if none arrived in time
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public ReceivedRequest awaitRequestContaining(String path, byte[] needle, long timeoutMillis)
            throws InterruptedException {
        return ReceivedRequest.awaitContaining(requests, path, needle, timeoutMillis);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream requestBody = exchange.getRequestBody()) {
            body = requestBody.readAllBytes();
        }
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        if ("gzip".equalsIgnoreCase(headers.get("content-encoding"))) {
            body = gunzip(body);
        }
        requests.add(new ReceivedRequest(exchange.getRequestURI().getPath(), headers, body));
        exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzipStream = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            return gzipStream.readAllBytes();
        }
    }
}
