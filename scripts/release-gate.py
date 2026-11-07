#!/usr/bin/env python3
"""The release gate: every release check of this line, one stack, one result table.

Usage: python3 scripts/release-gate.py [--image IMAGE | --published] [--manifest release-line.toml] [--hold]

Reads the line manifest (release-line.toml at the repository root: release line, Neo4j tag, DozerDB version,
published and local image, and every check with its switch) and checks its versions against
distribution/config.json. Then it runs each enabled check (scripts/release_checks.py defines them; --help lists
them in their order) as its own process and reads its exit status and its last RESULT line. The stack checks run last, against one stack: stack (the stack
is up and DozerDB answers Cypher), stack-metrics, and the flows, which assert the database metrics lifecycle
through the stack's Prometheus when stack-metrics is on.

The stack (DozerDB from IMAGE with every metric, Prometheus scraping it, Graphite receiving from it) runs as
the Compose project dozerdb-gate-<run id> with host ports chosen free on 127.0.0.1; it starts once for the
stack checks and is removed with its volumes afterwards, on failure and on Ctrl-C too, and the gate fails if
anything of the project remains. Its containers reach each other on the project network and the checks reach
them through the published ports, so macOS and Linux behave alike; the metrics check keeps its own host
networking per platform. A check disabled in the manifest is listed as SKIP and does not fail the run.

Each check's output goes to .build/logs/release-gate/<run id>/<check>.log (the stack's Compose output and
container logs to stack.log), the table to report.txt there. Exit status 0 and `RESULT PASS` mean every
enabled check passed; `RESULT FAIL (...)` names the failed ones. The image under test is the manifest's
local_image, the line's latest local build (scripts/docker-build.py tags it); --published tests the manifest's
image, the pushed tag of the version, and --image any other. --published also checks the floating tags the push
moved (the manifest's Docker series and, on the calendar line, latest; scripts/docker-build.py derives them): its
first row, floating-tags, resolves each through the Docker Hub registry and fails, naming the tag, unless it has
the exact image's digest. --hold leaves the stack running after the checks
until Ctrl-C, for the Browser, Prometheus and Graphite by hand.

There is no option for the release version: the version under test is the checkout's, the manifest's
dozerdb_version that the gate checks against distribution/config.json, because the plugin, distribution and JAR
checks test the artifacts built from this checkout. The image is the one part that can come from elsewhere, and
--image or --published names it; another release is tested from a checkout of its tag.
"""
import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid

from release_checks import CHECKS, Context
import release_checks
from release_line import MANIFEST, ROOT, ManifestError, checked_manifest
import smoke

STACK = ROOT / 'scripts/stack/compose.yaml'


@dataclass(frozen=True)
class Outcome:
    """PASS, FAIL or SKIP, with what a reader of the table needs to know beyond it."""
    status: str
    detail: str = ''


RESULT_LINE = re.compile(r'^RESULT (PASS|FAIL)\b.*$')


def outcome(output, exit_status, result_line):
    """The outcome of a finished check from its output and exit status. A check with result_line prints
    `RESULT PASS` or `RESULT FAIL ...`; its last such line must agree with the exit status."""
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    results = [line for line in lines if RESULT_LINE.match(line)]
    last = results[-1] if results else None
    if exit_status != 0:
        if last and last.startswith('RESULT FAIL'):
            return Outcome('FAIL', last)
        return Outcome('FAIL', f'exit status {exit_status}: {lines[-1]}' if lines else
                       f'exit status {exit_status}, no output')
    if not result_line:
        return Outcome('PASS')
    if last is None:
        return Outcome('FAIL', 'exit status 0 without a RESULT line')
    if last.startswith('RESULT FAIL'):
        return Outcome('FAIL', f'{last}, yet exit status 0')
    return Outcome('PASS')


@dataclass(frozen=True)
class Row:
    """One line of the result table: a check, its outcome, how long it ran and where its output is."""
    name: str
    outcome: Outcome
    seconds: float = None
    log: Path = None


