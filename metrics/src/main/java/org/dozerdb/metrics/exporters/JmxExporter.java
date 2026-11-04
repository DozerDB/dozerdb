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
import com.codahale.metrics.jmx.JmxReporter;
import com.codahale.metrics.jmx.ObjectNameFactory;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Publishes every registered metric as an MBean on the platform MBean server, in the domain
 * {@code <prefix>.metrics} under the single key {@code name=<full metric name>}: for example
 * {@code neo4j.metrics:name=neo4j.dbms.vm.heap.used}. That is the object name cypher-shell {@code :sysinfo},
 * the Browser and the fleet-management module build, and the attributes are Dropwizard's ({@code Value} for a
 * gauge, {@code Count} for a counter).
 */
public final class JmxExporter implements Exporter {

    public static final String NAME = "jmx";

    private final MetricRegistry registry;
    private final String domain;
    private final MBeanServer server;
    private JmxReporter reporter;

    public JmxExporter(MetricRegistry registry, String prefix, MBeanServer server) {
        this.registry = registry;
        this.domain = domain(prefix);
        this.server = server;
    }

    /** The JMX domain for a metric prefix: {@code <prefix>.metrics}. */
    public static String domain(String prefix) {
        return prefix + ".metrics";
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start() {
        reporter = JmxReporter.forRegistry(registry)
                .registerWith(server)
                .inDomain(domain)
                .createsObjectNamesWith(new NameOnlyObjectNames())
                .build();
        reporter.start();
    }

    /** Unregisters every MBean this exporter registered. */
    @Override
    public void stop() {
        if (reporter != null) {
            reporter.stop();
            reporter = null;
        }
    }

    /** {@code <domain>:name=<full name>}; Dropwizard's default factory would add a {@code type=} key. */
    private static final class NameOnlyObjectNames implements ObjectNameFactory {

        private static final String CHARACTERS_NEEDING_QUOTES = ",=:\"*?\n";

        @Override
        public ObjectName createName(String type, String domain, String name) {
            try {
                return new ObjectName(domain, "name", quoteIfNeeded(name));
            } catch (MalformedObjectNameException e) {
                throw new IllegalArgumentException("Not a valid JMX object name: " + domain + ":name=" + name, e);
            }
        }

        private static String quoteIfNeeded(String name) {
            return name.chars().anyMatch(c -> CHARACTERS_NEEDING_QUOTES.indexOf(c) >= 0)
                    ? ObjectName.quote(name)
                    : name;
        }
    }
}
