# Query logging

<!-- Blocks between BEGIN GENERATED and END GENERATED are written by scripts/query-log-docs.py from the field
inventory (scripts/fixtures/query-log-entries.json). Change the inventory and run the script; do not edit the
blocks. Neo4j's documentation is mentioned by manual version and page title and never linked: the script's check
fails on any link to Neo4j's site in this page. -->

<!-- BEGIN GENERATED: version -->
This page tracks the Neo4j Operations Manual **5** for the DozerDB **5.26** release line: DozerDB releases built on Neo4j `5.26.32` (`5.26.32-<dozerdb-version>`).
<!-- END GENERATED: version -->

DozerDB writes the query log that the Neo4j Operations Manual documents for Neo4j
Enterprise Edition: every `db.logs.query.*` setting with Neo4j's name, default,
type and validation, the file `query.log` in the logs directory with Neo4j's
rotation, Neo4j's plain-text line or, through `server-logs.xml`, Neo4j's JSON
fields, entries for queries and for transactions, and changes at runtime through
`dbms.setConfigValue`. The log is on by default, as in Neo4j: with no setting at
all, DozerDB writes a start entry and an end entry for every query at the
`VERBOSE` detail level. The plugin JAR, the standalone archives and the Docker
image behave the same way, and a `neo4j.conf` written for Neo4j's query log
starts DozerDB unchanged.

DozerDB is an independent open source project. Neo4j, Inc. has not reviewed or
endorsed this documentation. The descriptions of the settings and fields are
Neo4j's, in Neo4j's manual; this page says what applies to DozerDB and where
DozerDB differs. Neo4j's documentation is named here by manual version and page
title and never linked, so that these pages stay right when Neo4j moves a page.

## Which Neo4j pages apply

| Neo4j Operations Manual 5 | Applies to DozerDB |
| --- | --- |
| *Logging* under Monitoring, the *Configure the query log* section | Yes: the settings, the detail levels and the threshold, the plain-text examples, the JSON fields, the transaction metadata convention. The page marks the query log as an Enterprise Edition feature; on DozerDB it is free. Where the section leaves a shape open, DozerDB's own shape is on the [compatibility page](query-log-compatibility.md#shapes-dozerdb-defines); where Neo4j 5.26 reports less than its example shows, the difference is there too. |
| *Logging* under Monitoring, the *Default logging configuration* section | Yes: `server-logs.xml`, the `QueryLogger` logger and the `QueryLog` appender, the rotation at 20 MB keeping 7 files, and the JSON layout switch, are Neo4j's own and unchanged. |
| *Configuration settings* reference, the `db.logs.query.*` entries | Yes, every one, with Neo4j's default, type, validation and dynamic marking ([settings](#settings)). |
| *Update dynamic settings* under Configuration | Yes: every `db.logs.query.*` setting changes at runtime through `CALL dbms.setConfigValue(...)`, which DozerDB provides ([procedures](../procedures.md)). |
| *Neo4j Admin report* under Tools (`neo4j-admin server report`) | Yes for collecting the logs: the report carries `query.log` as it stands. The Enterprise `--obfuscate-query-log` option does not exist. |

## Settings