def failed(rows):
    return [row.name for row in rows if row.outcome.status == 'FAIL']


def report(rows):
    """The result table and the RESULT line: PASS when no check failed (a skipped check does not fail the run)."""
    width = max(len('CHECK'), *(len(row.name) for row in rows)) + 2
    lines = ['CHECK'.ljust(width) + 'RESULT' + 'TIME'.rjust(7) + '  LOG']
    for row in rows:
        seconds = '-' if row.seconds is None else f'{round(row.seconds)}s'
        where = [shown(row.log)] if row.log else []
        where += [row.outcome.detail] if row.outcome.detail else []
        lines.append(row.name.ljust(width) + row.outcome.status.ljust(6) + seconds.rjust(7) + '  ' + '  '.join(where))
    names = failed(rows)
    lines.append('RESULT ' + (f'FAIL ({", ".join(names)})' if names else 'PASS'))
    return '\n'.join(lines)


class NotReady(Exception):
    """A bounded wait ended without the answer it waited for."""


def wait_until(probe, seconds, what, clock=time.monotonic, sleep=time.sleep, interval=1.0):
    """Call probe until it returns instead of raising, sleeping interval between calls, never past the deadline;
    then raise NotReady naming what was awaited and the probe's last error."""
    deadline = clock() + seconds
    while True:
        try:
            return probe()
        except Exception as exc:  # any failure of a probe means not ready yet; the last one is reported
            last = exc
        remaining = deadline - clock()
        if remaining <= 0:
            raise NotReady(f'{what} not ready after {seconds} s: {str(last).strip() or type(last).__name__}')
        sleep(min(interval, remaining))


@dataclass(frozen=True)
class Ports:
    """The host ports the stack publishes on 127.0.0.1, chosen free for each run."""
    http: int
    bolt: int
    prometheus: int
    graphite: int


def run_id(now, token):
    """A run id: UTC start time and a random token; names the Compose project and the log directory."""
    return time.strftime('%Y%m%d-%H%M%S', time.gmtime(now)) + '-' + token


def project_name(run):
    return f'dozerdb-gate-{run}'


def compose_command(project, metrics, *arguments):
    """A docker compose (v2) command on the checked-in stack; the metrics profile adds Prometheus and Graphite."""
    profile = ['--profile', 'metrics'] if metrics else []
    return ['docker', 'compose', '--project-name', project, '--file', str(STACK), *profile, *arguments]


DOWN = ('down', '--volumes', '--remove-orphans', '--timeout', '30')


def leftovers(project, list_ids):
    """What of the project still exists, one 'kind: ids' entry per kind that has any. Compose labels every
    container, volume and network it creates with its project; list_ids(command) runs a docker listing command
    and returns the ids it prints."""
    label = f'label=com.docker.compose.project={project}'
    listings = {'containers': ['docker', 'ps', '--all', '--quiet', '--filter', label],
                'volumes': ['docker', 'volume', 'ls', '--quiet', '--filter', label],
                'networks': ['docker', 'network', 'ls', '--quiet', '--filter', label]}
    found = {kind: list_ids(command) for kind, command in listings.items()}
    return [f'{kind}: {" ".join(ids)}' for kind, ids in found.items() if ids]


def stack_environment(image, password, ports, metrics):
    """The variables the stack definition interpolates. STACK_METRICS empty leaves every metrics setting empty,
    and the image's entrypoint writes no setting for an empty NEO4J_* variable."""
    return {'DOZERDB_IMAGE': image, 'DOZERDB_PASSWORD': password,
            'DOZERDB_HTTP_PORT': str(ports.http), 'DOZERDB_BOLT_PORT': str(ports.bolt),
            'PROMETHEUS_PORT': str(ports.prometheus), 'GRAPHITE_PORT': str(ports.graphite),
            'STACK_METRICS': 'on' if metrics else ''}


def skipped(check, manifest):
    return Row(check.name, Outcome('SKIP', f'disabled in {manifest.source}'))


