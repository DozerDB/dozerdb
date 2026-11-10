# DozerDB

DozerDB is a free and open plugin for Neo4j Community Edition. Dropped into the
`lib/` directory of a stock Neo4j Community server of the matching version, it
adds capabilities that are otherwise reserved for the Enterprise edition:

- **Multiple databases**: `CREATE`, `SHOW`, `STOP`, `START` and `DROP DATABASE`
  (up to `dbms.max_databases`, 100 by default).
- **Schema constraints**: node and relationship property existence constraints,
  node key constraints and property type constraints, enforced on every write.
- **DozerDB Browser**: the Neo4j Browser with DozerDB's multi-database and
  constraint features enabled.
- **Metrics**: the `server.metrics.*` settings and the documented metrics, exported
  as CSV files, JMX MBeans, a Prometheus endpoint and Graphite
  ([monitoring](docs/monitoring/README.md)).
- **Query log**: the `db.logs.query.*` settings and `query.log` as the Neo4j
  Operations Manual documents them, with Neo4j's plain-text line or JSON fields,
  on by default ([query logging](docs/monitoring/query-logging.md)).
- **Runtime configuration**: `dbms.setConfigValue`, Neo4j's procedure for changing
  a dynamic setting without a restart ([procedures](docs/procedures.md)).

Every release also ships as a pre-packaged distribution: Neo4j Community built
from source with the plugin and DozerDB Browser already installed, as standalone
archives for Linux, macOS and Windows and as a Docker image. DozerDB is an
independent open source project; it is not Neo4j Enterprise Edition.

DozerDB is free software under the [GNU General Public License v3](LICENSE).
The project site is <https://dozerdb.org>.

## Release lines

| Branch | Tracks | Current release |
| --- | --- | --- |
| `main` | Neo4j calendar releases (`2026.x`) | `2026.10.2-1.2.1` |
| `lts/5.26` | Neo4j 5.26 long-term support | see the branch |

## Versioning

A release is named `<neo4j-tag>-<dozerdb-version>`: `2026.10.2-1.2.1` is DozerDB
`1.2.1` built on Neo4j `2026.10.2`. The name splits at its first hyphen.

- The first half is the exact Neo4j tag the release is built on. A Neo4j upgrade
  changes only this half; read Neo4j's own release notes for what it changes.
- The second half is DozerDB's own version, `MAJOR.MINOR.PATCH`, and covers what
  DozerDB adds to Neo4j Community: its procedures, settings, metrics and query
  log formats, the Docker image's environment variables and directories, the
  plugin JAR, the archive layout and DozerDB Browser. MAJOR changes for a
  breaking change to them, MINOR for an addition, PATCH for a fix. The same
  DozerDB version on two Neo4j tags (`2026.10.2-1.2.1`, `5.26.33-1.2.1`) has the
  same DozerDB features.

The one name is the Git tag, the GitHub release, both archives, every DozerDB JAR
inside them, the plugin JAR and the Docker image. A published release is never
rebuilt; a correction is a new release. The Docker image is also tagged with its
Neo4j series (`graphstack/dozerdb:2026.10`, `graphstack/dozerdb:5.26`), which
follows the latest release of that series, and `graphstack/dozerdb:latest` follows
the latest calendar release. The server logs `DozerDB 1.2.1 (Neo4j 2026.10.2)` when
it starts.

Releases up to `2026.10.1.0` on `main` and `5.26.32.1` on `lts/5.26` were named
by the earlier rule, the Neo4j tag plus a DozerDB revision counter; those names,
tags, files and images stay valid and unchanged.

## Get DozerDB

