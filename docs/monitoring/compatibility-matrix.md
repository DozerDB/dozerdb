# Metrics compatibility matrix

<!-- Blocks between BEGIN GENERATED and END GENERATED are written by scripts/metrics-docs.py from the metric
inventory and the settings declaration. Change those sources and run the script; do not edit the blocks. -->

<!-- BEGIN GENERATED: version -->
This page tracks the Neo4j Operations Manual **5** ([monitoring](https://neo4j.com/docs/operations-manual/5/monitoring/)) for the DozerDB **5.26** release line: DozerDB `5.26.32.<revision>`, built on Neo4j `5.26.32`.
<!-- END GENERATED: version -->

Every metric in Neo4j's
[metrics reference](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/),
in the reference's sections and order, with its compatibility tier on DozerDB. What a
metric measures is described on Neo4j's page: each row links to its section there.
The matrix is generated from DozerDB's metric inventory
(`metrics/src/main/resources/org/dozerdb/metrics/inventory/metrics-inventory.tsv`), the
same file the DozerDB test suite checks the running server against.

Columns:

- **Metric**: the full name. `<prefix>` is `server.metrics.prefix` (`neo4j` by
  default), `<db>` a database name, and `<gc>`, `<pool>` and `<bufferpool>` the names
  of the JVM's garbage collectors, memory pools and buffer pools, or in the Neo4j pools
  sections the names of Neo4j's memory pools (`bolt`, `transaction`, ...), one metric
  each. The
  Prometheus name replaces every character other than letters, digits and `:` with
  `_` and adds `_total` to a counter.
- **Type**: as Neo4j documents it, with the unit where the reference states one.
- **Scope**: `global` for one metric per server, `database` for one per database.
- **Tier**: `Compatible`, `Partial`, `Unsupported` or `Not applicable`; see
  [Compatibility at a glance](README.md#compatibility-at-a-glance).
- **Default filter**: `yes` when the default `server.metrics.filter` enables the
  metric, `some` when it enables only particular instances (for example the
  `g1_eden_space` memory pool), `no` when the filter must be widened, `-` for a metric
  DozerDB does not emit.
- **Note**: Neo4j's lifecycle label, and for a metric that is not Compatible the
  reason, which the [differences page](differences.md) also collects.

<!-- BEGIN GENERATED: matrix -->
| Section | Compatible | Partial | Unsupported | Not applicable |
| --- | --- | --- | --- | --- |
| [Bolt metrics](#bolt-metrics) | 10 | 4 | 0 | 0 |
| [Bolt Driver metrics](#bolt-driver-metrics) | 4 | 0 | 0 | 0 |
| [Cypher metrics](#cypher-metrics) | 26 | 11 | 0 | 0 |
| [Database checkpointing metrics](#database-checkpointing-metrics) | 6 | 3 | 0 | 0 |
| [Database data count metrics](#database-data-count-metrics) | 3 | 0 | 0 | 0 |
| [Database neo4j pools metrics](#database-neo4j-pools-metrics) | 5 | 0 | 0 | 0 |
| [Database operation count metrics](#database-operation-count-metrics) | 3 | 3 | 0 | 0 |
| [Database state count metrics](#database-state-count-metrics) | 3 | 0 | 0 | 0 |
| [Database data metrics](#database-data-metrics) | 4 | 0 | 0 | 0 |
| [Global neo4j pools metrics](#global-neo4j-pools-metrics) | 5 | 0 | 0 | 0 |
| [Database page cache metrics](#database-page-cache-metrics) | 15 | 9 | 0 | 0 |
| [Query execution metrics](#query-execution-metrics) | 4 | 8 | 0 | 0 |
| [Query routing metrics](#query-routing-metrics) | 1 | 2 | 0 | 0 |
| [Database store size metrics](#database-store-size-metrics) | 3 | 0 | 0 | 0 |
| [Database transaction log metrics](#database-transaction-log-metrics) | 6 | 0 | 0 | 0 |
| [Database transaction metrics](#database-transaction-metrics) | 16 | 2 | 0 | 0 |
| [Database index metrics](#database-index-metrics) | 12 | 0 | 0 | 0 |
| [Server metrics](#server-metrics) | 2 | 0 | 0 | 0 |
| [Catch-up metrics](#catch-up-metrics) | 0 | 0 | 0 | 1 |
| [Discovery metrics v1](#discovery-metrics-v1) | 0 | 0 | 0 | 6 |
| [Discovery metrics v2](#discovery-metrics-v2) | 0 | 0 | 0 | 2 |
| [Raft core metrics](#raft-core-metrics) | 0 | 0 | 0 | 25 |
| [Raft metrics](#raft-metrics) | 0 | 0 | 0 | 26 |
| [Read Replica metrics](#read-replica-metrics) | 0 | 0 | 0 | 3 |
| [Store copy metrics](#store-copy-metrics) | 0 | 0 | 0 | 3 |
| [JVM file descriptor metrics](#jvm-file-descriptor-metrics) | 2 | 0 | 0 | 0 |
| [GC metrics](#gc-metrics) | 2 | 0 | 0 | 0 |
| [JVM Heap metrics](#jvm-heap-metrics) | 3 | 0 | 0 | 0 |
| [JVM memory buffers metrics](#jvm-memory-buffers-metrics) | 3 | 0 | 0 | 0 |
| [JVM memory pools metrics](#jvm-memory-pools-metrics) | 1 | 0 | 0 | 0 |
| [JVM pause time metrics](#jvm-pause-time-metrics) | 1 | 0 | 0 | 0 |
| [JVM threads metrics](#jvm-threads-metrics) | 1 | 0 | 0 | 0 |
| **Total** | **141** | **42** | **0** | **66** |

## Bolt metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.bolt.connections_opened` | counter | global | Compatible | yes |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.connections_closed` | counter | global | Compatible | yes |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.connections_running` | gauge | global | Compatible | yes |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.connections_idle` | gauge | global | Compatible | yes |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.messages_received` | counter | global | Compatible | yes |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.messages_started` | counter | global | Compatible | yes |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.messages_done` | counter | global | Compatible | no |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.messages_failed` | counter | global | Compatible | no |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.accumulated_queue_time` | counter (ms) | global | Compatible | no | Neo4j labels it an unsupported feature. | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.accumulated_processing_time` | counter (ms) | global | Compatible | no |  | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.worker_thread_bound_time` | histogram (ms) | global | Partial | no | Introduced in 5.21. Dropwizard default reservoir; distribution semantics not established against Enterprise. | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.response_success` | counter | global | Partial | no | Neo4j labels it an unsupported feature. Counts only with the internal setting `internal.server.bolt.response_metrics=true`, which installs Neo4j's response metrics listener; 0 under default settings. | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.response_ignored` | counter | global | Partial | no | Neo4j labels it an unsupported feature. Counts only with the internal setting `internal.server.bolt.response_metrics=true`, which installs Neo4j's response metrics listener; 0 under default settings. | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |
| `<prefix>.dbms.bolt.response_failed` | counter | global | Partial | no | Neo4j labels it an unsupported feature. Counts only with the internal setting `internal.server.bolt.response_metrics=true`, which installs Neo4j's response metrics listener; 0 under default settings; one counter over every error code. | [Bolt metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics) |

## Bolt Driver metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.bolt_driver.api.managed_transaction_function_calls` | counter | global | Compatible | no | Introduced in 5.13. | [Bolt Driver metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-driver-metrics) |
| `<prefix>.dbms.bolt_driver.api.unmanaged_transaction_calls` | counter | global | Compatible | no | Introduced in 5.13. | [Bolt Driver metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-driver-metrics) |
| `<prefix>.dbms.bolt_driver.api.implicit_transaction_calls` | counter | global | Compatible | no | Introduced in 5.13. | [Bolt Driver metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-driver-metrics) |
| `<prefix>.dbms.bolt_driver.api.execute_calls` | counter | global | Compatible | no | Introduced in 5.13. | [Bolt Driver metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-driver-metrics) |

## Cypher metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.cypher.replan_events` | counter | database | Compatible | yes |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher.replan_wait_time` | counter (s) | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_ast_cache_flushes_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_ast_compiled_total` | counter | database | Partial | no | Neo4j recompiles with expression code generation only in the executable query cache, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_ast_discards_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_ast_entries` | gauge | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_ast_hits_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_ast_misses_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_ast_stale_entries_total` | counter | database | Partial | no | Neo4j checks staleness only in the executable query and logical plan caches, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_executable_query_cache_flushes_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_executable_query_compiled_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_executable_query_discards_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_executable_query_entries` | gauge | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_executable_query_hits_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_executable_query_misses_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_executable_query_stale_entries_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_execution_plan_cache_flushes_total` | counter | database | Partial | no | Counts only with the internal setting `internal.cypher.enable_runtime_monitors=true`, which attaches Neo4j's execution plan cache tracer; 0 under default settings. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_execution_plan_compiled_total` | counter | database | Partial | no | Neo4j recompiles with expression code generation only in the executable query cache, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_execution_plan_discards_total` | counter | database | Partial | no | Counts only with the internal setting `internal.cypher.enable_runtime_monitors=true`, which attaches Neo4j's execution plan cache tracer; 0 under default settings. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_execution_plan_entries` | gauge | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_execution_plan_hits_total` | counter | database | Partial | no | Counts only with the internal setting `internal.cypher.enable_runtime_monitors=true`, which attaches Neo4j's execution plan cache tracer; 0 under default settings. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_execution_plan_misses_total` | counter | database | Partial | no | Counts only with the internal setting `internal.cypher.enable_runtime_monitors=true`, which attaches Neo4j's execution plan cache tracer; 0 under default settings. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_execution_plan_stale_entries_total` | counter | database | Partial | no | Neo4j checks staleness only in the executable query and logical plan caches, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_logical_plan_cache_flushes_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_logical_plan_compiled_total` | counter | database | Partial | no | Neo4j recompiles with expression code generation only in the executable query cache, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_logical_plan_discards_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_logical_plan_entries` | gauge | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_logical_plan_hits_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_logical_plan_misses_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_logical_plan_stale_entries_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_preparser_cache_flushes_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_preparser_compiled_total` | counter | database | Partial | no | Neo4j recompiles with expression code generation only in the executable query cache, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_preparser_discards_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_preparser_entries` | gauge | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_preparser_hits_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_preparser_misses_total` | counter | database | Compatible | no |  | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |
| `<prefix>.database.<db>.cypher_cache_preparser_stale_entries_total` | counter | database | Partial | no | Neo4j checks staleness only in the executable query and logical plan caches, so this cache's counter stays zero; no event can move it, so the measured behaviour is unproven. | [Cypher metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#cypher-metrics) |

## Database checkpointing metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.check_point.events` | counter | database | Compatible | yes |  | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |
| `<prefix>.database.<db>.check_point.total_time` | counter (ms) | database | Compatible | yes |  | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |
| `<prefix>.database.<db>.check_point.duration` | gauge (ms) | database | Compatible | yes |  | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |
| `<prefix>.database.<db>.check_point.flushed_bytes` | counter (bytes) | database | Compatible | yes | Introduced in 5.10. | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |
| `<prefix>.database.<db>.check_point.limit_millis` | gauge (ms) | database | Partial | yes | Reads the checkpoint IO limiter's limit and pauses of the last checkpoint; Community has no IO limiter, so the value stays zero. | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |
| `<prefix>.database.<db>.check_point.limit_times` | gauge | database | Partial | yes | Reads the checkpoint IO limiter's limit and pauses of the last checkpoint; Community has no IO limiter, so the value stays zero. | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |
| `<prefix>.database.<db>.check_point.pages_flushed` | gauge | database | Compatible | yes |  | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |
| `<prefix>.database.<db>.check_point.io_performed` | gauge | database | Compatible | yes |  | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |
| `<prefix>.database.<db>.check_point.io_limit` | gauge | database | Partial | yes | Reads the checkpoint IO limiter's limit and pauses of the last checkpoint; Community has no IO limiter, so the value stays zero. | [Database checkpointing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#checkpointing-metrics) |

## Database data count metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.neo4j.count.relationship` | gauge | database | Compatible | no |  | [Database data count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-data-count-metrics) |
| `<prefix>.database.<db>.neo4j.count.node` | gauge | database | Compatible | no |  | [Database data count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-data-count-metrics) |
| `<prefix>.database.<db>.neo4j.count.relationship_types` | gauge | database | Compatible | no | Introduced in 5.15. | [Database data count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-data-count-metrics) |

## Database neo4j pools metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.pool.<pool>.<db>.used_heap` | gauge (bytes) | database | Compatible | some |  | [Database neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-neo4j-pool-metrics) |
| `<prefix>.database.<db>.pool.<pool>.<db>.used_native` | gauge (bytes) | database | Compatible | some |  | [Database neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-neo4j-pool-metrics) |
| `<prefix>.database.<db>.pool.<pool>.<db>.total_used` | gauge (bytes) | database | Compatible | some |  | [Database neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-neo4j-pool-metrics) |
| `<prefix>.database.<db>.pool.<pool>.<db>.total_size` | gauge | database | Compatible | no |  | [Database neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-neo4j-pool-metrics) |
| `<prefix>.database.<db>.pool.<pool>.<db>.free` | gauge (bytes) | database | Compatible | no |  | [Database neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-neo4j-pool-metrics) |

## Database operation count metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.db.operation.count.create` | counter | global | Compatible | no |  | [Database operation count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-operation-count-metrics) |
| `<prefix>.dbms.db.operation.count.start` | counter | global | Compatible | no |  | [Database operation count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-operation-count-metrics) |
| `<prefix>.dbms.db.operation.count.stop` | counter | global | Partial | no | A DROP DATABASE of an online database counts one stop as well as one drop, because Neo4j stops the database before dropping it and announces that stop with the same event as STOP DATABASE; whether Neo4j counts it as a stop is not established. | [Database operation count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-operation-count-metrics) |
| `<prefix>.dbms.db.operation.count.drop` | counter | global | Compatible | no |  | [Database operation count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-operation-count-metrics) |
| `<prefix>.dbms.db.operation.count.failed` | counter | global | Partial | no | Counts database panics, the one failure the public database events report; a failed start or stop raises no event and no test provokes a panic, so the measured behaviour is unproven. | [Database operation count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-operation-count-metrics) |
| `<prefix>.dbms.db.operation.count.recovered` | counter | global | Partial | no | Counts the next successful start of a database whose panic was counted as failed; no test provokes a panic, so the measured behaviour is unproven. | [Database operation count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-operation-count-metrics) |

## Database state count metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.db.state.count.hosted` | gauge | global | Compatible | no | Introduced in 5.7. The manual does not state the scope. | [Database state count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-state-count-metrics) |
| `<prefix>.dbms.db.state.count.failed` | gauge | global | Compatible | no | Introduced in 5.7. The manual does not state the scope. | [Database state count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-state-count-metrics) |
| `<prefix>.dbms.db.state.count.desired_started` | gauge | global | Compatible | no | Introduced in 5.7. The manual does not state the scope. | [Database state count metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-state-count-metrics) |

## Database data metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.ids_in_use.relationship_type` | gauge | database | Compatible | yes | Deprecated in 5.15. | [Database data metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-data-metrics) |
| `<prefix>.database.<db>.ids_in_use.property` | gauge | database | Compatible | yes | Deprecated in 5.15. | [Database data metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-data-metrics) |
| `<prefix>.database.<db>.ids_in_use.relationship` | gauge | database | Compatible | yes | Deprecated in 5.15. | [Database data metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-data-metrics) |
| `<prefix>.database.<db>.ids_in_use.node` | gauge | database | Compatible | yes | Deprecated in 5.15. | [Database data metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-data-metrics) |

## Global neo4j pools metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.pool.<pool>.used_heap` | gauge (bytes) | global | Compatible | some |  | [Global neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#global-neo4j-pools-metrics) |
| `<prefix>.dbms.pool.<pool>.used_native` | gauge (bytes) | global | Compatible | no |  | [Global neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#global-neo4j-pools-metrics) |
| `<prefix>.dbms.pool.<pool>.total_used` | gauge (bytes) | global | Compatible | some |  | [Global neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#global-neo4j-pools-metrics) |
| `<prefix>.dbms.pool.<pool>.total_size` | gauge | global | Compatible | some |  | [Global neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#global-neo4j-pools-metrics) |
| `<prefix>.dbms.pool.<pool>.free` | gauge (bytes) | global | Compatible | some |  | [Global neo4j pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#global-neo4j-pools-metrics) |

## Database page cache metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.page_cache.eviction_exceptions` | counter | global | Partial | no | Reads Neo4j's own failure counter; no test provokes a page cache IO failure, so the measured behaviour is unproven. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.flushes` | counter | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.merges` | counter | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.unpins` | counter | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.pins` | counter | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.evictions` | counter | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.evictions.cooperative` | counter | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.eviction.flushes` | counter | global | Compatible | no | Introduced in 5.17. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.eviction.cooperative.flushes` | counter | global | Compatible | no | Introduced in 5.17. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.page_faults` | counter | global | Compatible | yes |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.page_fault_failures` | counter | global | Partial | no | Reads Neo4j's own failure counter; no test provokes a page cache IO failure, so the measured behaviour is unproven. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.page_cancelled_faults` | counter | global | Partial | no | Counts pins of cursors opened with the no-fault flag; Community opens none, so the counter stays zero. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.page_vectored_faults` | counter | global | Partial | no | Counts the page prefetcher's vectored faults; Community's prefetcher is disabled, so the counter stays zero. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.page_vectored_faults_failures` | counter | global | Partial | no | Counts the page prefetcher's vectored faults; Community's prefetcher is disabled, so the counter stays zero. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.page_no_pin_page_faults` | counter | global | Partial | no | Counts the page prefetcher's vectored faults; Community's prefetcher is disabled, so the counter stays zero. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.hits` | counter | global | Compatible | yes |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.hit_ratio` | gauge (ratio) | global | Compatible | yes |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.usage_ratio` | gauge (ratio) | global | Compatible | yes |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.bytes_read` | counter (bytes) | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.bytes_written` | counter (bytes) | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.iops` | counter | global | Compatible | no |  | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.throttled.times` | counter | global | Partial | no | Counts checkpoint IO limiter throttling; Community has no IO limiter, so the counter stays zero. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.throttled.millis` | counter (ms) | global | Partial | no | Counts checkpoint IO limiter throttling; Community has no IO limiter, so the counter stays zero. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |
| `<prefix>.dbms.page_cache.pages_copied` | counter | global | Partial | no | Counts multi-version page copies; Community's stores are single-version, so the counter stays zero. | [Database page cache metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-page-cache-metrics) |

## Query execution metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.db.query.execution.success` | counter | database | Compatible | yes |  | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.failure` | counter | database | Compatible | yes |  | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.latency.millis` | histogram (ms) | database | Partial | yes | Dropwizard default reservoir; distribution semantics not established against Enterprise. | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.parallel.success` | counter | database | Partial | yes | Counts queries executed by the parallel runtime; Community has no parallel runtime, so the counter stays zero. | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.parallel.failure` | counter | database | Partial | yes | Counts queries executed by the parallel runtime; Community has no parallel runtime, so the counter stays zero. | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.parallel.latency.millis` | histogram (ms) | database | Partial | yes | Dropwizard default reservoir; distribution semantics not established against Enterprise; Community has no parallel runtime, so the histogram stays empty. | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.pipelined.success` | counter | database | Partial | yes | Counts queries executed by the pipelined runtime; Community has no pipelined runtime, so the counter stays zero. | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.pipelined.failure` | counter | database | Partial | yes | Counts queries executed by the pipelined runtime; Community has no pipelined runtime, so the counter stays zero. | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.pipelined.latency.millis` | histogram (ms) | database | Partial | yes | Dropwizard default reservoir; distribution semantics not established against Enterprise; Community has no pipelined runtime, so the histogram stays empty. | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.slotted.success` | counter | database | Compatible | yes |  | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.slotted.failure` | counter | database | Compatible | yes |  | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |
| `<prefix>.database.<db>.db.query.execution.slotted.latency.millis` | histogram (ms) | database | Partial | yes | Dropwizard default reservoir; distribution semantics not established against Enterprise. | [Query execution metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-execution-metrics) |

## Query routing metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.routing.query.count.local` | counter | global | Compatible | no | Introduced in 5.10. | [Query routing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-routing-metrics) |
| `<prefix>.dbms.routing.query.count.remote_internal` | counter | global | Partial | no | Introduced in 5.10. Counts queries routed to another member of the same cluster; DozerDB runs as a single instance, so the counter stays zero. | [Query routing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-routing-metrics) |
| `<prefix>.dbms.routing.query.count.remote_external` | counter | global | Partial | no | Introduced in 5.10. Counts queries routed to a remote database alias; DozerDB has no remote database aliases, so the counter stays zero. | [Query routing metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#query-routing-metrics) |

## Database store size metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.store.size.total` | gauge (bytes) | database | Compatible | yes | The manual does not state the scope. | [Database store size metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-store-size-metrics) |
| `<prefix>.database.<db>.store.size.database` | gauge (bytes) | database | Compatible | yes | The manual does not state the scope. | [Database store size metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-store-size-metrics) |
| `<prefix>.database.<db>.store.size.available_reserved` | gauge (bytes) | database | Compatible | yes | Introduced in 5.21. The manual does not state the scope. | [Database store size metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-store-size-metrics) |

## Database transaction log metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.log.rotation_events` | counter | database | Compatible | no |  | [Database transaction log metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-log-metrics) |
| `<prefix>.database.<db>.log.rotation_total_time` | counter (ms) | database | Compatible | no |  | [Database transaction log metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-log-metrics) |
| `<prefix>.database.<db>.log.rotation_duration` | gauge (ms) | database | Compatible | no |  | [Database transaction log metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-log-metrics) |
| `<prefix>.database.<db>.log.appended_bytes` | counter (bytes) | database | Compatible | no |  | [Database transaction log metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-log-metrics) |
| `<prefix>.database.<db>.log.flushes` | counter | database | Compatible | no |  | [Database transaction log metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-log-metrics) |
| `<prefix>.database.<db>.log.append_batch_size` | gauge | database | Compatible | no |  | [Database transaction log metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-log-metrics) |

## Database transaction metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.transaction.started` | counter | database | Compatible | no |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.peak_concurrent` | counter | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.active` | gauge | database | Compatible | no |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.active_read` | gauge | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.active_write` | gauge | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.committed` | counter | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.committed_read` | counter | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.committed_write` | counter | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.rollbacks` | counter | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.rollbacks_read` | counter | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.rollbacks_write` | counter | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.terminated` | counter | database | Compatible | no |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.terminated_read` | counter | database | Compatible | no |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.terminated_write` | counter | database | Compatible | no |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.last_committed_tx_id` | counter | database | Compatible | yes |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.last_closed_tx_id` | counter | database | Compatible | no |  | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.tx_size_heap` | histogram (bytes) | database | Partial | no | Dropwizard default reservoir; distribution semantics not established against Enterprise. | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |
| `<prefix>.database.<db>.transaction.tx_size_native` | histogram (bytes) | database | Partial | no | Dropwizard default reservoir; distribution semantics not established against Enterprise. | [Database transaction metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-tx-metrics) |

## Database index metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.index.fulltext.queried` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.fulltext.populated` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.lookup.queried` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.lookup.populated` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.text.queried` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.text.populated` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.range.queried` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.range.populated` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.point.queried` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.point.populated` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.vector.queried` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |
| `<prefix>.database.<db>.index.vector.populated` | counter | database | Compatible | no | The manual does not state the scope. | [Database index metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#db-index-metrics) |

## Server metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.server.threads.jetty.idle` | gauge | global | Compatible | no |  | [Server metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#server-metrics) |
| `<prefix>.dbms.server.threads.jetty.all` | gauge | global | Compatible | no |  | [Server metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#server-metrics) |

## Catch-up metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.cluster.catchup.tx_pull_requests_received` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Catch-up metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#catchup-metrics) |

## Discovery metrics v1

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.cluster.discovery.replicated_data` | gauge | database | Not applicable | - | Deprecated in 5.23. Clustering metric; a single-instance server never emits it. | [Discovery metrics v1](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#discovery-service-V1) |
| `<prefix>.database.<db>.cluster.discovery.cluster.members` | gauge | database | Not applicable | - | Deprecated in 5.23. Clustering metric; a single-instance server never emits it. | [Discovery metrics v1](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#discovery-service-V1) |
| `<prefix>.database.<db>.cluster.discovery.cluster.unreachable` | gauge | database | Not applicable | - | Deprecated in 5.23. Clustering metric; a single-instance server never emits it. | [Discovery metrics v1](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#discovery-service-V1) |
| `<prefix>.database.<db>.cluster.discovery.cluster.converged` | gauge | database | Not applicable | - | Deprecated in 5.23. Clustering metric; a single-instance server never emits it. | [Discovery metrics v1](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#discovery-service-V1) |
| `<prefix>.database.<db>.cluster.discovery.restart.success_count` | gauge | database | Not applicable | - | Deprecated in 5.23. Clustering metric; a single-instance server never emits it. | [Discovery metrics v1](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#discovery-service-V1) |
| `<prefix>.database.<db>.cluster.discovery.restart.failed_count` | gauge | database | Not applicable | - | Deprecated in 5.23. Clustering metric; a single-instance server never emits it. | [Discovery metrics v1](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#discovery-service-V1) |

## Discovery metrics v2

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.cluster.discovery.memberset.reachable` | gauge | database | Not applicable | - | Introduced in 5.22. Clustering metric; a single-instance server never emits it. | [Discovery metrics v2](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#discovery-service-V2) |
| `<prefix>.database.<db>.cluster.discovery.memberset.unreachable` | gauge | database | Not applicable | - | Introduced in 5.22. Clustering metric; a single-instance server never emits it. | [Discovery metrics v2](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#discovery-service-V2) |

## Raft core metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.causal_clustering.core.append_index` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.commit_index` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.applied_index` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.term` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.tx_retries` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.is_leader` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.total_bytes` | gauge (bytes) | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.max_bytes` | gauge (bytes) | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.element_count` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.max_elements` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.hits` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.in_flight_cache.misses` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.lag` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.bytes` | gauge (bytes) | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.size` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.async_put` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.raft_log_entry_prefetch_buffer.sync_put` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.message_processing_delay` | gauge | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.message_processing_timer` | timer | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.replication_new` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.replication_attempt` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.replication_fail` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.replication_maybe` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.replication_success` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |
| `<prefix>.database.<db>.causal_clustering.core.last_leader_message` | gauge (ms) | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Raft core metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-core-metrics) |

## Raft metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.cluster.raft.append_index` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.commit_index` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.applied_index` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.prune_index` | gauge | database | Not applicable | - | Introduced in 5.25. Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.term` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.tx_retries` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.is_leader` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.in_flight_cache.total_bytes` | gauge (bytes) | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.in_flight_cache.max_bytes` | gauge (bytes) | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.in_flight_cache.element_count` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.in_flight_cache.max_elements` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.in_flight_cache.hits` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.in_flight_cache.misses` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.lag` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.bytes` | gauge (bytes) | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.size` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.async_put` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.raft_log_entry_prefetch_buffer.sync_put` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.message_processing_delay` | gauge | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.message_processing_timer` | timer | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.replication_new` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.replication_attempt` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.replication_fail` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.replication_maybe` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.replication_success` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |
| `<prefix>.database.<db>.cluster.raft.last_leader_message` | gauge (ms) | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Raft metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#raft-metrics) |

## Read Replica metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.causal_clustering.read_replica.pull_updates` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Read Replica metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#read-replica-metrics) |
| `<prefix>.database.<db>.causal_clustering.read_replica.pull_update_highest_tx_id_requested` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Read Replica metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#read-replica-metrics) |
| `<prefix>.database.<db>.causal_clustering.read_replica.pull_update_highest_tx_id_received` | counter | database | Not applicable | - | Deprecated in 5.0. Clustering metric; a single-instance server never emits it. | [Read Replica metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#read-replica-metrics) |

## Store copy metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.database.<db>.cluster.store_copy.pull_updates` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Store copy metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#store-copy-metrics) |
| `<prefix>.database.<db>.cluster.store_copy.pull_update_highest_tx_id_requested` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Store copy metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#store-copy-metrics) |
| `<prefix>.database.<db>.cluster.store_copy.pull_update_highest_tx_id_received` | counter | database | Not applicable | - | Clustering metric; a single-instance server never emits it. | [Store copy metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#store-copy-metrics) |

## JVM file descriptor metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.vm.file.descriptors.count` | gauge | global | Compatible | yes |  | [JVM file descriptor metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-file-descriptor-metrics) |
| `<prefix>.dbms.vm.file.descriptors.maximum` | gauge | global | Compatible | no |  | [JVM file descriptor metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-file-descriptor-metrics) |

## GC metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.vm.gc.time.<gc>` | counter (ms) | global | Compatible | yes |  | [GC metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#gc-metrics) |
| `<prefix>.dbms.vm.gc.count.<gc>` | counter | global | Compatible | no |  | [GC metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#gc-metrics) |

## JVM Heap metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.vm.heap.committed` | gauge (bytes) | global | Compatible | no |  | [JVM Heap metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-heap-metrics) |
| `<prefix>.dbms.vm.heap.used` | gauge (bytes) | global | Compatible | yes |  | [JVM Heap metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-heap-metrics) |
| `<prefix>.dbms.vm.heap.max` | gauge (bytes) | global | Compatible | no |  | [JVM Heap metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-heap-metrics) |

## JVM memory buffers metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.vm.memory.buffer.<bufferpool>.count` | gauge | global | Compatible | no |  | [JVM memory buffers metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-memory-buffers-metrics) |
| `<prefix>.dbms.vm.memory.buffer.<bufferpool>.used` | gauge | global | Compatible | some |  | [JVM memory buffers metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-memory-buffers-metrics) |
| `<prefix>.dbms.vm.memory.buffer.<bufferpool>.capacity` | gauge | global | Compatible | no |  | [JVM memory buffers metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-memory-buffers-metrics) |

## JVM memory pools metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.vm.memory.pool.<pool>` | gauge (bytes) | global | Compatible | some |  | [JVM memory pools metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-memory-pools-metrics) |

## JVM pause time metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.vm.pause_time` | counter | global | Compatible | yes |  | [JVM pause time metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-pause-time-metrics) |

## JVM threads metrics

| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |
| --- | --- | --- | --- | --- | --- | --- |
| `<prefix>.dbms.vm.threads` | gauge | global | Compatible | yes |  | [JVM threads metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#jvm-threads-metrics) |
<!-- END GENERATED: matrix -->