def crashed(exc):
    """The outcome of a check, a stack start or a teardown that raised instead of finishing."""
    return Outcome('FAIL', f'crashed: {type(exc).__name__}: {exc}')


NOT_RUN = 'not run: the stack is not ready'


def run_all(manifest, image, execute, open_stack, hold=None):
    """Run every enabled check in order, the stack checks last against one stack, and return the rows.

    execute(check, context) runs one check and returns its row. open_stack(metrics) returns the stack without
    starting anything, an object with start() (start it and return the rows of its readiness: stack, and the
    Readiness checks of release_checks it judges), context and close() (remove it and return what the teardown
    left behind). hold(rows, stack), when given, runs after the checks while the stack is up. A check, a stack
    start or a teardown that raises is a failed row and the run goes on. Ctrl-C stops the run; the stack is
    removed whatever happens."""
    def run(check, context):
        try:
            return execute(check, context)
        except Exception as exc:  # whatever one check does, the others still run and the table is printed
            return Row(check.name, crashed(exc))

    rows = []
    try:
        for check in CHECKS:
            if check.stack:
                continue
            rows.append(run(check, Context(image=image, gds_version=manifest.gds_version)) if manifest.checks[check.name]
                        else skipped(check, manifest))
        stack_checks = [check for check in CHECKS if check.stack]
        if not any(manifest.checks[check.name] for check in stack_checks):
            return rows
        stack = None
        try:
            try:
                stack = open_stack(manifest.checks['stack-metrics'])
                readiness = {row.name: row for row in stack.start()}
            except Exception as exc:
                readiness = {'stack': Row('stack', crashed(exc))}
            rows.append(readiness['stack'])
            ready = readiness['stack'].outcome.status == 'PASS'
            for check in stack_checks:
                if not manifest.checks[check.name]:
                    rows.append(skipped(check, manifest))
                elif check.name in readiness:  # judged while the stack started
                    rows.append(readiness[check.name])
                elif ready:
                    rows.append(run(check, stack.context))
                else:
                    rows.append(Row(check.name, Outcome('FAIL', NOT_RUN)))
            if hold and stack:
                hold(rows, stack)
        finally:
            try:
                leftovers = stack.close() if stack else []
                if leftovers:
                    rows.append(Row('teardown', Outcome('FAIL', 'left ' + '; '.join(leftovers))))
            except Exception as exc:
                rows.append(Row('teardown', crashed(exc)))
    except KeyboardInterrupt:
        rows.append(Row('interrupted', Outcome('FAIL', 'stopped by Ctrl-C or SIGTERM')))
    return rows


JOB = 'neo4j'
PROMETHEUS_HEAP = 'neo4j_dbms_vm_heap_used'
GRAPHITE_PREFIX = 'neo4j'


def prometheus_problem(targets, heap):
    """What keeps Prometheus from being ready, given its /api/v1/targets answer and its answer to a query for the
    heap gauge; None when the neo4j job's target is up and the gauge has a series."""
    active = [t for t in targets['data']['activeTargets'] if t['labels'].get('job') == JOB]
    if not active:
        return f'no target of job {JOB} yet'
    if active[0]['health'] != 'up':
        return f"target {active[0]['labels']['instance']} {active[0]['health']}: {active[0].get('lastError', '')}"
    if not heap['data']['result']:
        return f'no {PROMETHEUS_HEAP} series yet'
    return None


def graphite_problem(found):
    """What keeps Graphite from being ready, given its find API's answer for the metric prefix's children; None
    when it stores series from DozerDB. Carbon creates a series when its first value arrives, and at most 50 new
    ones a minute, so with every metric on a particular series may take minutes to appear: any one will do."""
    return None if found else f'no series under {GRAPHITE_PREFIX}. stored yet'


# Everything below runs processes and containers; the functions above are pure and unit-tested.

LOGS = ROOT / '.build/logs/release-gate'


