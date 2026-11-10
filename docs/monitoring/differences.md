# Differences from Neo4j metrics

<!-- Blocks between BEGIN GENERATED and END GENERATED are written by scripts/metrics-docs.py from the metric
inventory and the settings declaration. Change those sources and run the script; do not edit the blocks. -->

<!-- BEGIN GENERATED: version -->
This page tracks the Neo4j Operations Manual **5** (*Monitoring* page) for the DozerDB **5.26** release line: DozerDB releases built on Neo4j `5.26.33` (`5.26.33-<dozerdb-version>`).
<!-- END GENERATED: version -->

This page collects every place where DozerDB's metrics differ from what the Neo4j
Operations Manual documents, or where the manual leaves a choice open and DozerDB had
to make one. The first part describes behaviour that applies across metrics; the
second lists every metric that is not Compatible, grouped by tier and reason. The
[compatibility matrix](compatibility-matrix.md) has every metric.

DozerDB has not been compared with the output of a running Neo4j Enterprise Edition
server. Where the manual is silent, DozerDB follows, in this order, Neo4j's open
source code (the names and attributes that cypher-shell and the Browser read), the
conventions of the metrics library Neo4j uses (Dropwizard Metrics) together with
Neo4j's last open source metrics code, and public Grafana dashboards built for Neo4j.

## Behaviour across metrics

### A metrics failure never stops the server

Invalid metrics settings stop the server at startup, as in Neo4j. Anything that goes
wrong afterwards does not: a collector that throws, a CSV directory that cannot be
written, or a Prometheus port that is already in use logs one error line in
`debug.log` and switches that part off, while the databases keep serving and the other
exporters keep running. Neo4j's extension loader would otherwise stop the server, and
Neo4j's last open source metrics code did stop it for an unusable port or directory;
DozerDB deliberately does not, because monitoring should never be the cause of an
outage. An unreachable Graphite server is not a failure at all: it is logged once and
tried again at every interval.

### Metrics that stay at zero

Some metrics read a Neo4j counter that only an Enterprise Edition mechanism drives,
such as the checkpoint IO limiter, the page cache prefetcher, multi-version
stores, or the pipelined and parallel Cypher runtimes; two query
routing counters need a cluster or a remote database alias. DozerDB still emits these
metrics with their documented names and types, so dashboards and alerts built for
Neo4j find them, and they read zero. They are tiered Partial, with the mechanism named
in the reason, rather than counted as supported or left out.

Partial is also the tier of a few counters that Neo4j Community does drive but whose
event cannot be provoked on purpose, such as a page cache read failure or a database
panic: DozerDB counts them, but no test has observed one move.

### Histograms

Histograms (`transaction.tx_size_heap` and `tx_size_native`, the query execution
latencies, the Bolt `worker_thread_bound_time`) keep a sample of recent values in Dropwizard Metrics'
default reservoir, which favours the last few minutes, and report percentiles over
that sample. Whether Neo4j Enterprise Edition samples the same way is not
established, so these metrics are Partial.

### Cypher cache metrics and the default filter

The manual names the Cypher cache metrics `<prefix>.cypher_cache_<cache>_<counter>`
(for example `cypher_cache_ast_hits_total`), and DozerDB registers exactly those
names per database. The default `server.metrics.filter` contains `*cypher.cache*`,
which matches none of them, so by default only `cypher.replan_events` of the Cypher
group is enabled. Add `*cypher_cache*` to the filter to get the cache metrics. Because
`_total` is part of the documented name, their Prometheus names end in `_total_total`.

`compiled_total` counts what the manual describes: a query's plan replaced by one that
compiles expressions into code after the query has run repeatedly. Neo4j Community
does this in the executable query cache, so that cache's `compiled_total` moves on
DozerDB although the manual says the metric is not available in Community Edition;
the other caches never do it and stay at zero.

### Data count metric names

The manual spells the node, relationship and relationship type counts two ways: the
reference table writes `<prefix>.neo4j.count.node`, and the descriptions of the
`ids_in_use` metrics point at the same names, while the essential metrics page writes
`<prefix>.database.<db>.count.node`. DozerDB emits the reference table's spelling,
`<prefix>.database.<db>.neo4j.count.node`. A dashboard written against the essential
page's spelling finds nothing.

As the essential metrics page says, these counts are not enabled by default: the
default `server.metrics.filter` has no pattern that matches them. Add
`*.neo4j.count.*` to the filter to get all three.

### Output formats

The manual does not document CSV file names, the Prometheus text or the Graphite
attribute segments. DozerDB uses:

