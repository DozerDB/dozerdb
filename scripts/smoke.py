#!/usr/bin/env python3
"""Test packaged startup and its banner, authentication, databases, constraints, persistence, metrics and the DozerDB procedures."""
import argparse
import base64
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tarfile
import time
import urllib.error
import urllib.request
import uuid
from zipfile import ZipFile

import distribution_version
import release_line

ROOT = Path(__file__).resolve().parent.parent
CONFIG = release_line.read_config()
# The distribution version the pins derive: every DozerDB JAR of every delivery form is named by it.
VERSION = release_line.pinned_version(CONFIG)
PASSWORD = 'DozerMigrationTest123'
AUTH = 'Basic ' + base64.b64encode(f'neo4j:{PASSWORD}'.encode()).decode()
# A log line that announces a DozerDB version (the banner, or one formatted differently), or the bootstrapper's warning
# that it could not read the version resource.
BANNER_MENTION = re.compile(r'\bDozerDB\s+(v?\d|version unknown)')
# Neo4j's user log line: timestamp, level, message.
LOG_LINE = re.compile(r'^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}[+-]\d{4} +[A-Z]+ +(?P<message>.*)$')

def banner(version):
    """The startup banner of a distribution version, split at the first hyphen: DozerDB <dozerdb> (Neo4j <tag>)."""
    neo4j_tag, dozerdb = distribution_version.split(version)
    return f'DozerDB {dozerdb} (Neo4j {neo4j_tag})'

def banner_problem(log, expected):
    """None when the server log of one start announces the release in exactly one line whose message is expected;
    otherwise what is wrong, naming every line that mentions a DozerDB version."""
    mentions = [line for line in log.splitlines() if BANNER_MENTION.search(line)]
    exact = [line for line in mentions if (m := LOG_LINE.match(line)) and m['message'].rstrip() == expected]
    if len(mentions) == 1 and len(exact) == 1:
        return None
    if not mentions:
        return f'no startup banner {expected!r} in the server log'
    return (f'expected exactly one startup banner line {expected!r} in the server log, found {len(mentions)}: '
            + '; '.join(repr(line) for line in mentions))

def components_problem(rows, neo4j_tag):
    """None when the rows of CALL dbms.components() (name, versions, edition) are Neo4j's own, as every delivery form
    returned them before the banner: one Neo4j Kernel row at the upstream tag with the edition Neo4j's way, and
    otherwise only Cypher rows with no edition (their versions are Neo4j's and differ between lines); DozerDB adds no
    row of its own. Otherwise what is wrong, listing the rows."""
    kernel = ['Neo4j Kernel', [neo4j_tag], 'enterprise']
    if [row for row in rows if row[0] == 'Neo4j Kernel'] != [kernel]:
        return f'dbms.components() must report one row {kernel}, found {rows}'
    if any(row[0] != 'Cypher' or row[2] != '' for row in rows if row[0] != 'Neo4j Kernel'):
        return f'dbms.components() must report only the Neo4j Kernel row and Cypher rows with no edition, found {rows}'
    return None

def component_rows(result):
    """The rows of the HTTP API's result of CALL dbms.components()."""
    return [entry['row'] for entry in result['data']]

def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def env_name(setting):
    """The NEO4J_* variable the image maps to a setting (docker-entrypoint.sh): '_' doubled, then '.' to '_'."""
    return 'NEO4J_' + setting.replace('_', '__').replace('.', '_')

def env_args(settings):
    """The docker run arguments that pass settings to the image as NEO4J_* variables."""
    return [arg for setting, value in settings.items() for arg in ('--env', f'{env_name(setting)}={value}')]

def parse_conf(text):
    """Settings of a neo4j.conf text: every uncommented key=value line, the last value of a key winning."""
    settings = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            settings[key.strip()] = value.strip()
    return settings

def prometheus_settings(host, port):
    """The two settings that switch the Prometheus endpoint on at host:port (Operations Manual, "Expose metrics")."""
    return {'server.metrics.prometheus.enabled': 'true', 'server.metrics.prometheus.endpoint': f'{host}:{port}'}

def fetch(url, expected=None):
    dest = ROOT / '.build' / url.rsplit('/', 1)[-1]
    if not dest.exists():
        tmp = dest.with_suffix(dest.suffix+'.download')
        run('curl', '--fail', '--silent', '--show-error', '--location', '--retry', '2', '--output', tmp, url)
        tmp.replace(dest)
    if expected and hashlib.sha256(dest.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f'Checksum mismatch for {dest}; remove it and retry')
    return dest

