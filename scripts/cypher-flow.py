#!/usr/bin/env python3
"""Run the maintainer's constraint and database lifecycle flow against a running DozerDB server.

Usage: python3 scripts/cypher-flow.py http://127.0.0.1:PORT PASSWORD [--database flowtest]
       [--prometheus http://127.0.0.1:9090]

Creates the database, runs every statement of scripts/fixtures/constraints-test.cypher in it (its schema
statements first, then the others, each in file order), and checks that statements under a "SHOULD FAIL"
comment are rejected and all others succeed. Then exercises STOP DATABASE, START DATABASE (requested and
current status in SHOW DATABASES, data retained) and DROP DATABASE. Exit status 0 means PASS.

With --prometheus, the URL of a Prometheus that scrapes the server, the flow also asserts what a monitoring
administrator sees there, as scripts/fixtures/database-metrics-lifecycle.json lists it: after CREATE DATABASE
the database's metrics are scraped, after the data writes its committed write-transaction counter has risen
above its value after the schema statements, and after DROP DATABASE no series of it remains. Each is polled
within a bound of the Prometheus scrape interval; a failure names the step, the query and what Prometheus
returned. Without it the flow prints a SKIP line naming the lifecycle assertions it leaves out.

Typical use after a build: `python3 scripts/smoke.py distribution --hold` prints
the Browser URL and password; pass the URL's origin and that password here.
"""
import argparse
import base64
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import metrics_lifecycle

ROOT = Path(__file__).resolve().parent.parent
FLOW = ROOT / 'scripts/fixtures/constraints-test.cypher'
LIFECYCLE = ROOT / 'scripts/fixtures/database-metrics-lifecycle.json'
# The points of the flow at which the lifecycle fixture's assertions are checked, in the order it reaches them.
LIFECYCLE_STEPS = ('created', 'constrained', 'written', 'dropped')
SCHEMA = re.compile(r'(CREATE|DROP)\s+(CONSTRAINT|INDEX)\b', re.IGNORECASE)


def parse_statements(text):
    """The statements of a flow file with whether each must be rejected: a statement under a "SHOULD FAIL"
    comment must fail, every other one must succeed."""
    statements, pending_fail, buffer = [], False, []
    for line in text.splitlines():
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
            statements.append(('\n'.join(buffer), pending_fail))
            buffer, pending_fail = [], False
    return statements


def schema_then_data(statements):
    """The schema statements (CREATE or DROP of a constraint or index) and then every other statement, each in
    file order. The flow runs them in that order, so the constraints exist before any data statement (each
    section of the fixture uses its own label, so its expectations hold) and the committed writes after the
    lifecycle's constrained step are the flow's data writes only."""
    schema = [item for item in statements if SCHEMA.match(item[0].lstrip())]
    return schema, [item for item in statements if not SCHEMA.match(item[0].lstrip())]


def poll(probe, done, seconds=60.0, interval=0.5, clock=time.monotonic, sleep=time.sleep):
    """Call probe every interval until done(its answer) or seconds have passed; return the last answer, which
    the flow reports when it is not the awaited one."""
    deadline = clock() + seconds
    while True:
        answer = probe()
        remaining = deadline - clock()
        if done(answer) or remaining <= 0:
            return answer
        sleep(min(interval, remaining))