- **CSV**: one file per metric named by the full metric name, prefix included
  (`neo4j.dbms.vm.heap.used.csv`), with a `t` column of epoch seconds and the metric
  library's columns; archives `<name>.csv.1` (newest) to `<name>.csv.N`, with `.zip` or
  `.gz` appended when compressed, a ZIP archive holding one file named like the live
  file. Neo4j's last open source metrics code named files the same way and did not
  compress them.
- **Prometheus**: counters as `counter` families with `_total`, gauges as `gauge`
  families, histograms as `summary` families with six quantiles and `_count` but no
  `_sum`, no labels and no added prefix. This is what public Grafana dashboards for
  Neo4j query and what third-party captures of Neo4j 4.4 and 5.11 output show. The
  endpoint answers only `/metrics`; other paths return 404. One metric follows the
  reference against such a capture: `check_point.flushed_bytes` is a counter, as the
  reference documents it, so DozerDB serves
  `neo4j_database_<db>_check_point_flushed_bytes_total`, while the Neo4j 5.11 capture
  shows a gauge `neo4j_database_<db>_check_point_flushed_bytes`. A dashboard built on
  that capture needs the `_total` name.
- **Graphite**: the path is the full metric name with the prefix once
  (`neo4j.dbms.vm.heap.used`), and counters and histograms add the metric library's
  segments (`.count`, `.max`, `.mean`, `.min`, `.stddev`, `.p50` to `.p999`).
- **JVM instance names**: the manual shows `g1_eden_space`, `g1_old_gen` and `direct`;
  DozerDB derives every other name the same way (lower case, other characters as
  `_`), for example `codeheap_non_nmethods`.

### Database operation and state counts

The `db.operation.count.*` metrics count from the moment the metrics start. Neo4j
5.26 starts the databases it starts at boot, `system` and the default database among
them, before the metrics start, so none of them is counted as created or started; the
counts begin with the first database operation after startup, such as `CREATE
DATABASE` or `START DATABASE`. A database counts as created the first time the server
initialises it, not again when it is started after a stop; dropping an online
database counts a stop and a drop. A start or stop that fails raises no event DozerDB
can observe, so `db.operation.count.failed` counts database panics only. The
`db.state.count.*` metrics are read from the server's database states whenever they
are reported, so they cover every database, those started at boot included, and
`db.state.count.failed` sees every failed database.

### Other notes

- The file descriptor metrics exist only where the JVM reports file descriptors,
  which excludes Windows.
- The Bolt `accumulated_queue_time` counter, which the manual labels an unsupported
  feature, counts under the default settings.
- Neo4j Ops Manager is not part of DozerDB and has not been tried with it.

## Metrics that are not Compatible

<!-- BEGIN GENERATED: differences -->
### Partial (42)

Emitted with the documented name and type, but the value differs from Neo4j, stays at zero on DozerDB, or could not be verified.

**Dropwizard default reservoir; distribution semantics not established against Enterprise.**

- `<prefix>.dbms.bolt.worker_thread_bound_time` (Operations Manual 5, *Metrics reference*, Bolt metrics)
- `<prefix>.database.<db>.db.query.execution.latency.millis` (Operations Manual 5, *Metrics reference*, Query execution metrics)
- `<prefix>.database.<db>.db.query.execution.slotted.latency.millis` (Operations Manual 5, *Metrics reference*, Query execution metrics)
- `<prefix>.database.<db>.transaction.tx_size_heap` (Operations Manual 5, *Metrics reference*, Database transaction metrics)
- `<prefix>.database.<db>.transaction.tx_size_native` (Operations Manual 5, *Metrics reference*, Database transaction metrics)

**Counts only with the internal setting `internal.server.bolt.response_metrics=true`, which installs Neo4j's response metrics listener; 0 under default settings.**

- `<prefix>.dbms.bolt.response_success` (Operations Manual 5, *Metrics reference*, Bolt metrics)
- `<prefix>.dbms.bolt.response_ignored` (Operations Manual 5, *Metrics reference*, Bolt metrics)

**Counts only with the internal setting `internal.server.bolt.response_metrics=true`, which installs Neo4j's response metrics listener; 0 under default settings; one counter over every error code.**

- `<prefix>.dbms.bolt.response_failed` (Operations Manual 5, *Metrics reference*, Bolt metrics)

**Neo4j recompiles with expression code generation only in the executable query cache, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven.**

- `<prefix>.database.<db>.cypher_cache_ast_compiled_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)
- `<prefix>.database.<db>.cypher_cache_execution_plan_compiled_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)
- `<prefix>.database.<db>.cypher_cache_logical_plan_compiled_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)
- `<prefix>.database.<db>.cypher_cache_preparser_compiled_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)

**Neo4j checks staleness only in the executable query and logical plan caches, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven.**

