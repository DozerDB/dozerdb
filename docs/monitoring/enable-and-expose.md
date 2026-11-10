# Enable and expose metrics

<!-- Blocks between BEGIN GENERATED and END GENERATED are written by scripts/metrics-docs.py from the metric
inventory and the settings declaration. Change those sources and run the script; do not edit the blocks. The
settings table is checked against the declaration by the same script. -->

<!-- BEGIN GENERATED: version -->
This page tracks the Neo4j Operations Manual **2026.10** (*Monitoring* page) for the DozerDB **2026.10** release line: DozerDB releases built on Neo4j `2026.10.2` (`2026.10.2-<dozerdb-version>`).
<!-- END GENERATED: version -->

Neo4j's pages *Enable metrics logging* (Operations Manual 2026.10)
and *Expose metrics* (Operations Manual 2026.10)
apply to DozerDB as written, apart from their Neo4j Ops Manager parts. Metrics are on
by default: with no metrics setting at all, DozerDB writes CSV files and registers JMX
MBeans for the metrics the default filter enables, exactly as Neo4j does. Prometheus
and Graphite are off until you enable them.

## Settings

DozerDB declares every `server.metrics.*` setting and `server.directories.metrics`
with the name, default, type and validation of Neo4j's
*configuration settings reference* (Operations Manual 2026.10).
An invalid value stops the server at startup with Neo4j's usual settings error; a
valid one never does ([why](differences.md#a-metrics-failure-never-stops-the-server)).

| Setting | Default (Neo4j and DozerDB) | DozerDB |
| --- | --- | --- |
| `server.metrics.enabled` | `true` | `false` switches every metric and exporter off. |
| `server.metrics.prefix` | `neo4j` | The first segment of every metric name, so also of every CSV file name and Graphite path, and the JMX domain `<prefix>.metrics`. |
| `server.metrics.filter` | [the default filter below](#default-filter) | Comma-separated patterns, `*` for any characters and `?` for one, matched against the full name, prefix included. Only matching metrics are registered. An empty filter enables nothing. |
| `server.metrics.deny_filter` | empty | Same pattern syntax; a metric matching it is not registered even when the filter matches it. |
| `server.metrics.csv.enabled` | `true` | One CSV file per metric in `server.directories.metrics`. |
| `server.directories.metrics` | `metrics` | Relative to `server.directories.neo4j_home`. The Docker image sets `/metrics`. |
| `server.metrics.csv.interval` | `30s` | How often a row is appended; at least `1ms`. The first row is written one interval after startup. |
| `server.metrics.csv.rotation.size` | `10.00MiB` | A file that has reached this size is archived before the next row; `0B` never rotates. |
| `server.metrics.csv.rotation.keep_number` | `7` | Archives kept per metric, at least 1. |
| `server.metrics.csv.rotation.compression` | `ZIP` | `NONE`, `ZIP` or `GZ`. The settings reference gives `ZIP`; the example comment on Neo4j's Expose page still says `NONE`. |
| `server.metrics.jmx.enabled` | `true` | MBeans in the domain `<prefix>.metrics`, readable with `CALL dbms.queryJmx(...)`, by DozerDB Browser's and cypher-shell's `:sysinfo`, and by any JMX client. |
| `server.metrics.graphite.enabled` | `false` | Sends every metric to a Graphite server over the plaintext protocol. |
| `server.metrics.graphite.server` | `:2003` | A missing host is taken from `server.default_listen_address`, `localhost` by default. |
| `server.metrics.graphite.interval` | `30s` | How often the metrics are sent. |
| `server.metrics.prometheus.enabled` | `false` | Serves the Prometheus text format at the path `/metrics`. |
| `server.metrics.prometheus.endpoint` | `localhost:2004` | A missing host is `localhost`, so `:2004` listens on `localhost:2004`. Set `0.0.0.0:2004` to listen on every interface, for example in a container. |

Every setting reaches the Docker image as an environment variable the way the Neo4j
image maps settings: prefix `NEO4J_`, each `_` doubled, each `.` replaced by `_`, for
example `NEO4J_server_metrics_prometheus_enabled=true`. The root README of the
repository has a Docker example that scrapes the endpoint from the host.

## Default filter

The default of `server.metrics.filter` is Neo4j's, character for character:

<!-- BEGIN GENERATED: default-filter -->
```properties
server.metrics.filter=*bolt.connections*,*bolt.messages_received*,*bolt.messages_started*,*dbms.pool.bolt.free,*dbms.pool.bolt.total_size,*dbms.pool.bolt.total_used,*dbms.pool.bolt.used_heap,*cluster.raft.is_leader,*cluster.raft.last_leader_message,*cluster.raft.replication_attempt,*cluster.raft.replication_fail,*cluster.raft.last_applied,*cluster.raft.last_appended,*cluster.raft.append_index,*cluster.raft.commit_index,*cluster.raft.applied_index,*check_point.*,*cypher.replan_events,*cypher.cache*,*ids_in_use*,*.neo4j.count.*,*pool.transaction.*.total_used,*pool.transaction.*.used_heap,*pool.transaction.*.used_native,*store.size*,*transaction.active_read,*transaction.active_write,*transaction.committed*,*transaction.last_committed_tx_id,*transaction.peak_concurrent,*transaction.rollbacks*,*page_cache.hit*,*page_cache.page_faults,*page_cache.usage_ratio,*vm.file.descriptors.count,*vm.gc.time.*,*vm.heap.used,*vm.memory.buffer.direct.used,*vm.memory.pool.g1_eden_space,*vm.memory.pool.g1_old_gen,*vm.pause_time,*vm.thread*,*db.query.execution*,*protocol*
```
<!-- END GENERATED: default-filter -->

It enables a subset of the metrics. The **Default filter** column of the
[compatibility matrix](compatibility-matrix.md) says for each metric whether the
default enables it. To get every metric, set `server.metrics.filter=*`; to add a
group, append its pattern to the default, for example `,*cypher_cache*` for the
Cypher cache metrics, which the default does not match
([why](differences.md#cypher-cache-metrics-and-the-default-filter)).

## Metric names

A global metric is named `<prefix>.dbms.<metric>` and a database metric
`<prefix>.database.<db>.<metric>`, as Neo4j's reference describes, for example
`neo4j.dbms.vm.heap.used` and `neo4j.database.neo4j.transaction.committed`. Database
metrics appear when a database starts and disappear when it stops or is dropped.
JVM instance names (`<gc>`, `<pool>`, `<bufferpool>`) are the JVM's names in lower
case, each run of other characters replaced by `_`, as in the manual's own examples
`g1_eden_space` and `g1_old_gen`; in the Neo4j pools metrics `<pool>` is one of Neo4j's
memory pools, such as `bolt`, `transaction` or `page_cache`.

## Reading the metrics

**CSV files.** One file per metric in `server.directories.metrics`, named by the full
metric name: `neo4j.dbms.vm.heap.used.csv`. The first column `t` is the time in epoch
seconds; a gauge adds `value`, a counter `count`, and a histogram its count and
statistics. Rotated files are `<name>.csv.1` (newest) to `<name>.csv.N`, with `.zip`
or `.gz` appended when compressed. `neo4j-admin server report` includes the directory
under the `metrics` classifier.

**JMX.** Each metric is the MBean `<prefix>.metrics:name=<full name>`. A gauge has
the attribute `Value`, a counter `Count`, and a histogram `Count` plus percentile
attributes such as `99thPercentile`:

```cypher
CALL dbms.queryJmx('neo4j.metrics:name=neo4j.dbms.vm.heap.used')
```

**Prometheus.** With `server.metrics.prometheus.enabled=true`, `GET /metrics` on the
endpoint returns every registered metric. The name is the full name with every
character other than letters, digits and `:` replaced by `_`. Counters are
`counter` families with the suffix `_total`
(`neo4j_database_neo4j_transaction_committed_total`), gauges keep the name
(`neo4j_dbms_vm_heap_used`), and histograms are summaries with the quantiles 0.5,
0.75, 0.95, 0.98, 0.99 and 0.999 and a `_count` sample. Neo4j's advice applies: never
expose the endpoint to the Internet directly.

**Graphite.** With `server.metrics.graphite.enabled=true`, every interval DozerDB
connects to the Graphite server and sends one line per value, the path being the full
metric name (`neo4j.dbms.vm.heap.used 123456789 1761800000`). Counters and histograms
add a segment per value (`.count`, `.p99`, ...). A Graphite server that is down is
logged once and tried again at every interval.

Where these formats go beyond what Neo4j documents, the
[differences page](differences.md#output-formats) says what they rest on.
