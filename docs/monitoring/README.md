# Monitoring DozerDB

<!-- Blocks between BEGIN GENERATED and END GENERATED are written by scripts/metrics-docs.py from the metric
inventory and the settings declaration, and the query log block by scripts/query-log-docs.py from the query log's
field inventory. Change those sources and run the scripts; do not edit the blocks. -->

<!-- BEGIN GENERATED: version -->
This page tracks the Neo4j Operations Manual **5** (*Monitoring* page) for the DozerDB **5.26** release line: DozerDB releases built on Neo4j `5.26.33` (`5.26.33-<dozerdb-version>`).
<!-- END GENERATED: version -->

DozerDB provides the metrics that the Neo4j Operations Manual documents for Neo4j
Enterprise Edition: the `server.metrics.*` settings with Neo4j's defaults, the
documented metric names and types, and the four ways to read them: CSV files, JMX
MBeans, a Prometheus endpoint and a Graphite exporter. The plugin JAR, the
standalone distribution and the Docker image behave the same way. A `neo4j.conf`
written for Neo4j's metrics starts DozerDB unchanged, and dashboards and alerts
built on Neo4j's metric names find every metric DozerDB emits under the same name.
DozerDB also writes the query log that the manual documents for Enterprise
Edition: the `db.logs.query.*` settings with Neo4j's defaults, `query.log` with
Neo4j's plain-text line or JSON fields, on by default
([query logging](query-logging.md)).

DozerDB is an independent open source project. Neo4j, Inc. has not reviewed or
endorsed this documentation. It links to Neo4j's documentation because DozerDB
follows it; the descriptions of the metrics are Neo4j's, on Neo4j's pages.

## Pages

- [Enable and expose metrics](enable-and-expose.md): the settings, their defaults and
  how DozerDB applies them, the default filter, and how each exporter names the metrics.
- [Compatibility matrix](compatibility-matrix.md): every metric of Neo4j's metrics
  reference with its type, scope, compatibility tier and whether the default filter
  enables it.
- [Differences from Neo4j](differences.md): every metric that is not fully compatible,
  with the reason, and the places where DozerDB behaves differently on purpose.
- [Query logging](query-logging.md): what applies, the settings, the JSON switch,
  where `query.log` lives in each delivery form, the Docker variables, the cost
  under load and where DozerDB differs.
- [Query log compatibility](query-log-compatibility.md): every documented setting,
  field and plain-text segment with its tier, and the shapes DozerDB defines where
  Neo4j's documentation is silent, each with an example line.

## Which Neo4j pages apply

| Neo4j Operations Manual page | Applies to DozerDB |
| --- | --- |
| *Metrics* (Operations Manual 5) | Yes, except the mention of Neo4j Ops Manager, which DozerDB does not include or support. |
| *Essential metrics* (Operations Manual 5) | Yes, except the Raft leader metric of the cluster health section, which a single server does not have. The node and relationship totals are emitted as `neo4j.count.*`, the reference table's spelling, and, as the page says, are not enabled by default ([details](differences.md#data-count-metric-names)). |
| *Enable metrics logging* (Operations Manual 5) | Yes. |
| *Expose metrics* (Operations Manual 5) | Yes for CSV files, JMX MBeans, Graphite and Prometheus; not the Neo4j Ops Manager section. |
| *Metrics reference* (Operations Manual 5) | Metric by metric, as the [compatibility matrix](compatibility-matrix.md) shows. |
| *Configuration settings* (Operations Manual 5), the `server.metrics.*` entries and `server.directories.metrics` | Yes: every one is accepted with Neo4j's default, type and validation ([settings](enable-and-expose.md#settings)). |
| *Logging* under Monitoring, the *Configure the query log* section | Yes: the settings, the detail levels, the plain-text line and the JSON fields; the shapes the section leaves open are DozerDB's ([query logging](query-logging.md)). |
| *Logging* under Monitoring, the *Default logging configuration* section | Yes: `server-logs.xml`, the `QueryLog` appender, the rotation and the JSON layout switch are Neo4j's own and unchanged ([the JSON layout](query-logging.md#the-json-layout)). |
| *Configuration settings*, the `db.logs.query.*` entries | Yes: every one is accepted with Neo4j's default, type and validation, and changes at runtime through `dbms.setConfigValue` ([settings](query-logging.md#settings)). |