class Server:
    def __init__(self, mode, image, docker_args=(), prometheus=False, settings=None):
        self.mode = mode
        self.image = image
        self.docker_args = list(docker_args)
        # Further settings for this run: written to neo4j.conf, or passed as NEO4J_* variables in docker mode.
        self.settings = dict(settings or {})
        self.id = f'dozer-smoke-{mode}-{uuid.uuid4().hex[:10]}'
        self.work = ROOT / '.build' / self.id
        self.work.mkdir(parents=True)
        self.http, self.bolt = port(), port()
        # With prometheus the server also serves the Prometheus endpoint on this host port (the container's 2004).
        self.prometheus = port() if prometheus else None
        self.url = f'http://127.0.0.1:{self.http}'
        self.process = None
        self.console = None
        self.container = False
        self.volume = False
        if mode != 'docker':
            if mode == 'plugin':
                spec = CONFIG['pluginTestArchive']
                archive = fetch(spec['url'], spec['sha256'])
            else:
                archive = ROOT / 'distribution/target' / f'dozerdb-{VERSION}-unix.tar.gz'
                digest, filename = archive.with_name(archive.name+'.sha256').read_text().split()
                assert filename == archive.name and hashlib.sha256(archive.read_bytes()).hexdigest() == digest
            with tarfile.open(archive) as tar:
                # tar's data filter rejects paths escaping the scratch directory.
                tar.extractall(self.work, filter='data')
            homes = [p for p in self.work.iterdir() if p.is_dir()]
            assert len(homes) == 1, f'Expected one distribution root: {homes}'
            self.home = homes[0]
            if mode == 'plugin':
                jar = ROOT / 'plugin/target' / f'dozerdb-plugin-{VERSION}.jar'
                shutil.copy2(jar, self.home / 'lib' / jar.name)
                # Stock Browser ZIPs in web/ precede classpath resources upstream.
                (self.home / 'dozer-web').mkdir()
            else:
                libs = [p.name for p in (self.home / 'lib').glob('*.jar')]
                for component in ('core', 'browser', 'metrics', 'procedures', 'query-log'):
                    assert f'dozerdb-{component}-{VERSION}.jar' in libs, (component, libs)
                assert not any(n.startswith('dozerdb-plugin') for n in libs)
                assert 'simpleclient-0.16.0.jar' in libs
                assert (self.home / 'DOZERDB-NOTICE.txt').is_file()
                assert (self.home / 'web').is_dir()
            conf = self.home / 'conf/neo4j.conf'
            if mode == 'distribution':
                defaults = dict(line.split('=', 1) for line in conf.read_text().splitlines()
                                if '=' in line and not line.lstrip().startswith('#'))
                assert defaults.get('dbms.usage_report.enabled') == 'false'
                assert defaults.get('dbms.max_databases') == '100'
                bootstrap = f'CLASSPATH="$BASEDIR/lib/dozerdb-core-{VERSION}.jar:$CLASSPATH"'
                for launcher in ('neo4j', 'neo4j-admin'):
                    assert bootstrap in (self.home / 'bin' / launcher).read_text(), launcher
            # Keep JVM arguments from the packaged config, replace only test environment settings.
            settings = {
                'server.default_listen_address': '127.0.0.1',
                'server.http.listen_address': f'127.0.0.1:{self.http}',
                'server.http.advertised_address': f'127.0.0.1:{self.http}',
                'server.bolt.listen_address': f'127.0.0.1:{self.bolt}',
                'server.bolt.advertised_address': f'127.0.0.1:{self.bolt}',
                'server.routing.listen_address': '127.0.0.1:0',
                'server.https.enabled': 'false',
                'server.memory.heap.initial_size': '256m',
                'server.memory.heap.max_size': '512m',
                'server.memory.pagecache.size': '128m',
                'dbms.security.auth_enabled': 'true',
                'dbms.usage_report.enabled': 'false',
                'dbms.max_databases': '100',
            }
            if mode == 'plugin':
                settings['internal.dbms.web_dir_path'] = 'dozer-web'
            if self.prometheus:
                settings.update(prometheus_settings('127.0.0.1', self.prometheus))
            settings.update(self.settings)
            text = '\n'.join(line for line in conf.read_text().splitlines()
                             if line.split('=', 1)[0].strip() not in settings)
            conf.write_text(text+'\n'+'\n'.join(f'{k}={v}' for k,v in settings.items())+'\n')
            run(self.home / 'bin/neo4j-admin', 'dbms', 'set-initial-password', PASSWORD, cwd=self.home)

    def start(self):
        if self.mode == 'docker':
            run('docker', 'volume', 'create', self.id, stdout=subprocess.DEVNULL)
            self.volume = True
            command = ['docker', 'run', '--detach', '--name', self.id,
                       '--publish', f'127.0.0.1:{self.http}:7474', '--publish', f'127.0.0.1:{self.bolt}:7687',
                       '--volume', f'{self.id}:/data',
                       # An anonymous volume at /metrics (removed with the container): the image prepares that
                       # directory for the CSV exporter whether it is a volume, a bind mount or the container's own.
                       '--volume', '/metrics',
                       '--env', f'NEO4J_AUTH=neo4j/{PASSWORD}',
                       '--env', f'NEO4J_server_bolt_advertised__address=127.0.0.1:{self.bolt}',
                       '--env', 'NEO4J_server_memory_heap_initial__size=256m',
                       '--env', 'NEO4J_server_memory_heap_max__size=512m',
                       '--env', 'NEO4J_server_memory_pagecache_size=128m']
            if self.prometheus:
                # The documented way: NEO4J_server_metrics_* variables plus a published port for the endpoint.
                command += ['--publish', f'127.0.0.1:{self.prometheus}:2004']
                command += env_args(prometheus_settings('0.0.0.0', 2004))
            command += env_args(self.settings)
            run(*command, *self.docker_args, self.image)
            self.container = True
        else:
            self.console = (self.work / 'console.log').open('ab')
            self.process = subprocess.Popen([str(self.home / 'bin/neo4j'), 'console'],
                                            cwd=self.home, stdout=self.console,
                                            stderr=subprocess.STDOUT, start_new_session=True)
        self.wait()
        endpoints = f'{self.url}/browser/ bolt://127.0.0.1:{self.bolt}'
        if self.prometheus:
            endpoints += f' http://127.0.0.1:{self.prometheus}/metrics'
        print(f'RUNNING {self.mode}: {endpoints}', flush=True)

    def log(self):
        """The server's log so far: the console output, or the container's."""
        if self.mode == 'docker':
            return subprocess.run(['docker', 'logs', self.id], check=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, errors='replace').stdout
        self.console.flush()
        return (self.work / 'console.log').read_text(errors='replace')

    def exec(self, *command):
        """Run a command inside the container and return its output."""
        return subprocess.run(['docker', 'exec', self.id, *command], check=True, capture_output=True, text=True).stdout

    def request(self, path, payload=None, auth=True):
        data = None if payload is None else json.dumps(payload).encode()
        headers = {'Content-Type': 'application/json'}
        if auth:
            headers['Authorization'] = AUTH
        with urllib.request.urlopen(urllib.request.Request(self.url+path, data=data, headers=headers), timeout=10) as response:
            return response.read()

    def query(self, text, database='neo4j', error=False):
        result = json.loads(self.request(f'/db/{database}/tx/commit', {'statements':[{'statement':text}]}))
        if error:
            assert result['errors'], f'Expected constraint rejection: {text}'
            assert any('Constraint' in e['code'] for e in result['errors']), result['errors']
        else:
            assert not result['errors'], result['errors']
        return result['results']

    def wait(self):
        deadline = time.monotonic()+150
        last = None
        while time.monotonic() < deadline:
            try:
                self.query('RETURN 1')
                return
            except (OSError, AssertionError, ValueError) as exc:
                last = exc
                if self.process and self.process.poll() is not None:
                    raise RuntimeError(f'Server exited; inspect {self.work}/console.log') from exc
                time.sleep(1)
        raise RuntimeError(f'Server did not become ready; {self.work}: {last}')

    def stop_process(self):
        if self.process and self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            try:
                self.process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait()
        if self.console:
            self.console.close()
        self.process = None

    def restart(self):
        if self.mode == 'docker':
            run('docker', 'restart', '--timeout', '45', self.id)
            self.wait()
        else:
            self.stop_process()
            self.start()

    def close(self):
        self.stop_process()
        if self.container:
            with (self.work / 'docker.log').open('wb') as log:
                subprocess.run(['docker', 'logs', self.id], stdout=log, stderr=subprocess.STDOUT)
            subprocess.run(['docker', 'rm', '--force', '--volumes', self.id], check=True)
        if self.volume:
            subprocess.run(['docker', 'volume', 'rm', self.id], check=True)

