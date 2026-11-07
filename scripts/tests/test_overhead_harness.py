"""The overhead harness, scripts/overhead_harness.py, shared by the metrics and query-log overhead scripts: block
scheduling, statistics, the workload, the report of a measurement and the common command line."""
import collections
import pathlib
import random
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import overhead_harness as harness  # noqa: E402  (a module beside the scripts, found through the path above)


class ScheduleTests(unittest.TestCase):
    def test_blocks_alternate_forward_and_reversed(self):
        order = harness.schedule(['off', 'info', 'verbose'], 2)
        self.assertEqual(order, [(0, 'off'), (0, 'info'), (0, 'verbose'), (1, 'verbose'), (1, 'info'), (1, 'off')])

    def test_no_rounds_or_no_configuration_is_rejected(self):
        with self.assertRaises(ValueError):
            harness.schedule(['off'], 0)
        with self.assertRaises(ValueError):
            harness.schedule([], 1)


class StatisticsTests(unittest.TestCase):
    def test_t_quantiles_take_the_next_lower_tabulated_degrees_of_freedom(self):
        self.assertEqual(harness.t95(2), 4.303)
        self.assertEqual(harness.t95(45), 2.021)
        self.assertEqual(harness.t95(1000), 1.960)

    def test_summary_of_rounds(self):
        s = harness.summarize([200.0, 210.0, 190.0])
        self.assertEqual((s['n'], s['mean'], s['stdev'], s['min'], s['max']), (3, 200.0, 10.0, 190.0, 210.0))
        self.assertAlmostEqual(s['cv_percent'], 5.0)
        self.assertEqual(harness.summarize([7.0])['stdev'], 0.0)

    def test_paired_delta_is_the_mean_per_block_ratio_with_a_t_interval(self):
        mean, low, high = harness.paired_delta([100.0, 200.0], [98.0, 196.0])
        self.assertAlmostEqual(mean, -2.0)
        self.assertAlmostEqual(low, -2.0)
        self.assertAlmostEqual(high, -2.0)
        single = harness.paired_delta([100.0], [98.0])
        self.assertAlmostEqual(single[0], -2.0)
        self.assertEqual(single[1:], (None, None))

    def test_unpaired_delta_compares_the_means_with_welch_interval(self):
        mean, low, high = harness.unpaired_delta([100.0, 100.0], [98.0, 98.0])
        self.assertAlmostEqual(mean, -2.0)
        self.assertEqual((low, high), (mean, mean))  # no variance: the interval is the mean
        self.assertEqual(harness.unpaired_delta([100.0], [98.0])[1:], (None, None))

    def test_verdict_against_an_explicit_budget(self):
        self.assertEqual(harness.verdict(-1.5, 0.5, 2.0)[0], 'MET')
        self.assertEqual(harness.verdict(-4.0, -2.5, 2.0)[0], 'EXCEEDED')
        status, reason = harness.verdict(-3.0, 1.0, 2.0)
        self.assertEqual(status, 'INCONCLUSIVE')
        self.assertIn('2 % budget', reason)
        self.assertIn('at most 3.00 %', reason)
        self.assertEqual(harness.verdict(None, None, 2.0)[0], 'INCONCLUSIVE')

    def test_interval_text(self):
        self.assertEqual(harness.interval(-1.25, 0.5), '-1.25 % .. +0.50 %')
        self.assertEqual(harness.interval(None, None), 'n/a')