def metrics_checker(url, database):
    """The lifecycle checker for the Prometheus at url, or exit with RESULT FAIL before the flow changes anything
    when the fixture is not valid or Prometheus cannot be asked."""
    try:
        fixture = metrics_lifecycle.load_fixture(LIFECYCLE, LIFECYCLE_STEPS)
        query, interval = metrics_lifecycle.prometheus(url)
    except metrics_lifecycle.FixtureError as exc:
        print(f'FAIL     metrics fixture {exc}')
        print('RESULT FAIL (metrics fixture)')
        sys.exit(1)
    except (metrics_lifecycle.PrometheusError, OSError, ValueError) as exc:
        print(f'FAIL     Prometheus at {url}: {exc}')
        print('RESULT FAIL (Prometheus not available)')
        sys.exit(1)
    print(f'metrics  {len(fixture.assertions)} lifecycle assertions from {LIFECYCLE.relative_to(ROOT)}, Prometheus '
          f'{url} scraping every {interval:g} s, bound {fixture.timeout_scrape_intervals * interval:g} s')
    return metrics_lifecycle.Lifecycle(fixture, database, query, interval)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('base', help='HTTP origin of the server, e.g. http://127.0.0.1:7474')
    parser.add_argument('password', help='Password of the neo4j user')
    parser.add_argument('--database', default='flowtest', help='Database to create for the flow')
    parser.add_argument('--prometheus', help='URL of a Prometheus scraping the server, e.g. http://127.0.0.1:9090; '
                                             'asserts the database metrics lifecycle there')
    args = parser.parse_args()
    auth = 'Basic ' + base64.b64encode(f'neo4j:{args.password}'.encode()).decode()
    db = args.database
    metrics = metrics_checker(args.prometheus.rstrip('/'), db) if args.prometheus else None
    if metrics is None:
        print(f'SKIP     metrics lifecycle ({", ".join(LIFECYCLE_STEPS)}): no --prometheus given, so what Prometheus '
              'shows of the database is not checked')
    failed_steps = []

    def check_metrics(step):
        """The lifecycle fixture's assertions at this step; each one failed counts as a mismatch."""
        if metrics is None:
            return 0
        results = metrics.check(step)
        for result in results:
            print(f'{"ok" if result.passed else "MISMATCH":8} {result.message}')
        if not all(result.passed for result in results):
            failed_steps.append(step)
        return sum(not result.passed for result in results)

    def run(statement, database=None):
        body = json.dumps({'statements': [{'statement': statement}]}).encode()
        request = urllib.request.Request(f'{args.base}/db/{database or db}/tx/commit', data=body,
                                         headers={'Content-Type': 'application/json', 'Authorization': auth})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())

    def availability(name):
        """available when the database answers a query, absent when the server does not know it (HTTP 404),
        unavailable otherwise."""
        try:
            return 'unavailable' if run('RETURN 1', name)['errors'] else 'available'
        except urllib.error.HTTPError as exc:
            return 'absent' if exc.code == 404 else 'unavailable'

    def wait_db(name, present=True):
        wanted = 'available' if present else 'absent'
        if poll(lambda: availability(name), lambda state: state == wanted) != wanted:
            raise SystemExit(f'database {name} did not become {wanted}')

    def statuses():
        result = run('SHOW DATABASES', 'system')['results'][0]
        columns = result['columns']
        return {row['row'][columns.index('name')]: (row['row'][columns.index('requestedStatus')],
                                                   row['row'][columns.index('currentStatus')])
                for row in result['data']}

    schema, data = schema_then_data(parse_statements(FLOW.read_text()))

    def replay(statements):
        """Run each statement and return how many did not meet their expectation."""
        mismatches = 0
        for statement, should_fail in statements:
            failed = bool(run(statement)['errors'])
            status = 'ok' if failed == should_fail else 'MISMATCH'
            mismatches += status == 'MISMATCH'
            print(f'{status:8} expect_fail={should_fail!s:5} got_error={failed!s:5} '
                  f'{statement.strip().splitlines()[0][:70]}')
        return mismatches

    run(f'CREATE DATABASE {db} IF NOT EXISTS', 'system')
    wait_db(db)
    failures = check_metrics('created')
    failures += replay(schema)
    failures += check_metrics('constrained')
    failures += replay(data)
    print('constraints:', len(run('SHOW CONSTRAINTS')['results'][0]['data']))
    failures += check_metrics('written')

    run(f'STOP DATABASE {db}', 'system')
    stopped = poll(lambda: statuses().get(db), lambda status: status == ('offline', 'offline'))
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
    failures += check_metrics('dropped')
    steps = f'; metrics {", ".join(failed_steps)}' if failed_steps else ''
    print('RESULT', 'PASS' if failures == 0 else f'FAIL ({failures} mismatches{steps})')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
