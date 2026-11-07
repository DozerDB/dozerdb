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

import collections
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import overhead_harness as harness  # noqa: E402  (the harness shared with the query-log overhead script)
from overhead_harness import (MEMORY, PEOPLE, WEIGHTS, interval, jfr_stacks, paired_delta, pick,  # noqa: E402
                              request_body, schedule, summarize, t95, unpaired_delta)

BUDGET_PERCENT = 1.0
BASELINE = 'off'
CONFIGURATIONS = {
    'off': 'server.metrics.enabled=false',
    'defaults': "Neo4j's defaults: metrics on, default filter, CSV and JMX on",
    'open': 'server.metrics.filter=*, Prometheus on and scraped every second',
}


def verdict(low, high, budget=BUDGET_PERCENT):
    return harness.verdict(low, high, budget)


class MetricsOverhead(harness.Experiment):
    """The metrics module's experiment: its configurations, the MBean count per round, the metrics share of a
    profile and the budget verdict of `defaults`; `open` is shown for information."""
    configurations = CONFIGURATIONS
    baseline = BASELINE
    prefix = 'server.metrics.'

    def __init__(self, budget=BUDGET_PERCENT):
        self.budget = budget

    def settings(self, configuration):
        if configuration == 'off':
            return {'server.metrics.enabled': 'false'}
        if configuration == 'open':
            return {'server.metrics.filter': '*'}
        return {}

    def prometheus(self, configuration):
        return configuration == 'open'

    def observe(self, configuration, server):
        return {'mbeans': len(server.query('CALL dbms.queryJmx("neo4j.metrics:*") YIELD name RETURN name')[0]['data'])}

    def describe(self, result):
        return f"metrics MBeans {result['mbeans']}"

    def classify(self, stacks):
        return metrics_share(stacks)

    def describe_profile(self, label, share):
        return format_profile(label, share)

    def merge_profiles(self, shares):
        merged = {'samples': 0, 'metrics_samples': 0, 'dispatch_samples': 0, 'hottest': collections.Counter()}
        for share in shares:
            for key in ('samples', 'metrics_samples', 'dispatch_samples'):
                merged[key] += share[key]
            merged['hottest'].update(dict(share['hottest']))
        return {**merged, 'hottest': merged['hottest'].most_common(),
                'metrics_percent': 100 * merged['metrics_samples'] / merged['samples'] if merged['samples'] else 0.0}

    def judge(self, configuration, low, high):
        status, reason = verdict(low, high, self.budget)
        if configuration == 'defaults':
            return f'RESULT {status} {configuration}: {reason}'
        return f'INFO {status} {configuration}: {reason} (worst case, outside the budget)'


EXPERIMENT = MetricsOverhead()


def format_round(index, total, result):
    return harness.format_round(index, total, result, EXPERIMENT)


def format_summary(results, budget=BUDGET_PERCENT):
    """The summary lines: per configuration, per comparison against the baseline, and the budget verdict."""
    return harness.format_summary(results, MetricsOverhead(budget))


def round_settings(configuration):
    """The settings a round's server gets: identical memory, and the metrics settings of the configuration."""
    return harness.round_settings(EXPERIMENT, configuration)


def foreign_metrics_settings(conf_text, own):
    """Metrics settings in a neo4j.conf that the round did not set: they would make the configurations differ."""
    return harness.foreign_settings(conf_text, EXPERIMENT.prefix, own)


# ---------------------------------------------------------------- profile attribution

METRICS_PACKAGE = 'org.dozerdb.metrics.'  # the module, the relocated Dropwizard library included
MONITORS = 'org.neo4j.monitoring.Monitors'  # Neo4j's monitor bus: its proxies dispatch every event to the listeners
JDK_PACKAGES = ('java.', 'jdk.', 'sun.')


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


def parse_args(argv=None):
    parser = harness.argument_parser(__doc__.split('\n', 1)[0], 'metrics')
    parser.add_argument('--worst-case', action='store_true',
                        help='add the open configuration: filter *, Prometheus scraped once per second')
    return parser.parse_args(argv)


def main():
    args = parse_args()
    harness.run(EXPERIMENT, ['off', 'defaults'] + (['open'] if args.worst_case else []), args)


if __name__ == '__main__':
    main()
