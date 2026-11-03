#!/usr/bin/env python3
"""Measure the throughput cost of the metrics subsystem on a packaged DozerDB (budget: under 1 % with defaults).

Usage: python3 scripts/metrics-overhead.py [distribution|plugin] [--rounds 3] [--warmup 20] [--duration 60]
       [--clients 16] [--worst-case] [--duet] [--output results.json] [--profile DIR]

Runs a fixed read and write workload against fresh packaged servers in two configurations,
`off` (server.metrics.enabled=false) and `defaults` (Neo4j's defaults: metrics on, the default
filter, CSV and JMX on), and with --worst-case a third, `open` (server.metrics.filter=*, plus
the Prometheus endpoint, scraped once per second by a thread of this script). The configurations
alternate in blocks (A B, B A, A B, ...; with three: A B C, C B A, ...) so that a drift in the
machine's load reaches every configuration alike; every round starts a fresh server with the
same memory settings, loads the same data, warms up, then counts completed requests for a fixed
duration. The workload is driven over the HTTP Query API (/db/neo4j/query/v2), which Neo4j serves
through a local Bolt connection, so the per-event collectors (Bolt, query routing, query execution)
all see it; --api tx drives the transactional endpoint instead.

With --duet the configurations of a block run side by side instead, each fresh server with its own
clients over the same window (duet benchmarking): on a machine whose other load comes and goes,
all of them see the same interference, and the per-block ratio keeps the comparison.

The report prints every round, the mean and spread per configuration, the throughput delta of
each configuration against `off` with a 95 % confidence interval (paired by block, and unpaired),
and whether the 1 % budget is met, exceeded, or not decidable at the noise of this machine. It is a
maintainer's measurement, not a release gate; its result is recorded once per release line.

With --profile DIR every measured window is also recorded with JFR (profile settings) and the
report adds the share of the server's CPU samples that run metrics code, with the hottest metrics
frames: the attribution to read when the throughput delta is larger than the budget or than the noise.
"""
import argparse
import collections
import http.client
import json
import math
import multiprocessing
import os
import platform
import random
import shutil
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import smoke  # noqa: E402  (the packaged-server helper of the release gate)

BUDGET_PERCENT = 1.0
BASELINE = 'off'
CONFIGURATIONS = {
    'off': 'server.metrics.enabled=false',
    'defaults': "Neo4j's defaults: metrics on, default filter, CSV and JMX on",
    'open': 'server.metrics.filter=*, Prometheus on and scraped every second',
}
# Identical in every configuration: a fixed heap avoids resizing noise, the page cache holds the data set.
MEMORY = {
    'server.memory.heap.initial_size': '1g',
    'server.memory.heap.max_size': '1g',
    'server.memory.pagecache.size': '256m',
}
PEOPLE = 10_000
SETUP = [
    'CREATE CONSTRAINT person_id IF NOT EXISTS FOR (p:Person) REQUIRE p.id IS UNIQUE',
    'CALL db.awaitIndexes(300)',
    f"UNWIND range(0, {PEOPLE - 1}) AS i CREATE (:Person {{id: i, name: 'person-' + i, visits: 0}})",
    f'UNWIND range(0, {PEOPLE - 1}) AS i MATCH (a:Person {{id: i}}), (b:Person {{id: (i * 7919 + 1) % {PEOPLE}}}) '
    'CREATE (a)-[:KNOWS]->(b)',
    f'UNWIND range(0, {PEOPLE - 1}) AS i MATCH (a:Person {{id: i}}), (b:Person {{id: (i * 104729 + 7) % {PEOPLE}}}) '
    'CREATE (a)-[:KNOWS]->(b)',
]
# The workload: (kind, weight, statement). Eighty percent reads, twenty percent writes.
OPERATIONS = [
    ('read', 40, 'MATCH (p:Person {id: $id}) RETURN p.name, p.visits'),
    ('read', 30, 'MATCH (:Person {id: $id})-[:KNOWS]->(f) RETURN f.id, f.name'),
    ('read', 10, 'MATCH (:Person {id: $id})-[:KNOWS*1..2]->(f) RETURN count(f)'),
    ('write', 15, 'MATCH (p:Person {id: $id}) SET p.visits = p.visits + 1'),
    ('write', 5, 'CREATE (:Event {person: $id, seq: $seq})'),
]
WEIGHTS = sum(weight for _, weight, _ in OPERATIONS)
# Two-sided 95 % quantiles of Student's t by degrees of freedom; a fractional or missing df takes the next lower
# entry, which widens the interval (conservative).
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
       11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131, 16: 2.120, 17: 2.110, 18: 2.101, 19: 2.093,
       20: 2.086, 21: 2.080, 22: 2.074, 23: 2.069, 24: 2.064, 25: 2.060, 26: 2.056, 27: 2.052, 28: 2.048,
       29: 2.045, 30: 2.042, 40: 2.021, 60: 2.000, 120: 1.980}


