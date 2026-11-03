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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.neo4j.configuration.helpers.SocketAddress;

/**
 * A plaintext Graphite server for the tests: listens on the loopback interface, accepts one connection at a time
 * and records every line it receives, exactly as a Graphite carbon receiver would see them. The tests observe
 * the exporter through nothing else.
 */
final class GraphiteListener implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<List<String>> reports = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean closed;

    private GraphiteListener(ServerSocket server) {
        this.server = server;
        this.acceptor = new Thread(this::accept, "graphite-listener-" + server.getLocalPort());
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Listens on a free loopback port. */
    static GraphiteListener listen() throws IOException {
        return listenOn(0);
    }

    /** Listens on the given loopback port, for example one that {@link #freePort()} returned earlier. */
    static GraphiteListener listenOn(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        return new GraphiteListener(socket);
    }

    /** A loopback port nothing listens on; a connection to it is refused at once. */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    static SocketAddress loopback(int port) {
        return new SocketAddress(InetAddress.getLoopbackAddress().getHostAddress(), port);
    }

    int port() {
        return server.getLocalPort();
    }

    /** The value for {@code server.metrics.graphite.server}. */
    SocketAddress address() {
        return loopback(port());
    }

    /** Every line received so far, in arrival order. */
    List<String> lines() {
        return List.copyOf(lines);
    }

    /** The lines of every connection closed so far, one list per connection, in arrival order. */
    List<List<String>> reports() {
        return List.copyOf(reports);
    }

    /** How many connections were accepted so far. */
    int connections() {
        return connections.get();
    }

    private void accept() {
        while (!closed) {
            try (Socket socket = server.accept();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                connections.incrementAndGet();
                List<String> report = new ArrayList<>();
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                    report.add(line);
                }
                reports.add(List.copyOf(report));
            } catch (IOException e) {
                if (!closed) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        try {
            acceptor.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
