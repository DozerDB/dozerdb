#!/usr/bin/env python3
"""Measure the throughput cost of the query log on a packaged DozerDB: INFO, VERBOSE and the mitigations against OFF, no budget.

Usage: python3 scripts/query-log-overhead.py [distribution|plugin] [--rounds 3] [--warmup 20] [--duration 60]
       [--clients 16] [--duet] [--configurations NAME ...] [--match] [--output results.json] [--profile DIR]

Runs the shared overhead harness's fixed read and write workload (scripts/overhead_harness.py: fresh packaged
servers per round, alternating blocks or --duet side by side, paired and unpaired 95 % intervals, optional JFR
recording) against the configurations of the query log, all of them or those named with --configurations:

  off             db.logs.query.enabled=OFF and db.logs.query.transaction.enabled=OFF: the module's listeners hear
                  every event and return at once
  info            db.logs.query.enabled=INFO with db.logs.query.threshold=0s: one end entry per query
  verbose         Neo4j's defaults, nothing set: a start entry and an end entry per query, through the QueryLog
                  appender of the shipped server-logs.xml
  no-flush        verbose, with scripts/fixtures/server-logs-no-flush.xml installed as the server's
                  conf/server-logs.xml: the QueryLog appender with immediateFlush="false"
  async           verbose, with server-logs-async.xml: a Log4j Async appender (a blocking queue of 1024 entries, one
                  writer thread) in front of the shipped QueryLog appender
  async-no-flush  verbose, with server-logs-async-no-flush.xml: both

The mitigations are operator changes to server-logs.xml, the file the Operations Manual's "Configure logging" page
documents; the fixtures are whole files written by DozerDB with the shipped QueryLog appender's file, pattern and
rotation, changed only as each mitigation says. The report prints every round with the size the query.log reached
(Log4j rolls it at 20 MB, seven files kept), its files, its entries and, where the log holds start entries, how
many entries are unpaired (a start without its end or an end without its start among the entries the files still
hold: an entry dropped on the way to the file shows up here; with --match also how many entries fit no shape of
the field inventory, the release-gate check's matcher run over the whole file), then the mean and spread per
configuration and the throughput delta of every other configuration against off with a 95 % confidence interval. There is no pass/fail
budget and no RESULT line: the cost of the module's OFF fast path against a server without the module cannot be
measured this way, so the record is every configuration against off, kept per release line in the maintainer's
overhead record. With --profile DIR the report adds the share of the server's CPU samples in
query-log code, with the hottest query-log frames.
"""

import collections
import importlib.util
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import overhead_harness as harness  # noqa: E402  (the harness shared with the metrics overhead script)

# The release-gate check beside this script, for its matcher (--match): the entries of a plain query.log and the
# shapes the field inventory gives them.
_spec = importlib.util.spec_from_file_location('query_log_check', Path(__file__).resolve().with_name('query-log-check.py'))
check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check)

MITIGATIONS = {
    'no-flush': 'verbose with server-logs-no-flush.xml: the QueryLog appender with immediateFlush="false"',
    'async': 'verbose with server-logs-async.xml: a blocking Log4j Async appender (1024 entries) in front of QueryLog',
    'async-no-flush': 'verbose with server-logs-async-no-flush.xml: the Async appender and immediateFlush="false"',
}
CONFIGURATIONS = {
    'off': 'db.logs.query.enabled=OFF, db.logs.query.transaction.enabled=OFF',
    'info': 'db.logs.query.enabled=INFO, db.logs.query.threshold=0s: one end entry per query',
    'verbose': "Neo4j's defaults: db.logs.query.enabled=VERBOSE, a start and an end entry per query",
    **MITIGATIONS,
}
FIXTURES = harness.smoke.ROOT / 'scripts/fixtures'
QUERY_LOG_PACKAGE = 'org.dozerdb.querylog.'
MONITORS = 'org.neo4j.monitoring.Monitors'  # Neo4j's monitor bus: its proxies dispatch every event to the listeners
JDK_PACKAGES = ('java.', 'jdk.', 'sun.')
# A line that opens a plain entry: the layout's timestamp and level, then the %m, which begins with the start
# marker and the query id at VERBOSE, with the id alone on an end entry, and without an id at INFO.
ENTRY = re.compile(r'^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}[+-]\d{4} [A-Z]+ +(Query started: )?(?:id:(\d+) - )?',
                   re.MULTILINE)


def fixture(configuration):
    """The server-logs.xml of a mitigation, under scripts/fixtures."""
    return FIXTURES / f'server-logs-{configuration}.xml'


def audit(texts):
    """What the query.log files of a round hold, read together: the number of entries (a line opening with the
    layout's timestamp and level; a stack trace's lines belong to their entry), of start entries, and of unpaired
    entries: a start whose end is in none of the files, or an end whose start is in none of them. An end entry whose
    id is below the oldest start the files hold had its start in a file the rotation removed, so it is not unpaired.
    Pairs are checked at VERBOSE only (no starts: none unpaired)."""
    starts, ends, entries = set(), set(), 0
    for text in texts:
        for match in ENTRY.finditer(text):
            entries += 1
            if match.group(2) is None:
                continue
            (starts if match.group(1) else ends).add(int(match.group(2)))
    unpaired = 0
    if starts:
        oldest = min(starts)
        unpaired = len(starts - ends) + sum(1 for end in ends - starts if end >= oldest)
    return {'entries': entries, 'starts': len(starts), 'unpaired': unpaired}


