#!/usr/bin/env python3
"""Release-gate check of DozerDB's metrics in every delivery form, with a real Prometheus.

Usage: python3 scripts/metrics-check.py [plugin] [distribution] [docker]
       [--image IMAGE] [--no-prometheus] [--prometheus-image prom/prometheus:v3.15.1] [--verbose]

Each form (all three by default) is started through the smoke helper (scripts/smoke.py) with the CSV exporter
at a 1 s interval, the Prometheus endpoint enabled on a free port with an explicit endpoint, and the Graphite
exporter reporting every second to a plaintext listener this script runs on a free local port. The probes,
each named in the output:

  csv                 the metrics directory holds *.csv files; the heap gauge's file starts with `t,value`
                      and the neo4j database's committed-transactions counter's with `t,count`
  jmx                 dbms.queryJmx answers a value for every metric of the :sysinfo fixture
                      (metrics/src/test/java/org/dozerdb/metrics/SysInfoQueries.java) that DozerDB emits: those
                      with a Compatible or Partial row of their scope in the metric inventory
  prometheus-endpoint GET /metrics answers 200 with `# TYPE neo4j_dbms_vm_heap_used gauge` and the
                      committed-transactions counter family with its `_total` suffix
  graphite            the listener receives lines `<name> <value> <epoch seconds>` whose names carry the
                      metric prefix once, among them the heap gauge
  prometheus-scrape   (Prometheus mode) a Prometheus container scraping the endpoint reports the target up
  promql              (Prometheus mode) every expression of scripts/fixtures/dashboard-promql.json tagged
                      `data-with-default-filter` returns at least one series through Prometheus's HTTP API,
                      every `not-applicable-clustering` one returns none; the other tags are reported as
                      skipped with their tag

Prometheus mode (on by default; --no-prometheus when Docker is unavailable) runs the pinned Prometheus image
with a generated scrape configuration. The docker form needs Docker anyway. On macOS (Docker Desktop) the
containers reach the host through host.docker.internal; on Linux Prometheus runs on the host network and the
DozerDB container maps host.docker.internal to the host gateway. Exit status 0 and `RESULT PASS` mean every
probe of every form passed; `RESULT FAIL` names the failing probes.
"""
import argparse
from collections import Counter
from dataclasses import dataclass, field
import importlib.util
import json
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from probes import Probes
import release_line
import smoke


