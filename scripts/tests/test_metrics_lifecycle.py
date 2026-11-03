"""The database metrics lifecycle of the flows: the shared fixture, the outcome of each expectation over
Prometheus answers, and the bounded polling, as scripts/metrics_lifecycle.py implements them."""
import copy
import importlib.util
import json
import pathlib
import re
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import metrics_lifecycle as lifecycle  # noqa: E402

spec = importlib.util.spec_from_file_location('cypher_flow', SCRIPTS / 'cypher-flow.py')
cypher_flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cypher_flow)

FIXTURE = SCRIPTS / 'fixtures' / 'database-metrics-lifecycle.json'
STEPS = ('created', 'written', 'dropped')
FLOW_STEPS = ('created', 'constrained', 'written', 'dropped')  # the steps both flows run
EXAMPLE = {
    'description': 'example',
    'timeout_scrape_intervals': 10,
    'steps': [{'name': 'created', 'when': 'after CREATE DATABASE'},
              {'name': 'written', 'when': 'after the accepted writes'},
              {'name': 'dropped', 'when': 'after DROP DATABASE'}],
    'assertions': [
        {'step': 'created', 'expect': 'present', 'query': '{__name__=~"neo4j_database_${database}_.+"}',
         'description': 'the database metrics are scraped'},
        {'step': 'written', 'expect': 'increased', 'since': 'created',
         'query': 'neo4j_database_${database}_transaction_committed_total',
         'description': 'the committed-transaction counter rose'},
        {'step': 'dropped', 'expect': 'absent', 'query': '{__name__=~"neo4j_database_${database}_.+"}',
         'description': 'no series of the database remains'},
    ],
}


class FixtureTests(unittest.TestCase):
    def test_the_assertions_are_read_in_step_order_with_their_query_and_expectation(self):
        fixture = lifecycle.parse_fixture(EXAMPLE, STEPS)
        self.assertEqual(fixture.timeout_scrape_intervals, 10)
        self.assertEqual([(a.step, a.expect, a.since) for a in fixture.assertions],
                         [('created', 'present', None), ('written', 'increased', 'created'),
                          ('dropped', 'absent', None)])
        self.assertEqual(fixture.assertions[1].query, 'neo4j_database_${database}_transaction_committed_total')
        self.assertEqual(fixture.steps, {'created': 'after CREATE DATABASE', 'written': 'after the accepted writes',
                                         'dropped': 'after DROP DATABASE'})

    def test_the_shared_fixture_holds_the_lifecycle_expectations_for_the_cypher_flow_steps(self):
        self.assertEqual(cypher_flow.LIFECYCLE_STEPS, FLOW_STEPS)
        self.assertEqual(cypher_flow.LIFECYCLE, FIXTURE)
        fixture = lifecycle.load_fixture(FIXTURE, cypher_flow.LIFECYCLE_STEPS)
        self.assertEqual([(a.step, a.expect, a.since) for a in fixture.assertions],
                         [('created', 'present', None), ('written', 'increased', 'constrained'),
                          ('written', 'increased', 'constrained'), ('dropped', 'absent', None)])
        for assertion in fixture.assertions:
            self.assertIn('${database}', assertion.query)

    def test_the_written_step_proves_the_accepted_data_writes(self):
        # Reads commit too, and so do the schema statements (hence the baseline at constrained, after them) and
        # the token a rejected write creates for a new label or property key: the write-transaction counter
        # shows the writes, the node count that the accepted ones were stored.
        fixture = lifecycle.load_fixture(FIXTURE, FLOW_STEPS)
        self.assertEqual([a.query for a in fixture.assertions if a.step == 'written'],
                         ['neo4j_database_${database}_transaction_committed_write_total',
                          'neo4j_database_${database}_neo4j_count_node'])

    def test_the_browser_flow_runs_the_same_lifecycle_steps_and_the_fixture_names_both_flows(self):
        # scripts/browser-flow.mjs evaluates the fixture in Node (scripts/metrics-lifecycle.mjs) and refuses a file
        # whose steps differ from its own; this reads its constant so a renamed step fails here, not in the gate.
        source = (SCRIPTS / 'browser-flow.mjs').read_text()
        declared = re.search(r"^const LIFECYCLE_STEPS = \[([^\]]*)\];$", source, re.MULTILINE)
        self.assertIsNotNone(declared, 'browser-flow.mjs declares const LIFECYCLE_STEPS = [...] on one line')
        self.assertEqual(tuple(re.findall(r"'([^']*)'", declared.group(1))), FLOW_STEPS)
        self.assertIn("resolve(ROOT, 'scripts/fixtures/database-metrics-lifecycle.json')", source)
        description = json.loads(FIXTURE.read_text())['description']
        for flow in ('scripts/cypher-flow.py', 'scripts/browser-flow.mjs'):
            self.assertIn(flow, description)


    def test_the_shared_fixture_describes_every_field_and_outcome_for_any_flow_that_evaluates_it(self):
        description = json.loads(FIXTURE.read_text())['description']
        for term in ('`steps`', '`when`', '`assertions`', '`step`', '`query`', '`expect`', '`since`',
                     '`description`', '`timeout_scrape_intervals`', '${database}', '`present`', '`increased`',
                     '`absent`', '[A-Za-z0-9:]', 'scrapeInterval', 'bad_data', 'not a number'):
            self.assertIn(term, description)


