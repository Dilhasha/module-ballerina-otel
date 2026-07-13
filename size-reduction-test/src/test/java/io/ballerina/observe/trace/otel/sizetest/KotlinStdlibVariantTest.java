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

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.GzipSink;
import okio.GzipSource;
import okio.Okio;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.logging.Logger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Sanity checks that the expected kotlin-stdlib jar variant (original or slim) is on the
 * classpath and that okhttp/okio (the Kotlin-written libraries) work on top of it.
 */
public class KotlinStdlibVariantTest {

    private static final String VARIANT_PROPERTY = "kotlin.stdlib.variant";
    private static final String SLIM_VARIANT = "slim";

    @Test
    public void testExpectedKotlinStdlibVariantIsLoaded() throws Exception {
        String variant = System.getProperty(VARIANT_PROPERTY, "original");
        URL location = kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(location, "kotlin-stdlib code source location");
        File stdlibJar = Paths.get(location.toURI()).toFile();
        Logger.getLogger(KotlinStdlibVariantTest.class.getName()).info(() -> "kotlin-stdlib variant '" + variant
                + "' loaded from " + stdlibJar.getName() + " (" + stdlibJar.length() + " bytes)");
        if (SLIM_VARIANT.equals(variant)) {
            assertTrue(stdlibJar.getName().contains(SLIM_VARIANT),
                    "expected the slim kotlin-stdlib jar but found " + stdlibJar.getName());
        } else {
            assertFalse(stdlibJar.getName().contains(SLIM_VARIANT),
                    "expected the original kotlin-stdlib jar but found " + stdlibJar.getName());
        }
    }

    @Test
    public void testOkioBufferRoundTrip() {
        try (Buffer buffer = new Buffer()) {
            buffer.writeUtf8("ballerina-otel-size-reduction");
            assertEquals(buffer.readUtf8(), "ballerina-otel-size-reduction");
        }
    }

    @Test
    public void testOkioByteStringHashing() {
        ByteString byteString = ByteString.encodeUtf8("ballerina-otel");
        assertEquals(byteString.utf8(), "ballerina-otel");
        assertEquals(byteString.sha256().hex().length(), 64);
    }

    @Test
    public void testOkhttpUrlParsing() {
        HttpUrl url = HttpUrl.parse("http://localhost:4318/v1/traces?compression=none");
        assertNotNull(url);
        assertEquals(url.encodedPath(), "/v1/traces");
        assertEquals(url.port(), 4318);
        assertEquals(url.queryParameter("compression"), "none");
    }

    @Test
    public void testOkioGzipRoundTrip() throws IOException {
        String payload = "ballerina-otel-gzip-compressed-export-payload";

        Buffer compressed = new Buffer();
        try (BufferedSink gzipSink = Okio.buffer(new GzipSink(compressed))) {
            gzipSink.writeUtf8(payload);
        }
        assertTrue(compressed.size() > 0, "gzip produced no output");

        Buffer decompressed = new Buffer();
        try (BufferedSource gzipSource = Okio.buffer(new GzipSource(compressed))) {
            decompressed.writeAll(gzipSource);
        }
        assertEquals(decompressed.readUtf8(), payload);
    }

    @Test
    public void testOkhttpHeadersAndRequestConstruction() {
        Headers headers = new Headers.Builder()
                .add("x-size-test", "enabled")
                .add("Content-Type", "application/x-protobuf")
                .build();
        assertEquals(headers.size(), 2);
        assertEquals(headers.get("X-Size-Test"), "enabled");

        Request request = new Request.Builder()
                .url("http://localhost:4318/v1/metrics")
                .headers(headers)
                .build();
        assertEquals(request.method(), "GET");
        assertEquals(request.header("content-type"), "application/x-protobuf");
        assertEquals(request.url().encodedPath(), "/v1/metrics");
    }
}