# ---------------------------------------------------------------- pure parts (unit-tested)

def schedule(configurations, rounds):
    """Blocks of every configuration, alternately forward and reversed (A B, B A, A B ...): one block per round.

    Returns a list of (block, configuration) pairs; each block holds every configuration exactly once.
    """
    if rounds < 1 or not configurations:
        raise ValueError('need at least one round and one configuration')
    order = []
    for block in range(rounds):
        names = list(configurations) if block % 2 == 0 else list(reversed(configurations))
        order.extend((block, name) for name in names)
    return order


def t95(df):
    """The two-sided 95 % quantile of Student's t for df degrees of freedom (next lower table entry)."""
    if df < 1:
        raise ValueError(f'degrees of freedom must be at least 1: {df}')
    if df >= 1000:
        return 1.960
    return T95[max(d for d in T95 if d <= df)]


def summarize(values):
    """Mean, sample standard deviation and coefficient of variation in percent of a configuration's rounds."""
    mean = statistics.fmean(values)
    stdev = statistics.stdev(values) if len(values) > 1 else 0.0
    return {'n': len(values), 'mean': mean, 'stdev': stdev, 'cv_percent': 100 * stdev / mean if mean else 0.0,
            'min': min(values), 'max': max(values)}


def paired_delta(baseline, candidate):
    """Throughput change of candidate over baseline in percent, from per-block ratios (rounds paired by block).

    Returns (mean, low, high) of the 95 % confidence interval; low and high are None with fewer than two pairs.
    """
    if len(baseline) != len(candidate) or not baseline:
        raise ValueError('paired rounds must be non-empty and of equal number')
    changes = [100 * (c / b - 1) for b, c in zip(baseline, candidate)]
    mean = statistics.fmean(changes)
    if len(changes) < 2:
        return mean, None, None
    half = t95(len(changes) - 1) * statistics.stdev(changes) / math.sqrt(len(changes))
    return mean, mean - half, mean + half


def unpaired_delta(baseline, candidate):
    """Throughput change of the candidate's mean over the baseline's in percent with Welch's 95 % interval."""
    mb, mc = statistics.fmean(baseline), statistics.fmean(candidate)
    mean = 100 * (mc / mb - 1)
    if len(baseline) < 2 or len(candidate) < 2:
        return mean, None, None
    vb, vc = statistics.variance(baseline) / len(baseline), statistics.variance(candidate) / len(candidate)
    if vb + vc == 0:
        return mean, mean, mean
    df = (vb + vc) ** 2 / (vb ** 2 / (len(baseline) - 1) + vc ** 2 / (len(candidate) - 1))
    half = 100 * t95(math.floor(df)) * math.sqrt(vb + vc) / mb
    return mean, mean - half, mean + half