def verify_banner(server):
    """The first start announced the release in one log line, and dbms.components() is Neo4j's as before."""
    problem = banner_problem(server.log(), banner(VERSION))
    assert problem is None, f'{server.mode}: {problem}'
    neo4j_tag, _ = distribution_version.split(VERSION)
    rows = component_rows(server.query('CALL dbms.components() YIELD name, versions, edition RETURN name, versions, edition')[0])
    problem = components_problem(rows, neo4j_tag)
    assert problem is None, f'{server.mode}: {problem}'
    return 'startup banner, dbms.components'

def verify(server):
    banner_checks = verify_banner(server)
    try:
        server.request('/db/neo4j/tx/commit', {'statements':[{'statement':'RETURN 1'}]}, auth=False)
        raise AssertionError('Unauthenticated query unexpectedly accepted')
    except urllib.error.HTTPError as exc:
        assert exc.code == 401, exc.code
    if server.mode == 'docker':
        browser = server.work / 'browser.jar'
        run('docker', 'cp', f'{server.id}:/var/lib/neo4j/lib/dozerdb-browser-{VERSION}.jar', browser)
    else:
        component = 'plugin' if server.mode == 'plugin' else 'browser'
        browser = server.home / 'lib' / f'dozerdb-{component}-{VERSION}.jar'
    with ZipFile(browser) as jar:
        for resource in ['index.html', 'manifest.json', 'NOTICE.txt', 'LICENSES.txt']:
            assert server.request('/browser/'+resource) == jar.read('browser/'+resource), f'Customized Browser mismatch: {resource}'
        # The shipped Browser is the release's: its manifest carries the package version, the distribution version.
        manifest_version = json.loads(jar.read('browser/manifest.json')).get('version')
        assert manifest_version == VERSION, f'Browser manifest version {manifest_version!r}, expected {VERSION}'
    # Default metrics settings: the CSV exporter creates the metrics directory at startup and writes the first
    # rows after one server.metrics.csv.interval (30 s).
    if server.mode == 'docker':
        # The image owns /metrics and its entrypoint points server.directories.metrics there; the NEO4J_server_metrics_*
        # variables of the run reach neo4j.conf through the same mapping (checked here, served by the exporter below).
        conf = parse_conf(server.exec('cat', '/var/lib/neo4j/conf/neo4j.conf'))
        assert conf.get('server.directories.metrics') == '/metrics', conf.get('server.directories.metrics')
        if server.prometheus:
            for setting, value in prometheus_settings('0.0.0.0', 2004).items():
                assert conf.get(setting) == value, f'{setting}={conf.get(setting)} in the container, expected {value}'
        listing = lambda: server.exec('ls', '/metrics').split()
        deadline = time.monotonic()+90
        while not any(name.endswith('.csv') for name in listing()):
            assert time.monotonic() < deadline, f'No CSV metrics written to /metrics in the container: {listing()}'
            time.sleep(1)
    else:
        metrics = server.home / 'metrics'
        assert metrics.is_dir(), f'Metrics directory missing: {metrics}'
        deadline = time.monotonic()+90
        while not any(metrics.glob('*.csv')):
            assert time.monotonic() < deadline, f'No CSV metrics written to {metrics}'
            time.sleep(1)
    for database in ('migrationa', 'migrationb'):
        server.query(f'CREATE DATABASE {database}', 'system')
        deadline = time.monotonic()+60
        while True:
            try:
                server.query('RETURN 1', database)
                break
            except AssertionError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.5)
    names = str(server.query('SHOW DATABASES', 'system'))
    assert 'migrationa' in names and 'migrationb' in names
    server.query('CREATE CONSTRAINT migration_required FOR (n:MigrationProbe) REQUIRE n.id IS NOT NULL', 'migrationa')
    server.query('CREATE CONSTRAINT migration_unique FOR (n:MigrationProbe) REQUIRE n.id IS UNIQUE', 'migrationa')
    server.query("CREATE (:MigrationProbe {id: 'saved'})", 'migrationa')
    server.query('CREATE (:MigrationProbe)', 'migrationa', error=True)
    server.query("CREATE (:MigrationProbe {id: 'saved'})", 'migrationa', error=True)
    assert server.query('MATCH (n:MigrationProbe) RETURN count(n)', 'migrationb')[0]['data'][0]['row'] == [0]
    server.restart()
    assert server.query('MATCH (n:MigrationProbe) RETURN n.id', 'migrationa')[0]['data'][0]['row'] == ['saved']
    assert len(server.query('SHOW CONSTRAINTS', 'migrationa')[0]['data']) == 2
    server.query('CREATE (:MigrationProbe)', 'migrationa', error=True)
    # The metrics module is active in every delivery form: the heap gauge is readable under Neo4j's JMX object name.
    heap = server.query('CALL dbms.queryJmx("neo4j.metrics:name=neo4j.dbms.vm.heap.used") YIELD attributes RETURN attributes.Value.value')[0]['data']
    assert heap and isinstance(heap[0]['row'][0], int) and heap[0]['row'][0] > 0, f'Heap metric over JMX: {heap}'
    checks = f'{banner_checks}, heap metric over JMX, CSV metrics directory, ' + verify_set_config_value(server)
    if server.prometheus:
        checks += ', ' + verify_prometheus(server)
    print(f'PASS {server.mode}: authentication, queries, two databases, isolation, existence/uniqueness constraints, restart persistence, customized Browser and notices, {checks}', flush=True)

