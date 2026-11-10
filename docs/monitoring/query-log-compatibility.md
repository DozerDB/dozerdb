# Query log compatibility

<!-- Blocks between BEGIN GENERATED and END GENERATED are written by scripts/query-log-docs.py from the field
inventory (scripts/fixtures/query-log-entries.json). Change the inventory and run the script; do not edit the
blocks. Neo4j's documentation is mentioned by manual version and page title and never linked: the script's check
fails on any link to Neo4j's site in this page. -->

<!-- BEGIN GENERATED: version -->
This page tracks the Neo4j Operations Manual **5** for the DozerDB **5.26** release line: DozerDB releases built on Neo4j `5.26.33` (`5.26.33-<dozerdb-version>`).
<!-- END GENERATED: version -->

Every setting, JSON field and plain-text segment of the query log that the Neo4j
Operations Manual documents (the *Configure the query log* section of the
*Logging* page under Monitoring and the `db.logs.query.*` entries of the *Configuration settings* reference), with
its compatibility tier on DozerDB, and every shape DozerDB defines where the
manual is silent, each with an example line. The tables are generated from
DozerDB's field inventory (`scripts/fixtures/query-log-entries.json`), the same
file the module's renderer tests and the release checks hold the running server
against, so the page cannot disagree with them. The
[query logging page](query-logging.md) says how to use the log.