- `<prefix>.database.<db>.cypher_cache_ast_stale_entries_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)
- `<prefix>.database.<db>.cypher_cache_execution_plan_stale_entries_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)
- `<prefix>.database.<db>.cypher_cache_preparser_stale_entries_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)

**Counts only with the internal setting `internal.cypher.enable_runtime_monitors=true`, which attaches Neo4j's execution plan cache tracer; 0 under default settings.**

- `<prefix>.database.<db>.cypher_cache_execution_plan_cache_flushes_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)
- `<prefix>.database.<db>.cypher_cache_execution_plan_discards_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)
- `<prefix>.database.<db>.cypher_cache_execution_plan_hits_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)
- `<prefix>.database.<db>.cypher_cache_execution_plan_misses_total` (Operations Manual 5, *Metrics reference*, Cypher metrics)

**Reads the checkpoint IO limiter's limit and pauses of the last checkpoint; Community has no IO limiter, so the value stays zero.**

- `<prefix>.database.<db>.check_point.limit_millis` (Operations Manual 5, *Metrics reference*, Database checkpointing metrics)
- `<prefix>.database.<db>.check_point.limit_times` (Operations Manual 5, *Metrics reference*, Database checkpointing metrics)
- `<prefix>.database.<db>.check_point.io_limit` (Operations Manual 5, *Metrics reference*, Database checkpointing metrics)

**A DROP DATABASE of an online database counts one stop as well as one drop, because Neo4j stops the database before dropping it and announces that stop with the same event as STOP DATABASE; whether Neo4j counts it as a stop is not established.**

- `<prefix>.dbms.db.operation.count.stop` (Operations Manual 5, *Metrics reference*, Database operation count metrics)

**Counts database panics, the one failure the public database events report; a failed start or stop raises no event and no test provokes a panic, so the measured behaviour is unproven.**

- `<prefix>.dbms.db.operation.count.failed` (Operations Manual 5, *Metrics reference*, Database operation count metrics)

**Counts the next successful start of a database whose panic was counted as failed; no test provokes a panic, so the measured behaviour is unproven.**

- `<prefix>.dbms.db.operation.count.recovered` (Operations Manual 5, *Metrics reference*, Database operation count metrics)

**Reads Neo4j's own failure counter; no test provokes a page cache IO failure, so the measured behaviour is unproven.**

- `<prefix>.dbms.page_cache.eviction_exceptions` (Operations Manual 5, *Metrics reference*, Database page cache metrics)
- `<prefix>.dbms.page_cache.page_fault_failures` (Operations Manual 5, *Metrics reference*, Database page cache metrics)

**Counts pins of cursors opened with the no-fault flag; Community opens none, so the counter stays zero.**

- `<prefix>.dbms.page_cache.page_cancelled_faults` (Operations Manual 5, *Metrics reference*, Database page cache metrics)

**Counts the page prefetcher's vectored faults; Community's prefetcher is disabled, so the counter stays zero.**

- `<prefix>.dbms.page_cache.page_vectored_faults` (Operations Manual 5, *Metrics reference*, Database page cache metrics)
- `<prefix>.dbms.page_cache.page_vectored_faults_failures` (Operations Manual 5, *Metrics reference*, Database page cache metrics)
- `<prefix>.dbms.page_cache.page_no_pin_page_faults` (Operations Manual 5, *Metrics reference*, Database page cache metrics)

**Counts checkpoint IO limiter throttling; Community has no IO limiter, so the counter stays zero.**

- `<prefix>.dbms.page_cache.throttled.times` (Operations Manual 5, *Metrics reference*, Database page cache metrics)
- `<prefix>.dbms.page_cache.throttled.millis` (Operations Manual 5, *Metrics reference*, Database page cache metrics)

**Counts multi-version page copies; Community's stores are single-version, so the counter stays zero.**

- `<prefix>.dbms.page_cache.pages_copied` (Operations Manual 5, *Metrics reference*, Database page cache metrics)

**Counts queries executed by the parallel runtime; Community has no parallel runtime, so the counter stays zero.**

- `<prefix>.database.<db>.db.query.execution.parallel.success` (Operations Manual 5, *Metrics reference*, Query execution metrics)
- `<prefix>.database.<db>.db.query.execution.parallel.failure` (Operations Manual 5, *Metrics reference*, Query execution metrics)

**Dropwizard default reservoir; distribution semantics not established against Enterprise; Community has no parallel runtime, so the histogram stays empty.**

- `<prefix>.database.<db>.db.query.execution.parallel.latency.millis` (Operations Manual 5, *Metrics reference*, Query execution metrics)

**Counts queries executed by the pipelined runtime; Community has no pipelined runtime, so the counter stays zero.**

- `<prefix>.database.<db>.db.query.execution.pipelined.success` (Operations Manual 5, *Metrics reference*, Query execution metrics)
- `<prefix>.database.<db>.db.query.execution.pipelined.failure` (Operations Manual 5, *Metrics reference*, Query execution metrics)