def announce(row):
    """Print a row as soon as it is known, as the run goes on: status, name, time and detail."""
    took = '' if row.seconds is None else f' ({round(row.seconds)} s)'
    print(f'{row.outcome.status} {row.name}{took}{": " + row.outcome.detail if row.outcome.detail else ""}',
          flush=True)


def shown(path):
    """A path as the gate prints it: relative to the repository when it lies inside."""
    return path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else str(path)


def stop(process):
    """Ask a check's process group to stop (the release scripts clean up their containers on SIGTERM), then kill."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=90)
    except ProcessLookupError:
        pass
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()


def run_logged(command, log, timeout, env=None):
    """Run a command from the repository root with its output appended to log; return its exit status, or None
    when it ran out of time and was stopped. Ctrl-C stops it too before propagating."""
    with log.open('a') as out:
        out.write(f'$ {shlex.join(command)}\n')
        out.flush()
        process = subprocess.Popen(command, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT,
                                   env={**os.environ, 'PYTHONUNBUFFERED': '1', **(env or {})},
                                   start_new_session=True)  # its own group: Ctrl-C reaches it as SIGTERM, below
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            stop(process)
            return None
        except BaseException:
            stop(process)
            raise


def execute(check, context, logs):
    """Run one check with its output in its own log and return its row; a check that cannot be run fails with
    the error in its log."""
    log = logs / f'{check.name}.log'
    started = time.monotonic()
    try:
        command = check.command(context)
        print(f'RUN  {check.name}: {shlex.join(command)} > {shown(log)}', flush=True)
        status = run_logged(command, log, check.timeout)
        result = (Outcome('FAIL', f'stopped after its time limit of {check.timeout} s') if status is None
                  else outcome(log.read_text(errors='replace'), status, check.result_line))
        if result.status == 'PASS' and check.stack and check.without_prometheus and not context.prometheus_url:
            result = Outcome('PASS', f'{check.without_prometheus}: stack-metrics off')
    except Exception as exc:
        with log.open('a') as out:
            traceback.print_exc(file=out)
        result = crashed(exc)
    row = Row(check.name, result, time.monotonic() - started, log)
    announce(row)
    return row


def free_ports():
    ports = set()
    while len(ports) < 4:
        ports.add(smoke.port())
    return Ports(*sorted(ports))


def http_json(url, payload=None, headers=None):
    request = urllib.request.Request(url, data=None if payload is None else json.dumps(payload).encode(),
                                     headers={'Content-Type': 'application/json', **(headers or {})})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read())


# The manifest media types a tag can resolve to; the registry answers with the digest of the one it stores.
MANIFEST_TYPES = ', '.join((
    'application/vnd.oci.image.index.v1+json', 'application/vnd.docker.distribution.manifest.list.v2+json',
    'application/vnd.oci.image.manifest.v1+json', 'application/vnd.docker.distribution.manifest.v2+json'))
DOCKER_HUB_TOKEN = 'https://auth.docker.io/token'
DOCKER_HUB = 'https://registry-1.docker.io'


def registry_digest(image, urlopen=urllib.request.urlopen):
    """The digest a Docker Hub tag (<repository>:<tag>) resolves to, or None when the registry has no such tag.
    Anonymous and read-only: a pull token, then a HEAD request for the tag's manifest."""
    repository, _, tag = image.rpartition(':')
    if '/' not in repository:
        repository = f'library/{repository}'
    query = urllib.parse.urlencode({'service': 'registry.docker.io', 'scope': f'repository:{repository}:pull'})
    with urlopen(f'{DOCKER_HUB_TOKEN}?{query}', timeout=30) as response:
        token = json.loads(response.read())['token']
    request = urllib.request.Request(f'{DOCKER_HUB}/v2/{repository}/manifests/{tag}', method='HEAD',
                                     headers={'Authorization': f'Bearer {token}', 'Accept': MANIFEST_TYPES})
    try:
        with urlopen(request, timeout=30) as response:
            return response.headers['Docker-Content-Digest']
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise


