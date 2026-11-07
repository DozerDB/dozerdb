# Building DozerDB

The repository holds every component of a release: `core` (the Neo4j
integration), `metrics` (the metrics settings, collectors and exporters),
`procedures` (the administration procedures Neo4j reserves for Enterprise),
`query-log` (Neo4j-compatible query logging), `browser` (DozerDB Browser), `plugin`
(the shaded plugin JAR), `distribution` (the standalone archives) and `docker`. Each keeps its native
build tool; Nx orchestrates the hand-offs between them.

## Toolchain

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21 or newer on `main`; 17 on `lts/5.26` | `JAVA_HOME` must point to it; the scripts honour it |
| Maven | downloaded by `mvnw` | the wrapper version is pinned per release line |
| Node.js | 22.12 or newer | runs Nx and the Browser verification flow |
| Node.js + Yarn for Browser | exactly Node 16.10.0 and Yarn 1.22.22 | installed locally, see below |
| Python | 3 | packaging and verification scripts |
| Docker with Buildx | recent | only for the image and the Docker smoke test |

Install the root tooling and the Browser toolchain once:

```sh
npm ci
npx playwright install chromium
npm install --prefix .build/browser-tools --no-audit --no-fund --package-lock=false node@16.10.0 yarn@1.22.22
```

`scripts/browser.sh` uses the Browser toolchain from `.build/browser-tools`
whatever Node version runs Nx. Alternatively set `BROWSER_NODE` to a Node
16.10.0 executable and `BROWSER_YARN` to Yarn Classic's `yarn.js`.

All Maven commands use the checkout-local repository `.build/m2`; nothing is
installed into `~/.m2`.

## Build

```sh
npx nx build core           # core and its parent POM
npx nx build browser        # Browser bundle and JAR
npx nx build plugin         # shaded plugin JAR
npx nx build distribution   # standalone archives and the release bundle
npx nx build docker         # local image from the Unix archive, see below
npx nx run-many -t build    # everything
```

The same steps without Nx, in order:

```sh
scripts/maven.sh -pl core -am install -DskipTests
scripts/browser.sh install --frozen-lockfile
CI=true scripts/browser.sh build
scripts/browser-jar.sh
scripts/maven.sh -pl plugin -am package -DskipTests
python3 scripts/check-jars.py
NEO4J_SOURCE_REPOSITORY=/path/to/neo4j python3 scripts/distribution.py
python3 scripts/docker-build.py
```

`distribution/config.json` pins the Neo4j tag and commit the release is built
on, the DozerDB version (the distribution version `<neo4j-tag>-<dozerdb-version>`
is derived from the two) and the packaging patch. `scripts/distribution.py`
exports that exact upstream commit (cloning it into `.build/neo4j-git`, or
reading an existing Neo4j clone named by `NEO4J_SOURCE_REPOSITORY`), applies the
patch, runs Neo4j's own standalone assembly with DozerDB's core and Browser, adds
the metrics, procedures and query-log JARs to `lib/` and `DOZERDB-NOTICE.txt` (the third-party
notice for the libraries DozerDB adds) to both archives, and leaves the release bundle in
`distribution/target/`:

| File | Content |
| --- | --- |
| `dozerdb-<distribution version>-unix.tar.gz` | Standalone server for Linux and macOS |
| `dozerdb-<distribution version>-windows.zip` | Standalone server for Windows (checked statically) |
| `dozerdb-plugin-<distribution version>.jar` | Shaded core, metrics, procedures, query log and Browser for a stock Neo4j `lib/` |

Each file has a `.sha256` sidecar. `scripts/docker-build.py` builds the image
from the Unix archive and tags it `dozerdb:<distribution version>-local` and with the
`local_image` of `release-line.toml`, the latest local build of the release line
(`dozerdb:calendar-local` or `dozerdb:lts-local`); the Docker smoke test uses that
tag. `--platform` and `--push` produce multi-platform images for a registry,
tagged from `release-line.toml` and never by hand: the exact `image` and the
`docker_series` tag, `graphstack/dozerdb:2026.10` on the calendar line and
`graphstack/dozerdb:5.26` on the LTS line. Only the calendar line also tags
`latest`.

## Test and verify

```sh
npx nx run-many -t test             # core tests, Browser tests, packaging smoke tests
scripts/browser-test.sh             # TypeScript, ESLint and Jest for Browser only
scripts/maven.sh verify             # Java verification
python3 scripts/smoke.py plugin     # plugin JAR in a downloaded stock Neo4j
python3 scripts/smoke.py distribution
python3 scripts/smoke.py docker
```

The smoke tests start the packaged server in a scratch directory under
`.build/`, check authentication, create two databases, create constraints, verify
that violating writes are rejected, restart the server and confirm the data is
still there. They also check the metrics: CSV files appear in the metrics
directory with the default settings, the heap gauge is readable over
`dbms.queryJmx`, and the Prometheus endpoint, enabled for the run, serves it.
In Docker the metrics directory is the container's `/metrics` and the Prometheus
settings reach the container as `NEO4J_server_metrics_*` variables with the
endpoint port published.

Two further flows exercise a running server the way a user does. Hold a server
with `python3 scripts/smoke.py distribution --hold` (or `plugin` or `docker`);
it prints the Browser URL, the Bolt URL and the password. Then:

- `python3 scripts/cypher-flow.py <http-origin> <password>` creates a database,
  replays `scripts/fixtures/constraints-test.cypher` checking every `SHOULD
  FAIL` and `SHOULD PASS` marker, stops and starts the database (checking both
  `requestedStatus` and `currentStatus` in `SHOW DATABASES`, with data
  retained) and drops it. It must print `RESULT PASS`. With `--prometheus
  <url>` of a Prometheus that scrapes the server, it also checks there that the
  new database's metrics appear, that its committed write-transaction counter
  and its node count rise with the data writes (measured from after the
  constraints, which the flow creates first) and that none of its series
  remain after the drop, as
  `scripts/fixtures/database-metrics-lifecycle.json` lists them.
- `node scripts/browser-flow.mjs <http-origin> <password>` drives DozerDB
  Browser in Playwright's Chromium: log in over Bolt, confirm the DozerDB
  badge, create a database, create the constraint kinds, see violating writes
  rejected, stop, start and drop the database. It must print `RESULT PASS` and
  leaves screenshots and a transcript in `.build/logs/browser-flow/`. With
  `--prometheus <url>` it makes the same Prometheus checks as the Cypher flow,
  from the same fixture, while the Browser drives the database, and it opens
  `:sysinfo` in the editor and requires a number in every Store Size, Page Cache
  and Transactions cell of the frame, whose screenshot joins the others.

The monitoring pages under `docs/monitoring/` are partly generated from the metric
inventory (`metrics/src/main/resources/org/dozerdb/metrics/inventory/metrics-inventory.tsv`)
and the metrics settings declaration. `python3 scripts/metrics-docs.py` (or `npx nx
run metrics:docs`) rewrites the generated parts after either changes; `python3
scripts/metrics-docs.py --check` (`npx nx run metrics:docs-check`, which `npx nx run
metrics:test` runs first) fails when a page is stale, and so does `python3 -m
unittest discover -s scripts/tests`.

`scripts/check-rebuild.sh` is a packaging regression check: it proves that a
removed Browser asset disappears from both the Browser and the plugin JAR on an
incremental rebuild.

## Code formatting

Core uses Spotless. `scripts/maven.sh -pl core spotless:apply` formats the code;
`-Dspotless.check.skip -Dspotless.apply.skip` skips the check during local
iteration. Browser uses the ESLint and Prettier configuration in `browser/`.