SET_CONFIG_VALUE = {'name': 'dbms.setConfigValue', 'signature': 'dbms.setConfigValue(setting :: STRING, value :: STRING)',
                    'mode': 'DBMS', 'admin': True}

def listed_procedure(rows):
    """The one SHOW PROCEDURES row of dbms.setConfigValue as a dict of its yielded columns, from the HTTP API's result."""
    assert len(rows) == 1, f'SHOW PROCEDURES lists dbms.setConfigValue {len(rows)} times: {rows}'
    return dict(zip(('name', 'signature', 'mode', 'admin'), rows[0]['row']))

def verify_set_config_value(server):
    """The procedures module is active in every delivery form: SHOW PROCEDURES lists dbms.setConfigValue with Neo4j's
    signature, mode and admin marking, and a call changes a dynamic setting for the running server."""
    rows = server.query("SHOW PROCEDURES YIELD name, signature, mode, admin WHERE name = 'dbms.setConfigValue' "
                        "RETURN name, signature, mode, admin")[0]['data']
    assert listed_procedure(rows) == SET_CONFIG_VALUE, rows
    value = lambda: server.query("CALL dbms.listConfig('db.logs.query.enabled') YIELD value RETURN value")[0]['data'][0]['row'][0]
    before = value()
    server.query("CALL dbms.setConfigValue('db.logs.query.enabled', 'INFO')")
    assert value() == 'INFO', f'db.logs.query.enabled after dbms.setConfigValue: {value()}'
    server.query(f"CALL dbms.setConfigValue('db.logs.query.enabled', '{before}')")
    assert value() == before, f'db.logs.query.enabled restored: {value()}'
    return 'dbms.setConfigValue listed and applied'