DozerDB has not been compared with the output of a running Neo4j Enterprise
Edition server. A setting or field is **Compatible** when the manual's
description holds on DozerDB unchanged: the same name, default, placement and
value. Where the manual is silent, DozerDB follows, in this order, Neo4j's open
source code at the pinned version (the connection details, the obfuscation, the
parameter printer and the plan rendering are Neo4j Community's own), Neo4j's last
open source query logger read for its approach, and the conventions of the logging
library Neo4j uses; a shape settled that way is DozerDB's own, chosen for a
complete audit record over byte-exactness, and is **Partial** with an example line
below, never guessed Compatible. The one **Unsupported** row is the deprecated
setting DozerDB does not read; **Not applicable** marks a value this release line's
Neo4j does not have.

## Compatibility at a glance

<!-- BEGIN GENERATED: tier-summary -->
| Tier | Settings | Fields and segments | Meaning |
| --- | --- | --- | --- |
| Compatible | 10 | 20 | The Operations Manual's description holds on DozerDB unchanged: the same name, default, placement and value. |
| Partial | 1 | 15 | Honoured or written with a recorded difference: a shape the manual leaves open and DozerDB defined, or a value this release line cannot supply the same way. The reason is in the Note column and the shape under [Shapes DozerDB defines](#shapes-dozerdb-defines). |
| Unsupported | 1 | 0 | Not read although DozerDB could read it, with the reason recorded. |
| Not applicable | 0 | 1 | A value that does not exist on this release line, which DozerDB therefore never writes. |
| **All documented** | **12** | **36** | |
<!-- END GENERATED: tier-summary -->

## Settings

- **Setting**: Neo4j's name; the manual's *Configuration settings* reference
  describes it.
- **Default**: Neo4j's default on this release line, which DozerDB shares; `-` for
  a setting this line's Neo4j does not declare.
- **Dynamic**: whether `dbms.setConfigValue` changes it without a restart.
- **Tier** and **Note**: the tier and, for every row, how DozerDB applies the
  setting or why it differs.

<!-- BEGIN GENERATED: settings-matrix -->
| Setting | Default | Dynamic | Tier | Note |
| --- | --- | --- | --- | --- |
| `db.logs.query.enabled` | `VERBOSE` | yes | Compatible | Read from Neo4j's own declaration, so the name, the default and the three values are Neo4j's. VERBOSE writes a start entry and an end entry for every query; INFO writes an end entry for every query whose elapsed time reaches `db.logs.query.threshold` and for every failed query; OFF writes nothing and costs one volatile read per monitor event. A change at runtime applies to the next query. |
| `db.logs.query.threshold` | `0s` | yes | Compatible | Compared with the query's elapsed time at INFO: a query at or above the threshold is logged, so the default 0s logs every query, sub-millisecond ones included. A failed query is logged whatever the threshold. Ignored at VERBOSE, as documented. |
| `db.logs.query.transaction.enabled` | `OFF` | yes | Compatible | OFF by default, as in Neo4j, so enabling the query log alone adds no transaction entry. VERBOSE writes a start entry and an end entry for every transaction; INFO writes an end entry for every transaction open for at least `db.logs.query.transaction.threshold` and for every rollback caused by a failure. Every kernel transaction is a candidate, the system database's and Neo4j's internal ones included. The plain-text shape of a transaction entry is DozerDB's (see the shapes DozerDB defines). |
| `db.logs.query.transaction.threshold` | `0s` | yes | Compatible | Compared with how long the transaction had been open, measured at its commit or rollback, at INFO; a rollback caused by a failure is logged whatever the threshold. Ignored at VERBOSE. |
| `db.logs.query.parameter_logging_enabled` | `true` | yes | Compatible | false leaves the parameters out of the entry: the plain segment goes with its separator and the JSON key is absent, never an empty {}. |
| `db.logs.query.max_parameter_length` | `2147483647` | yes | Compatible | Applied to each parameter value, as the manual says: a value longer than the maximum is cut to that many characters and ... is appended inside the value, a string keeping its closing quote, so the entry still reads as a Cypher map. |
| `db.logs.query.obfuscate_literals` | `false` | yes | Compatible | Neo4j's own obfuscation of the query text: Community computes the obfuscated text once per compiled query and caches it with the query, so a change reaches a cached query after CALL db.clearQueryCaches(), as documented; passwords are masked at every setting, and a query that cannot be parsed has its text withheld, as the manual says. Parameter values are not obfuscated by this setting: they are written as they are, except that a password parameter is masked at every setting; to keep parameter values out of the log set `db.logs.query.parameter_logging_enabled=false`. |
| `db.logs.query.obfuscate_errors` | `false` | yes | Partial | Neo4j 5.26 has no obfuscated status description, so the standard description of the GQLSTATUS code (condition and sub-condition, without the message) stands in for the failure reason and at every level of errorInfo; no stack trace follows the entry, as on the calendar line. |
| `db.logs.query.plan_description_enabled` | `false` | yes | Compatible | Attaches Neo4j's own EXPLAIN rendering of the plan to the end entry: on the lines after the plain line, and as the one JSON string queryPlan. A start entry never carries a plan and a query that failed to compile has none. The placement is DozerDB's (see the shapes DozerDB defines). |
| `db.logs.query.early_raw_logging_enabled` | `false` | yes | Compatible | Moves the start entry to the moment the query is received, before parsing and obfuscation, so a query that hangs or fails in parsing is still recorded, with its raw text and parameters (a password literal in clear, as the manual says of the setting); the end entry of the same query follows the obfuscation settings as always, and `db.logs.query.parameter_logging_enabled` and `db.logs.query.max_parameter_length` still apply. The early start entry's shape is DozerDB's (see the shapes DozerDB defines). |
| `db.logs.query.annotation_data_format` | `CYPHER` | yes | Compatible | Applies to the JSON layout only, as documented: CYPHER writes the annotation data as the Cypher-map string, JSON as an object, FLAT_JSON as an object whose nested maps are folded into dotted keys; keys are sorted in every format, and the plain line is always the Cypher map. CYPHER is the default of this line; Neo4j 2026 defaults to JSON. |
| `db.logs.query.annotation_data_as_json_enabled` | `false` | yes | Unsupported | Not read, although Neo4j 5.26 still declares it (deprecated since 5.12): a neo4j.conf that sets it starts and the value changes nothing; use `db.logs.query.annotation_data_format`, which replaced it. |
<!-- END GENERATED: settings-matrix -->

## Fields and segments

- **Field**: the JSON key of the manual's *Configure the query log* section and the plain-text
  segment it appears as, `<name>` standing for the value; a row with one of the two
  exists in that layout only. The timestamp and the Log4j level before every entry
  are written by Neo4j's layout.
- **Layout**: `plain`, `json` or `both`.
- **Appears in**: the entry kinds (a query entry's `start`, `success` and `fail`;
  a transaction entry's `start`, `commit` and `rollback`) and the detail levels
  (`db.logs.query.enabled` or `db.logs.query.transaction.enabled`) the field is
  written at; `optional` when it may be absent even then, under a detail flag or
  because Neo4j reported no value.
- **Tier** and **Note**: the tier and, for every row, what DozerDB writes or why
  it differs.

<!-- BEGIN GENERATED: fields-matrix -->
### Fields of every entry

| Field | Layout | Appears in | Tier | Note |
| --- | --- | --- | --- | --- |
| `time` / `<time>` | both | start, success, fail, commit, rollback (INFO, VERBOSE) | Compatible | Written by Neo4j's layout: the shipped pattern %d{yyyy-MM-dd HH:mm:ss.SSSZ}{GMT+0} in plain text, the timestamp resolver in JSON. |
| `level` / `<level>` | both | start, success, fail, commit, rollback (INFO, VERBOSE) | Compatible | Written by Neo4j's layout (%-5p, so INFO is followed by two spaces) from the InternalLog method the entry is written with: INFO for start and success entries, ERROR for a failed-query entry, for a rollback caused by a failure and for a fallback entry. |
| `type` | json | start, success, fail, commit, rollback (INFO, VERBOSE) | Compatible | The constant the message puts in the field map: query for query entries, transaction for transaction entries. |
| `stacktrace` / `<stacktrace>` | both | fail, rollback (INFO, VERBOSE); optional | Compatible | Present when the entry carries a throwable, as the manual says: Log4j writes it on the lines after the plain line and as the JSON stacktrace field. DozerDB hands Neo4j's exception to Log4j for every failed-query entry, none under obfuscate_errors, and for every rollback Neo4j reports with a throwable. |

### Fields of a query entry

| Field | Layout | Appears in | Tier | Note |
| --- | --- | --- | --- | --- |
| `event` / `Query started: ` | both | start, success, fail (INFO, VERBOSE) | Partial | The manual's only start entry is a Neo4j 4.4 capture; a Neo4j 5 start entry is DozerDB's shape: the end grammar with zeroed values behind the documented 'Query started: ' prefix; under early_raw_logging_enabled the early start entry carries transaction id:0 and the database <none>. Success and failed-query entries carry no plain marker; JSON names the event. |
| `id` / `id:<id>` | both | start, success, fail (VERBOSE) | Compatible | The query id (ExecutingQuery.internalQueryId(), incremental from 1 per server start), written at VERBOSE only on both lines, as the manual's JSON field list says; the manual's plain-text field list calls the prefix a connection id and shows it under a stated INFO configuration, which DozerDB does not follow. |
| `transactionId` / `transaction id:<transactionId>` | both | start, success, fail (INFO, VERBOSE) | Compatible | The outer transaction's sequence number, the number SHOW TRANSACTIONS exposes; -1 for a query that failed before it was bound to a transaction. |
| `elapsedTimeMs` / `<elapsedTimeMs> ms` | both | start, success, fail (INFO, VERBOSE) | Compatible | Wall time from the query's start, microseconds truncated to milliseconds; 0 on a start entry. |
| `planning` / `(planning: <planning>, waiting: <waiting>)` | both | start, success, fail (INFO, VERBOSE) | Compatible | Compilation time in milliseconds, always present (the 4.4 toggle is gone); 0 on a start entry. |
| `cpu` | json | success, fail (INFO, VERBOSE); optional | Partial | JSON only, and only when `db.track_query_cpu_time=true`: Neo4j's snapshot has no CPU figure otherwise, and DozerDB never invents one. Never in the plain line. |
| `waiting` / `(planning: <planning>, waiting: <waiting>)` | both | start, success, fail (INFO, VERBOSE) | Compatible | Time spent waiting on locks or other queries, in milliseconds; 0 on a start entry. |
| `allocatedBytes` / `<allocatedBytes> B` | both | start, success, fail (INFO, VERBOSE) | Compatible | The heap high-water mark of the query; 0 on a start entry. |
| `pageHits` / `<pageHits> page hits, <pageFaults> page faults` | both | start, success, fail (INFO, VERBOSE) | Compatible | Page cache hits of the query's transactions; 0 on a start entry. |
| `pageFaults` / `<pageHits> page hits, <pageFaults> page faults` | both | start, success, fail (INFO, VERBOSE) | Compatible | Page cache faults of the query's transactions; 0 on a start entry. |
| `source` / `<source>` | both | start, success, fail (INFO, VERBOSE) | Partial | Community's ClientConnectionInfo.asConnectionDetails(): the Bolt shape is the manual's; the HTTP shape of the transactional endpoint (server-session, scheme, client host, request path) and the embedded shape (embedded-session plus its own tab) are shown by no 4.x+ manual example. A query over the Query API, which Neo4j serves over an in-process Bolt channel, carries the Bolt shape with neo4j-query-api/v2 as the client and local channel addresses. |
| `database` / `<database>` | both | start, success, fail (INFO, VERBOSE) | Partial | The database name after one tab, as documented; <none> for a query that was never bound to a database, which the manual documents for JSON only and DozerDB writes in the plain line too. |
| `executingUser` / `<executingUser>` | both | start, success, fail (INFO, VERBOSE) | Compatible | The executing user, the plain line's one username ('Username of the query executioner'); empty when authentication is disabled. |
| `authenticatedUser` | json | start, success, fail (INFO, VERBOSE); optional | Compatible | JSON only, as the manual lists it: the authenticated user beside executingUser, so impersonation is visible in JSON; the plain line shows the executing user alone and never the authenticated one. |
| `query` / `<query>` | both | start, success, fail (INFO, VERBOSE) | Compatible | The query text verbatim, its own line breaks included, obfuscated per `db.logs.query.obfuscate_literals` with passwords always masked; withheld under obfuscate_literals for a query that cannot be parsed, as the manual says. |
| `queryParameters` / `<queryParameters>` | both | start, success, fail (INFO, VERBOSE); optional | Partial | The parameters as Neo4j's PrettyPrinter single-line Cypher map ({} when there are none); `db.logs.query.max_parameter_length` truncates each value with ... inside the value, and parameter_logging_enabled=false drops the segment rather than writing {}. The manual shows {} only. |
| `runtime` / `runtime=<runtime>` | both | start, success, fail (INFO, VERBOSE); optional | Compatible | Community's lower-case runtime name, or null when the query never reached a runtime: on every start entry (as the manual shows) and on a query that failed before compilation. |
| `annotationData` / `<annotationData>` | both | start, success, fail (INFO, VERBOSE) | Compatible | The transaction's metadata (tx.setMetaData) as a Cypher map with unquoted keys and single-quoted strings in the plain line regardless of `db.logs.query.annotation_data_format`, which the manual says affects JSON only; keys sorted, since the manual's examples fix no order. |
| `failureReason` / `<failureReason>` | both | fail (INFO, VERBOSE); optional | Partial | The failed-query entry is DozerDB's shape: level ERROR, the success grammar plus ' - <failure reason>' last and the stack trace after; the manual documents no failed query line. Written whatever the threshold. The JSON field is deprecated upstream and kept beside errorInfo because parsers key on it. |
| `errorInfo` | json | fail (INFO, VERBOSE); optional | Partial | JSON only, as on the calendar line, with Neo4j 5.26's GQLSTATUS tree: an error Neo4j 5.26 raises without a GQL status of its own (a variable not defined, for instance, which the router reports before the query reaches a database) carries the default 50N42 with no position and no cause, where the manual's example shows 42N62 with a position; an error with a GQL status carries its tree and position as on the calendar line. obfuscatedStatusDescription() does not exist at 5.26, so under obfuscate_errors the standard description of the GQLSTATUS code (condition and sub-condition, no message) stands in at every level of the tree. |
| `queryPlan` / `<queryPlan>` | both | success, fail (INFO, VERBOSE); optional | Partial | Only under `db.logs.query.plan_description_enabled`: the text of the ExecutionPlanDescription (Neo4j's EXPLAIN rendering: header lines, the operator table, the summary) on the lines after the entry in plain text and one string with embedded line breaks in JSON; the manual documents the field's presence and names the table, but fixes neither the placement nor the JSON type. |
| `executionPlanCacheKeyHash` | json | start, success, fail (INFO, VERBOSE) | Compatible | JSON only: the plan cache key hash as eight upper-case hex digits, 00000000 for a start entry, as documented. |
| `queryLang` | json | start, success, fail (INFO, VERBOSE) | Not applicable | Neo4j 5.26 has no query language accessor on the snapshot, so the field is never written on the LTS line; DozerDB invents no value for a field a line cannot supply. |

### Fields of a transaction entry

| Field | Layout | Appears in | Tier | Note |
| --- | --- | --- | --- | --- |
| `event` / `Transaction started: \|Transaction committed: \|Transaction rolled back: ` | both | start, commit, rollback (INFO, VERBOSE) | Partial | No plain-text transaction entry is documented; DozerDB writes 'Transaction started: ', 'Transaction committed: ' or 'Transaction rolled back: ' before the entry. JSON names the event as documented. |
| `transactionId` / `transaction id:<transactionId>` | both | start, commit, rollback (INFO, VERBOSE) | Compatible | The transaction's sequence number, the same number the query entries run inside it carry. |
| `<openTimeMs> ms` | plain | start, commit, rollback (INFO, VERBOSE) | Partial | Plain text only, DozerDB's shape: how long the transaction had been open, measured at commit or rollback (0 at start), the figure `db.logs.query.transaction.threshold` filters on. |
| `<source>` | plain | start, commit, rollback (INFO, VERBOSE) | Partial | Plain text only, DozerDB's shape: the transaction's connection details, as in a query entry; the manual lists no source for transaction entries. |
| `database` / `<database>` | both | start, commit, rollback (INFO, VERBOSE) | Partial | JSON as documented; in plain text after the source's tab, DozerDB's placement. |
| `executingUser` / `<executingUser>` | both | start, commit, rollback (INFO, VERBOSE) | Partial | JSON as documented; in plain text after the database, DozerDB's placement. |
| `authenticatedUser` | json | start, commit, rollback (INFO, VERBOSE) | Compatible | JSON only, as the manual lists it: the authenticated user of the transaction beside executingUser; the plain line shows the executing user alone. |
| `<annotationData>` | plain | start, commit, rollback (INFO, VERBOSE) | Partial | Plain text only, DozerDB's shape: the transaction's metadata as a sorted-key Cypher map; the manual lists no annotation data for transaction entries. |
| `failureReason` / `<failureReason>` | both | rollback (INFO, VERBOSE); optional | Partial | DozerDB's shape in both layouts: a rollback with a throwable is written whatever the threshold, at ERROR as a failed-query entry is, because Neo4j's structured log carries a throwable at that level only; the plain line appends ' - <reason>' with the stack trace after, and the JSON entry carries the reason under failureReason, the key a failed-query entry uses, beside the layout's stacktrace. The manual lists no reason key for a transaction entry. |
<!-- END GENERATED: fields-matrix -->

## Shapes DozerDB defines

Where the manual documents no shape, this is the one DozerDB writes. Each example
line is the message of the entry, exactly as DozerDB writes it from the entry it
names, and is held byte for byte against the module's renderer by its test suite;
the layout prefixes the timestamp and the Log4j level, `2026-11-06
12:00:00.000+0000 INFO  ` in the shipped pattern. Tabs are literal in the lines.
Some values in the lines name versions rather than a shape: the client name and
version in a source segment, and the `app` that `cypher-shell` puts in the
annotation data, are those of the client that sent the query; the `Runtime
version` line of a plan table is the Neo4j version of the server that wrote it
(the example's, 2026.10.1, comes from a calendar-line server).
The JSON line of an error without a GQL status is a whole entry as Neo4j's JSON
layout writes it; the module's tests hold its fields against a running Neo4j 5.26
server.

<!-- BEGIN GENERATED: shapes -->
### Start entry

Written at the VERBOSE detail level only, once the query is parsed and obfuscated. The manual's only start entry is a Neo4j 4.4 capture, so the current shape is DozerDB's: the end entry's grammar behind the documented `Query started: ` prefix, the timing, allocation and page figures at 0, `runtime=null`, and the annotation data the transaction carries so far. In JSON the entry has `event` `start`, no `runtime` key and the cache key hash `00000000`, as the manual documents for a start event.

Rows: `event`.

DozerDB's start entry: the end grammar with zeroed values behind the manual's 'Query started: ' prefix (`INFO` at `VERBOSE`):

```text
Query started: id:34 - transaction id:12 - 0 ms: (planning: 0, waiting: 0) - 0 B - 0 page hits, 0 page faults - bolt-session	bolt	neo4j-javascript/6.0.1		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - MATCH (n:Person) RETURN n  LIMIT 5 - {} - runtime=null - {}
```

### Early start entry

With `db.logs.query.early_raw_logging_enabled=true` the start entry is written when the query is received, before parsing and obfuscation, so a query that hangs or fails in parsing is still recorded. It has one shape on every path, so a reader can tell it from a start entry written after planning: `transaction id:0` and the database `<none>`, because the query has reached neither yet (the end entry carries both); the raw query text and parameters (a literal stays visible under `obfuscate_literals`, a password literal is in clear; `parameter_logging_enabled` and `max_parameter_length` still apply); the figures at 0, `runtime=null`, the cache key hash `00000000` and, in JSON, no `queryLang`. A query has exactly one start entry, this one or the parsed one.

Rows: `event`, `database`.

DozerDB's early start entry under early_raw_logging_enabled: the start entry written when the query is received, before parsing and obfuscation, with transaction id:0 and database <none>, the raw text and parameters (the literal stays, obfuscate_literals or not), no runtime and no query language (`INFO` at `VERBOSE`):

```text
Query started: id:35 - transaction id:0 - 0 ms: (planning: 0, waiting: 0) - 0 B - 0 page hits, 0 page faults - bolt-session	bolt	neo4j-javascript/6.0.1		client/127.0.0.1:53864	server/127.0.0.1:7687>	<none> - neo4j - MATCH (p:Person {name: 'Alexander the Great'}) RETURN p.born + $n - {n: 42} - runtime=null - {}
```

### Failed-query entry

The manual documents no plain-text line for a failed query. DozerDB writes the entry at the Log4j level `ERROR` (every other entry is `INFO`): the success entry's line, then ` - ` and the failure reason Neo4j reported, which keeps its own line breaks (a syntax error adds the query and a caret), and the exception's stack trace on the lines after. In JSON the entry has `event` `fail`, the deprecated `failureReason` beside the documented `errorInfo` tree, because parsers key on either, and the layout's `stacktrace`. A failed query is logged at `INFO` whatever `db.logs.query.threshold` says, without the `id:` prefix, as at `VERBOSE` with it. A failure Neo4j reports without a reason ends with the annotation data.

Rows: `level`, `failureReason`, `stacktrace`.

DozerDB's failed-query entry: level ERROR, the success grammar plus ' - <failure reason>' last, the throwable's stack trace on the lines after (`ERROR` at `VERBOSE`, the stack trace on the lines after):

```text
id:7 - transaction id:12 - 3 ms: (planning: 1, waiting: 0) - 0 B - 0 page hits, 0 page faults - bolt-session	bolt	neo4j-javascript/6.0.1		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - MATCH (n) RETURN m - {} - runtime=null - {} - Variable `m` not defined
```

DozerDB's failed-query entry at INFO: a failure is written at the INFO detail level whatever the threshold, without the id: prefix (`ERROR` at `INFO`, the stack trace on the lines after):

```text
transaction id:12 - 1 ms: (planning: 1, waiting: 0) - 0 B - 0 page hits, 0 page faults - bolt-session	bolt	neo4j-javascript/6.0.1		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - MATCH (n) RETURN m - {} - runtime=null - {} - Variable `m` not defined
```

### Failed-query entry under `db.logs.query.obfuscate_errors`

Neo4j's obfuscated status description replaces the failure reason, and no stack trace follows the line, in either layout; in JSON `failureReason` and every `statusDescription` of `errorInfo` carry the obfuscated description and there is no `stacktrace`. A failure Neo4j reports without a GQL status object then carries no reason at all. On Neo4j 5.26, which has no obfuscated status description, the standard description of the GQLSTATUS code (its condition and sub-condition, without the message) stands in wherever the obfuscated one would.

Rows: `failureReason`, `db.logs.query.obfuscate_errors`.

DozerDB's failed-query entry under db.logs.query.obfuscate_errors=true: the obfuscated status description replaces the failure reason, and no throwable follows the line, so there is no stack trace (`ERROR` at `VERBOSE`):

```text
id:10 - transaction id:12 - 3 ms: (planning: 1, waiting: 0) - 0 B - 0 page hits, 0 page faults - bolt-session	bolt	neo4j-javascript/6.0.1		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - MATCH (n) RETURN m - {} - runtime=null - {} - error: syntax error or access rule violation - invalid syntax
```

### A query never bound to a database

A query that failed before it was routed to a database (a parse error, for instance) has no database. The manual documents `<none>` for the JSON `database` field; DozerDB writes `<none>` in the plain line too, with `transaction id:-1`, Neo4j's own value for a query that never reached a transaction, and `runtime=null`, so nothing that reached the server is missing from the log.

Rows: `database`.

DozerDB's shape for a query never bound to a database: <none> as the database of a query that failed before it was routed, transaction id:-1 before binding, runtime=null (`ERROR` at `VERBOSE`, the stack trace on the lines after):

```text
id:9 - transaction id:-1 - 2 ms: (planning: 2, waiting: 0) - 0 B - 0 page hits, 0 page faults - bolt-session	bolt	neo4j-javascript/6.0.1		client/127.0.0.1:53864	server/127.0.0.1:7687>	<none> - neo4j - RETURN 1 ( - {} - runtime=null - {} - Invalid input '(': expected an expression
```

### Error without a GQL status on Neo4j 5.26

On Neo4j 5.26 some errors have no GQL status of their own: "variable not defined" is raised by the router before the query reaches a database. DozerDB writes what Neo4j 5.26 reports, so the failed-query entry's `errorInfo` carries the default `50N42` with no `position` and no `cause`, and its `database` is `<none>`, where the manual's example for the same query shows `42N62` with a position. An error that has a GQL status (an unaliased `WITH` item, for instance) carries its tree with the position at every level, as on the calendar line. The line below is the JSON entry Neo4j 5.26 gives for `MATCH (n) RETURN m`, its stack trace shortened here to the first line.

Rows: `errorInfo`.

```text
{"time":"2026-11-06 18:02:12.138+0000","level":"ERROR","allocatedBytes":0,"annotationData":"{}","authenticatedUser":"neo4j","database":"<none>","elapsedTimeMs":6,"errorInfo":{"GQLSTATUS":"50N42","statusDescription":"error: general processing exception - unexpected error. Unexpected error has occurred. See debug log for details."},"event":"fail","executingUser":"neo4j","executionPlanCacheKeyHash":"00000000","failureReason":"Variable `m` not defined (line 1, column 18 (offset: 17))\n\"MATCH (n) RETURN m\"\n                  ^","id":"5","pageFaults":0,"pageHits":0,"planning":6,"query":"MATCH (n) RETURN m","queryParameters":"{}","source":"bolt-session\tbolt\tneo4j-javascript/6.0.1\t\tclient/127.0.0.1:53864\tserver/127.0.0.1:7687>","transactionId":-1,"type":"query","waiting":0,"stacktrace":"org.neo4j.exceptions.SyntaxException: Variable `m` not defined (line 1, column 18 (offset: 17))\n…"}
```

### Source segment of HTTP and embedded queries

The source is Neo4j Community's own connection details, the same text Neo4j writes for a Bolt connection in the manual's examples (`bolt-session`, the protocol, the driver name and version, the client and server addresses, each after a tab). The manual shows no current example for the other sources: a query over the transactional endpoint (`POST /db/{database}/tx/commit`) is `server-session`, the scheme, the client host and the request path, each after a tab; a query over the Query API (`POST /db/{database}/query/v2`), which Neo4j serves over an in-process Bolt channel, is the Bolt shape with `neo4j-query-api/v2` as the client and the channel's local addresses (`clientlocal:...`, `serverlocal:...`), so the request path is not in the entry; an embedded query (Neo4j's own and a procedure's inner queries) is `embedded-session` followed by its own tab, so two tabs precede the database. Transaction entries carry the same segment.

Rows: `source`, `transaction.source`.

DozerDB's source segment of a query over the transactional endpoint (POST /db/{database}/tx/commit): Community's HTTP connection details (scheme, client host, request path), followed by the database after one tab; the manual's only HTTP example is the 3.x shape (`INFO` at `VERBOSE`):

```text
id:5 - transaction id:11 - 4 ms: (planning: 2, waiting: 0) - 72 B - 1 page hits, 0 page faults - server-session	http	127.0.0.1	/db/neo4j/tx/commit	neo4j - neo4j - RETURN TRUE - {} - runtime=pipelined - {}
```

DozerDB's source segment of a query over the Query API (POST /db/{database}/query/v2), which Neo4j serves over an in-process Bolt channel: Community's Bolt connection details naming the API as the client and the channel's local addresses (`INFO` at `VERBOSE`):

```text
id:6 - transaction id:13 - 3 ms: (planning: 1, waiting: 0) - 72 B - 1 page hits, 0 page faults - bolt-session	bolt	neo4j-query-api/v2		clientlocal:E:fd970042	serverlocal:0a0902e0-7951-430c-b3b8-672cfc76b6a3>	neo4j - neo4j - RETURN 'over the query api' AS text - {} - runtime=slotted - {}
```

DozerDB's source segment: the embedded source 'embedded-session\t' keeps its own tab, so two tabs precede the database (`INFO` at `VERBOSE`):

```text
id:3 - transaction id:9 - 2 ms: (planning: 1, waiting: 0) - 136 B - 3 page hits, 0 page faults - embedded-session		neo4j - neo4j - MATCH (n:Person) RETURN n  LIMIT 5; - {} - runtime=slotted - {}
```

### Parameters: the cut value and the dropped segment

The manual's examples show `{}` only. DozerDB writes the parameters as Neo4j's single-line Cypher map (`{name: 'Alexander', n: 42}`, strings in single quotes, `{}` when there are none). `db.logs.query.max_parameter_length` cuts each value to that many characters and appends `...` inside the value, a string keeping its closing quote, so the entry still reads as a map (the first line, at a maximum of 10). `db.logs.query.parameter_logging_enabled=false` drops the segment and its ` - ` separator rather than writing `{}`, and the JSON key `queryParameters` is absent (the second line). A parameter value is never obfuscated by `db.logs.query.obfuscate_literals`, except that a password parameter is `'******'` at every setting.

Rows: `queryParameters`.

DozerDB's parameters segment: parameters as the single-line Cypher map with max_parameter_length applied per value, the ... inside the value (`INFO` at `VERBOSE`):

```text
id:41 - transaction id:20 - 12 ms: (planning: 4, waiting: 0) - 1120 B - 12 page hits, 0 page faults - bolt-session	bolt	neo4j-javascript/6.0.1		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - MATCH (p:Person {name: $name}) RETURN p.born + $n - {name: 'Alexander ...', n: 42} - runtime=pipelined - {}
```

DozerDB's parameters segment: db.logs.query.parameter_logging_enabled=false drops the parameters segment, separator included, rather than writing {} (`INFO` at `VERBOSE`):

```text
id:42 - transaction id:20 - 2 ms: (planning: 0, waiting: 0) - 1120 B - 12 page hits, 0 page faults - bolt-session	bolt	neo4j-javascript/6.0.1		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - MATCH (p:Person {name: $name}) RETURN p.born + $n - runtime=pipelined - {}
```

### Plan table

The manual says `queryPlan` is included under `db.logs.query.plan_description_enabled` and names the plan description table, but fixes neither its placement in the plain line nor its JSON type. DozerDB attaches the text Neo4j's `EXPLAIN` shows (the `Cypher`, `Planner`, `Runtime` and `Runtime version` header lines, the operator table, `Total database accesses: ?`) on the lines after the entry in plain text, and as one JSON string with the line breaks escaped, so the entry stays one object per line (the layout cuts any string over its maximum, 32 KiB with the shipped `neo4j.conf`). Only an end entry of a query that compiled carries it; a failed query that compiled has its table before its stack trace; a `PROFILE` query still gets the `EXPLAIN` shape, never profile figures.

Rows: `queryPlan`.

DozerDB's plan table: under db.logs.query.plan_description_enabled the end entry is followed, on the lines after it, by the plan description text Neo4j's EXPLAIN shows (the header lines, the operator table, the summary), whose Runtime version line names the Neo4j version of the server that wrote it (PlanDescriptionIT); JSON carries the same text as the one string queryPlan (`INFO` at `VERBOSE`):

```text
id:6 - transaction id:10 - 52 ms: (planning: 50, waiting: 0) - 64 B - 3 page hits, 0 page faults - bolt-session	bolt	neo4j-java/6.3.0-76f6a1e888163f7aa7c82e671bda1c284c66824b		client/127.0.0.1:61887	server/127.0.0.1:61885>	neo4j - neo4j - MATCH (n:Person) RETURN n.name AS withPlan - {} - runtime=slotted - {}
Cypher 5

Planner COST

Runtime SLOTTED

Runtime version 2026.10.1

+------------------+----+--------------------+----------------+
| Operator         | Id | Details            | Estimated Rows |
+------------------+----+--------------------+----------------+
| +ProduceResults  |  0 | withPlan           |             10 |
| |                +----+--------------------+----------------+
| +Projection      |  1 | n.name AS withPlan |             10 |
| |                +----+--------------------+----------------+
| +NodeByLabelScan |  2 | n:Person           |             10 |
+------------------+----+--------------------+----------------+

Total database accesses: ?
```

### `cpu` only when Neo4j tracks it

The JSON field `cpu` is present only while `db.track_query_cpu_time=true` (a dynamic setting): Neo4j's query snapshot has no CPU figure otherwise, and DozerDB never invents one. It never appears in the plain line, whose grammar is the same with or without it (the line below was written with the setting on).

Rows: `cpu`.

DozerDB's cpu field: cpu is JSON-only and present only under db.track_query_cpu_time=true; the plain line is the same with or without it (`INFO` at `VERBOSE`):

```text
id:12 - transaction id:14 - 7 ms: (planning: 2, waiting: 0) - 136 B - 3 page hits, 0 page faults - embedded-session		neo4j - neo4j - MATCH (n:Person) RETURN count(n) - {} - runtime=pipelined - {}
```

### Transaction entries

The manual documents transaction entries for the JSON layout only (`type` `transaction`, `event` `start`, `commit` or `rollback`, `transactionId`, `database`, `executingUser`, `authenticatedUser`), and DozerDB's JSON entry carries exactly those keys, plus `failureReason` on a rollback caused by a failure (the shape below). The plain-text line is DozerDB's: `Transaction started: `, `Transaction committed: ` or `Transaction rolled back: `, then `transaction id:N - N ms - <source>`, a tab, `<database> - <user> - <annotation data>`. The milliseconds are how long the transaction had been open at its commit or rollback (0 on a start entry), the figure `db.logs.query.transaction.threshold` selects on; the transaction id is the number the query entries run inside the transaction carry, so the two kinds of entry join on it; the annotation data is `{}` on a start entry, because clients attach their metadata after the kernel reports the start, and the final metadata on an end entry. Every kernel transaction is a candidate, the system database's and Neo4j's internal ones included; a rollback the client asked for carries no reason and stays at `INFO`.

Rows: `transaction.event`, `transaction.openTimeMs`, `transaction.source`, `transaction.database`, `transaction.executingUser`, `transaction.annotationData`.

DozerDB's transaction entries: the start entry at VERBOSE, open time 0 and the annotation data still empty because a client attaches its metadata after the kernel reports the start (`INFO` at `VERBOSE`):

```text
Transaction started: transaction id:12 - 0 ms - bolt-session	bolt	neo4j-cypher-shell/v2026.10.0		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - {}
```

DozerDB's transaction entries: a commit at INFO over the threshold, the annotation data a sorted-key Cypher map (`INFO` at `INFO`):

```text
Transaction committed: transaction id:12 - 41 ms - bolt-session	bolt	neo4j-cypher-shell/v2026.10.0		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - {app: 'cypher-shell_v2026.10', type: 'system'}
```

DozerDB's transaction entries: a rollback the client asked for carries no reason and stays at INFO; the embedded source keeps its own tab, so two tabs precede the database (`INFO` at `VERBOSE`):

```text
Transaction rolled back: transaction id:9 - 3 ms - embedded-session		neo4j - neo4j - {}
```

### Rollback caused by a failure

A rollback Neo4j reports with an exception (a transaction event listener that refused the commit, a constraint violation at commit) is written whatever the threshold, at the Log4j level `ERROR` like a failed-query entry, with ` - ` and the exception's message last and its stack trace on the lines after. In JSON the entry carries the manual's keys for a transaction entry, the exception's message under `failureReason` (the key a failed-query entry uses, which the manual does not list for a transaction entry) and the layout's `stacktrace`.

Rows: `transaction.failureReason`, `level`, `stacktrace`.

DozerDB's rollback caused by a failure: a rollback Neo4j reports with a throwable, written whatever the threshold with the reason last and the stack trace on the lines after; at ERROR, as a failed-query entry is, because Neo4j's structured log carries a throwable at that level only (`ERROR` at `INFO`, the stack trace on the lines after):

```text
Transaction rolled back: transaction id:15 - 2 ms - bolt-session	bolt	neo4j-cypher-shell/v2026.10.0		client/127.0.0.1:53864	server/127.0.0.1:7687>	neo4j - neo4j - {app: 'cypher-shell_v2026.10', type: 'system'} - Node(0) already exists with label `Person` and property `id` = 1
```

### Fallback entry

An entry that cannot be rendered or written never fails the query or the transaction and never switches the log off; DozerDB writes a fallback entry in its place, at the Log4j level `ERROR`, naming the query id (or the transaction id) and the exception, so the gap is visible in `query.log` itself and not only in `debug.log`, where each kind of failure is reported once. In JSON the fallback is a `query` entry with the `id` and this text as its `failureReason`, or a `transaction` entry with the `transactionId`. Neo4j Enterprise has no counterpart; the shape is DozerDB's.

```text
Query log entry could not be written: id:34 - java.lang.IllegalStateException: renderer broke
```

```text
Query log entry could not be written: transaction id:12 - java.lang.IllegalStateException: renderer broke
```
<!-- END GENERATED: shapes -->