**Dropwizard default reservoir; distribution semantics not established against Enterprise; Community has no pipelined runtime, so the histogram stays empty.**

- `<prefix>.database.<db>.db.query.execution.pipelined.latency.millis` (Operations Manual 5, *Metrics reference*, Query execution metrics)

**Counts queries routed to another member of the same cluster; DozerDB runs as a single instance, so the counter stays zero.**

- `<prefix>.dbms.routing.query.count.remote_internal` (Operations Manual 5, *Metrics reference*, Query routing metrics)

**Counts queries routed to a remote database alias; DozerDB has no remote database aliases, so the counter stays zero.**

- `<prefix>.dbms.routing.query.count.remote_external` (Operations Manual 5, *Metrics reference*, Query routing metrics)

### Unsupported (0)

Not emitted although a single server could have it.

No metric of this release line has this tier.

### Not applicable (66)

Not emitted because it measures a capability a single-instance server does not have (clustering).

**Clustering metric; a single-instance server never emits it.**

- `<prefix>.database.<db>.cluster.catchup.tx_pull_requests_received` (Operations Manual 5, *Metrics reference*, Catch-up metrics)
- `<prefix>.database.<db>.cluster.discovery.replicated_data` (Operations Manual 5, *Metrics reference*, Discovery metrics v1)
- `<prefix>.database.<db>.cluster.discovery.cluster.members` (Operations Manual 5, *Metrics reference*, Discovery metrics v1)
- `<prefix>.database.<db>.cluster.discovery.cluster.unreachable` (Operations Manual 5, *Metrics reference*, Discovery metrics v1)
- `<prefix>.database.<db>.cluster.discovery.cluster.converged` (Operations Manual 5, *Metrics reference*, Discovery metrics v1)
- `<prefix>.database.<db>.cluster.discovery.restart.success_count` (Operations Manual 5, *Metrics reference*, Discovery metrics v1)
- `<prefix>.database.<db>.cluster.discovery.restart.failed_count` (Operations Manual 5, *Metrics reference*, Discovery metrics v1)
- `<prefix>.database.<db>.cluster.discovery.memberset.reachable` (Operations Manual 5, *Metrics reference*, Discovery metrics v2)
- `<prefix>.database.<db>.cluster.discovery.memberset.unreachable` (Operations Manual 5, *Metrics reference*, Discovery metrics v2)
- `<prefix>.database.<db>.causal_clustering.core.append_index` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.commit_index` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.applied_index` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.term` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.tx_retries` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.is_leader` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.total_bytes` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.max_bytes` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.element_count` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.max_elements` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.hits` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.misses` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.lag` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.bytes` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.size` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.async_put` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.sync_put` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.message_processing_delay` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.message_processing_timer` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.replication_new` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.replication_attempt` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.replication_fail` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.replication_maybe` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.replication_success` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.causal_clustering.core.last_leader_message` (Operations Manual 5, *Metrics reference*, Raft core metrics)
- `<prefix>.database.<db>.cluster.raft.append_index` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.commit_index` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.applied_index` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.prune_index` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.term` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.tx_retries` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.is_leader` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.in_flight_cache.total_bytes` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.in_flight_cache.max_bytes` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.in_flight_cache.element_count` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.in_flight_cache.max_elements` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.in_flight_cache.hits` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.in_flight_cache.misses` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.lag` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.bytes` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.size` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.async_put` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.sync_put` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.message_processing_delay` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.message_processing_timer` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.replication_new` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.replication_attempt` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.replication_fail` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.replication_maybe` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.replication_success` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.cluster.raft.last_leader_message` (Operations Manual 5, *Metrics reference*, Raft metrics)
- `<prefix>.database.<db>.causal_clustering.read_replica.pull_updates` (Operations Manual 5, *Metrics reference*, Read Replica metrics)
- `<prefix>.database.<db>.causal_clustering.read_replica.pull_update_highest_tx_id_requested` (Operations Manual 5, *Metrics reference*, Read Replica metrics)
- `<prefix>.database.<db>.causal_clustering.read_replica.pull_update_highest_tx_id_received` (Operations Manual 5, *Metrics reference*, Read Replica metrics)
- `<prefix>.database.<db>.cluster.store_copy.pull_updates` (Operations Manual 5, *Metrics reference*, Store copy metrics)
- `<prefix>.database.<db>.cluster.store_copy.pull_update_highest_tx_id_requested` (Operations Manual 5, *Metrics reference*, Store copy metrics)
- `<prefix>.database.<db>.cluster.store_copy.pull_update_highest_tx_id_received` (Operations Manual 5, *Metrics reference*, Store copy metrics)
<!-- END GENERATED: differences -->
