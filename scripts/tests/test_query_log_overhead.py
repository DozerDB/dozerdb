"""The query-log overhead experiment of scripts/query-log-overhead.py: its configurations and what each sets, the
mitigations through a fixture server-logs.xml, the audit of the query.log it reads after a round, that it judges no
budget, and the query-log share of a JFR profile."""
import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import overhead_harness as harness  # noqa: E402
spec = importlib.util.spec_from_file_location('query_log_overhead', SCRIPTS / 'query-log-overhead.py')
overhead = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overhead)

EXPERIMENT = overhead.QueryLogOverhead()
MITIGATIONS = ('no-flush', 'async', 'async-no-flush')
PREFIX = '2026-11-06 09:15:02.117+0000 '
QUERY_LOG_PATTERN = '%d{yyyy-MM-dd HH:mm:ss.SSSZ}{GMT+0} %-5p %m%n'


def result(block, configuration, throughput):
    return {'block': block, 'configuration': configuration, 'throughput': throughput, 'reads': 80 * throughput,
            'writes': 20 * throughput, 'second_half': 40 * throughput, 'errors': 0, 'duration': 100,
            'query_log_bytes': 1024, 'load_before': 1.0, 'load_after': 2.0}


JFR_PRINT = """jdk.ExecutionSample {
  stackTrace = [
    org.dozerdb.querylog.entry.PlainTextRenderer.render(QueryEntry) line: 40
    org.dozerdb.querylog.extension.RenderedEntry.of(QueryEntry, AnnotationDataFormat) line: 20
    org.dozerdb.querylog.adapter.QueryExecutionListener.endSuccess(ExecutingQuery) line: 88
    java.lang.reflect.Method.invoke(Object, Object[]) line: 580
    org.neo4j.monitoring.Monitors$MonitorInvocationHandler.invoke(Object, Method, Object[]) line: 199
  ]
}

jdk.ExecutionSample {
  stackTrace = [
    java.lang.reflect.Method.invoke(Object, Object[]) line: 580
    org.neo4j.monitoring.Monitors$UntaggedMonitorListenerInvocationHandler.invoke(Object, Method, Object[], String[]) line: 168
  ]
}

jdk.ExecutionSample {
  stackTrace = [
    org.eclipse.jetty.server.internal.HttpConnection$HttpStreamOverHTTP1.consumeAvailable() line: 1279
  ]
}
"""


class ConfigurationTests(unittest.TestCase):
    def test_off_info_verbose_then_the_mitigations_with_off_as_the_baseline(self):
        self.assertEqual(list(EXPERIMENT.configurations), ['off', 'info', 'verbose', *MITIGATIONS])
        self.assertEqual(EXPERIMENT.baseline, 'off')
        self.assertEqual(EXPERIMENT.prefix, 'db.logs.query.')

    def test_a_mitigation_keeps_neo4j_defaults_for_the_query_log_and_names_its_fixture(self):
        for name in MITIGATIONS:
            self.assertEqual(EXPERIMENT.settings(name), {}, name)
            fixture = overhead.fixture(name)
            self.assertEqual(fixture, SCRIPTS / 'fixtures' / f'server-logs-{name}.xml')
            self.assertTrue(fixture.is_file(), fixture)

    def test_prepare_installs_the_fixture_as_the_servers_logging_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            home = pathlib.Path(directory)
            shipped = home / 'conf/server-logs.xml'
            shipped.parent.mkdir()
            shipped.write_text('<Configuration/>')
            EXPERIMENT.prepare('verbose', home)
            self.assertEqual(shipped.read_text(), '<Configuration/>')
            EXPERIMENT.prepare('async', home)
            self.assertEqual(shipped.read_text(), overhead.fixture('async').read_text())

    def test_the_configurations_to_run_are_chosen_on_the_command_line(self):
        parser = overhead.argument_parser()
        self.assertEqual(parser.parse_args([]).configurations, ['off', 'info', 'verbose', *MITIGATIONS])
        self.assertEqual(parser.parse_args(['--configurations', 'off', 'verbose', 'async']).configurations,
                         ['off', 'verbose', 'async'])
        self.assertFalse(parser.parse_args([]).match)
        self.assertTrue(parser.parse_args(['--match']).match)
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            parser.parse_args(['--configurations', 'off', 'quiet'])

    def test_off_switches_both_detail_levels_off(self):
        self.assertEqual(EXPERIMENT.settings('off'),
                         {'db.logs.query.enabled': 'OFF', 'db.logs.query.transaction.enabled': 'OFF'})

    def test_info_logs_every_query_end_at_a_zero_threshold(self):
        self.assertEqual(EXPERIMENT.settings('info'), {'db.logs.query.enabled': 'INFO', 'db.logs.query.threshold': '0s'})

    def test_verbose_is_neo4j_defaults_and_nothing_opens_prometheus(self):
        self.assertEqual(EXPERIMENT.settings('verbose'), {})
        for name in EXPERIMENT.configurations:
            self.assertFalse(EXPERIMENT.prometheus(name))