DozerDB reads Neo4j Community's own declarations of the eleven `db.logs.query.*`
settings and declares none of its own, so a setting has exactly Neo4j's name,
default, type and validation; an invalid value stops the server at startup with
Neo4j's usual settings error. Every setting is dynamic: a change through
`CALL dbms.setConfigValue('db.logs.query.enabled', 'INFO')` applies to the next
query without a restart and is lost at the next restart
([`dbms.setConfigValue`](../procedures.md#dbmssetconfigvalue)). The legacy
`dbms.logs.query.*` names of Neo4j 4 are migrated by Neo4j's own setting
migrator, as on Neo4j. The DozerDB column says how DozerDB applies a setting
where the manual's description leaves room, and names the one setting DozerDB
does not read; the manual's description is not repeated here.

<!-- BEGIN GENERATED: settings -->
| Setting | Default | Dynamic | DozerDB |
| --- | --- | --- | --- |
| `db.logs.query.enabled` | `VERBOSE` | yes | Read from Neo4j's own declaration, so the name, the default and the three values are Neo4j's. VERBOSE writes a start entry and an end entry for every query; INFO writes an end entry for every query whose elapsed time reaches `db.logs.query.threshold` and for every failed query; OFF writes nothing and costs one volatile read per monitor event. A change at runtime applies to the next query. |
| `db.logs.query.threshold` | `0s` | yes | Compared with the query's elapsed time at INFO: a query at or above the threshold is logged, so the default 0s logs every query, sub-millisecond ones included. A failed query is logged whatever the threshold. Ignored at VERBOSE, as documented. |
| `db.logs.query.transaction.enabled` | `OFF` | yes | OFF by default, as in Neo4j, so enabling the query log alone adds no transaction entry. VERBOSE writes a start entry and an end entry for every transaction; INFO writes an end entry for every transaction open for at least `db.logs.query.transaction.threshold` and for every rollback caused by a failure. Every kernel transaction is a candidate, the system database's and Neo4j's internal ones included. The plain-text shape of a transaction entry is DozerDB's (see the shapes DozerDB defines). |
| `db.logs.query.transaction.threshold` | `0s` | yes | Compared with how long the transaction had been open, measured at its commit or rollback, at INFO; a rollback caused by a failure is logged whatever the threshold. Ignored at VERBOSE. |
| `db.logs.query.parameter_logging_enabled` | `true` | yes | false leaves the parameters out of the entry: the plain segment goes with its separator and the JSON key is absent, never an empty {}. |
| `db.logs.query.max_parameter_length` | `2147483647` | yes | Applied to each parameter value, as the manual says: a value longer than the maximum is cut to that many characters and ... is appended inside the value, a string keeping its closing quote, so the entry still reads as a Cypher map. |
| `db.logs.query.obfuscate_literals` | `false` | yes | Neo4j's own obfuscation of the query text: Community computes the obfuscated text once per compiled query and caches it with the query, so a change reaches a cached query after CALL db.clearQueryCaches(), as documented; passwords are masked at every setting, and a query that cannot be parsed has its text withheld, as the manual says. Parameter values are not obfuscated by this setting: they are written as they are, except that a password parameter is masked at every setting; to keep parameter values out of the log set `db.logs.query.parameter_logging_enabled=false`. |
| `db.logs.query.obfuscate_errors` | `false` | yes | Neo4j 5.26 has no obfuscated status description, so the standard description of the GQLSTATUS code (condition and sub-condition, without the message) stands in for the failure reason and at every level of errorInfo; no stack trace follows the entry, as on the calendar line. |
| `db.logs.query.plan_description_enabled` | `false` | yes | Attaches Neo4j's own EXPLAIN rendering of the plan to the end entry: on the lines after the plain line, and as the one JSON string queryPlan. A start entry never carries a plan and a query that failed to compile has none. The placement is DozerDB's (see the shapes DozerDB defines). |
| `db.logs.query.early_raw_logging_enabled` | `false` | yes | Moves the start entry to the moment the query is received, before parsing and obfuscation, so a query that hangs or fails in parsing is still recorded, with its raw text and parameters (a password literal in clear, as the manual says of the setting); the end entry of the same query follows the obfuscation settings as always, and `db.logs.query.parameter_logging_enabled` and `db.logs.query.max_parameter_length` still apply. The early start entry's shape is DozerDB's (see the shapes DozerDB defines). |
| `db.logs.query.annotation_data_format` | `CYPHER` | yes | Applies to the JSON layout only, as documented: CYPHER writes the annotation data as the Cypher-map string, JSON as an object, FLAT_JSON as an object whose nested maps are folded into dotted keys; keys are sorted in every format, and the plain line is always the Cypher map. CYPHER is the default of this line; Neo4j 2026 defaults to JSON. |
| `db.logs.query.annotation_data_as_json_enabled` | `false` | yes | Not read, although Neo4j 5.26 still declares it (deprecated since 5.12): a neo4j.conf that sets it starts and the value changes nothing; use `db.logs.query.annotation_data_format`, which replaced it. |
<!-- END GENERATED: settings -->

The `VERBOSE` default writes two entries per query. On a busy server set
`db.logs.query.enabled=INFO` with a `db.logs.query.threshold` to keep only the
end entries of slow queries, or `OFF` to write none; a failed query is logged at
`INFO` whatever the threshold. Where the log must not carry data values, set
`db.logs.query.obfuscate_literals=true` for the literals in the query text and
`db.logs.query.parameter_logging_enabled=false` for the parameter values, which
`obfuscate_literals` leaves as they are (only a password parameter is masked), and
`db.logs.query.obfuscate_errors=true` for the failure messages, as the manual
recommends for production.

## The JSON layout

The query log is written through Neo4j's own logging configuration, so the
manual's edit of `server-logs.xml` switches it to Neo4j's JSON layout with
Neo4j's field names and nothing else changes. In the `QueryLog` appender, replace
the `PatternLayout` line:

```xml
<JsonTemplateLayout eventTemplateUri="classpath:org/neo4j/logging/QueryLogJsonLayout.json"/>
```

The server reloads the file within 30 seconds, as Neo4j's configuration says
(`monitorInterval="30"`). Every entry is then one JSON object per line with the
keys of the manual's *Configure the query log* section; the
[compatibility page](query-log-compatibility.md#fields-and-segments) lists each
key with its tier, and `db.logs.query.annotation_data_format` chooses the form of
`annotationData`. Neo4j's shipped `neo4j.conf` raises the layout's string limit to
32 KiB, so a longer query text or plan is cut there with `…` and the entry stays
one object.

## Where the file is

The file is `query.log` in `server.directories.logs`, rotated at 20 MB with 7
files kept (`query.log.01` the newest archive), as Neo4j's shipped
`server-logs.xml` configures it; your own `server-logs.xml` applies as it stands.

- **Standalone archives**: `logs/query.log` under the unpacked directory; the
  shipped `conf/server-logs.xml` defines the appender.
- **Plugin JAR**: the Neo4j Community server's own logs directory and its own
  `conf/server-logs.xml`, which already defines the `QueryLogger` logger and the
  `QueryLog` appender; nothing is added to the configuration.
- **Docker image**: `/logs/query.log`. Mount `/logs` to keep the file
  (`-v "$HOME/dozerdb/logs:/logs"`). To switch the layout, mount a `/conf`
  directory holding the edited `server-logs.xml`, as the Neo4j image reads it.

Every setting reaches the Docker image as an environment variable the way the
Neo4j image maps settings: prefix `NEO4J_`, each `_` of the setting doubled, each
`.` replaced by `_`:

| Setting | Variable | Example value |
| --- | --- | --- |
| `db.logs.query.enabled` | `NEO4J_db_logs_query_enabled` | `INFO` |
| `db.logs.query.threshold` | `NEO4J_db_logs_query_threshold` | `500ms` |
| `db.logs.query.transaction.enabled` | `NEO4J_db_logs_query_transaction_enabled` | `VERBOSE` |
| `db.logs.query.parameter_logging_enabled` | `NEO4J_db_logs_query_parameter__logging__enabled` | `false` |
| `db.logs.query.obfuscate_literals` | `NEO4J_db_logs_query_obfuscate__literals` | `true` |
| `db.logs.query.max_parameter_length` | `NEO4J_db_logs_query_max__parameter__length` | `200` |
| `db.logs.query.annotation_data_format` | `NEO4J_db_logs_query_annotation__data__format` | `CYPHER` |

```sh
docker run --name dozerdb -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/your-password \
  -e NEO4J_db_logs_query_enabled=INFO -e NEO4J_db_logs_query_threshold=500ms \
  -v "$HOME/dozerdb/logs:/logs" \
  graphstack/dozerdb:5.26.32-1.2.0
tail -f "$HOME/dozerdb/logs/query.log"
```

## Cost under load

An entry is rendered once, on the thread that ends the query, and handed to
Log4j's `QueryLog` appender synchronously, as Neo4j does; with both detail levels
`OFF` the cost is one volatile read per monitor event. Under a heavy load of short
queries the query log's cost is the flush that Neo4j's shipped logging
configuration performs after every entry, not the entries themselves. Measured on
DozerDB 5.26.32-1.2.0 under sixteen clients issuing sub-millisecond queries in a
closed loop, the shipped configuration served 95 % fewer requests per second than
with the query log off (86 % with one server alone on the machine); a workload of
ordinary queries pays a fraction of that, since the flush is paid once per entry.

If the query log costs you throughput, edit the `QueryLog` appender in
`conf/server-logs.xml` (the file the *Default logging configuration* section of
the *Logging* page of the Operations Manual 5 describes) in two places and restart
the server:
add `immediateFlush="false"` to the `RollingRandomAccessFile`, and put a Log4j
`Async` appender in front of it, pointing the `QueryLogger` logger at the `Async`
appender instead. In the same measurement this configuration served under 1 %
fewer requests per second than with the query log off (1 % with one server alone), with
every entry still written and matched against the field inventory. The cost is
durability: if the server process dies, the entries still in the queue (at most
1024) and in the appender's 8 KiB buffer are lost, while a normal stop writes
them all; no entry is ever dropped silently under load (the queue blocks the
query's thread until it has room), and an entry that cannot be written goes to
`debug.log`. Do not set `immediateFlush="false"` without the `Async` appender: an
idle server's entries then wait in the buffer, invisible to `tail -f` and to a log
shipper, until 8 KiB of them exist.

```xml
<RollingRandomAccessFile name="QueryLog" fileName="${config:server.directories.logs}/query.log"
                         filePattern="$${config:server.directories.logs}/query.log.%02i" immediateFlush="false">
    <!-- the layout, policies and rollover strategy as shipped -->
</RollingRandomAccessFile>
<Async name="QueryLogAsync" bufferSize="1024" blocking="true" errorRef="DebugLog">
    <AppenderRef ref="QueryLog"/>
</Async>
```

```xml
<Logger name="QueryLogger" level="INFO" additivity="false">
    <AppenderRef ref="QueryLogAsync"/>
</Logger>
```

Neo4j Enterprise's query log passes through the same shipped appender, so the
same edit applies there.

## Where DozerDB differs

Each point is one sentence here; the
[compatibility page](query-log-compatibility.md) has every setting and field with
its tier, and shows each shape with an example line.

- Where the manual documents no plain-text shape, DozerDB defines one, favouring a
  complete audit record over byte-exactness with Neo4j Enterprise, and records the
  row as Partial ([shapes](query-log-compatibility.md#shapes-dozerdb-defines)).
- A start entry is the end entry's line behind `Query started: ` with zeroed
  figures ([details](query-log-compatibility.md#start-entry)); under
  `db.logs.query.early_raw_logging_enabled` it carries `transaction id:0` and the
  database `<none>` ([details](query-log-compatibility.md#early-start-entry)).
- A failed query is written at the Log4j level `ERROR`, the reason last and the
  stack trace on the lines after, whatever the threshold
  ([details](query-log-compatibility.md#failed-query-entry)).
- A transaction entry's plain-text line is DozerDB's, and a rollback caused by a
  failure is written at `ERROR` like a failed query
  ([details](query-log-compatibility.md#transaction-entries)).
- With `db.logs.query.parameter_logging_enabled=false` the parameters segment is
  dropped, not written as `{}`
  ([details](query-log-compatibility.md#parameters-the-cut-value-and-the-dropped-segment)).
- The plan table follows the entry on the lines after it in plain text and is one
  string in JSON ([details](query-log-compatibility.md#plan-table)).
- An entry that cannot be rendered or written never fails the query and never
  switches the log off: a fallback entry at `ERROR` names the query id and the
  exception in `query.log` itself
  ([details](query-log-compatibility.md#fallback-entry)).
- `db.logs.query.annotation_data_as_json_enabled`, deprecated since Neo4j 5.12
  and still declared by Neo4j 5.26, is accepted and not read; use
  `db.logs.query.annotation_data_format`.
- Neo4j 5.26 has no obfuscated status description, so under
  `db.logs.query.obfuscate_errors=true` the standard description of the
  GQLSTATUS code, without the message, stands in for it
  ([details](query-log-compatibility.md#failed-query-entry-under-dblogsqueryobfuscate_errors)).
- An error Neo4j 5.26 raises without a GQL status of its own, such as a variable
  not defined, carries the default `50N42` in `errorInfo` with no position, where
  the manual's example shows `42N62` with a position
  ([details](query-log-compatibility.md#error-without-a-gql-status-on-neo4j-526)).
- `neo4j-admin server report` collects `query.log` as it stands, with the other
  logs; the Enterprise `--obfuscate-query-log` option does not exist.
- `dbms.setConfigValue` is DozerDB's own implementation of Neo4j's procedure
  ([procedures](../procedures.md)).