def verdict(low, high, budget=BUDGET_PERCENT):
    """Decide the budget from a confidence interval of the throughput change in percent (negative is a cost).

    MET when even the interval's worst end costs less than the budget; EXCEEDED when even its best end costs more;
    otherwise INCONCLUSIVE, with the largest cost the data supports.
    """
    if low is None:
        return 'INCONCLUSIVE', 'a single round per configuration has no spread; run more rounds'
    worst, best = -low, -high
    if worst < budget:
        return 'MET', f'the cost is below {worst:.2f} % at 95 % confidence, under the {budget:g} % budget'
    if best > budget:
        return 'EXCEEDED', f'the cost is above {best:.2f} % at 95 % confidence, over the {budget:g} % budget'
    return 'INCONCLUSIVE', (f'the noise of this machine is larger than the {budget:g} % budget; '
                            f'the data supports a cost of at most {worst:.2f} %')


def pick(rng):
    """The next operation of a worker's deterministic sequence: (kind, statement, parameters)."""
    roll = rng.randrange(WEIGHTS)
    for kind, weight, statement in OPERATIONS:
        if roll < weight:
            return kind, statement, {'id': rng.randrange(PEOPLE), 'seq': rng.randrange(1 << 30)}
        roll -= weight
    raise AssertionError('weights exhausted')


def request_body(api, statement, parameters):
    """The JSON body for the Query API (`query`) or the transactional endpoint (`tx`)."""
    if api == 'query':
        return {'statement': statement, 'parameters': parameters}
    return {'statements': [{'statement': statement, 'parameters': parameters}]}


def format_round(index, total, result):
    return (f"ROUND {index}/{total} {result['configuration']:<8} {result['throughput']:9.1f} ops/s "
            f"(reads {result['reads'] / result['duration']:.1f}/s, writes {result['writes'] / result['duration']:.1f}/s, "
            f"halves {halves(result)}, "
            f"errors {result['errors']}, metrics MBeans {result['mbeans']}, "
            f"load {result['load_before']:.1f}->{result['load_after']:.1f}"
            + (f", scrapes {result['scrapes']}" if result.get('scrapes') is not None else '') + ')'
            + (f"\n  {format_profile(result['configuration'], result['profile'])}" if result.get('profile') else ''))


def halves(result):
    """Throughput of the measured window's first and second half: a rising second half means a short warm-up."""
    total, late, half = result['reads'] + result['writes'], result['second_half'], result['duration'] / 2
    return f'{(total - late) / half:.0f}/{late / half:.0f}'


def format_summary(results, budget=BUDGET_PERCENT):
    """The summary lines: per configuration, per comparison against the baseline, and the budget verdict."""
    by_config = {}
    for result in results:
        by_config.setdefault(result['configuration'], {})[result['block']] = result['throughput']
    lines = []
    for name, rounds in by_config.items():
        s = summarize([rounds[b] for b in sorted(rounds)])
        values = ' '.join(f'{rounds[b]:.1f}' for b in sorted(rounds))
        lines.append(f"CONFIG {name:<8} mean {s['mean']:.1f} ops/s, stdev {s['stdev']:.1f} ({s['cv_percent']:.2f} %), "
                     f"range {s['min']:.1f}..{s['max']:.1f}, rounds {values}")
    base = by_config.get(BASELINE)
    result_line = None
    for name, rounds in by_config.items():
        if name == BASELINE or base is None:
            continue
        blocks = sorted(set(base) & set(rounds))
        pm, plow, phigh = paired_delta([base[b] for b in blocks], [rounds[b] for b in blocks])
        um, ulow, uhigh = unpaired_delta(list(base.values()), list(rounds.values()))
        lines.append(f'DELTA {name} vs {BASELINE}: {pm:+.2f} % throughput, paired 95 % CI {interval(plow, phigh)}; '
                     f'means {um:+.2f} %, unpaired 95 % CI {interval(ulow, uhigh)}')
        status, reason = verdict(plow, phigh, budget)
        line = f'RESULT {status} {name}: {reason}'
        if name == 'defaults':
            result_line = line
        else:
            lines.append(line.replace('RESULT', 'INFO', 1) + ' (worst case, outside the budget)')
    profiles = {}
    for result in results:
        if result.get('profile'):
            merged = profiles.setdefault(result['configuration'], {'samples': 0, 'metrics_samples': 0,
                                                                   'dispatch_samples': 0,
                                                                   'hottest': collections.Counter()})
            for key in ('samples', 'metrics_samples', 'dispatch_samples'):
                merged[key] += result['profile'][key]
            merged['hottest'].update(dict(result['profile']['hottest']))
    for name, merged in profiles.items():
        lines.append(format_profile(f'{name} (all rounds)', {
            **merged, 'hottest': merged['hottest'].most_common(),
            'metrics_percent': 100 * merged['metrics_samples'] / merged['samples'] if merged['samples'] else 0.0}))
    if result_line:
        lines.append(result_line)
    return lines