The other chapters of the Monitoring section (the other logs, query, transaction
and connection management, and the rest) are outside the scope of these pages.

## Compatibility at a glance

Every metric in Neo4j's reference has exactly one compatibility tier.

<!-- BEGIN GENERATED: tier-summary -->
| Tier | Metrics | Meaning |
| --- | --- | --- |
| Compatible | 141 | Emitted with the documented name and type; a test provokes the real event and observes the change. |
| Partial | 42 | Emitted with the documented name and type, but the value differs from Neo4j, stays at zero on DozerDB, or could not be verified. The reason is on the [differences page](differences.md). |
| Unsupported | 0 | Not emitted although a single server could have it. The reason is on the [differences page](differences.md). |
| Not applicable | 66 | Not emitted because it measures a capability a single-instance server does not have (clustering). |
| **All documented metrics** | **249** | |
<!-- END GENERATED: tier-summary -->

## Query log at a glance

Every documented query log setting, field and plain-text segment has exactly one
compatibility tier; the [compatibility page](query-log-compatibility.md) has the
rows and the shapes.

<!-- BEGIN GENERATED: query-log-tier-summary -->
| Tier | Settings | Fields and segments | Meaning |
| --- | --- | --- | --- |
| Compatible | 10 | 20 | The Operations Manual's description holds on DozerDB unchanged: the same name, default, placement and value. |
| Partial | 1 | 15 | Honoured or written with a recorded difference: a shape the manual leaves open and DozerDB defined, or a value this release line cannot supply the same way. The reason is in the Note column and the shape under [Shapes DozerDB defines](query-log-compatibility.md#shapes-dozerdb-defines). |
| Unsupported | 1 | 0 | Not read although DozerDB could read it, with the reason recorded. |
| Not applicable | 0 | 1 | A value that does not exist on this release line, which DozerDB therefore never writes. |
| **All documented** | **12** | **36** | |
<!-- END GENERATED: query-log-tier-summary -->

## Where DozerDB differs

Each point is one sentence here; the [differences page](differences.md) has the
detail for metrics, and the [query logging page](query-logging.md#where-dozerdb-differs)
lists the query log's points.

- A metrics problem at runtime never stops the server: a failing collector or
  exporter logs one error and switches itself off
  ([details](differences.md#a-metrics-failure-never-stops-the-server)).
- Some metrics are emitted but stay at zero, because the Neo4j mechanism that
  drives them exists only in Enterprise Edition or in a cluster
  ([details](differences.md#metrics-that-stay-at-zero)).
- Histograms keep a sample of recent values whose statistics have not been compared
  with Neo4j Enterprise Edition ([details](differences.md#histograms)).
- The default filter enables none of the Cypher cache metrics, as the documented
  default would on Neo4j; widen the filter to get them
  ([details](differences.md#cypher-cache-metrics-and-the-default-filter)).
- The node and relationship totals carry the reference table's `neo4j.count`
  segment, not the essential page's `count`, and the default filter does not
  enable them ([details](differences.md#data-count-metric-names)).
- `check_point.flushed_bytes` is a counter, as the reference documents it, so its
  Prometheus name ends in `_total`; published output of Neo4j 5.11 shows it as a
  gauge without the suffix ([details](differences.md#output-formats)).
- The CSV file names, the Prometheus text and the Graphite paths follow the
  conventions Neo4j uses where it documents them and public evidence where it does
  not; they have not been compared with Neo4j Enterprise Edition output
  ([details](differences.md#output-formats)).
- Clustering metrics are not emitted
  ([details](differences.md#metrics-that-are-not-compatible)).