def floating_tags(image, floating, digest_of):
    """The floating-tags row: every floating tag must resolve to the digest of the exact image. digest_of(tag)
    returns a tag's digest from the registry, or None when the tag is missing."""
    started = time.monotonic()
    try:
        exact = digest_of(image)
        if exact is None:
            problems = [f'{image} is not in the registry']
        else:
            problems = []
            for tag in floating:
                digest = digest_of(tag)
                if digest is None:
                    problems.append(f'{tag} is not in the registry')
                elif digest != exact:
                    problems.append(f'{tag} is {digest}, not {exact} of {image}')
        outcome = (Outcome('FAIL', '; '.join(problems)) if problems else
                   Outcome('PASS', f'{", ".join(floating)} = {image} ({exact})'))
    except Exception as exc:
        outcome = crashed(exc)
    return Row('floating-tags', outcome, time.monotonic() - started)


class Stack:
    """The checked-in stack as one Compose project of this run. Nothing starts before start()."""

    def __init__(self, run, image, metrics, logs):
        self.project = project_name(run)
        self.metrics = metrics
        self.ports = free_ports()
        self.log = logs / 'stack.log'
        self.environment = stack_environment(image, smoke.PASSWORD, self.ports, metrics)
        self.context = Context(image=image, origin=f'http://127.0.0.1:{self.ports.http}', password=smoke.PASSWORD,
                               prometheus_url=f'http://127.0.0.1:{self.ports.prometheus}' if metrics else None)
        self.graphite_url = f'http://127.0.0.1:{self.ports.graphite}'

    def compose(self, *arguments, timeout=300):
        return run_logged(compose_command(self.project, self.metrics, *arguments), self.log, timeout, self.environment)

    def start(self):
        print(f'RUN  stack: Compose project {self.project} > {shown(self.log)}', flush=True)
        started = time.monotonic()
        try:
            # Pulls the pinned images when missing, hence the long limit.
            if self.compose('up', '--detach', '--quiet-pull', timeout=900) != 0:
                raise NotReady(f'docker compose up failed (see {self.log.name})')
            wait_until(self.dozerdb_answers, 180, 'DozerDB')
            rows = [Row('stack', Outcome('PASS'), time.monotonic() - started, self.log)]
        except NotReady as exc:
            rows = [Row('stack', Outcome('FAIL', str(exc)), time.monotonic() - started, self.log)]
        if self.metrics:
            if rows[0].outcome.status != 'PASS':
                rows.append(Row('stack-metrics', Outcome('FAIL', NOT_RUN)))
            else:
                started = time.monotonic()
                try:
                    wait_until(self.prometheus_scrapes, 120, 'Prometheus', interval=2)
                    wait_until(self.graphite_stores, 180, 'Graphite', interval=2)
                    rows.append(Row('stack-metrics', Outcome('PASS'), time.monotonic() - started, self.log))
                except NotReady as exc:
                    rows.append(Row('stack-metrics', Outcome('FAIL', str(exc)), time.monotonic() - started, self.log))
        for row in rows:
            announce(row)
        return rows

    def dozerdb_answers(self):
        answer = http_json(f'{self.context.origin}/db/neo4j/tx/commit', {'statements': [{'statement': 'RETURN 1'}]},
                           {'Authorization': smoke.AUTH})
        assert not answer['errors'], answer['errors']

    def prometheus_scrapes(self):
        url = self.context.prometheus_url
        problem = prometheus_problem(http_json(f'{url}/api/v1/targets'),
                                     http_json(f'{url}/api/v1/query?query={PROMETHEUS_HEAP}'))
        assert problem is None, problem

    def graphite_stores(self):
        query = urllib.parse.urlencode({'query': f'{GRAPHITE_PREFIX}.*'})
        problem = graphite_problem(http_json(f'{self.graphite_url}/metrics/find?{query}'))
        assert problem is None, problem

    def endpoints(self):
        endpoints = {'Browser': f'{self.context.origin}/browser/ (user neo4j, password {self.context.password})',
                     'Bolt': f'bolt://127.0.0.1:{self.ports.bolt}'}
        if self.metrics:
            endpoints.update({'Prometheus': self.context.prometheus_url, 'Graphite': self.graphite_url})
        return [f'{name:<11}{where}' for name, where in endpoints.items()]

    def close(self):
        """Record the containers' logs, remove the project with its volumes, and return what remains of it.
        A further Ctrl-C or SIGTERM cannot interrupt the removal."""
        previous = {number: signal.signal(number, signal.SIG_IGN) for number in (signal.SIGINT, signal.SIGTERM)}
        try:
            with self.log.open('a') as out:
                out.write('\n# container logs\n')
            self.compose('logs', '--no-color', '--timestamps', timeout=120)
            self.compose(*DOWN, timeout=180)
        finally:
            for number, handler in previous.items():
                signal.signal(number, handler)
        left = leftovers(self.project, lambda command: subprocess.run(command, capture_output=True,
                                                                      text=True).stdout.split())
        announce(Row(f'teardown of {self.project}', Outcome('FAIL', 'left ' + '; '.join(left)) if left
                      else Outcome('PASS', 'no container, volume or network left')))
        return left