class FixtureTests(unittest.TestCase):
    """The fixtures are whole server-logs.xml files: DozerDB-written, minimal, with the QueryLog appender Neo4j ships
    (file, pattern, 20 MB rotation, seven files) changed only as the mitigation says, and the other logs routed."""

    def appenders(self, name):
        root = ElementTree.parse(overhead.fixture(name)).getroot()
        return {appender.get('name'): appender for appender in root.find('Appenders')}

    def loggers(self, name):
        root = ElementTree.parse(overhead.fixture(name)).getroot()
        return {logger.get('name', 'Root'): logger for logger in root.find('Loggers')}

    def test_every_fixture_keeps_the_query_log_appender_and_routes_the_other_logs(self):
        for name in MITIGATIONS:
            with self.subTest(name):
                query_log = self.appenders(name)['QueryLog']
                self.assertEqual(query_log.tag, 'RollingRandomAccessFile')
                self.assertEqual(query_log.get('fileName'), '${config:server.directories.logs}/query.log')
                self.assertEqual(query_log.get('filePattern'), '$${config:server.directories.logs}/query.log.%02i')
                self.assertEqual(query_log.find('PatternLayout').get('pattern'), QUERY_LOG_PATTERN)
                self.assertEqual(query_log.find('Policies/SizeBasedTriggeringPolicy').get('size'), '20 MB')
                self.assertEqual(query_log.find('DefaultRolloverStrategy').attrib, {'fileIndex': 'min', 'max': '7'})
                loggers = self.loggers(name)
                self.assertEqual(set(loggers), {'Root', 'QueryLogger', 'HttpLogger', 'SecurityLogger'})
                self.assertEqual(loggers['Root'].find('AppenderRef').get('ref'), 'DebugLog')
                self.assertEqual(loggers['HttpLogger'].find('AppenderRef').get('ref'), 'HttpLog')
                self.assertEqual(loggers['SecurityLogger'].find('AppenderRef').get('ref'), 'SecurityLog')
                self.assertEqual(loggers['QueryLogger'].get('additivity'), 'false')
                self.assertEqual(self.appenders(name)['DebugLog'].find('JsonTemplateLayout').get('eventTemplateUri'),
                                 'classpath:org/neo4j/logging/StructuredLayoutWithMessage.json')

    def test_no_flush_switches_immediate_flush_off_and_nothing_else(self):
        appenders = self.appenders('no-flush')
        self.assertEqual(appenders['QueryLog'].get('immediateFlush'), 'false')
        self.assertNotIn('Async', [a.tag for a in appenders.values()])
        self.assertEqual(self.loggers('no-flush')['QueryLogger'].find('AppenderRef').get('ref'), 'QueryLog')

    def test_async_puts_a_blocking_async_appender_in_front_of_the_unchanged_query_log_appender(self):
        appenders = self.appenders('async')
        self.assertIsNone(appenders['QueryLog'].get('immediateFlush'))
        asynchronous = appenders['QueryLogAsync']
        self.assertEqual(asynchronous.tag, 'Async')
        self.assertEqual(asynchronous.get('blocking'), 'true')
        self.assertEqual(asynchronous.get('bufferSize'), '1024')
        self.assertEqual(asynchronous.get('errorRef'), 'DebugLog')
        self.assertEqual(asynchronous.find('AppenderRef').get('ref'), 'QueryLog')
        self.assertEqual(self.loggers('async')['QueryLogger'].find('AppenderRef').get('ref'), 'QueryLogAsync')

    def test_async_no_flush_combines_both(self):
        appenders = self.appenders('async-no-flush')
        self.assertEqual(appenders['QueryLog'].get('immediateFlush'), 'false')
        self.assertEqual(appenders['QueryLogAsync'].get('blocking'), 'true')
        self.assertEqual(self.loggers('async-no-flush')['QueryLogger'].find('AppenderRef').get('ref'), 'QueryLogAsync')


