#!/usr/bin/env python3
"""Check that a DozerDB Docker image installs and runs APOC and Open GDS from NEO4J_PLUGINS.

Usage: python3 scripts/docker-plugins.py --image graphstack/dozerdb:2026.10.0.0
       [--gds-version 2026.10.0] [--apoc-version 2026.10.1]

Starts the image once with NEO4J_PLUGINS='["apoc", "open-gds"]' and once with
the alias '["graph-data-science"]'. For each container the script checks that the
entrypoint found a compatible plugin, that apoc.version() and gds.version()
answer (with the expected versions when given), that APOC functions and
procedures run, and that GDS projects a graph and runs PageRank. The entrypoint
downloads the jars, so the container needs network access. Exit status 0 and
`RESULT PASS` mean every plugin works.
"""
import argparse
import json
import subprocess
import sys

import smoke

RUNS = (['apoc', 'open-gds'], ['graph-data-science'])


def check_apoc(server, version=None):
    found = server.query('RETURN apoc.version()')[0]['data'][0]['row'][0]
    assert found and (version is None or found == version), f'apoc.version() returned {found}, expected {version}'
    assert server.query('RETURN apoc.coll.sum([1, 2, 3])')[0]['data'][0]['row'] == [6.0]
    server.query("CALL apoc.create.node(['ApocProbe'], {id: 1}) YIELD node RETURN node")
    stats = server.query('CALL apoc.meta.stats() YIELD labels RETURN labels')[0]['data'][0]['row'][0]
    assert stats.get('ApocProbe') == 1, stats
    server.query('MATCH (n:ApocProbe) DETACH DELETE n')
    return found


def check_gds(server, version=None):
    found = server.query('RETURN gds.version()')[0]['data'][0]['row'][0]
    assert found and (version is None or found == version), f'gds.version() returned {found}, expected {version}'
    server.query('CREATE (a:GdsProbe {id: 1})-[:LINK]->(b:GdsProbe {id: 2})-[:LINK]->(c:GdsProbe {id: 3})-[:LINK]->(a)')
    server.query("CALL gds.graph.project('gdsprobe', 'GdsProbe', 'LINK')")
    scores = server.query("CALL gds.pageRank.stream('gdsprobe') YIELD nodeId, score RETURN count(*), sum(score) > 0")
    assert scores[0]['data'][0]['row'] == [3, True], scores
    server.query("CALL gds.graph.drop('gdsprobe')")
    server.query('MATCH (n:GdsProbe) DETACH DELETE n')
    return found


def run(image, plugins, gds_version=None, apoc_version=None):
    """Start the image with these plugins; return the failures, one line each."""
    checks = {'apoc': lambda server: check_apoc(server, apoc_version),
              'open-gds': lambda server: check_gds(server, gds_version),
              'graph-data-science': lambda server: check_gds(server, gds_version)}
    server = smoke.Server('docker', image, ['--env', f'NEO4J_PLUGINS={json.dumps(plugins)}'])
    failures = []
    try:
        server.start()
        logs = subprocess.run(['docker', 'logs', server.id], capture_output=True, text=True)
        output = logs.stdout + logs.stderr
        for plugin in plugins:
            label = f'{image} NEO4J_PLUGINS={json.dumps(plugins)} {plugin}'
            if f"Installing Plugin '{plugin}'" not in output:
                problem = next((line for line in output.splitlines() if 'No compatible' in line), 'no install line in the log')
                failures.append(f'{label}: not installed ({problem.strip()})')
                continue
            try:
                print(f'PASS {label} {checks[plugin](server)}', flush=True)
            except (AssertionError, KeyError, IndexError) as exc:
                failures.append(f'{label}: {exc}')
    finally:
        server.close()
    for failure in failures:
        print(f'FAIL {failure}; container log in {server.work}/docker.log', flush=True)
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--image', required=True)
    parser.add_argument('--gds-version', help='Expected gds.version()')
    parser.add_argument('--apoc-version', help='Expected apoc.version()')
    args = parser.parse_args()
    failures = sum((run(args.image, plugins, args.gds_version, args.apoc_version) for plugins in RUNS), [])
    print('RESULT', 'PASS' if not failures else f'FAIL ({len(failures)} checks)')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
