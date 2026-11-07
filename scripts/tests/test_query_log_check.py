"""The matcher of scripts/query-log-check.py: the plain-text grammar it derives from the field inventory
(scripts/fixtures/query-log-entries.json), the entries it reads from a query.log text, and the JSON layout check."""
import contextlib
import copy
import importlib.util
import io
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))  # query-log-check.py imports the smoke helper beside it
spec = importlib.util.spec_from_file_location('query_log_check', SCRIPTS / 'query-log-check.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

PREFIX = '2026-11-06 09:15:02.117+0000 '


class PlainEntriesTests(unittest.TestCase):
    """A plain query.log is split into entries: one line opening with the layout's timestamp and level, plus the
    continuation lines (a stack trace, a plan table, a line break inside the query text) up to the next timestamp."""

    def test_an_entry_is_its_timestamp_level_and_message(self):
        text = PREFIX + 'INFO  transaction id:3 - 1 ms: (planning: 0, waiting: 0) - 0 B - 0 page hits, 0 page faults - embedded-session\t\tneo4j - neo4j - RETURN 1 - {} - runtime=slotted - {}\n'
        [entry] = check.plain_entries(text)
        self.assertEqual(entry.time, '2026-11-06 09:15:02.117+0000')
        self.assertEqual(entry.level, 'INFO')
        self.assertTrue(entry.message.startswith('transaction id:3 - 1 ms:'))
        self.assertTrue(entry.message.endswith('runtime=slotted - {}'))

    def test_continuation_lines_belong_to_the_entry_before_them(self):
        text = (PREFIX + 'ERROR id:7 - transaction id:12 - 3 ms: ... - {} - Variable `m` not defined\n'
                'org.neo4j.exceptions.SyntaxException: Variable `m` not defined\n'
                '\tat org.neo4j.cypher.internal.Compiler.compile(Compiler.scala:10)\n'
                + PREFIX + 'INFO  id:8 - transaction id:13 - 0 ms: ... - \nMATCH (n) RETURN n - {} - runtime=null - {}\n')
        first, second = check.plain_entries(text)
        self.assertEqual(first.level, 'ERROR')
        self.assertEqual(first.message, 'id:7 - transaction id:12 - 3 ms: ... - {} - Variable `m` not defined\n'
                         'org.neo4j.exceptions.SyntaxException: Variable `m` not defined\n'
                         '\tat org.neo4j.cypher.internal.Compiler.compile(Compiler.scala:10)')
        self.assertEqual(second.message, 'id:8 - transaction id:13 - 0 ms: ... - \nMATCH (n) RETURN n - {} - runtime=null - {}')

    def test_an_empty_or_absent_file_has_no_entries(self):
        self.assertEqual(check.plain_entries(''), [])

    def test_the_level_padding_of_the_layout_is_not_part_of_the_message(self):
        # %-5p pads INFO to five characters, so two spaces precede the message of an INFO entry and one an ERROR one.
        info = check.plain_entries(PREFIX + 'INFO  Query started: id:1 - transaction id:2\n')[0]
        error = check.plain_entries(PREFIX + 'ERROR id:1 - transaction id:2\n')[0]
        self.assertEqual(info.message, 'Query started: id:1 - transaction id:2')
        self.assertEqual(error.message, 'id:1 - transaction id:2')


if __name__ == '__main__':
    unittest.main()


INVENTORY = check.Inventory.load()


def entry_text(example):
    """The entry as the file shows it: the %m, then the stack trace on the lines after when the example has one."""
    line = example['line']
    if example['stacktrace']:
        line += '\norg.neo4j.exceptions.ProbeException: ' + line.rsplit(' - ', 1)[-1] + '\n\tat org.neo4j.Probe.run(Probe.java:1)'
    return line


class MatcherTests(unittest.TestCase):
    """The matcher derives one grammar per entry shape from the inventory and classifies an entry by it."""

    def test_every_inventory_example_matches_its_own_shape_and_no_other(self):
        for example in INVENTORY.examples:
            with self.subTest(example['id']):
                matches = INVENTORY.matches(example['level'], entry_text(example))
                self.assertEqual(len(matches), 1, [m.shape.name for m in matches])
                shape = matches[0].shape
                self.assertEqual(shape.type, example.get('type', 'query'))
                self.assertEqual(shape.kind, example['kind'])
                self.assertIn(example['detailLevel'], shape.levels)

    def test_the_log_level_is_part_of_the_shape(self):
        # A failed-query entry is ERROR, a start or success entry INFO (the inventory's level row); so a failed-query
        # entry at INFO is no entry, and the success grammar at ERROR is a failed-query entry whose reason Neo4j
        # left out (the failureReason row: absent when Neo4j reports none).
        fail = next(e for e in INVENTORY.examples if e['id'] == 'fail-verbose-bolt')
        success = next(e for e in INVENTORY.examples if e['id'] == 'end-verbose-bolt-manual')
        self.assertEqual(INVENTORY.matches('INFO', entry_text(fail)), [])
        [without_reason] = INVENTORY.matches('ERROR', success['line'])
        self.assertEqual((without_reason.shape.kind, without_reason.fields['failureReason']), ('fail', None))

    def test_the_fields_of_a_match_are_the_segments_values(self):
        http = next(e for e in INVENTORY.examples if e['id'] == 'end-verbose-http')
        [match] = INVENTORY.matches('INFO', http['line'])
        self.assertEqual(match.fields['source'], 'server-session\thttp\t127.0.0.1\t/db/neo4j/tx/commit')
        self.assertEqual(match.fields['database'], 'neo4j')
        self.assertEqual(match.fields['executingUser'], 'neo4j')
        self.assertEqual(match.fields['query'], 'RETURN TRUE')
        self.assertEqual(match.fields['queryParameters'], '{}')
        self.assertEqual(match.fields['runtime'], 'pipelined')
        self.assertIsNone(match.fields['queryPlan'])

    def test_a_query_api_entry_carries_the_bolt_shape_naming_the_api_as_the_client(self):
        api = next(e for e in INVENTORY.examples if e['id'] == 'end-verbose-query-api')
        [match] = INVENTORY.matches('INFO', api['line'])
        self.assertTrue(match.fields['source'].startswith(check.QUERY_API_SOURCE), match.fields['source'])
        self.assertIn('\tserverlocal:', match.fields['source'])
        self.assertEqual(match.fields['database'], 'neo4j')

    def test_a_failed_query_entry_yields_its_reason_and_its_stack_trace_apart(self):
        fail = next(e for e in INVENTORY.examples if e['id'] == 'fail-verbose-no-database')
        [match] = INVENTORY.matches('ERROR', entry_text(fail))
        self.assertEqual(match.fields['failureReason'], "Invalid input '(': expected an expression")
        self.assertTrue(match.fields['stacktrace'].startswith('org.neo4j.exceptions.ProbeException: '))
        self.assertIn('\n\tat ', match.fields['stacktrace'])

    def test_a_multi_line_reason_keeps_its_lines_and_the_stack_trace_starts_at_the_throwable(self):
        fail = next(e for e in INVENTORY.examples if e['id'] == 'fail-verbose-no-database')
        reason = "Invalid input '(': expected an expression (line 1, column 10 (offset: 9))\n\"RETURN 1 (\"\n          ^"
        line = fail['line'].replace("Invalid input '(': expected an expression", reason)
        text = line + '\norg.neo4j.exceptions.SyntaxException: ' + reason + '\n\tat org.neo4j.Probe.run(Probe.java:1)'
        [match] = INVENTORY.matches('ERROR', text)
        self.assertEqual(match.fields['failureReason'], reason)
        self.assertTrue(match.fields['stacktrace'].startswith('org.neo4j.exceptions.SyntaxException: '))

    def test_an_entry_whose_expected_segment_the_inventory_no_longer_has_matches_nothing(self):
        # What a probe relies on: a line is an entry only as the inventory describes one, so a row removed from the
        # fixture (here the transaction id) leaves every line that carries the segment unmatched, and the event row
        # removed leaves no shape of its type at all.
        def without(*names):
            return check.Inventory({**INVENTORY.data,
                                    'fields': [f for f in INVENTORY.data['fields'] if f['name'] not in names]})
        for example in INVENTORY.examples:
            if example.get('type', 'query') == 'query':
                with self.subTest(example['id']):
                    self.assertEqual(without('transactionId').matches(example['level'], entry_text(example)), [])
                    self.assertEqual(without('event').matches(example['level'], entry_text(example)), [])
        commit = next(e for e in INVENTORY.examples if e['id'] == 'transaction-commit-info-bolt')
        self.assertEqual(without('transaction.openTimeMs').matches('INFO', commit['line']), [])
        self.assertEqual(without('transaction.event').matches('INFO', commit['line']), [])


def json_success(**changes):
    """A success entry as Neo4j's QueryLogJsonLayout writes one on the calendar line (the layout's time, level and the
    field map's keys), changed by `changes` (None removes a key)."""
    entry = {
        'time': '2026-11-06 09:15:02.117+0000', 'level': 'INFO', 'allocatedBytes': 72, 'annotationData': {},
        'database': 'neo4j', 'elapsedTimeMs': 4, 'event': 'success', 'executingUser': 'neo4j',
        'authenticatedUser': 'neo4j', 'executionPlanCacheKeyHash': '1A2B3C4D', 'id': '5', 'pageFaults': 0,
        'pageHits': 1, 'planning': 2, 'query': 'RETURN TRUE', 'queryLang': 'CYPHER 25', 'queryParameters': '{}',
        'runtime': 'pipelined', 'source': 'server-session\thttp\t127.0.0.1\t/db/neo4j/tx/commit',
        'transactionId': 11, 'type': 'query', 'waiting': 0}
    for key, value in changes.items():
        if value is None:
            entry.pop(key, None)
        else:
            entry[key] = value
    return entry


class JsonEntryTests(unittest.TestCase):
    """Under the JSON layout an entry is one object whose keys are the inventory's JSON keys for its kind and
    detail level, each value matching the row's pattern (an object for a row without one)."""

    def test_a_complete_success_entry_has_no_problems(self):
        self.assertEqual(check.json_problems(json_success(), 'success', 'VERBOSE', 'calendar', INVENTORY), [])

    def test_a_key_the_inventory_does_not_know_is_named(self):
        problems = check.json_problems(json_success(colour='blue'), 'success', 'VERBOSE', 'calendar', INVENTORY)
        self.assertEqual(problems, ['colour: not a query log field'])

    def test_a_key_that_does_not_appear_in_the_kind_or_level_is_named(self):
        problems = check.json_problems(json_success(failureReason='x'), 'success', 'VERBOSE', 'calendar', INVENTORY)
        self.assertEqual(problems, ['failureReason: not written on a success entry'])
        problems = check.json_problems(json_success(), 'success', 'INFO', 'calendar', INVENTORY)
        self.assertEqual(problems, ['id: not written at INFO'])

    def test_a_value_outside_its_pattern_is_named(self):
        problems = check.json_problems(json_success(executionPlanCacheKeyHash='nope'), 'success', 'VERBOSE', 'calendar',
                                       INVENTORY)
        self.assertEqual(problems, ["executionPlanCacheKeyHash: 'nope' does not match [0-9A-F]{8}"])
        # annotationData is a Cypher-map string or an object as annotation_data_format says; errorInfo is the one
        # row without a pattern, always an object.
        self.assertEqual(check.json_problems(json_success(annotationData='{}'), 'success', 'VERBOSE', 'calendar',
                                             INVENTORY), [])
        failed = json_success(level='ERROR', event='fail', failureReason='x', errorInfo='42001')
        self.assertEqual(check.json_problems(failed, 'fail', 'VERBOSE', 'calendar', INVENTORY),
                         ["errorInfo: '42001' is not an object"])

    def test_a_missing_key_is_named_unless_the_field_is_optional_or_not_applicable_on_the_line(self):
        problems = check.json_problems(json_success(executionPlanCacheKeyHash=None, queryParameters=None,
                                                    authenticatedUser=None, runtime=None),
                                       'success', 'VERBOSE', 'calendar', INVENTORY)
        self.assertEqual(problems, ['executionPlanCacheKeyHash: missing'])
        # queryLang is not applicable on the LTS line, so its absence is no problem there and its presence is one.
        self.assertEqual(check.json_problems(json_success(queryLang=None), 'success', 'VERBOSE', 'lts', INVENTORY), [])
        self.assertEqual(check.json_problems(json_success(), 'success', 'VERBOSE', 'lts', INVENTORY),
                         ['queryLang: not applicable on the lts line'])

    def test_a_rollback_caused_by_a_failure_carries_its_reason_and_a_commit_does_not(self):
        rollback = {'time': '2026-11-06 09:15:02.117+0000', 'level': 'ERROR', 'type': 'transaction', 'event': 'rollback',
                    'transactionId': 15, 'database': 'neo4j', 'executingUser': 'neo4j', 'authenticatedUser': 'neo4j',
                    'failureReason': 'Node(0) already exists', 'stacktrace': 'org.neo4j.graphdb.ConstraintViolationException: …'}
        self.assertEqual(check.json_problems(rollback, 'rollback', 'VERBOSE', 'calendar', INVENTORY, type_='transaction'), [])
        commit = {**rollback, 'level': 'INFO', 'event': 'commit'}
        del commit['stacktrace']
        self.assertEqual(check.json_problems(commit, 'commit', 'VERBOSE', 'calendar', INVENTORY, type_='transaction'),
                         ['failureReason: not written on a commit entry'])

    def test_the_level_follows_the_kind(self):
        problems = check.json_problems(json_success(level='ERROR'), 'success', 'VERBOSE', 'calendar', INVENTORY)
        self.assertEqual(problems, ["level: 'ERROR' is not the level of a success entry"])
        failed = json_success(level='ERROR', event='fail', failureReason='Variable `m` not defined',
                              errorInfo={'GQLSTATUS': '42001'}, stacktrace='org.neo4j.exceptions.SyntaxException: x…')
        self.assertEqual(check.json_problems(failed, 'fail', 'VERBOSE', 'calendar', INVENTORY), [])


# A server-logs.xml in the shape Neo4j ships (written here, not copied): several appenders with a PatternLayout, the
# QueryLog one among them, and the QueryLogger logger pointing at it.
SERVER_LOGS_XML = '''<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="ERROR" monitorInterval="30">
    <Appenders>
        <RollingRandomAccessFile name="HttpLog" fileName="${config:server.directories.logs}/http.log"
                                 filePattern="$${config:server.directories.logs}/http.log.%02i">
            <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSSZ}{GMT+0} %-5p %m%n"/>
        </RollingRandomAccessFile>
        <!-- the query log appender -->
        <RollingRandomAccessFile name="QueryLog" fileName="${config:server.directories.logs}/query.log"
                                 filePattern="$${config:server.directories.logs}/query.log.%02i">
            <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSSZ}{GMT+0} %-5p %m%n"/>
            <Policies>
                <SizeBasedTriggeringPolicy size="20 MB"/>
            </Policies>
        </RollingRandomAccessFile>
    </Appenders>
    <Loggers>
        <Logger name="QueryLogger" level="INFO" additivity="false">
            <AppenderRef ref="QueryLog"/>
        </Logger>
    </Loggers>
</Configuration>
'''
JSON_TEMPLATE_LAYOUT = '<JsonTemplateLayout eventTemplateUri="classpath:org/neo4j/logging/QueryLogJsonLayout.json"/>'
PATTERN_LAYOUT = '<PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSSZ}{GMT+0} %-5p %m%n"/>'


class JsonLayoutEditTests(unittest.TestCase):
    """The manual's JSON switch: in the shipped server-logs.xml the QueryLog appender's PatternLayout is replaced by
    the JsonTemplateLayout with Neo4j's query log template, and nothing else changes."""

    def test_only_the_query_log_appenders_layout_changes(self):
        edited = check.json_layout(SERVER_LOGS_XML)
        self.assertEqual(edited.count(JSON_TEMPLATE_LAYOUT), 1)
        self.assertEqual(edited.count('<PatternLayout'), 1)  # the HttpLog one
        self.assertIn('/http.log.%02i">\n            ' + PATTERN_LAYOUT, edited)
        self.assertIn('/query.log.%02i">\n            ' + JSON_TEMPLATE_LAYOUT, edited)
        self.assertEqual(edited.replace(JSON_TEMPLATE_LAYOUT, PATTERN_LAYOUT), SERVER_LOGS_XML)

    def test_a_file_without_the_query_log_appender_is_refused(self):
        with self.assertRaises(ValueError):
            check.json_layout(SERVER_LOGS_XML.replace('name="QueryLog"', 'name="OtherLog"'))


class CommandLineTests(unittest.TestCase):
    """The forms to check, and --server-logs-xml: a whole logging configuration installed as conf/server-logs.xml of
    the plugin and distribution forms before they start (the overhead mitigations' fixtures), so the probes run against
    it; the docker form cannot take one."""

    def test_the_forms_default_to_all_three(self):
        args = check.parse_arguments([])
        self.assertEqual((args.forms, args.image, args.server_logs_xml), (list(check.FORMS), None, None))
        self.assertEqual(check.parse_arguments(['docker', '--image', 'x']).forms, ['docker'])

    def test_an_unknown_form_is_refused(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            check.parse_arguments(['windows'])

    def test_a_logging_configuration_applies_to_the_forms_with_a_conf_directory(self):
        args = check.parse_arguments(['--server-logs-xml', 'scripts/fixtures/server-logs-async.xml'])
        self.assertEqual(args.forms, ['plugin', 'distribution'])
        self.assertEqual(args.server_logs_xml, pathlib.Path('scripts/fixtures/server-logs-async.xml'))
        self.assertEqual(check.parse_arguments(['distribution', '--server-logs-xml', 'x.xml']).forms, ['distribution'])
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            check.parse_arguments(['docker', '--server-logs-xml', 'x.xml'])


class StartupSettingsTests(unittest.TestCase):
    """Every form starts with db.logs.query.enabled=INFO and a one-second threshold, written to neo4j.conf on the
    plugin and distribution forms and passed to the docker form as the NEO4J_* variables the public page documents, so
    the first probe proves a configured setting reaches query.log before the probes reset to Neo4j's defaults."""

    def test_the_startup_settings_are_the_level_and_the_threshold(self):
        self.assertEqual(check.STARTUP_SETTINGS, {'db.logs.query.enabled': 'INFO', 'db.logs.query.threshold': '1s'})

    def test_the_docker_form_passes_them_as_the_documented_variables(self):
        self.assertEqual(check.startup_variables(),
                         {'NEO4J_db_logs_query_enabled': 'INFO', 'NEO4J_db_logs_query_threshold': '1s'})

    def test_a_container_conf_that_carries_them_has_no_problem_and_one_that_does_not_names_the_setting(self):
        conf = 'server.directories.logs=/logs\ndb.logs.query.enabled=INFO\ndb.logs.query.threshold=1s\n'
        self.assertEqual(check.startup_settings_problems(conf), [])
        self.assertEqual(check.startup_settings_problems('db.logs.query.enabled=VERBOSE\n'),
                         ['db.logs.query.enabled=VERBOSE in the container, expected INFO',
                          'db.logs.query.threshold missing from the container, expected 1s'])


class InventoryValidationTests(unittest.TestCase):
    """The matcher validates the part of the inventory's shape it reads; a problem names the row."""

    def invalid(self, change):
        data = copy.deepcopy(INVENTORY.data)
        change(data)
        with self.assertRaises(ValueError) as caught:
            check.Inventory(data)
        return str(caught.exception)

    @staticmethod
    def row(data, name):
        return next(f for f in data['fields'] if f['name'] == name)

    def test_the_committed_inventory_is_valid(self):
        check.Inventory(INVENTORY.data)

    def test_optional_must_be_a_boolean(self):
        self.assertIn("field 'runtime': 'optional' is true or false",
                      self.invalid(lambda d: self.row(d, 'runtime').pop('optional')))

    def test_a_plain_segment_needs_its_position_separator_and_placeholder(self):
        self.assertIn("field 'runtime': 'plain.position' is an integer",
                      self.invalid(lambda d: self.row(d, 'runtime')['plain'].update(position='12')))
        self.assertIn("field 'runtime': 'plain.separator' is a string",
                      self.invalid(lambda d: self.row(d, 'runtime')['plain'].pop('separator')))
        self.assertIn("field 'runtime': segment 'runtime=<run>' has no <runtime> placeholder",
                      self.invalid(lambda d: self.row(d, 'runtime')['plain'].update(segment='runtime=<run>')))

    def test_rows_at_one_position_share_the_segment_and_the_separator(self):
        self.assertIn("field 'waiting': position 4 has another segment or separator than field 'planning'",
                      self.invalid(lambda d: self.row(d, 'waiting')['plain'].update(separator=' - ')))

    def test_a_pattern_must_compile(self):
        self.assertIn("field 'runtime': 'pattern' is not a regular expression",
                      self.invalid(lambda d: self.row(d, 'runtime').update(pattern='[')))

    def test_the_marker_has_at_most_an_alternative_per_kind(self):
        self.assertIn("field 'transaction.event': the segment has more alternatives than the pattern has kinds",
                      self.invalid(lambda d: self.row(d, 'transaction.event')['plain'].update(segment='A: |B: |C: |D: ')))