QUERY_LOG = """\
2026-11-06 09:15:02.117+0000 INFO  Query started: id:7 - transaction id:3 - 0 ms: (planning: 0, waiting: 0) - 0 B - 0 page hits, 0 page faults - server-session\thttp\t127.0.0.1\t/db/neo4j/query/v2\tneo4j - neo4j - RETURN 1 - {} - runtime=null - {}
2026-11-06 09:15:02.117+0000 INFO  Query started: id:8 - transaction id:4 - 0 ms: (planning: 0, waiting: 0) - 0 B - 0 page hits, 0 page faults - server-session\thttp\t127.0.0.1\t/db/neo4j/query/v2\tneo4j - neo4j - RETURN 2 - {} - runtime=null - {}
2026-11-06 09:15:02.119+0000 INFO  id:7 - transaction id:3 - 2 ms: (planning: 1, waiting: 0) - 72 B - 1 page hits, 0 page faults - server-session\thttp\t127.0.0.1\t/db/neo4j/query/v2\tneo4j - neo4j - RETURN 1 - {} - runtime=slotted - {}
2026-11-06 09:15:02.120+0000 ERROR id:9 - transaction id:5 - 1 ms: (planning: 1, waiting: 0) - 0 B - 0 page hits, 0 page faults - server-session\thttp\t127.0.0.1\t/db/neo4j/query/v2\tneo4j - neo4j - MATCH (n) RETURN m - {} - runtime=null - {} - Variable `m` not defined
org.neo4j.exceptions.SyntaxException: Variable `m` not defined
\tat org.neo4j.cypher.internal.Something.check(Something.java:1)
2026-11-06 09:15:02.121+0000 INFO  id:1 - transaction id:1 - 2 ms: (planning: 1, waiting: 0) - 72 B - 1 page hits, 0 page faults - server-session\thttp\t127.0.0.1\t/db/neo4j/query/v2\tneo4j - neo4j - RETURN 0 - {} - runtime=slotted - {}
"""


class AuditTests(unittest.TestCase):
    """After a round the experiment reads the server's query.log files: how many entries they hold, and whether every
    start entry has its end entry and every end entry its start, which an entry dropped on the way would break."""

    def test_entries_are_counted_and_starts_are_paired_with_ends(self):
        audit = overhead.audit([QUERY_LOG])
        self.assertEqual(audit['entries'], 5)  # the stack trace's lines are not entries
        self.assertEqual(audit['starts'], 2)
        # id:8 started and never ended; id:9 ended (failed) without a start; id:1 ended before the oldest start
        # the files still hold, so its start was in a rolled-away file: not a gap.
        self.assertEqual(audit['unpaired'], 2)

    def test_a_log_of_end_entries_only_has_no_pairs_to_check(self):
        lines = [line for line in QUERY_LOG.splitlines(keepends=True) if 'Query started' not in line]
        audit = overhead.audit([''.join(lines)])
        self.assertEqual((audit['entries'], audit['starts'], audit['unpaired']), (3, 0, 0))

    def test_the_files_of_a_rotation_are_read_together(self):
        start, rest = QUERY_LOG.split('\n', 1)
        self.assertEqual(overhead.audit([rest, start + '\n']), overhead.audit([QUERY_LOG]))

    def test_an_empty_log_audits_to_zero(self):
        self.assertEqual(overhead.audit([]), {'entries': 0, 'starts': 0, 'unpaired': 0})

    def test_with_match_every_entry_is_held_against_the_inventory_shapes(self):
        """The release-gate check's matcher on the file (--match): the inventory's own example lines each fit one
        shape; a line in the layout's form that fits none is counted."""
        inventory = overhead.check.Inventory.load()
        examples = '\n'.join(f"{PREFIX}{example['level']:<5} {example['line']}"
                             + ('\norg.neo4j.exceptions.ProbeException: x\n\tat org.neo4j.Probe.run(Probe.java:1)'
                                if example['stacktrace'] else '')
                             for example in inventory.examples)
        self.assertEqual(overhead.unmatched([examples], inventory), 0)
        self.assertEqual(overhead.unmatched([examples + f'\n{PREFIX}INFO  id:3 - not an entry\n'], inventory), 1)

    def test_a_round_observes_the_files_of_the_servers_query_log(self):
        class Server:
            pass
        with tempfile.TemporaryDirectory() as directory:
            server = Server()
            server.home = pathlib.Path(directory)
            (server.home / 'logs').mkdir()
            start, rest = QUERY_LOG.split('\n', 1)
            (server.home / 'logs/query.log').write_text(rest)
            (server.home / 'logs/query.log.01').write_text(start + '\n')
            observed = overhead.QueryLogOverhead().observe('verbose', server)
            self.assertEqual(observed, {'query_log_bytes': len(QUERY_LOG.encode()), 'query_log_files': 2,
                                        'query_log_entries': 5, 'query_log_starts': 2, 'query_log_unpaired': 2})
            matching = overhead.QueryLogOverhead()
            matching.match = True
            self.assertEqual(matching.observe('verbose', server)['query_log_unmatched'], 0)  # the lines above are well-formed