def unmatched(texts, inventory):
    """The entries of the query.log files that fit no shape of the field inventory, or more than one: the
    release-gate check's matcher run over the whole file (about a quarter of a minute per 20 MB file)."""
    return sum(1 for text in texts for entry in check.plain_entries(text, inventory)
               if len(inventory.matches(entry.level, entry.message)) != 1)


class QueryLogOverhead(harness.Experiment):
    """The query log's experiment: its configurations, the mitigations installed as the server's server-logs.xml,
    the size and the audit of the query.log per round (every entry matched against the inventory with --match), the
    query-log share of a profile, and no verdict (the result is recorded, not judged)."""
    configurations = CONFIGURATIONS
    baseline = 'off'
    prefix = 'db.logs.query.'
    match = False

    def settings(self, configuration):
        if configuration == 'off':
            return {'db.logs.query.enabled': 'OFF', 'db.logs.query.transaction.enabled': 'OFF'}
        if configuration == 'info':
            return {'db.logs.query.enabled': 'INFO', 'db.logs.query.threshold': '0s'}
        return {}

    def prepare(self, configuration, home):
        """A mitigation's fixture replaces the shipped conf/server-logs.xml, as an operator's edit would."""
        if configuration in MITIGATIONS:
            shutil.copyfile(fixture(configuration), home / 'conf/server-logs.xml')

    def observe(self, configuration, server):
        files = sorted((server.home / 'logs').glob('query.log*'))
        texts = [path.read_text(errors='replace') for path in files]
        audited = audit(texts)
        observed = {'query_log_bytes': sum(path.stat().st_size for path in files), 'query_log_files': len(files),
                    'query_log_entries': audited['entries'], 'query_log_starts': audited['starts'],
                    'query_log_unpaired': audited['unpaired']}
        if self.match:
            observed['query_log_unmatched'] = unmatched(texts, check.Inventory.load())
        return observed

    def describe(self, result):
        text = f"query.log {size(result['query_log_bytes'])}"
        if 'query_log_entries' not in result:
            return text
        files = result['query_log_files']
        text += f" in {files} file{'s' if files != 1 else ''}, {result['query_log_entries']} entries"
        if result['query_log_starts']:
            text += f", {result['query_log_unpaired']} unpaired"
        if 'query_log_unmatched' in result:
            text += f", {result['query_log_unmatched']} unmatched"
        return text

    def classify(self, stacks):
        return query_log_share(stacks)

    def describe_profile(self, label, share):
        return format_profile(label, share)

    def merge_profiles(self, shares):
        merged = {'samples': 0, 'query_log_samples': 0, 'dispatch_samples': 0, 'hottest': collections.Counter()}
        for share in shares:
            for key in ('samples', 'query_log_samples', 'dispatch_samples'):
                merged[key] += share[key]
            merged['hottest'].update(dict(share['hottest']))
        return {**merged, 'hottest': merged['hottest'].most_common()}


def size(count):
    """A byte count in KiB or MiB with one decimal."""
    return f'{count / 2 ** 20:.1f} MiB' if count >= 2 ** 20 else f'{count / 2 ** 10:.1f} KiB'


def query_log_share(stacks):
    """Classify CPU samples: query-log code (any frame of the module, its topmost such frame the hot spot), and
    monitor dispatch (the topmost frame outside the JDK is Neo4j's monitor bus), which Neo4j pays with or without
    listeners and a listener makes larger."""
    hottest = collections.Counter()
    dispatch = 0
    for frames in stacks:
        top = next((frame for frame in frames if frame.startswith(QUERY_LOG_PACKAGE)), None)
        if top:
            hottest[top] += 1
            continue
        own = next((frame for frame in frames if not frame.startswith(JDK_PACKAGES)), '')
        if own.startswith(MONITORS):
            dispatch += 1
    return {'samples': len(stacks), 'query_log_samples': sum(hottest.values()), 'dispatch_samples': dispatch,
            'hottest': hottest.most_common()}


def format_profile(configuration, share):
    total = share['samples'] or 1
    hottest = ', '.join(f'{frame.removeprefix(QUERY_LOG_PACKAGE)} {count}' for frame, count in share['hottest'][:5])
    return (f"PROFILE {configuration}: {share['query_log_samples']} of {share['samples']} CPU samples "
            f"({100 * share['query_log_samples'] / total:.3f} %) in query-log code, {share['dispatch_samples']} "
            f"({100 * share['dispatch_samples'] / total:.3f} %) in monitor dispatch"
            + (f'; hottest: {hottest}' if hottest else ''))


EXPERIMENT = QueryLogOverhead()


def argument_parser():
    """The harness's options, plus the choice of configurations (all of them by default, off first)."""
    parser = harness.argument_parser(__doc__.split('\n', 1)[0], 'query-log')
    parser.add_argument('--configurations', nargs='+', choices=list(CONFIGURATIONS), default=list(CONFIGURATIONS),
                        metavar='NAME', help='the configurations of a block, in this order (default: all, '
                        + ', '.join(CONFIGURATIONS) + ')')
    parser.add_argument('--match', action='store_true',
                        help="after each round, match every entry of the server's query.log files against the field "
                             "inventory (the release-gate check's matcher) and report the unmatched ones; about a "
                             'quarter of a minute per 20 MB file, after the measured window')
    return parser


def main():
    args = argument_parser().parse_args()
    EXPERIMENT.match = args.match
    harness.run(EXPERIMENT, args.configurations, args)


if __name__ == '__main__':
    main()