def edited(change):
    """EXAMPLE with change(data) applied to a deep copy."""
    data = copy.deepcopy(EXAMPLE)
    change(data)
    return data


class FixtureErrorTests(unittest.TestCase):
    """A fixture the flow cannot trust stops it before it creates the database, naming the field."""

    def assertRejected(self, data, *named):
        with self.assertRaises(lifecycle.FixtureError) as raised:
            lifecycle.parse_fixture(data, STEPS)
        for name in named:
            self.assertIn(name, str(raised.exception))

    def test_an_unknown_expectation_is_named_with_the_known_ones(self):
        self.assertRejected(edited(lambda d: d['assertions'][2].update(expect='gone')),
                            'assertions[2].expect', 'gone', 'present', 'increased', 'absent')

    def test_an_assertion_at_a_step_the_fixture_does_not_list_is_named(self):
        self.assertRejected(edited(lambda d: d['assertions'][0].update(step='stopped')),
                            'assertions[0].step', 'stopped')

    def test_the_steps_must_be_the_ones_the_flow_runs_in_its_order(self):
        self.assertRejected(edited(lambda d: d['steps'].reverse()), 'steps', 'created, written, dropped')
        self.assertRejected(edited(lambda d: d['steps'].pop()), 'steps', 'created, written, dropped')

    def test_a_query_that_does_not_name_the_flow_database_is_rejected(self):
        self.assertRejected(edited(lambda d: d['assertions'][0].update(query='up')),
                            'assertions[0].query', '${database}')

    def test_increased_needs_an_earlier_step_to_compare_with(self):
        self.assertRejected(edited(lambda d: d['assertions'][1].pop('since')), 'assertions[1].since', 'missing')
        self.assertRejected(edited(lambda d: d['assertions'][1].update(since='dropped')),
                            'assertions[1].since', 'dropped', 'earlier')
        self.assertRejected(edited(lambda d: d['assertions'][1].update(since='written')),
                            'assertions[1].since', 'earlier')

    def test_since_belongs_to_increased_only(self):
        self.assertRejected(edited(lambda d: d['assertions'][2].update(since='created')),
                            'assertions[2].since', 'increased')

    def test_a_missing_field_is_named(self):
        self.assertRejected(edited(lambda d: d['assertions'][1].pop('query')), 'assertions[1].query', 'missing')
        self.assertRejected(edited(lambda d: d.pop('timeout_scrape_intervals')), 'timeout_scrape_intervals',
                            'missing')
        self.assertRejected(edited(lambda d: d.pop('assertions')), 'assertions', 'missing')

    def test_a_field_of_the_wrong_type_is_named(self):
        self.assertRejected(edited(lambda d: d['assertions'][0].update(query=['up'])), 'assertions[0].query',
                            'string')
        self.assertRejected(edited(lambda d: d.update(timeout_scrape_intervals=0)), 'timeout_scrape_intervals',
                            'positive number')
        self.assertRejected(edited(lambda d: d.update(timeout_scrape_intervals=True)),
                            'timeout_scrape_intervals', 'positive number')
        self.assertRejected(edited(lambda d: d.update(assertions=[])), 'assertions', 'non-empty list')

    def test_an_unknown_field_is_named_so_a_typo_does_not_silently_drop_an_expectation(self):
        self.assertRejected(edited(lambda d: d['assertions'][1].update(snice='created')), 'assertions[1].snice',
                            'unknown')

    def test_a_file_that_is_not_json_names_the_file_and_the_position(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'broken.json'
            path.write_text('{"steps": [}')
            with self.assertRaises(lifecycle.FixtureError) as raised:
                lifecycle.load_fixture(path, STEPS)
            self.assertIn('broken.json', str(raised.exception))
            self.assertIn('line 1 column 12', str(raised.exception))

    def test_an_error_in_a_file_names_the_file_and_the_field(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'edited.json'
            path.write_text(json.dumps(edited(lambda d: d['assertions'][2].update(expect='gone'))))
            with self.assertRaises(lifecycle.FixtureError) as raised:
                lifecycle.load_fixture(path, STEPS)
            self.assertIn('edited.json', str(raised.exception))
            self.assertIn('assertions[2].expect', str(raised.exception))



def vector(*samples):
    """A Prometheus instant-query answer (the data member) holding one series per (name, value)."""
    return {'resultType': 'vector', 'result': [
        {'metric': {'__name__': name, 'instance': 'dozerdb:2004', 'job': 'neo4j'}, 'value': [1793380000.5, value]}
        for name, value in samples]}


COMMITTED = 'neo4j_database_flowtest_transaction_committed_total'


class DatabaseNameTests(unittest.TestCase):
    def test_the_database_is_named_in_the_prometheus_form_of_its_metrics(self):
        self.assertEqual(lifecycle.bind('neo4j_database_${database}_transaction_committed_total', 'flowtest'),
                         COMMITTED)
        # Every character outside [A-Za-z0-9:] becomes _, as DozerDB sanitises metric names.
        self.assertEqual(lifecycle.bind('{__name__=~"neo4j_database_${database}_.+"}', 'my-db.v2'),
                         '{__name__=~"neo4j_database_my_db_v2_.+"}')


class OutcomeTests(unittest.TestCase):
    def test_present_needs_at_least_one_series(self):
        self.assertTrue(lifecycle.met('present', vector((COMMITTED, '3'))))
        self.assertFalse(lifecycle.met('present', vector()))

    def test_absent_needs_no_series(self):
        self.assertTrue(lifecycle.met('absent', vector()))
        self.assertFalse(lifecycle.met('absent', vector((COMMITTED, '0'))))

    def test_increased_needs_the_sum_above_the_baseline(self):
        self.assertTrue(lifecycle.met('increased', vector((COMMITTED, '6')), baseline=3.0))
        self.assertFalse(lifecycle.met('increased', vector((COMMITTED, '3')), baseline=3.0))
        self.assertFalse(lifecycle.met('increased', vector((COMMITTED, '2')), baseline=3.0))
        self.assertTrue(lifecycle.met('increased', vector(('a', '2'), ('b', '2')), baseline=3.0))

    def test_increased_is_not_met_without_a_series_or_with_a_value_that_is_not_a_number(self):
        self.assertFalse(lifecycle.met('increased', vector(), baseline=0.0))
        self.assertFalse(lifecycle.met('increased', vector((COMMITTED, 'NaN')), baseline=0.0))

    def test_a_scalar_answer_is_one_series(self):
        scalar = {'resultType': 'scalar', 'result': [1793380000.5, '4']}
        self.assertTrue(lifecycle.met('present', scalar))
        self.assertEqual(lifecycle.total(scalar), 4.0)

    def test_the_total_of_an_answer_is_the_sum_of_its_sample_values_or_none_without_series(self):
        self.assertEqual(lifecycle.total(vector(('a', '2'), ('b', '3.5'))), 5.5)
        self.assertIsNone(lifecycle.total(vector()))


class AnswerTests(unittest.TestCase):
    """What Prometheus returned, as a failure shows it."""

    def test_no_series(self):
        self.assertEqual(lifecycle.answer(vector()), 'no series')

    def test_a_series_with_its_labels_and_value(self):
        self.assertEqual(lifecycle.answer(vector((COMMITTED, '3'))),
                         f'1 series: {COMMITTED}{{instance="dozerdb:2004", job="neo4j"}} 3')

    def test_many_series_are_counted_and_the_first_three_shown(self):
        shown = lifecycle.answer(vector(*((f'neo4j_database_flowtest_m{i}', str(i)) for i in range(5))))
        self.assertTrue(shown.startswith('5 series: neo4j_database_flowtest_m0{'), shown)
        self.assertIn('neo4j_database_flowtest_m2{', shown)
        self.assertNotIn('neo4j_database_flowtest_m3{', shown)
        self.assertTrue(shown.endswith('and 2 more'), shown)

    def test_a_scalar(self):
        self.assertEqual(lifecycle.answer({'resultType': 'scalar', 'result': [1.0, '4']}), 'scalar 4')



class FakeClock:
    """Time that moves only when the code under test sleeps."""

    def __init__(self):
        self.now = 1000.0
        self.sleeps = []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


NOW = 1793380000.0  # Prometheus's time() when a flow reaches a step, unless a test scripts it


def scalar(value):
    return {'resultType': 'scalar', 'result': [NOW, str(value)]}


def stamps(*seconds):
    """The answer to timestamp(<query>): the scrape time of each series."""
    return vector(*((f'series{i}', str(second)) for i, second in enumerate(seconds)))


class FakePrometheus:
    """Answers each query with its scripted answers in turn, repeating the last; an answer that is an exception
    is raised. Records every query asked. Unscripted, time() is NOW, every timestamp() query a scrape one second
    after it, and any other query no series."""

    def __init__(self, answers):
        self.answers = {query: list(scripted) for query, scripted in answers.items()}
        self.asked = []

    def __call__(self, query):
        self.asked.append(query)
        default = (scalar(NOW) if query == 'time()' else stamps(NOW + 1) if query.startswith('timestamp(')
                   else vector())
        scripted = self.answers.get(query, [default])
        answer = scripted.pop(0) if len(scripted) > 1 else scripted[0]
        if isinstance(answer, Exception):
            raise answer
        return answer


ALL = '{__name__=~"neo4j_database_flowtest_.+"}'
SCRAPE = 2.0  # seconds; EXAMPLE waits 10 scrape intervals, so 20 s, polled every 1 s


def flow(answers):
    clock = FakeClock()
    prometheus = FakePrometheus(answers)
    checker = lifecycle.Lifecycle(lifecycle.parse_fixture(EXAMPLE, STEPS), 'flowtest', prometheus, SCRAPE,
                                  clock=clock, sleep=clock.sleep)
    return checker, prometheus, clock


def failures(results):
    return [result.message for result in results if not result.passed]


class StepTests(unittest.TestCase):
    def test_the_bound_is_the_fixture_number_of_scrape_intervals_polled_at_half_an_interval(self):
        checker, _, _ = flow({})
        self.assertEqual(checker.timeout, 20.0)
        self.assertEqual(checker.interval, 1.0)

    def test_metrics_that_appear_after_a_few_scrapes_pass_without_waiting_longer(self):
        checker, prometheus, clock = flow({ALL: [vector(), vector(), vector(('neo4j_database_flowtest_x', '1'))],
                                          COMMITTED: [vector((COMMITTED, '3'))]})
        results = checker.check('created')
        self.assertEqual(failures(results), [])
        self.assertEqual(prometheus.asked, [ALL, ALL, ALL, 'time()', f'timestamp({COMMITTED})', COMMITTED])
        self.assertEqual(clock.sleeps, [1.0, 1.0])

    def test_metrics_that_never_appear_fail_the_named_step_at_the_bound_with_the_query_and_the_answer(self):
        checker, _, clock = flow({ALL: [vector()], COMMITTED: [vector()]})
        problems = failures(checker.check('created'))
        self.assertEqual(clock.now, 1000.0 + 20.0 + 20.0)  # the step's bound, then the baseline's
        self.assertIn('created', problems[0])
        self.assertIn('present', problems[0])
        self.assertIn(ALL, problems[0])
        self.assertIn('returned no series', problems[0])
        self.assertIn('20 s', problems[0])

    def test_a_counter_that_rises_after_the_writes_passes(self):
        checker, _, _ = flow({ALL: [vector(('neo4j_database_flowtest_x', '1'))],
                              COMMITTED: [vector((COMMITTED, '3')), vector((COMMITTED, '3')),
                                          vector((COMMITTED, '7'))]})
        self.assertEqual(failures(checker.check('created')), [])
        self.assertEqual(failures(checker.check('written')), [])

    def test_a_counter_that_does_not_rise_fails_the_written_step_naming_the_baseline_and_the_answer(self):
        checker, _, clock = flow({ALL: [vector(('neo4j_database_flowtest_x', '1'))],
                                  COMMITTED: [vector((COMMITTED, '3'))]})
        checker.check('created')
        started = clock.now
        problems = failures(checker.check('written'))
        self.assertEqual(clock.now - started, 20.0)
        self.assertEqual(len(problems), 1)
        for part in ('written', 'increased', 'above 3', 'created', COMMITTED,
                     f'returned 1 series: {COMMITTED}{{instance="dozerdb:2004", job="neo4j"}} 3'):
            self.assertIn(part, problems[0])

    def test_series_that_survive_drop_fail_the_dropped_step_with_what_remains(self):
        checker, _, _ = flow({ALL: [vector((COMMITTED, '7'))]})
        problems = failures(checker.check('dropped'))
        self.assertEqual(len(problems), 1)
        for part in ('dropped', 'absent', ALL, f'returned 1 series: {COMMITTED}'):
            self.assertIn(part, problems[0])

    def test_series_gone_after_drop_pass(self):
        checker, _, _ = flow({ALL: [vector((COMMITTED, '7')), vector()]})
        self.assertEqual(failures(checker.check('dropped')), [])

    def test_a_query_prometheus_rejects_fails_at_once_with_its_error(self):
        checker, _, clock = flow({ALL: [lifecycle.QueryRejected('bad_data: parse error at char 5')]})
        problems = failures(checker.check('dropped'))
        self.assertEqual(clock.sleeps, [])
        self.assertTrue(problems[0].endswith(f'expected absent, but Prometheus rejected {ALL}: '
                                             'bad_data: parse error at char 5'), problems[0])
        self.assertIn('dropped', problems[0])

    def test_an_unreachable_prometheus_is_retried_until_the_bound_and_its_error_reported(self):
        checker, _, clock = flow({ALL: [ConnectionRefusedError('connection refused'), vector()]})
        self.assertEqual(failures(checker.check('dropped')), [])
        checker, _, clock = flow({ALL: [ConnectionRefusedError('connection refused')]})
        problems = failures(checker.check('dropped'))
        self.assertEqual(clock.now, 1020.0)
        self.assertIn('connection refused', problems[0])

    def test_without_a_baseline_the_increase_fails_at_once_naming_the_step_that_should_have_recorded_it(self):
        checker, _, _ = flow({ALL: [vector(('neo4j_database_flowtest_x', '1'))], COMMITTED: [vector()]})
        problems = failures(checker.check('created'))
        self.assertEqual(len(problems), 1)
        for part in ('created', 'baseline', 'written', COMMITTED, 'no series'):
            self.assertIn(part, problems[0])
        problems = failures(checker.check('written'))
        self.assertIn('no baseline', problems[0])
        self.assertIn('created', problems[0])

    def test_passing_results_say_what_was_seen(self):
        checker, _, _ = flow({ALL: [vector(('neo4j_database_flowtest_x', '1'))],
                              COMMITTED: [vector((COMMITTED, '3')), vector((COMMITTED, '7'))]})
        created = [result.message for result in checker.check('created')]
        self.assertTrue(any(line.endswith(f'present: {ALL} returned 1 series') for line in created), created)
        self.assertTrue(any('baseline' in line and '3' in line for line in created), created)
        written = checker.check('written')[0].message
        self.assertTrue(written.endswith(f'increased above 3 recorded at created: {COMMITTED} returned 1 series '
                                         'totalling 7'), written)

    def test_the_baseline_is_read_from_a_scrape_made_after_the_flow_reached_its_step(self):
        # What happened just before the step (schema commits, for the written step) reaches Prometheus only at
        # its next scrape; a value scraped earlier would let that count as the increase.
        checker, prometheus, clock = flow({
            ALL: [vector(('neo4j_database_flowtest_x', '1'))],
            'time()': [scalar(NOW)],
            f'timestamp({COMMITTED})': [stamps(NOW - 1.5), stamps(NOW - 1.5), stamps(NOW + 0.5)],
            COMMITTED: [vector((COMMITTED, '20'))]})
        results = checker.check('created')
        self.assertEqual(failures(results), [])
        self.assertTrue(results[-1].message.endswith(f'{COMMITTED} = 20'), results[-1].message)
        self.assertEqual(prometheus.asked[1:], ['time()', *[f'timestamp({COMMITTED})'] * 3, COMMITTED])
        self.assertEqual(clock.sleeps, [1.0, 1.0])

    def test_without_a_scrape_after_the_step_no_baseline_is_recorded(self):
        checker, _, clock = flow({ALL: [vector(('neo4j_database_flowtest_x', '1'))],
                                  f'timestamp({COMMITTED})': [stamps(NOW - 1.5)],
                                  COMMITTED: [vector((COMMITTED, '2'))]})
        problems = failures(checker.check('created'))
        self.assertEqual(clock.now, 1020.0)
        self.assertEqual(len(problems), 1)
        for part in ('baseline', 'scraped after', f'timestamp({COMMITTED})', '20 s'):
            self.assertIn(part, problems[0])
        self.assertIn('no baseline', failures(checker.check('written'))[0])

    def test_a_step_without_assertions_checks_nothing(self):
        data = edited(lambda d: d['assertions'].pop(2))
        checker = lifecycle.Lifecycle(lifecycle.parse_fixture(data, STEPS), 'flowtest', FakePrometheus({}), SCRAPE)
        self.assertEqual(checker.check('dropped'), [])



def targets(*intervals):
    return {'status': 'success', 'data': {'activeTargets': [
        {'labels': {'job': f'job{i}', 'instance': f'host{i}:2004'}, 'scrapeInterval': interval, 'health': 'up'}
        for i, interval in enumerate(intervals)], 'droppedTargets': []}}


class ScrapeIntervalTests(unittest.TestCase):
    def test_durations_in_prometheus_form(self):
        for text, seconds in (('2s', 2.0), ('500ms', 0.5), ('1m30s', 90.0), ('1h', 3600.0), ('15s', 15.0)):
            self.assertEqual(lifecycle.duration(text), seconds, text)

    def test_a_duration_prometheus_would_not_write_is_rejected(self):
        for text in ('', '2', 's', '2 s', '-1s'):
            with self.assertRaises(ValueError, msg=text):
                lifecycle.duration(text)

    def test_the_longest_interval_of_the_active_targets_bounds_the_waits(self):
        self.assertEqual(lifecycle.scrape_interval(targets('2s')), 2.0)
        self.assertEqual(lifecycle.scrape_interval(targets('2s', '1m', '15s')), 60.0)

    def test_a_prometheus_scraping_nothing_cannot_see_the_database(self):
        with self.assertRaises(lifecycle.PrometheusError) as raised:
            lifecycle.scrape_interval(targets())
        self.assertIn('no active scrape target', str(raised.exception))


class AnswerInterpretationTests(unittest.TestCase):
    """Prometheus's HTTP answer to /api/v1/query: its data, a rejected query, or an error worth waiting out."""

    def test_a_successful_answer_is_its_data(self):
        body = json.dumps({'status': 'success', 'data': vector((COMMITTED, '3'))}).encode()
        self.assertEqual(lifecycle.interpret(200, body), vector((COMMITTED, '3')))

    def test_bad_data_is_a_rejected_query_with_prometheus_error(self):
        body = json.dumps({'status': 'error', 'errorType': 'bad_data',
                           'error': 'invalid parameter "query": 1:7: parse error'}).encode()
        with self.assertRaises(lifecycle.QueryRejected) as raised:
            lifecycle.interpret(400, body)
        self.assertIn('bad_data: invalid parameter "query": 1:7: parse error', str(raised.exception))

    def test_any_other_error_is_retried(self):
        body = json.dumps({'status': 'error', 'errorType': 'unavailable', 'error': 'starting up'}).encode()
        with self.assertRaises(lifecycle.PrometheusError) as raised:
            lifecycle.interpret(503, body)
        self.assertNotIsInstance(raised.exception, lifecycle.QueryRejected)
        self.assertIn('starting up', str(raised.exception))
        with self.assertRaises(lifecycle.PrometheusError) as raised:
            lifecycle.interpret(502, b'<html>Bad Gateway</html>')
        self.assertIn('502', str(raised.exception))


if __name__ == '__main__':
    unittest.main()
