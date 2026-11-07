"""The release checks the gate runs, in its order: their one definition.

scripts/release-gate.py runs CHECKS in this order and lists them in its --help; the line manifest
(release-line.toml, read by scripts/release_line.py) switches each of them on or off by name and knows no
other. A new release check is one entry here and its switch in every line's manifest (plus a service in
scripts/stack/compose.yaml when it needs one).
"""
from dataclasses import dataclass
import sys


@dataclass(frozen=True)
class Context:
    """What a check's command is built from: the image under test and the Open GDS version it must install (the
    manifest's gds_version), and, for a stack check, the stack's DozerDB (HTTP origin and password) and its
    Prometheus (None when the stack runs without metrics)."""
    image: str = None
    gds_version: str = None
    origin: str = None
    password: str = None
    prometheus_url: str = None


@dataclass(frozen=True)
class Check:
    """A release check the gate runs as its own process: command(context) is the command line, result_line says
    whether the script prints a RESULT line, timeout is its time limit in seconds, stack whether it runs against
    the gate's stack. without_prometheus says what a flow leaves unchecked when the stack runs without metrics;
    its passing row then says so."""
    name: str
    summary: str
    command: object
    result_line: bool
    timeout: int
    stack: bool = False
    without_prometheus: str = None


@dataclass(frozen=True)
class Readiness:
    """A readiness check of the stack itself, which the gate judges while it starts the stack, so it has no
    command of its own. Its switch also decides what the stack runs (stack-metrics: Prometheus and Graphite)."""
    name: str
    summary: str
    stack: bool = True


PYTHON = sys.executable


def prometheus(context):
    """The option that has a flow assert the database metrics lifecycle through the stack's Prometheus, when the
    stack runs one."""
    return ['--prometheus', context.prometheus_url] if context.prometheus_url else []


CHECKS = (
    Check('jars', 'scripts/check-jars.py: JAR contents, notices and service files',
          lambda c: [PYTHON, 'scripts/check-jars.py'], False, 300),
    Check('smoke-plugin', 'scripts/smoke.py plugin: the shaded plugin in stock Neo4j',
          lambda c: [PYTHON, 'scripts/smoke.py', 'plugin'], False, 900),
    Check('smoke-distribution', 'scripts/smoke.py distribution: the standalone archive',
          lambda c: [PYTHON, 'scripts/smoke.py', 'distribution'], False, 900),
    Check('smoke-docker', 'scripts/smoke.py docker --image IMAGE: the image',
          lambda c: [PYTHON, 'scripts/smoke.py', 'docker', '--image', c.image], False, 900),
    Check('metrics', 'scripts/metrics-check.py --image IMAGE: every delivery form with a real Prometheus',
          lambda c: [PYTHON, 'scripts/metrics-check.py', '--image', c.image], True, 1800),
    Check('query-log', 'scripts/query-log-check.py --image IMAGE: the query log on every delivery form, its entries '
          'matched against the field inventory',
          lambda c: [PYTHON, 'scripts/query-log-check.py', '--image', c.image], True, 1800),
    Check('docker-plugins', 'scripts/docker-plugins.py --image IMAGE --gds-version GDS: APOC and Open GDS from '
          'NEO4J_PLUGINS, GDS at the manifest\'s gds_version',
          lambda c: [PYTHON, 'scripts/docker-plugins.py', '--image', c.image, '--gds-version', c.gds_version], True,
          1200),
    Readiness('stack-metrics', "the stack's Prometheus scrapes DozerDB and its Graphite stores series"),
    Check('cypher-flow', "scripts/cypher-flow.py against the stack's DozerDB",
          lambda c: [PYTHON, 'scripts/cypher-flow.py', c.origin, c.password, *prometheus(c)], True, 900, stack=True,
          without_prometheus='metrics lifecycle assertions skipped'),
    Check('browser-flow', "scripts/browser-flow.mjs against the stack's DozerDB, headless Chromium",
          lambda c: ['node', 'scripts/browser-flow.mjs', c.origin, c.password, *prometheus(c)], True, 900,
          stack=True, without_prometheus='metrics lifecycle and :sysinfo assertions skipped'),
)
NAMES = tuple(check.name for check in CHECKS)


def listing():
    """The checks in their order with what each runs, as the gate's --help shows them."""
    width = max(map(len, NAMES)) + 2
    return '\n'.join(f'  {check.name:<{width}}{check.summary}' for check in CHECKS)