class ReportTests(unittest.TestCase):
    def test_the_round_line_shows_the_query_log_size(self):
        self.assertEqual(EXPERIMENT.describe(result(0, 'verbose', 100.0)), 'query.log 1.0 KiB')
        self.assertEqual(EXPERIMENT.describe({**result(0, 'verbose', 100.0), 'query_log_bytes': 3 * 1024 * 1024}),
                         'query.log 3.0 MiB')

    def test_the_round_line_shows_the_entries_and_the_unpaired_ones_when_there_are_starts(self):
        audited = {**result(0, 'verbose', 100.0), 'query_log_files': 2, 'query_log_entries': 61000,
                   'query_log_starts': 30500, 'query_log_unpaired': 0}
        self.assertEqual(EXPERIMENT.describe(audited), 'query.log 1.0 KiB in 2 files, 61000 entries, 0 unpaired')
        self.assertEqual(EXPERIMENT.describe({**audited, 'query_log_starts': 0, 'query_log_files': 1}),
                         'query.log 1.0 KiB in 1 file, 61000 entries')
        self.assertEqual(EXPERIMENT.describe({**audited, 'query_log_unmatched': 0}),
                         'query.log 1.0 KiB in 2 files, 61000 entries, 0 unpaired, 0 unmatched')

    def test_the_summary_has_deltas_against_off_and_no_result_line(self):
        results = [result(0, 'off', 100.0), result(0, 'info', 99.0), result(0, 'verbose', 98.0),
                   result(1, 'verbose', 97.0), result(1, 'info', 99.0), result(1, 'off', 101.0)]
        lines = harness.format_summary(results, EXPERIMENT)
        self.assertEqual([line.split()[0] for line in lines], ['CONFIG', 'CONFIG', 'CONFIG', 'DELTA', 'DELTA'])
        self.assertTrue(lines[3].startswith('DELTA info vs off:'))
        self.assertTrue(lines[4].startswith('DELTA verbose vs off:'))
        self.assertFalse(any(line.startswith('RESULT') for line in lines))

    def test_a_mitigation_is_compared_with_off_like_the_others(self):
        results = [result(0, 'off', 100.0), result(0, 'verbose', 10.0), result(0, 'async', 50.0),
                   result(1, 'async', 52.0), result(1, 'verbose', 11.0), result(1, 'off', 102.0)]
        lines = harness.format_summary(results, EXPERIMENT)
        self.assertTrue(lines[4].startswith('DELTA async vs off: -49.51 % throughput'), lines[4])


class ProfileTests(unittest.TestCase):
    def test_the_share_counts_samples_with_a_query_log_frame_and_names_the_hottest(self):
        share = EXPERIMENT.classify(harness.jfr_stacks(JFR_PRINT))
        self.assertEqual((share['samples'], share['query_log_samples'], share['dispatch_samples']), (3, 1, 1))
        self.assertEqual(share['hottest'], [('org.dozerdb.querylog.entry.PlainTextRenderer.render', 1)])
        self.assertEqual(EXPERIMENT.describe_profile('verbose', share),
                         'PROFILE verbose: 1 of 3 CPU samples (33.333 %) in query-log code, 1 (33.333 %) in monitor '
                         'dispatch; hottest: entry.PlainTextRenderer.render 1')

    def test_profiles_of_the_rounds_merge(self):
        share = EXPERIMENT.classify(harness.jfr_stacks(JFR_PRINT))
        merged = EXPERIMENT.merge_profiles([share, share])
        self.assertEqual((merged['samples'], merged['query_log_samples'], merged['dispatch_samples']), (6, 2, 2))
        self.assertEqual(merged['hottest'], [('org.dozerdb.querylog.entry.PlainTextRenderer.render', 2)])


if __name__ == '__main__':
    unittest.main()
