/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.dozerdb.metrics;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads the Prometheus endpoint the way a Prometheus server does: a plain HTTP GET of {@code /metrics} on the
 * configured host and port. Tests observe nothing else of the exporter.
 */
public final class PrometheusScrapes {

    public static final String PATH = "/metrics";

    private PrometheusScrapes() {}

    /** A port nothing listens on right now, for an endpoint a test configures. */
    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static HttpResponse<String> get(String host, int port, String path)
            throws IOException, InterruptedException {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://" + authority + ":" + port + path))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    /** The exposition text of a successful scrape. */
    public static String scrape(String host, int port) throws IOException, InterruptedException {
        HttpResponse<String> response = get(host, port, PATH);
        if (response.statusCode() != 200) {
            throw new AssertionError("GET " + PATH + " on " + host + ":" + port + " answered " + response.statusCode());
        }
        return response.body();
    }

    /** Whether a TCP connection to the port is refused, that is, nothing listens there. */
    public static boolean refusesConnections(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return false;
        } catch (ConnectException e) {
            return true;
        }
    }

    /** The family names of an exposition: the names on its {@code # TYPE} lines. */
    public static Set<String> families(String text) {
        return text.lines()
                .filter(line -> line.startsWith("# TYPE "))
                .map(line -> line.split(" ")[2])
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