def load_metrics_docs():
    """scripts/metrics-docs.py, the monitoring documentation generator, which holds the one reader of the metric
    inventory's format; the jmx probe reads what DozerDB emits through it."""
    spec = importlib.util.spec_from_file_location('metrics_docs', smoke.ROOT / 'scripts/metrics-docs.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


metrics_docs = load_metrics_docs()

FORMS = ('plugin', 'distribution', 'docker')
PROMETHEUS_IMAGE = 'prom/prometheus:v3.15.1'
SCRAPE_SECONDS = 2
FIXTURE = smoke.ROOT / 'scripts/fixtures/dashboard-promql.json'
SYSINFO = smoke.ROOT / 'metrics/src/test/java/org/dozerdb/metrics/SysInfoQueries.java'
INVENTORY = metrics_docs.INVENTORY
PREFIX = 'neo4j'
DATABASE = 'neo4j'
# A gauge and a counter: Dropwizard's CSV layout writes `t,value` and `t,count` (each file is named by the full metric name).
CSV_HEADERS = {f'{PREFIX}.dbms.vm.heap.used.csv': 't,value',
               f'{PREFIX}.database.{DATABASE}.transaction.committed.csv': 't,count'}
HEAP = f'{PREFIX}.dbms.vm.heap.used'
JOB = 'neo4j'
CSV_INTERVAL = '1s'
GRAPHITE_INTERVAL = '1s'


def metrics_settings(graphite_host, graphite_port):
    """The settings of a checked server beyond the smoke helper's Prometheus endpoint."""
    return {
        'server.metrics.csv.interval': CSV_INTERVAL,
        'server.metrics.graphite.enabled': 'true',
        'server.metrics.graphite.server': f'{graphite_host}:{graphite_port}',
        'server.metrics.graphite.interval': GRAPHITE_INTERVAL,
    }


@dataclass
class HostAccess:
    """How containers reach services on this host, and where this host's listeners bind for them."""
    listen_host: str          # where the Graphite listener binds
    from_container: str       # the host's name inside the DozerDB container
    scrape_host: str          # the host's name inside the Prometheus container
    server_docker_args: list = field(default_factory=list)
    host_network: bool = False

    def prometheus_docker_args(self, container_port, host_port):
        return ['--network', 'host'] if self.host_network else ['--publish', f'127.0.0.1:{host_port}:{container_port}']

    def prometheus_listen(self, port):
        return f'127.0.0.1:{port}' if self.host_network else f'0.0.0.0:{port}'


def host_access(platform):
    if platform == 'darwin':
        # Docker Desktop forwards host.docker.internal to the host, loopback-bound services included.
        return HostAccess('127.0.0.1', 'host.docker.internal', 'host.docker.internal')
    # Docker Engine on Linux: a loopback-bound host service is unreachable from the bridge network.
    return HostAccess('0.0.0.0', 'host.docker.internal', '127.0.0.1',
                      ['--add-host', 'host.docker.internal:host-gateway'], host_network=True)


def prometheus_config(target, interval_seconds):
    """A Prometheus configuration (YAML) scraping one DozerDB endpoint as job `neo4j`."""
    return '\n'.join([
        'global:',
        f'  scrape_interval: {interval_seconds}s',
        f'  scrape_timeout: {interval_seconds}s',
        f'  evaluation_interval: {interval_seconds}s',
        'scrape_configs:',
        '  - job_name: "neo4j"',
        '    metrics_path: "/metrics"',
        '    static_configs:',
        f'      - targets: ["{target}"]',
        ''])


# The dashboards' template variables a replay substitutes; `$1` in label_replace is PromQL's own and stays.
TEMPLATE_VARIABLE = re.compile(r'\$(job|neo4j_instance|leader|interval)\b')
# Dashboard 10371's "Leader Vs Others" panels select the members other than $leader; on a single instance,
# where $leader is that instance, they select nothing whatever the server emits.
OTHER_MEMBERS = re.compile(r'instance!="\$leader"')


def replay_queries(expression):
    """What the replay evaluates for a fixture expression, as the fixture's description prescribes."""
    if expression['verbatim_is_2026_10_form']:
        return [expression['expr']]
    if expression['expr_2026_10_form'] is not None:
        return [expression['expr_2026_10_form']]
    return list(expression['names_2026_10_form'])


def expectation(expression):
    """'data' (at least one series per query), 'empty' (none), or None (skipped), from the expectation tag."""
    tag = expression['expect']
    if tag == 'data-with-default-filter':
        return 'empty' if any(OTHER_MEMBERS.search(q) for q in replay_queries(expression)) else 'data'
    if tag == 'not-applicable-clustering':
        return 'empty'
    return None


def substitute(expr, variables):
    return TEMPLATE_VARIABLE.sub(lambda match: variables[match.group(1)], expr)


def interval_option(dashboard):
    """The first option of the dashboard's `interval` variable, Grafana's default selection."""
    variable = next(v for v in dashboard['template_variables'] if v['name'] == 'interval')
    return variable['query'].split(',')[0].strip()


@dataclass
class Replay:
    expression: dict
    expect: str
    queries: list


def replay_plan(fixture, instance):
    """Every fixture expression with its expectation and its queries, variables bound to the scraped instance
    (which, on a single instance, is also the leader)."""
    intervals = {(d['id'], d['revision']): interval_option(d) for d in fixture['dashboards']}
    plan = []
    for expression in fixture['expressions']:
        variables = {'job': JOB, 'neo4j_instance': instance, 'leader': instance,
                     'interval': intervals[(expression['dashboard'], expression['revision'])]}
        queries = [substitute(query, variables) for query in replay_queries(expression)]
        plan.append(Replay(expression, expectation(expression), queries))
    return plan


def csv_problems(files):
    """What is wrong with the metrics directory, given the expected files' contents by name."""
    problems = []
    for name, header in CSV_HEADERS.items():
        if name not in files:
            problems.append(f'{name} missing')
        elif (first := files[name].split('\n', 1)[0]) != header:
            problems.append(f'{name} starts with "{first}", expected "{header}"')
    return problems


GRAPHITE_LINE = re.compile(r'^(\S+) (\S+) (\d+)$')


def parse_graphite_line(line):
    """(name, value, epoch seconds) of a plaintext-protocol line, or None when it is not one."""
    match = GRAPHITE_LINE.match(line)
    if not match:
        return None
    try:
        value = float(match.group(2))
    except ValueError:
        return None
    return match.group(1), value, int(match.group(3))


def graphite_problems(lines, started, ended):
    """What is wrong with the lines the listener received between two epoch seconds."""
    if not lines:
        return ['no lines received']
    problems = []
    heap = False
    for line in lines:
        parsed = parse_graphite_line(line)
        if parsed is None:
            problems.append(f'malformed line "{line}"')
            continue
        name, value, epoch = parsed
        if not name.startswith((f'{PREFIX}.dbms.', f'{PREFIX}.database.')):
            problems.append(f'name without the prefix once: "{line}"')
        if not started - 5 <= epoch <= ended + 5:
            problems.append(f'epoch outside the run: "{line}"')
        heap = heap or (name == HEAP and value > 0)
    if not heap:
        problems.append(f'no positive {HEAP} line')
    return problems


def sysinfo_metrics():
    """The metrics the :sysinfo of cypher-shell and of the Browser ask for, as (name, database metric), once each,
    read from the Java fixture the metrics module's SysInfoQueriesIT uses."""
    found = re.findall(r'new Metric\("([^"]+)", (true|false)\)', SYSINFO.read_text())
    return list(dict.fromkeys((name, flag == 'true') for name, flag in found))


def emitted_metrics(text):
    """(name without prefix and scope segment, database metric) of every metric the inventory says DozerDB emits:
    its Compatible and Partial rows. The inventory documents the line's manual, so a :sysinfo name the line does not
    emit (one its manual does not document) needs no list of its own here."""
    return {(entry.metric_name, entry.scope == 'database')
            for entry in metrics_docs.read_inventory(text) if entry.emitted}


def not_emitted(sysinfo, emitted):
    """The names of the :sysinfo metrics DozerDB does not emit, in the fixture's order."""
    return [name for name, database in sysinfo if (name, database) not in emitted]


def sysinfo_object_name(name, database):
    """The JMX object name both clients build, namespaces on (cypher-shell's Metric.fullName)."""
    scope = f'database.{DATABASE}' if database else 'dbms'
    return f'{PREFIX}.metrics:name={PREFIX}.{scope}.{name}'


EXPOSITION_FAMILIES = [f'# TYPE {PREFIX}_dbms_vm_heap_used gauge',
                       f'# TYPE {PREFIX}_database_{DATABASE}_transaction_committed_total counter']


def exposition_problems(text):
    """What the scrape lacks: the heap gauge family and a documented counter's family with `_total`."""
    lines = set(text.splitlines())
    return [f'no "{family}"' for family in EXPOSITION_FAMILIES if family not in lines]


def scraped_target(response):
    """(health, instance, last error) of the one active target in a /api/v1/targets response, or None."""
    targets = response['data']['activeTargets']
    if not targets:
        return None
    target = targets[0]
    return target['health'], target['labels']['instance'], target.get('lastError', '')


def series_count(response):
    """How many series a /api/v1/query response holds; a scalar or string result counts as one."""
    if response['status'] != 'success':
        raise ValueError(f"{response.get('errorType')}: {response.get('error')}")
    data = response['data']
    return len(data['result']) if data['resultType'] in ('vector', 'matrix') else 1


# Everything below runs servers and containers; the functions above are pure and unit-tested.

def wait_for(check, seconds, interval=0.5):
    """Call check until it returns without raising; re-raise its last error after the deadline."""
    deadline = time.monotonic() + seconds
    while True:
        try:
            return check()
        except (AssertionError, OSError, ValueError, KeyError, subprocess.CalledProcessError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(interval)


def http_json(url, method='GET', payload=None, headers=None):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={'Content-Type': 'application/json', **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read()
    except urllib.error.HTTPError as exc:  # Prometheus answers 400/422 with a JSON error body
        body = exc.read()
        if not body:
            raise
    return json.loads(body) if body else None


class GraphiteListener:
    """A plaintext Graphite receiver: records every line on every connection, as carbon would see them."""

    def __init__(self, host):
        self.socket = socket.socket()
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind((host, 0))
        self.socket.listen(16)
        self.socket.settimeout(0.5)
        self.port = self.socket.getsockname()[1]
        self.lines = []
        self.connections = 0
        self.closed = False
        self.lock = threading.Lock()
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while not self.closed:
            try:
                connection, _ = self.socket.accept()
            except (socket.timeout, OSError):
                continue
            with self.lock:
                self.connections += 1
            threading.Thread(target=self.read, args=(connection,), daemon=True).start()

    def read(self, connection):
        with connection, connection.makefile('r', encoding='utf-8', newline='\n') as stream:
            for line in stream:
                with self.lock:
                    self.lines.append(line.rstrip('\n'))

    def received(self):
        with self.lock:
            return list(self.lines), self.connections

    def close(self):
        self.closed = True
        self.socket.close()


def generate_load(server):
    """Committed writes and reads, a rolled-back transaction and a failing query on the neo4j database."""
    for _ in range(3):
        server.query('UNWIND range(1, 20) AS i CREATE (:MetricsCheck {i: i})')
        server.query('MATCH (n:MetricsCheck) RETURN count(n)')
        opened = http_json(f'{server.url}/db/{DATABASE}/tx', 'POST',
                           {'statements': [{'statement': 'CREATE (:MetricsCheck {rolledBack: true})'}]},
                           {'Authorization': smoke.AUTH})
        assert not opened['errors'], opened['errors']
        transaction = opened['commit'].rsplit('/', 1)[0]
        http_json(transaction, 'DELETE', headers={'Authorization': smoke.AUTH})
        try:
            server.query('RETURN 1/0')
        except AssertionError:
            pass
    server.query('MATCH (n:MetricsCheck) DELETE n')


def read_csv_files(server):
    names = list(CSV_HEADERS)
    if server.mode == 'docker':
        listing = server.exec('ls', '/metrics').split()
        files = {name: server.exec('head', '-n', '1', f'/metrics/{name}') for name in names if name in listing}
    else:
        directory = server.home / 'metrics'
        listing = [path.name for path in directory.glob('*')] if directory.is_dir() else []
        files = {name: (directory / name).read_text() for name in names if name in listing}
    return listing, files


def check_csv(server):
    def check():
        listing, files = read_csv_files(server)
        problems = csv_problems(files)
        assert not problems, ', '.join(problems)
        return listing
    listing = wait_for(check, 30)
    count = sum(1 for name in listing if name.endswith('.csv'))
    return f'{count} CSV files; {", ".join(f"{n} starts with {h}" for n, h in CSV_HEADERS.items())}'


def check_jmx(server):
    answered, missing = 0, []
    sysinfo = sysinfo_metrics()
    emitted = emitted_metrics(INVENTORY.read_text(encoding='utf-8'))
    for name, database in sysinfo:
        if (name, database) not in emitted:
            continue
        rows = server.query(f'CALL dbms.queryJmx("{sysinfo_object_name(name, database)}") YIELD attributes '
                            'RETURN attributes.Value.value, attributes.Count.value', DATABASE)[0]['data']
        if rows and any(value is not None for value in rows[0]['row']):
            answered += 1
        else:
            missing.append(name)
    assert not missing, f'no value over JMX for {", ".join(missing)}'
    return (f'dbms.queryJmx answers all {answered} :sysinfo metrics DozerDB emits '
            f'(not emitted: {", ".join(not_emitted(sysinfo, emitted)) or "none"})')


def check_endpoint(server):
    url = f'http://127.0.0.1:{server.prometheus}/metrics'
    with urllib.request.urlopen(url, timeout=10) as response:
        assert response.status == 200, f'{url} answered {response.status}'
        text = response.read().decode()
    problems = exposition_problems(text)
    assert not problems, f'{url}: {", ".join(problems)}'
    families = sum(1 for line in text.splitlines() if line.startswith('# TYPE '))
    return f'GET {url} 200, {families} families, {"; ".join(EXPOSITION_FAMILIES)}'


def check_graphite(listener, started):
    def check():
        lines, _ = listener.received()
        assert any(line.startswith(HEAP + ' ') for line in lines), f'no {HEAP} line yet ({len(lines)} lines)'
    wait_for(check, 30)
    lines, connections = listener.received()
    problems = graphite_problems(lines, started, time.time())
    assert not problems, ', '.join(problems[:5]) + (f' and {len(problems) - 5} more' if len(problems) > 5 else '')
    return f'{len(lines)} lines over {connections} connections, each `<name> <value> <epoch>` with the prefix once'


class PrometheusContainer:
    """A real Prometheus scraping one DozerDB endpoint with a generated configuration."""

    def __init__(self, image, access, target, work):
        self.name = f'dozer-prometheus-{uuid.uuid4().hex[:10]}'
        self.work = work
        self.port = smoke.port()
        self.url = f'http://127.0.0.1:{self.port}'
        self.created = False
        config = work / 'prometheus.yml'
        config.write_text(prometheus_config(target, SCRAPE_SECONDS))
        listen = access.prometheus_listen(self.port if access.host_network else 9090)
        smoke.run('docker', 'create', '--name', self.name, *access.prometheus_docker_args(9090, self.port), image,
                  '--config.file=/etc/prometheus/prometheus.yml', '--storage.tsdb.path=/prometheus',
                  f'--web.listen-address={listen}', stdout=subprocess.DEVNULL)
        self.created = True
        # Copied in rather than bind-mounted, so no file sharing setting of the Docker host matters.
        smoke.run('docker', 'cp', config, f'{self.name}:/etc/prometheus/prometheus.yml')
        smoke.run('docker', 'start', self.name, stdout=subprocess.DEVNULL)

    def api(self, path, **parameters):
        query = f'?{urllib.parse.urlencode(parameters)}' if parameters else ''
        return http_json(f'{self.url}/api/v1/{path}{query}')

    def wait_ready(self):
        def ready():
            with urllib.request.urlopen(f'{self.url}/-/ready', timeout=5) as response:
                assert response.status == 200
        wait_for(ready, 60)

    def close(self):
        if self.created:
            with (self.work / 'prometheus.log').open('wb') as log:
                subprocess.run(['docker', 'logs', self.name], stdout=log, stderr=subprocess.STDOUT)
            subprocess.run(['docker', 'rm', '--force', '--volumes', self.name], check=True, stdout=subprocess.DEVNULL)


def check_scrape(prometheus):
    prometheus.wait_ready()
    state = {}

    def up():
        target = scraped_target(prometheus.api('targets'))
        state['target'] = target
        assert target and target[0] == 'up', f'target {target}'
        return target
    try:
        health, instance, _ = wait_for(up, 60, interval=1)
    except AssertionError:
        raise AssertionError(f'target never up: {state.get("target")}') from None
    prometheus.instance = instance
    return f'{prometheus.url} target {instance} job {JOB} {health}'


def check_promql(prometheus, server, verbose):
    generate_load(server)
    time.sleep(2 * SCRAPE_SECONDS + 1)  # two more scrapes after the load, so rate() windows hold samples
    plan = replay_plan(json.loads(FIXTURE.read_text()), prometheus.instance)
    counts, problems, skipped = Counter(), [], Counter()
    results = {}
    for item in plan:
        e = item.expression
        label = f'{e["dashboard"]} r{e["revision"]} "{e["panel"]}" {e["ref_id"]}'
        if item.expect is None:
            skipped[e['expect']] += 1
            if verbose:
                print(f'  skip {label} [{e["expect"]}]', flush=True)
            continue
        for query in item.queries:
            if query not in results:
                try:
                    results[query] = series_count(prometheus.api('query', query=query))
                except ValueError as exc:
                    results[query] = exc
            found = results[query]
            if isinstance(found, ValueError):
                problems.append(f'{label}: {query} failed: {found}')
            elif (found > 0) != (item.expect == 'data'):
                problems.append(f'{label} [{e["expect"]}]: {query} returned {found} series, expected '
                                f'{"some" if item.expect == "data" else "none"}')
            elif verbose:
                print(f'  ok {label}: {found} series for {query}', flush=True)
        counts[item.expect] += 1
    for tag, count in sorted(skipped.items()):
        print(f'SKIP {server.mode} promql [{tag}]: {count} expressions', flush=True)
    for problem in problems:
        print(f'  {problem}', flush=True)
    assert not problems, f'{len(problems)} queries did not meet their expectation tag (listed above)'
    return (f'{counts["data"]} fixture expressions return series, {counts["empty"]} return none as expected, '
            f'{len(results)} distinct queries')


def check_form(form, args, access):
    """Start one delivery form with metrics settings and run every probe; return the failed probe names."""
    probes = Probes(form)
    listener = GraphiteListener(access.listen_host)
    graphite_host = access.from_container if form == 'docker' else '127.0.0.1'
    server = prometheus = None
    try:
        started = time.time()

        def start():
            nonlocal server
            server = smoke.Server(form, args.image, access.server_docker_args if form == 'docker' else (),
                                  prometheus=True, settings=metrics_settings(graphite_host, listener.port))
            server.start()
            return f'answers Cypher, work directory {server.work}'
        if not probes.run('start', start):
            return probes.failures
        probes.run('load', lambda: generate_load(server) or 'writes, reads, a rollback and a failing query')
        probes.run('csv', lambda: check_csv(server))
        probes.run('jmx', lambda: check_jmx(server))
        probes.run('prometheus-endpoint', lambda: check_endpoint(server))
        probes.run('graphite', lambda: check_graphite(listener, started))
        if args.prometheus:
            def scrape():
                nonlocal prometheus
                prometheus = PrometheusContainer(args.prometheus_image, access,
                                                 f'{access.scrape_host}:{server.prometheus}', server.work)
                return check_scrape(prometheus)
            if probes.run('prometheus-scrape', scrape):
                probes.run('promql', lambda: check_promql(prometheus, server, args.verbose))
    finally:
        for resource in (prometheus, server, listener):
            if resource:
                try:
                    resource.close()
                except Exception as exc:  # keep cleaning up the rest
                    print(f'WARN {form} cleanup: {exc}', flush=True)
    return probes.failures


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('forms', nargs='*', metavar='form',
                        help='plugin, distribution or docker; all three when none is given')
    parser.add_argument('--image', help="DozerDB image of the docker form (default: the line manifest's local_image)")
    parser.add_argument('--no-prometheus', dest='prometheus', action='store_false',
                        help='skip the Prometheus container and the dashboard replay')
    parser.add_argument('--prometheus-image', default=PROMETHEUS_IMAGE)
    parser.add_argument('--verbose', action='store_true', help='list every replayed and skipped expression')
    args = parser.parse_args(argv)
    for form in args.forms:
        if form not in FORMS:
            parser.error(f'unknown form {form!r}; choose from {", ".join(FORMS)}')
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit(143)))
    if 'docker' in (args.forms or FORMS):
        args.image = release_line.default_image(args.image)
    access = host_access(sys.platform)
    failures = []
    for form in args.forms or FORMS:
        failures += check_form(form, args, access)
    print('RESULT', 'PASS' if not failures else f'FAIL ({", ".join(failures)})', flush=True)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