class WorkloadTests(unittest.TestCase):
    def test_the_operation_sequence_is_repeatable_for_a_seed_and_reads_eighty_percent(self):
        self.assertEqual([harness.pick(random.Random(3)) for _ in range(30)],
                         [harness.pick(random.Random(3)) for _ in range(30)])
        rng = random.Random(25)
        kinds = collections.Counter(harness.pick(rng)[0] for _ in range(20_000))
        self.assertAlmostEqual(kinds['read'] / 20_000, 0.8, delta=0.01)

    def test_parameters_stay_inside_the_loaded_data(self):
        rng = random.Random(1)
        for _ in range(1000):
            kind, statement, parameters = harness.pick(rng)
            self.assertIn(kind, ('read', 'write'))
            self.assertIn('$id', statement)
            self.assertTrue(0 <= parameters['id'] < harness.PEOPLE)

    def test_request_bodies_of_both_endpoints(self):
        self.assertEqual(harness.request_body('query', 'RETURN $id', {'id': 1}),
                         {'statement': 'RETURN $id', 'parameters': {'id': 1}})
        self.assertEqual(harness.request_body('tx', 'RETURN $id', {'id': 1}),
                         {'statements': [{'statement': 'RETURN $id', 'parameters': {'id': 1}}]})


def result(block, configuration, throughput, **extra):
    return {'block': block, 'configuration': configuration, 'throughput': throughput, 'reads': 80 * throughput,
            'writes': 20 * throughput, 'second_half': 40 * throughput, 'errors': 0, 'duration': 100,
            'load_before': 1.0, 'load_after': 2.0, **extra}


class Plain(harness.Experiment):
    """An experiment that only names its configurations: no budget, no observations, no profile attribution."""
    configurations = {'off': 'logging off', 'verbose': "Neo4j's defaults"}
    baseline = 'off'


class Observing(Plain):
    """An experiment with a per-round observation, a profile attribution and a verdict."""

    def describe(self, result):
        return f"lines {result['lines']}"

    def describe_profile(self, label, share):
        return f"PROFILE {label}: {share['n']} samples"

    def merge_profiles(self, shares):
        return {'n': sum(share['n'] for share in shares)}

    def judge(self, configuration, low, high):
        return f'RESULT JUDGED {configuration}: {harness.interval(low, high)}'


class ReportTests(unittest.TestCase):
    def test_round_line_without_observations(self):
        line = harness.format_round(1, 4, result(0, 'verbose', 1234.56), Plain())
        self.assertTrue(line.startswith('ROUND 1/4 verbose     1234.6 ops/s (reads 987.6/s, writes 246.9/s, '), line)
        self.assertIn('halves 1481/988, errors 0, load 1.0->2.0)', line)
        self.assertNotIn('scrapes', line)
        self.assertIn('scrapes 59', harness.format_round(1, 4, result(0, 'verbose', 1000.0, scrapes=59), Plain()))

    def test_round_line_carries_the_observation_and_the_profile(self):
        line = harness.format_round(2, 4, result(0, 'verbose', 1000.0, lines=42, profile={'n': 7}), Observing())
        self.assertIn('errors 0, lines 42, load 1.0->2.0)', line)
        self.assertEqual(line.splitlines()[1], '  PROFILE verbose: 7 samples')

    def test_summary_without_a_verdict_has_configuration_and_delta_lines_only(self):
        rounds = [result(0, 'off', 1000.0), result(0, 'verbose', 990.0),
                  result(1, 'verbose', 1089.0), result(1, 'off', 1100.0)]
        lines = harness.format_summary(rounds, Plain())
        self.assertEqual([line.split()[0] for line in lines], ['CONFIG', 'CONFIG', 'DELTA'])
        self.assertTrue(lines[0].startswith('CONFIG off      mean 1050.0 ops/s, stdev 70.7 (6.73 %), '
                                            'range 1000.0..1100.0, rounds 1000.0 1100.0'), lines[0])
        self.assertTrue(lines[2].startswith('DELTA verbose vs off: -1.00 % throughput, paired 95 % CI '), lines[2])
        self.assertIn('; means -1.00 %, unpaired 95 % CI ', lines[2])

    def test_summary_puts_the_verdict_last_and_merges_the_profiles_of_every_round(self):
        rounds = [result(0, 'off', 1000.0), result(0, 'verbose', 990.0, lines=1, profile={'n': 4}),
                  result(1, 'verbose', 1089.0, lines=2, profile={'n': 5}), result(1, 'off', 1100.0)]
        lines = harness.format_summary(rounds, Observing())
        self.assertEqual(lines[-2], 'PROFILE verbose (all rounds): 9 samples')
        self.assertEqual(lines[-1], 'RESULT JUDGED verbose: -1.00 % .. -1.00 %')


