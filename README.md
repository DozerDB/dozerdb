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

Every release also ships as a pre-packaged distribution: Neo4j Community built
from source with the plugin and DozerDB Browser already installed, as standalone
archives for Linux, macOS and Windows and as a Docker image. DozerDB is an
independent open source project; it is not Neo4j Enterprise Edition.

DozerDB is free software under the [GNU General Public License v3](LICENSE).
The project site is <https://dozerdb.org>.

## Release lines

| Branch | Tracks | Current release |
| --- | --- | --- |
| `main` | Neo4j calendar releases (`2026.x`) | `2026.10.0.0` |
| `lts/5.26` | Neo4j 5.26 long-term support | see the branch |

A DozerDB version is the exact Neo4j tag it is built on plus a DozerDB revision:
`2026.10.0.0` is the first DozerDB release built on Neo4j `2026.10.0`. The same
version names the Git tag, the archives, the plugin JAR and the Docker image.

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
  graphstack/dozerdb:2026.10.0.0
```

The image accepts the same environment variables as the official Neo4j image.
Open <http://localhost:7474> and log in with the credentials from `NEO4J_AUTH`.

### Standalone server

Unpack the archive and run `bin/neo4j console`. The archive is a complete Neo4j
Community distribution with DozerDB and its Browser already installed; it needs
a Java 21 runtime (`JAVA_HOME`). The Windows archive works the same way with
`bin\neo4j.bat`.

### Plugin JAR in an existing Neo4j

1. Install Neo4j Community `2026.10.0` (the exact tag of the release).
2. Copy `dozerdb-plugin-<version>.jar` into Neo4j's `lib/` directory.
3. To serve DozerDB Browser, create an empty directory (for example `dozer-web`)
   and set `internal.dbms.web_dir_path=dozer-web` in `neo4j.conf`; Browser
   bundles found in `web/` would otherwise take precedence.
4. Restart Neo4j. The Browser's About panel shows "Enhanced by DozerDB".

## Build from source

See [docs/building.md](docs/building.md) for the toolchain, the build commands
and the verification flows that every release passes.

## Contributing and security

Bug reports and pull requests are welcome on this repository; see
[docs/CONTRIBUTING.md](docs/CONTRIBUTING.md). Report security problems privately
as described in [docs/SECURITY.md](docs/SECURITY.md).