def interval(low, high):
    return 'n/a' if low is None else f'{low:+.2f} % .. {high:+.2f} %'


METRICS_PACKAGE = 'org.dozerdb.metrics.'  # the module, the relocated Dropwizard library included
MONITORS = 'org.neo4j.monitoring.Monitors'  # Neo4j's monitor bus: its proxies dispatch every event to the listeners
JDK_PACKAGES = ('java.', 'jdk.', 'sun.')
STACK_DEPTH = 128


def jfr_stacks(text):
    """The stack traces (top frame first, as method names) of the jdk.ExecutionSample events `jfr print` wrote."""
    stacks, frames, inside = [], None, False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith('jdk.ExecutionSample'):
            inside = True
        elif inside and stripped == 'stackTrace = [':
            frames = []
        elif frames is not None and stripped == ']':
            stacks.append(frames)
            frames, inside = None, False
        elif frames is not None and stripped and stripped != '...':
            frames.append(stripped.split('(', 1)[0])
    return stacks


def metrics_share(stacks):
    """Classify CPU samples: metrics code (any frame of the module, inclusive), and monitor dispatch.

    A sample runs metrics code when any frame belongs to the module; its topmost such frame is the hot spot. A sample
    is monitor dispatch when its topmost frame outside the JDK is Neo4j's monitor bus (the proxy that hands an event
    to its listeners, reflectively): the cost a listener adds without running listener code. Neo4j dispatches with
    or without listeners, so compare the dispatch share with metrics off and on.
    """
    hottest = collections.Counter()
    dispatch = 0
    for frames in stacks:
        top = next((frame for frame in frames if frame.startswith(METRICS_PACKAGE)), None)
        if top:
            hottest[top] += 1
            continue
        own = next((frame for frame in frames if not frame.startswith(JDK_PACKAGES)), '')
        if own.startswith(MONITORS):
            dispatch += 1
    total, inside = len(stacks), sum(hottest.values())
    return {'samples': total, 'metrics_samples': inside, 'dispatch_samples': dispatch,
            'metrics_percent': 100 * inside / total if total else 0.0, 'hottest': hottest.most_common()}


def format_profile(configuration, share):
    hottest = ', '.join(f'{frame.removeprefix(METRICS_PACKAGE)} {count}' for frame, count in share['hottest'][:5])
    dispatch = share['dispatch_samples']
    return (f"PROFILE {configuration}: {share['metrics_samples']} of {share['samples']} CPU samples "
            f"({share['metrics_percent']:.3f} %) in metrics code, {dispatch} "
            f"({100 * dispatch / share['samples'] if share['samples'] else 0.0:.3f} %) in monitor dispatch"
            + (f'; hottest: {hottest}' if hottest else ''))


# ---------------------------------------------------------------- workload driver

