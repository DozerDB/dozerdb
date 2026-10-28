#!/usr/bin/env python3
"""Run the maintainer's constraint and database lifecycle flow against a running DozerDB server.

Usage: python3 scripts/cypher-flow.py http://127.0.0.1:PORT PASSWORD [--database flowtest]

Creates the database, runs every statement of
scripts/fixtures/constraints-test.cypher in it, and checks that
statements under a "SHOULD FAIL" comment are rejected and all others succeed.
Then exercises STOP DATABASE, START DATABASE (requested and current status in
SHOW DATABASES, data retained) and DROP DATABASE. Exit status 0 means PASS.

Typical use after a build: `python3 scripts/smoke.py distribution --hold` prints
the Browser URL and password; pass the URL's origin and that password here.
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FLOW = ROOT / 'scripts/fixtures/constraints-test.cypher'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('base', help='HTTP origin of the server, e.g. http://127.0.0.1:7474')
    parser.add_argument('password', help='Password of the neo4j user')
    parser.add_argument('--database', default='flowtest', help='Database to create for the flow')
    args = parser.parse_args()
    auth = 'Basic ' + base64.b64encode(f'neo4j:{args.password}'.encode()).decode()
    db = args.database

    def run(statement, database=None):
        body = json.dumps({'statements': [{'statement': statement}]}).encode()
        request = urllib.request.Request(f'{args.base}/db/{database or db}/tx/commit', data=body,
                                         headers={'Content-Type': 'application/json', 'Authorization': auth})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())

    def wait_db(name, present=True):
        for _ in range(120):
            try:
                if not run('RETURN 1', name)['errors'] and present:
                    return
            except urllib.error.HTTPError as exc:
                if not present and exc.code == 404:
                    return
            time.sleep(0.5)
        raise SystemExit(f'database {name} did not become {"available" if present else "absent"}')

    def statuses():
        result = run('SHOW DATABASES', 'system')['results'][0]
        columns = result['columns']
        return {row['row'][columns.index('name')]: (row['row'][columns.index('requestedStatus')],
                                                   row['row'][columns.index('currentStatus')])
                for row in result['data']}

    statements, expect_fail, pending_fail, buffer = [], [], False, []
    for line in FLOW.read_text().splitlines():
        stripped = line.strip()
        if stripped.startswith('//'):
            if 'SHOULD FAIL' in stripped:
                pending_fail = True
            elif 'SHOULD PASS' in stripped:
                pending_fail = False
            continue
        if not stripped:
            continue
        buffer.append(line)
        if stripped.endswith(';'):
            statements.append('\n'.join(buffer))
            expect_fail.append(pending_fail)
            buffer, pending_fail = [], False

    run(f'CREATE DATABASE {db} IF NOT EXISTS', 'system')
    wait_db(db)
    failures = 0
    for statement, should_fail in zip(statements, expect_fail):
        failed = bool(run(statement)['errors'])
        status = 'ok' if failed == should_fail else 'MISMATCH'
        failures += status == 'MISMATCH'
        print(f'{status:8} expect_fail={should_fail!s:5} got_error={failed!s:5} {statement.strip().splitlines()[0][:70]}')
    print('constraints:', len(run('SHOW CONSTRAINTS')['results'][0]['data']))

    run(f'STOP DATABASE {db}', 'system')
    time.sleep(2)
    stopped = statuses().get(db)
    print('after STOP (requested, current):', stopped)
    failures += stopped != ('offline', 'offline')
    run(f'START DATABASE {db}', 'system')
    wait_db(db)
    started = statuses().get(db)
    print('after START (requested, current):', started)
    failures += started != ('online', 'online')
    count = run("MATCH (n) WHERE any(l IN labels(n) WHERE l STARTS WITH 'Test') RETURN count(n)")
    print('test nodes after the restart cycle:', count['results'][0]['data'][0]['row'][0])
    run(f'DROP DATABASE {db}', 'system')
    wait_db(db, present=False)
    remaining = sorted(statuses())
    print('after DROP, databases:', remaining)
    failures += db in remaining
    print('RESULT', 'PASS' if failures == 0 else f'FAIL ({failures} mismatches)')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