def verify_prometheus(server):
    """The Prometheus endpoint the settings switched on serves the heap gauge in the text format."""
    url = f'http://127.0.0.1:{server.prometheus}/metrics'
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            assert response.status == 200, response.status
            text = response.read().decode()
    except OSError as exc:  # URLError and HTTPError are OSErrors
        raise AssertionError(f'Prometheus endpoint {url} did not answer: {exc}') from exc
    assert '# TYPE neo4j_dbms_vm_heap_used gauge\n' in text, f'Prometheus exposition at {url}:\n{text[:800]}'
    return 'Prometheus endpoint'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['plugin', 'distribution', 'docker'])
    parser.add_argument('--image', help="image of the docker form (default: the line manifest's local_image)")
    parser.add_argument('--hold', action='store_true', help='Keep validated server running for interactive Browser verification; Ctrl-C stops it')
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit(143)))
    server = None
    try:
        image = release_line.default_image(args.image) if args.mode == 'docker' else None
        server = Server(args.mode, image, prometheus=True)
        server.start()
        verify(server)
        if args.hold:
            print(f'HOLD for Browser verification: user neo4j, password {PASSWORD}', flush=True)
            while True:
                time.sleep(1)
        for database in ('migrationa', 'migrationb'):
            server.query(f'DROP DATABASE {database}', 'system')
    except KeyboardInterrupt:
        if not args.hold:
            raise
    finally:
        if server:
            server.close()

if __name__ == '__main__':
    main()
