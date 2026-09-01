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
package io.ballerina.observe.trace.otel.logging;


import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OtelTraceLogger {
    // Instance-scoped anonymous logger so that concurrently live OtelTraceLogger instances
    // (e.g. re-initialization or parallel tests) cannot race on a shared logger's handler
    // configuration. An anonymous logger is not registered in the global LogManager, so it
    // is private to this instance.
    private final Logger traceLogger = Logger.getAnonymousLogger();
    // Strong reference so the JUL logger (and its handlers) are not garbage collected.
    // Warnings and errors emitted by the OpenTelemetry SDK (e.g. the reason an OTLP export
    // failed) are routed to the same handlers as the trace log so that they are visible
    // even when the runtime suppresses default JUL console output.
    private static final Logger otelSdkLogger = Logger.getLogger("io.opentelemetry");

    public OtelTraceLogger(boolean traceLogConsole, Path logFilePath) {
        synchronized (otelSdkLogger) {
            for (Handler handler : otelSdkLogger.getHandlers()) {
                otelSdkLogger.removeHandler(handler);
            }
        }
        if (traceLogConsole) {
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new OtelTraceLogFormatter());
            consoleHandler.setLevel(Level.ALL);
            traceLogger.addHandler(consoleHandler);
            otelSdkLogger.addHandler(consoleHandler);
        }
        if (logFilePath != null) {
            try {
                FileHandler fileHandler = new FileHandler(logFilePath.toString(), true);
                fileHandler.setFormatter(new OtelTraceLogFormatter());
                fileHandler.setLevel(Level.ALL);
                traceLogger.addHandler(fileHandler);
                otelSdkLogger.addHandler(fileHandler);

            } catch (IOException e) {
                throw new RuntimeException("failed to setup Otel trace log file: " + logFilePath, e);
            }
        }
        traceLogger.setUseParentHandlers(false);
        otelSdkLogger.setLevel(Level.WARNING);
    }

    public OtelTraceLogger() {
        traceLogger.setUseParentHandlers(false);
    }

    public boolean isLoggable(Level level) {
        return traceLogger.isLoggable(level);
    }

    public void setLogLevel(Level logLevel) {
        traceLogger.setLevel(logLevel);
    }

    public void printInfo(String message) {
        traceLogger.log(Level.INFO, message);
    }

    public void printDebug(String message) {
        traceLogger.log(Level.CONFIG, message);
    }

    public void printSevere(String message) {
        traceLogger.log(Level.SEVERE, message);
    }
}