def worker(tag, url, api, authorization, seed, start, warm_end, end, queue):
    """One client: a keep-alive connection running the deterministic operation sequence until end."""
    rng = random.Random(seed)
    host, port = url.removeprefix('http://').split(':')
    connection = http.client.HTTPConnection(host, int(port), timeout=30)
    path = '/db/neo4j/query/v2' if api == 'query' else '/db/neo4j/tx/commit'
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json', 'Authorization': authorization}
    counts = {'read': 0, 'write': 0, 'errors': 0, 'late': 0}
    middle = warm_end + (end - warm_end) / 2
    first_error = None
    while time.monotonic() < start:
        time.sleep(0.001)
    while True:
        now = time.monotonic()
        if now >= end:
            break
        kind, statement, parameters = pick(rng)
        try:
            connection.request('POST', path, json.dumps(request_body(api, statement, parameters)), headers)
            response = connection.getresponse()
            body = response.read()
            ok = 200 <= response.status < 300 and not json.loads(body).get('errors')
        except (OSError, http.client.HTTPException, ValueError) as exc:
            connection.close()
            ok, body = False, str(exc).encode()
        if time.monotonic() <= end and now >= warm_end:
            if ok:
                counts[kind] += 1
                counts['late'] += now >= middle
            else:
                counts['errors'] += 1
        if not ok and first_error is None:
            first_error = body[:300].decode(errors='replace')
    connection.close()
    queue.put((tag, counts, first_error))


def scrape(url, stop, counter):
    """The worst case's Prometheus scraper: one GET of /metrics per second until stopped."""
    while not stop.wait(1.0):
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                response.read()
            counter['ok'] += 1
        except OSError:
            counter['failed'] += 1


def round_settings(configuration):
    """The settings a round's server gets: identical memory, and the metrics settings of the configuration.

    The open configuration's Prometheus endpoint is added by the smoke helper, which knows its port.
    """
    settings = dict(MEMORY)
    if configuration == 'off':
        settings['server.metrics.enabled'] = 'false'
    elif configuration == 'open':
        settings['server.metrics.filter'] = '*'
    return settings


def foreign_metrics_settings(conf_text, own):
    """Metrics settings in a neo4j.conf that the round did not set: they would make the configurations differ."""
    return {key: value for key, value in smoke.parse_conf(conf_text).items()
            if key.startswith('server.metrics.') and key not in own}


def start_server(mode, configuration):
    """A fresh packaged server in the configuration, started and loaded with the data set."""
    settings = round_settings(configuration)
    server = smoke.Server(mode, None, prometheus=configuration == 'open', settings=settings)
    try:
        own = set(settings) | set(smoke.prometheus_settings('', 0) if server.prometheus else ())
        foreign = foreign_metrics_settings((server.home / 'conf/neo4j.conf').read_text(), own)
        if foreign:
            raise RuntimeError(f'The packaged neo4j.conf sets metrics settings; the configurations would differ: {foreign}')
        server.start()
        for statement in SETUP:
            server.query(statement)
        return server
    except BaseException:
        server.close()
        raise