class RoundTests(unittest.TestCase):
    def test_a_round_server_gets_the_common_memory_and_the_experiments_settings(self):
        class Logging(Plain):
            prefix = 'db.logs.query.'

            def settings(self, configuration):
                return {'db.logs.query.enabled': 'OFF'} if configuration == 'off' else {}

        self.assertEqual(harness.round_settings(Logging(), 'off'), {**harness.MEMORY, 'db.logs.query.enabled': 'OFF'})
        self.assertEqual(harness.round_settings(Logging(), 'verbose'), harness.MEMORY)
        self.assertEqual(harness.MEMORY['server.memory.heap.initial_size'], harness.MEMORY['server.memory.heap.max_size'])

    def test_an_experiment_prepares_nothing_under_the_server_home_unless_it_says_so(self):
        self.assertIsNone(harness.Experiment().prepare('off', pathlib.Path('/nowhere/neo4j-home')))

    def test_settings_of_the_varied_prefix_the_round_did_not_set_are_foreign(self):
        conf = ('# db.logs.query.threshold=0\n'
                'db.logs.query.enabled=OFF\n'
                'db.logs.query.transaction.enabled=INFO\n'
                'server.metrics.enabled=false\n')
        self.assertEqual(harness.foreign_settings(conf, 'db.logs.query.', {'db.logs.query.enabled'}),
                         {'db.logs.query.transaction.enabled': 'INFO'})
        self.assertEqual(harness.foreign_settings(conf, 'db.logs.query.',
                                                  {'db.logs.query.enabled', 'db.logs.query.transaction.enabled'}), {})


class ProfileTests(unittest.TestCase):
    JFR_PRINT = """jdk.ExecutionSample {
  startTime = 08:49:42.073 (2026-10-30)
  stackTrace = [
    java.util.concurrent.atomic.LongAdder.add(long) line: 90
    org.dozerdb.querylog.QueryLogger.record(QueryEntry) line: 42
    ...
  ]
}

jdk.ExecutionSample {
  startTime = 08:49:42.080 (2026-10-30)
  stackTrace = [
    org.eclipse.jetty.ee8.nested.HttpChannel.handle() line: 458
  ]
}
"""

    def test_stacks_are_read_top_frame_first_as_method_names(self):
        self.assertEqual(harness.jfr_stacks(self.JFR_PRINT), [
            ['java.util.concurrent.atomic.LongAdder.add', 'org.dozerdb.querylog.QueryLogger.record'],
            ['org.eclipse.jetty.ee8.nested.HttpChannel.handle']])


class CommandLineTests(unittest.TestCase):
    def test_the_common_options_and_their_defaults(self):
        args = harness.argument_parser('Measure.', 'query log').parse_args([])
        self.assertEqual((args.mode, args.rounds, args.warmup, args.duration, args.clients, args.api, args.duet,
                          args.seed, args.output, args.profile, args.keep),
                         ('distribution', 3, 20, 60, 16, 'query', False, 25, None, None, False))

    def test_a_script_adds_its_own_options(self):
        parser = harness.argument_parser('Measure.', 'query log')
        parser.add_argument('--worst-case', action='store_true')
        args = parser.parse_args(['plugin', '--duet', '--rounds', '5', '--worst-case', '--profile', 'x'])
        self.assertEqual((args.mode, args.duet, args.rounds, args.worst_case, str(args.profile)),
                         ('plugin', True, 5, True, 'x'))


if __name__ == '__main__':
    unittest.main()
