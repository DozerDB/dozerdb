#!/usr/bin/env python3
"""Test packaged startup, authentication, databases, constraints and persistence."""
import argparse
import base64
import hashlib
import json
import os
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

ROOT = Path(__file__).resolve().parent.parent
CONFIG = json.loads((ROOT / 'distribution/config.json').read_text())
PASSWORD = 'DozerMigrationTest123'
AUTH = 'Basic ' + base64.b64encode(f'neo4j:{PASSWORD}'.encode()).decode()

def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

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
    def __init__(self, mode, image, docker_args=()):
        self.mode = mode
        self.image = image
        self.docker_args = list(docker_args)
        self.id = f'dozer-smoke-{mode}-{uuid.uuid4().hex[:10]}'
        self.work = ROOT / '.build' / self.id
        self.work.mkdir(parents=True)
        self.http, self.bolt = port(), port()
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
                archive = ROOT / 'distribution/target' / f'dozerdb-{CONFIG["distributionVersion"]}-unix.tar.gz'
                digest, filename = archive.with_name(archive.name+'.sha256').read_text().split()
                assert filename == archive.name and hashlib.sha256(archive.read_bytes()).hexdigest() == digest
            with tarfile.open(archive) as tar:
                # tar's data filter rejects paths escaping the scratch directory.
                tar.extractall(self.work, filter='data')
            homes = [p for p in self.work.iterdir() if p.is_dir()]
            assert len(homes) == 1, f'Expected one distribution root: {homes}'
            self.home = homes[0]
            if mode == 'plugin':
                jar = ROOT / 'plugin/target' / f'dozerdb-plugin-{CONFIG["browserVersion"]}.jar'
                shutil.copy2(jar, self.home / 'lib' / jar.name)
                # Stock Browser ZIPs in web/ precede classpath resources upstream.
                (self.home / 'dozer-web').mkdir()
            else:
                libs = [p.name for p in (self.home / 'lib').glob('*.jar')]
                assert f'dozerdb-core-{CONFIG["coreVersion"]}.jar' in libs
                assert f'dozerdb-browser-{CONFIG["browserVersion"]}.jar' in libs
                assert not any(n.startswith('dozerdb-plugin') for n in libs)
                assert 'simpleclient-0.16.0.jar' in libs
                assert (self.home / 'web').is_dir()
            conf = self.home / 'conf/neo4j.conf'
            if mode == 'distribution':
                defaults = dict(line.split('=', 1) for line in conf.read_text().splitlines()
                                if '=' in line and not line.lstrip().startswith('#'))
                assert defaults.get('dbms.usage_report.enabled') == 'false'
                assert defaults.get('dbms.max_databases') == '100'
                bootstrap = f'CLASSPATH="$BASEDIR/lib/dozerdb-core-{CONFIG["coreVersion"]}.jar:$CLASSPATH"'
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
            text = '\n'.join(line for line in conf.read_text().splitlines()
                             if line.split('=', 1)[0].strip() not in settings)
            conf.write_text(text+'\n'+'\n'.join(f'{k}={v}' for k,v in settings.items())+'\n')
            run(self.home / 'bin/neo4j-admin', 'dbms', 'set-initial-password', PASSWORD, cwd=self.home)

    def start(self):
        if self.mode == 'docker':
            run('docker', 'volume', 'create', self.id, stdout=subprocess.DEVNULL)
            self.volume = True
            run('docker', 'run', '--detach', '--name', self.id,
                '--publish', f'127.0.0.1:{self.http}:7474', '--publish', f'127.0.0.1:{self.bolt}:7687',
                '--volume', f'{self.id}:/data', '--env', f'NEO4J_AUTH=neo4j/{PASSWORD}',
                '--env', f'NEO4J_server_bolt_advertised__address=127.0.0.1:{self.bolt}',
                '--env', 'NEO4J_server_memory_heap_initial__size=256m',
                '--env', 'NEO4J_server_memory_heap_max__size=512m',
                '--env', 'NEO4J_server_memory_pagecache_size=128m',
                *self.docker_args, self.image)
            self.container = True
        else:
            self.console = (self.work / 'console.log').open('ab')
            self.process = subprocess.Popen([str(self.home / 'bin/neo4j'), 'console'],
                                            cwd=self.home, stdout=self.console,
                                            stderr=subprocess.STDOUT, start_new_session=True)
        self.wait()
        print(f'RUNNING {self.mode}: {self.url}/browser/ bolt://127.0.0.1:{self.bolt}', flush=True)

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

def verify(server):
    try:
        server.request('/db/neo4j/tx/commit', {'statements':[{'statement':'RETURN 1'}]}, auth=False)
        raise AssertionError('Unauthenticated query unexpectedly accepted')
    except urllib.error.HTTPError as exc:
        assert exc.code == 401, exc.code
    if server.mode == 'docker':
        browser = server.work / 'browser.jar'
        run('docker', 'cp', f'{server.id}:/var/lib/neo4j/lib/dozerdb-browser-{CONFIG["browserVersion"]}.jar', browser)
    else:
        component = 'plugin' if server.mode == 'plugin' else 'browser'
        browser = server.home / 'lib' / f'dozerdb-{component}-{CONFIG["browserVersion"]}.jar'
    with ZipFile(browser) as jar:
        for resource in ['index.html', 'manifest.json', 'NOTICE.txt', 'LICENSES.txt']:
            assert server.request('/browser/'+resource) == jar.read('browser/'+resource), f'Customized Browser mismatch: {resource}'
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
    print(f'PASS {server.mode}: authentication, queries, two databases, isolation, existence/uniqueness constraints, restart persistence, customized Browser and notices', flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['plugin', 'distribution', 'docker'])
    parser.add_argument('--image', default='dozerdb:local')
    parser.add_argument('--hold', action='store_true', help='Keep validated server running for interactive Browser verification; Ctrl-C stops it')
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit(143)))
    server = None
    try:
        server = Server(args.mode, args.image)
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