def measure(block, servers, args):
    """Drive every (configuration, server) pair with its own clients over one common window.

    Sequential rounds pass one pair; a duet passes one per configuration, so that whatever else the machine does
    during the window reaches all of them alike.
    """
    ctx = multiprocessing.get_context('spawn')
    queue = ctx.Queue()
    start = time.monotonic() + 2.0
    warm_end = start + args.warmup
    end = warm_end + args.duration
    processes = [ctx.Process(target=worker, args=(tag, server.url, args.api, smoke.AUTH, args.seed * 1000 + i,
                                                  start, warm_end, end, queue))
                 for tag, (_, server) in enumerate(servers) for i in range(args.clients)]
    for process in processes:
        process.start()
    recordings = {}
    if args.profile:
        for tag, (configuration, server) in enumerate(servers):
            recordings[tag] = Recording(server, args.profile / f'round-{block}-{configuration}.jfr')
            threading.Timer(max(0.0, warm_end - time.monotonic()), recordings[tag].start).start()
    stop, scrapes, scrapers = threading.Event(), {}, []
    for tag, (configuration, server) in enumerate(servers):
        if configuration == 'open':
            scrapes[tag] = {'ok': 0, 'failed': 0}
            scrapers.append(threading.Thread(target=scrape, daemon=True, args=(
                f'http://127.0.0.1:{server.prometheus}/metrics', stop, scrapes[tag])))
            scrapers[-1].start()
    load_before = os.getloadavg()[0]
    totals = [{'read': 0, 'write': 0, 'errors': 0, 'late': 0} for _ in servers]
    errors = [[] for _ in servers]
    for _ in processes:
        tag, counts, first_error = queue.get(timeout=args.warmup + args.duration + 120)
        for key in totals[tag]:
            totals[tag][key] += counts[key]
        if first_error:
            errors[tag].append(first_error)
    for process in processes:
        process.join()
    load_after = os.getloadavg()[0]
    stop.set()
    for scraper in scrapers:
        scraper.join()
    results = []
    for tag, (configuration, server) in enumerate(servers):
        profile = recordings[tag].stop() if tag in recordings else None
        mbeans = len(server.query('CALL dbms.queryJmx("neo4j.metrics:*") YIELD name RETURN name')[0]['data'])
        if errors[tag]:
            print(f'WARN {configuration}: first error: {errors[tag][0]}', flush=True)
        total = totals[tag]
        results.append({
            'block': block, 'configuration': configuration,
            'throughput': (total['read'] + total['write']) / args.duration,
            'reads': total['read'], 'writes': total['write'], 'errors': total['errors'], 'second_half': total['late'],
            'duration': args.duration, 'mbeans': mbeans, 'load_before': load_before, 'load_after': load_after,
            'scrapes': scrapes[tag]['ok'] if tag in scrapes else None,
            'scrape_failures': scrapes[tag]['failed'] if tag in scrapes else None, 'profile': profile})
    return results


def run_block(mode, block, configurations, args):
    """One block: the configurations one after another on fresh servers, or with --duet side by side."""
    results = []
    if args.duet:
        servers = []
        try:
            for configuration in configurations:
                servers.append((configuration, start_server(mode, configuration)))
            results.extend(measure(block, servers, args))
        finally:
            for _, server in servers:
                discard(server, args)
    else:
        for configuration in configurations:
            server = start_server(mode, configuration)
            try:
                results.extend(measure(block, [(configuration, server)], args))
            finally:
                discard(server, args)
    return results


def discard(server, args):
    server.close()
    if not args.keep:
        shutil.rmtree(server.work, ignore_errors=True)


class Recording:
    """A JFR recording (profile settings) of the server JVM over the measured window, and its metrics share."""

    def __init__(self, server, path):
        self.path = path.resolve()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        listing = subprocess.run(['jcmd', '-l'], capture_output=True, text=True, check=True).stdout
        pids = [line.split()[0] for line in listing.splitlines() if f'--home-dir={server.home}' in line]
        if len(pids) != 1:
            raise RuntimeError(f'Expected one server JVM for {server.home}: {listing}')
        self.pid = pids[0]

    def start(self):
        subprocess.run(['jcmd', self.pid, 'JFR.configure', f'stackdepth={STACK_DEPTH}'], check=True, capture_output=True)
        subprocess.run(['jcmd', self.pid, 'JFR.start', 'name=overhead', 'settings=profile'],
                       check=True, capture_output=True)

    def stop(self):
        subprocess.run(['jcmd', self.pid, 'JFR.stop', 'name=overhead', f'filename={self.path}'],
                       check=True, capture_output=True)
        text = subprocess.run(['jfr', 'print', '--events', 'jdk.ExecutionSample', '--stack-depth', str(STACK_DEPTH),
                               str(self.path)], capture_output=True, text=True, check=True).stdout
        return metrics_share(jfr_stacks(text))