def hold(rows, stack):
    print(report(rows), flush=True)
    print(f'HOLD the stack {stack.project} stays up until Ctrl-C:', flush=True)
    for line in stack.endpoints():
        print(f'  {line}', flush=True)
    try:
        signal.pause()
    except KeyboardInterrupt:
        print('Removing the stack', flush=True)


def interrupt(*_):
    raise KeyboardInterrupt


def parse_arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
                                     epilog='checks, in the order the gate runs them:\n' + release_checks.listing())
    image = parser.add_mutually_exclusive_group()
    image.add_argument('--image', help="the DozerDB image under test; the manifest's local_image by default")
    image.add_argument('--published', action='store_true',
                       help="test the manifest's image, the published image of the version, after the push")
    parser.add_argument('--manifest', type=Path, default=MANIFEST, help='the line manifest (default: %(default)s)')
    parser.add_argument('--hold', action='store_true', help='leave the stack running after the checks until Ctrl-C')
    return parser.parse_args(argv)


def release_name(manifest):
    """The release under test as the server's startup banner names it (the smoke check's banner): DozerDB
    <dozerdb_version> (Neo4j <neo4j_tag>)."""
    return smoke.banner(manifest.distribution_version)


def header(run, manifest, image, logs):
    """The gate's first line: run id, the release under test, its line, the image and where the logs go."""
    return f'Release gate {run}: {release_name(manifest)}, {manifest.line} line, image {image}, logs in {shown(logs)}'


def image_under_test(manifest, args):
    """The line's latest local build unless --published or --image names another image."""
    return args.image or (manifest.image if args.published else manifest.local_image)


def main(argv=None):
    args = parse_arguments(argv)
    signal.signal(signal.SIGTERM, interrupt)  # SIGTERM stops the run the way Ctrl-C does, stack removed
    try:
        manifest = checked_manifest(args.manifest)
    except ManifestError as exc:
        for problem in exc.problems:
            print(f'FAIL manifest {problem}', flush=True)
        print('RESULT FAIL (manifest)', flush=True)
        return 1
    image = image_under_test(manifest, args)
    run = run_id(time.time(), uuid.uuid4().hex[:6])
    logs = LOGS / run
    logs.mkdir(parents=True)
    print(header(run, manifest, image, logs), flush=True)
    rows = []
    if args.published:
        rows.append(floating_tags(manifest.image, manifest.floating_images, registry_digest))
        announce(rows[0])
    rows += run_all(manifest, image, lambda check, context: execute(check, context, logs),
                    lambda metrics: Stack(run, image, metrics, logs), hold=hold if args.hold else None)
    table = report(rows)
    (logs / 'report.txt').write_text(table + '\n')
    print(table, flush=True)
    return 1 if failed(rows) else 0


if __name__ == '__main__':
    sys.exit(main())
