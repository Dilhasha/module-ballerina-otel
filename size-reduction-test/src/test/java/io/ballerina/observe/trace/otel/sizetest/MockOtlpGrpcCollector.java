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

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Minimal in-JVM OTLP/gRPC collector stand-in used to verify that the OTLP gRPC
 * exporters complete real export requests. With opentelemetry-exporter-sender-okhttp
 * (the bundled sender) the client side runs gRPC over okhttp's HTTP/2 transport
 * (h2c prior knowledge), so a successful export exercises okhttp's HTTP/2 stack on
 * top of the kotlin-stdlib jar under test.
 *
 * <p>The server side is grpc-java on shaded Netty (pure Java): no Kotlin code on the
 * server can mask a missing kotlin-stdlib member on the exporter side.</p>
 */
public final class MockOtlpGrpcCollector implements AutoCloseable {

    /** Full gRPC method path of the OTLP trace export RPC. */
    public static final String TRACES_EXPORT_PATH = "/opentelemetry.proto.collector.trace.v1.TraceService/Export";
    /** Full gRPC method path of the OTLP metrics export RPC. */
    public static final String METRICS_EXPORT_PATH =
            "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export";

    private static final Context.Key<Map<String, String>> HEADERS =
            Context.keyWithDefault("otlp-request-headers", Map.of());
    private static final MethodDescriptor.Marshaller<byte[]> BYTES_MARSHALLER = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    private final Server server;
    private final List<ReceivedRequest> requests;

    private MockOtlpGrpcCollector(Server server, List<ReceivedRequest> requests) {
        this.server = server;
        this.requests = requests;
    }

    /**
     * Starts a collector on an ephemeral loopback port serving the OTLP trace and
     * metrics export RPCs.
     *
     * @return the started collector
     * @throws IOException if the server socket cannot be opened
     */
    public static MockOtlpGrpcCollector start() throws IOException {
        List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
        ServerInterceptor headerCapture = new HeaderCaptureInterceptor();
        Server server = NettyServerBuilder
                .forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(ServerInterceptors.intercept(exportService(TRACES_EXPORT_PATH, requests), headerCapture))
                .addService(ServerInterceptors.intercept(exportService(METRICS_EXPORT_PATH, requests), headerCapture))
                .build()
                .start();
        return new MockOtlpGrpcCollector(server, requests);
    }

    /**
     * Returns the plaintext endpoint URL (http://host:port) of this collector, in the
     * form the OTLP gRPC exporters expect.
     *
     * @return the endpoint URL of the collector
     */
    public String endpointUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getPort();
    }

    /**
     * Waits until an export RPC for the given method path whose message contains the given bytes arrives.
     *
     * @param path          the full gRPC method path to match (see {@link #TRACES_EXPORT_PATH})
     * @param needle        the byte sequence that must be present in the request message
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
        server.shutdownNow();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ServerServiceDefinition exportService(String exportPath, List<ReceivedRequest> requests) {
        String fullMethodName = exportPath.substring(1);
        String serviceName = fullMethodName.substring(0, fullMethodName.lastIndexOf('/'));
        MethodDescriptor<byte[], byte[]> method = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(fullMethodName)
                .setRequestMarshaller(BYTES_MARSHALLER)
                .setResponseMarshaller(BYTES_MARSHALLER)
                .build();
        return ServerServiceDefinition.builder(serviceName)
                .addMethod(method, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    requests.add(new ReceivedRequest(exportPath, HEADERS.get(), request));
                    // The OTLP Export*ServiceResponse is an empty protobuf message.
                    responseObserver.onNext(new byte[0]);
                    responseObserver.onCompleted();
                }))
                .build();
    }

    /**
     * Captures the ASCII request metadata of each call so tests can assert custom
     * exporter headers, mirroring the header assertions of the OTLP/HTTP tests.
     */
    private static final class HeaderCaptureInterceptor implements ServerInterceptor {

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                     Metadata metadata,
                                                                     ServerCallHandler<ReqT, RespT> next) {
            Map<String, String> headers = new HashMap<>();
            for (String key : metadata.keys()) {
                if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                    headers.put(key, metadata.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)));
                }
            }
            return Contexts.interceptCall(Context.current().withValue(HEADERS, headers), call, metadata, next);
        }
    }
}