def environment():
    def output(*command):
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=30)
            return (result.stdout + result.stderr).strip()
        except OSError:
            return 'unknown'
    java = Path(os.environ['JAVA_HOME'], 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
    cpu = output('sysctl', '-n', 'machdep.cpu.brand_string') if platform.system() == 'Darwin' else platform.processor()
    memory = output('sysctl', '-n', 'hw.memsize') if platform.system() == 'Darwin' else ''
    return {
        'commit': output('git', '-C', str(smoke.ROOT), 'rev-parse', '--short', 'HEAD'),
        'dirty': bool(output('git', '-C', str(smoke.ROOT), 'status', '--porcelain', '--untracked-files=no')),
        'platform': platform.platform(),
        'cpu': cpu,
        'cores': os.cpu_count(),
        'memory_gib': round(int(memory) / 2 ** 30) if memory.isdigit() else None,
        'java': output(str(java), '-version').splitlines()[0] if output(str(java), '-version') else 'unknown',
        'load_average': os.getloadavg(),
    }


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    parser.add_argument('mode', nargs='?', default='distribution', choices=['distribution', 'plugin'],
                        help='packaged form: the distribution archive (default) or the plugin JAR in stock Neo4j')
    parser.add_argument('--rounds', type=int, default=3, help='rounds per configuration (default 3)')
    parser.add_argument('--warmup', type=float, default=20, help='seconds of warm-up per round (default 20)')
    parser.add_argument('--duration', type=float, default=60, help='measured seconds per round (default 60)')
    parser.add_argument('--clients', type=int, default=16, help='concurrent client processes per server (default 16)')
    parser.add_argument('--api', choices=['query', 'tx'], default='query',
                        help='HTTP Query API (default, served over a local Bolt connection) or the tx endpoint')
    parser.add_argument('--worst-case', action='store_true',
                        help='add the open configuration: filter *, Prometheus scraped once per second')
    parser.add_argument('--duet', action='store_true',
                        help='run the configurations of a block side by side, each server with its own clients, '
                             'instead of one after another (duet benchmarking: interference reaches both alike)')
    parser.add_argument('--seed', type=int, default=25, help='seed of the operation sequences (default 25)')
    parser.add_argument('--output', type=Path, help='write the environment and every round as JSON')
    parser.add_argument('--profile', type=Path, metavar='DIR',
                        help='record the server with JFR over each measured window into DIR and report the share of '
                             'CPU samples in metrics code (JFR costs throughput itself: a profiling run attributes, '
                             'it does not measure the budget)')
    parser.add_argument('--keep', action='store_true', help='keep each round\'s server directory under .build')
    return parser.parse_args(argv)


def main():
    args = parse_args()
    configurations = ['off', 'defaults'] + (['open'] if args.worst_case else [])
    env = environment()
    print(f"ENV commit {env['commit']}{' (dirty)' if env['dirty'] else ''}, {env['platform']}, {env['cpu']}, "
          f"{env['cores']} cores, {env['memory_gib']} GiB, {env['java']}, "
          f"load {' '.join(f'{x:.1f}' for x in env['load_average'])}", flush=True)
    for name in configurations:
        print(f'CONFIGURATION {name}: {CONFIGURATIONS[name]}', flush=True)
    print(f"PLAN {args.mode}, api {args.api}, {args.clients} clients per server, warm-up {args.warmup:g} s, "
          f"measure {args.duration:g} s, {args.rounds} {'duet ' if args.duet else ''}rounds of "
          + ', '.join(configurations), flush=True)
    order = schedule(configurations, args.rounds)
    blocks = {}
    for block, configuration in order:
        blocks.setdefault(block, []).append(configuration)
    results = []
    for block, names in blocks.items():
        for result in run_block(args.mode, block, names, args):
            results.append(result)
            print(format_round(len(results), len(order), result), flush=True)
        if args.output:
            args.output.write_text(json.dumps({'environment': env, 'arguments': {k: str(v) for k, v in vars(args).items()},
                                               'rounds': results}, indent=2) + '\n')
    for line in format_summary(results):
        print(line, flush=True)
    if any(r['errors'] for r in results):
        print('WARN some requests failed; see the WARN lines above', flush=True)


if __name__ == '__main__':
    main()