Every release on the [releases page](https://github.com/DozerDB/dozerdb/releases)
ships three files, each with a `.sha256` checksum:

| File | Use |
| --- | --- |
| `dozerdb-<version>-unix.tar.gz` | Standalone server for Linux and macOS |
| `dozerdb-<version>-windows.zip` | Standalone server for Windows |
| `dozerdb-plugin-<version>.jar` | Single JAR that turns a stock Neo4j Community `<neo4j-tag>` into DozerDB |

### Docker

```sh
docker run --name dozerdb -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/your-password \
  -v "$HOME/dozerdb/data:/data" -v "$HOME/dozerdb/logs:/logs" \
  graphstack/dozerdb:2026.10.2-1.2.1
```

The image accepts the same environment variables as the official Neo4j image.
Open <http://localhost:7474> and log in with the credentials from `NEO4J_AUTH`.

Ports and directories of the container:

| Port | Serves | Publish with |
| --- | --- | --- |
| 7474 | HTTP: DozerDB Browser and the HTTP API | `-p 7474:7474` |
| 7687 | Bolt | `-p 7687:7687` |
| 7473 | HTTPS, when `server.https.enabled=true` | `-p 7473:7473` |
| 2004 | Prometheus endpoint `/metrics`, when `server.metrics.prometheus.enabled=true` | `-p 2004:2004` |
| 2003 | Default port of the Graphite server the Graphite exporter sends to; outbound, nothing to publish, but the container must reach that host | |

| Directory | Content |
| --- | --- |
| `/data` | Databases and transaction logs |
| `/logs` | Log files, `query.log` among them; mount it (`-v "$HOME/dozerdb/logs:/logs"`) to keep them |
| `/metrics` | CSV metrics, one file per metric (`neo4j.dbms.vm.heap.used.csv`), a row every 30 seconds by default, rotated and archived next to it; `neo4j-admin server report` collects them. The image always writes metrics here; mount it (`-v "$HOME/dozerdb/metrics:/metrics"`) to keep the files |

Every `server.metrics.*` setting is passed as an environment variable the way
the Neo4j image maps settings: prefix `NEO4J_`, each `_` of the setting doubled,
each `.` replaced by `_`. Examples:

| Setting | Variable | Example value |
| --- | --- | --- |
| `server.metrics.enabled` | `NEO4J_server_metrics_enabled` | `false` switches every metric and exporter off |
| `server.metrics.prometheus.enabled` | `NEO4J_server_metrics_prometheus_enabled` | `true` |
| `server.metrics.prometheus.endpoint` | `NEO4J_server_metrics_prometheus_endpoint` | `0.0.0.0:2004` (required to reach the endpoint from outside the container; the default is `localhost:2004`) |
| `server.metrics.csv.interval` | `NEO4J_server_metrics_csv_interval` | `10s` |
| `server.metrics.csv.rotation.keep_number` | `NEO4J_server_metrics_csv_rotation_keep__number` | `3` |
| `server.metrics.graphite.enabled` | `NEO4J_server_metrics_graphite_enabled` | `true` |
| `server.metrics.graphite.server` | `NEO4J_server_metrics_graphite_server` | `graphite.example.com:2003` |
| `server.directories.metrics` | `NEO4J_server_directories_metrics` | another directory than `/metrics` |

Prometheus, scraped from the host:

```sh
docker run --name dozerdb -p 7474:7474 -p 7687:7687 -p 2004:2004 \
  -e NEO4J_AUTH=neo4j/your-password \
  -e NEO4J_server_metrics_prometheus_enabled=true \
  -e NEO4J_server_metrics_prometheus_endpoint=0.0.0.0:2004 \
  graphstack/dozerdb:2026.10.2-1.2.1
curl http://localhost:2004/metrics
```

The endpoint variable is required: the endpoint's default is `localhost:2004`, as
in Neo4j, which inside the container is reachable from the container only.
`0.0.0.0:2004` binds every interface of the container, so the published port
reaches it; this is what Neo4j's own Kubernetes example sets. Set another address
or port the same way.

### Standalone server

Unpack the archive and run `bin/neo4j console`. The archive is a complete Neo4j
Community distribution with DozerDB and its Browser already installed; it needs
a Java 21 runtime (`JAVA_HOME`). The Windows archive works the same way with
`bin\neo4j.bat`.

### Plugin JAR in an existing Neo4j

1. Install Neo4j Community `2026.10.2` (the exact tag of the release).
2. Copy `dozerdb-plugin-<version>.jar` into Neo4j's `lib/` directory.
3. To serve DozerDB Browser, create an empty directory (for example `dozer-web`)
   and set `internal.dbms.web_dir_path=dozer-web` in `neo4j.conf`; Browser
   bundles found in `web/` would otherwise take precedence.
4. Restart Neo4j. The Browser's About panel shows "Enhanced by DozerDB".

## Monitoring

DozerDB emits the metrics that the Neo4j Operations Manual documents, with Neo4j's
`server.metrics.*` settings and defaults, as CSV files, JMX MBeans, a Prometheus
endpoint and Graphite, and writes the query log the manual documents, on by
default at the `VERBOSE` detail level. [docs/monitoring](docs/monitoring/README.md)
says which of Neo4j's monitoring pages apply, lists the settings, and has the
compatibility matrices of every documented metric and of every query log setting
and field, with the differences from Neo4j.

## Procedures

DozerDB provides the administration procedures that the Neo4j Operations Manual
documents for Enterprise Edition where DozerDB implements the feature behind
them, with Neo4j's names and signatures; [docs/procedures.md](docs/procedures.md)
lists them, beginning with `dbms.setConfigValue`.

## Build from source

See [docs/building.md](docs/building.md) for the toolchain, the build commands
and the verification flows that every release passes.

## Contributing and security

Bug reports and pull requests are welcome on this repository; see
[docs/CONTRIBUTING.md](docs/CONTRIBUTING.md). Report security problems privately
as described in [docs/SECURITY.md](docs/SECURITY.md).
