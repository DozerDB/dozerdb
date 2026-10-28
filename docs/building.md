# Building DozerDB

The repository holds every component of a release: `core` (the Neo4j
integration), `browser` (DozerDB Browser), `plugin` (the shaded plugin JAR),
`distribution` (the standalone archives) and `docker`. Each keeps its native
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
npx nx build docker         # local image dozerdb:local from the Unix archive
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
on, the component versions and the packaging patch. `scripts/distribution.py`
exports that exact upstream commit (cloning it into `.build/neo4j-git`, or
reading an existing Neo4j clone named by `NEO4J_SOURCE_REPOSITORY`), applies the
patch, runs Neo4j's own standalone assembly with DozerDB's core and Browser, and
leaves the release bundle in `distribution/target/`:

| File | Content |
| --- | --- |
| `dozerdb-<version>-unix.tar.gz` | Standalone server for Linux and macOS |
| `dozerdb-<version>-windows.zip` | Standalone server for Windows (checked statically) |
| `dozerdb-plugin-<version>.jar` | Shaded core plus Browser for a stock Neo4j `lib/` |

Each file has a `.sha256` sidecar. `scripts/docker-build.py` builds the image
from the Unix archive; `--platform`, repeatable `--tag` and `--push` produce
multi-platform images for a registry.

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
still there.

Two further flows exercise a running server the way a user does. Hold a server
with `python3 scripts/smoke.py distribution --hold` (or `plugin` or `docker`);
it prints the Browser URL, the Bolt URL and the password. Then:

- `python3 scripts/cypher-flow.py <http-origin> <password>` creates a database,
  replays `scripts/fixtures/constraints-test.cypher` checking every `SHOULD
  FAIL` and `SHOULD PASS` marker, stops and starts the database (checking both
  `requestedStatus` and `currentStatus` in `SHOW DATABASES`, with data
  retained) and drops it. It must print `RESULT PASS`.
- `node scripts/browser-flow.mjs <http-origin> <password>` drives DozerDB
  Browser in Playwright's Chromium: log in over Bolt, confirm the DozerDB
  badge, create a database, create the constraint kinds, see violating writes
  rejected, stop, start and drop the database. It must print `RESULT PASS` and
  leaves screenshots and a transcript in `.build/logs/browser-flow/`.

`scripts/check-rebuild.sh` is a packaging regression check: it proves that a
removed Browser asset disappears from both the Browser and the plugin JAR on an
incremental rebuild.

## Code formatting

Core uses Spotless. `scripts/maven.sh -pl core spotless:apply` formats the code;
`-Dspotless.check.skip -Dspotless.apply.skip` skips the check during local
iteration. Browser uses the ESLint and Prettier configuration in `browser/`.
