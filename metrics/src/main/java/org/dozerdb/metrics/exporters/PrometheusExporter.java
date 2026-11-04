/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics.exporters;

import com.codahale.metrics.MetricRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.dozerdb.metrics.settings.MetricsConfiguration;

/**
 * Serves the {@link PrometheusExposition} over plain HTTP at {@value #PATH} on {@code server.metrics.prometheus.endpoint}
 * (Operations Manual 2026.10, "Expose metrics": default {@code localhost:2004}, which also supplies a missing host,
 * so {@code :port} binds localhost), as Neo4j's endpoint does: no TLS, no authentication, text format 0.0.4.
 * Any other path is answered 404.
 *
 * <p>The server is the JDK's {@link HttpServer}; requests run on the exporter's own daemon thread, never on a
 * database thread. A port that cannot be bound fails {@link #start()} and is therefore isolated:
 * one error line, the exporter disabled, the server serving. {@link #stop()} closes the listener and the thread.
 */
public final class PrometheusExporter implements Exporter {

    public static final String NAME = "prometheus";

    /** The one path served; Neo4j's own test support requests {@code http://<endpoint>/metrics}. */
    public static final String PATH = "/metrics";

    /**
     * The request thread's name, beside the scheduler's {@code neo4j.MetricsCsvWrite-N}; Community has no scheduler
     * group for a Prometheus endpoint, so the exporter owns the thread.
     */
    public static final String THREAD_NAME = "neo4j.MetricsPrometheus";

    private final PrometheusExposition exposition;
    private final String host;
    private final int port;
    private HttpServer server;
    private ExecutorService requests;

    public PrometheusExporter(MetricRegistry registry, MetricsConfiguration.Prometheus settings) {
        this(registry, settings.host(), settings.port());
    }

    public PrometheusExporter(MetricRegistry registry, String host, int port) {
        this.exposition = new PrometheusExposition(registry);
        this.host = host;
        this.port = port;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        try {
            requests = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, THREAD_NAME);
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(requests);
            server.createContext("/", this::handle);
            server.start();
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (requests != null) {
            requests.shutdownNow();
            requests = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = exposition.text().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", PrometheusExposition.CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
