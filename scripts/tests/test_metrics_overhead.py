"""Round scheduling, statistics, workload selection and report formatting of scripts/metrics-overhead.py."""
import collections
import importlib.util
import pathlib
import random
import statistics
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'metrics-overhead.py'
spec = importlib.util.spec_from_file_location('metrics_overhead', SCRIPT)
overhead = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overhead)


def result(block, configuration, throughput, **extra):
    return {'block': block, 'configuration': configuration, 'throughput': throughput, 'reads': 80 * throughput,
            'writes': 20 * throughput, 'second_half': 40 * throughput, 'errors': 0, 'duration': 100, 'mbeans': 0, 'load_before': 1.0,
            'load_after': 2.0, **extra}


# The shape `jfr print --events jdk.ExecutionSample` writes (frames abridged from a recording of the defaults).
JFR_PRINT = """jdk.ExecutionSample {
  startTime = 08:49:42.073 (2026-10-30)
  sampledThread = "neo4j.BoltWorker-7" (javaThreadId = 127)
  state = "STATE_RUNNABLE"
  stackTrace = [
    java.util.concurrent.atomic.LongAdder.add(long) line: 90
    org.dozerdb.metrics.shaded.com.codahale.metrics.Counter.inc(long) line: 28
    org.dozerdb.metrics.collectors.QueryExecutionCollector$Series.succeeded(long) line: 92
    org.dozerdb.metrics.adapter.QueryExecutionMonitorAdapter$Listener.endSuccess(ExecutingQuery) line: 88
    java.lang.reflect.Method.invoke(Object, Object[]) line: 580
    org.neo4j.monitoring.Monitors$MonitorInvocationHandler.invoke(Object, Method, Object[]) line: 199
    ...
  ]
}

jdk.ExecutionSample {
  startTime = 08:49:42.080 (2026-10-30)
  sampledThread = "neo4j.BoltWorker-3" (javaThreadId = 123)
  state = "STATE_RUNNABLE"
  stackTrace = [
    jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Object, Object[]) line: 103
    java.lang.reflect.Method.invoke(Object, Object[]) line: 580
    org.neo4j.monitoring.Monitors$UntaggedMonitorListenerInvocationHandler.invoke(Object, Method, Object[], String[]) line: 168
    org.neo4j.fabric.executor.QueryStatementLifecycles$StatementLifecycleImpl.endSuccess() line: 240
  ]
}

jdk.ExecutionSample {
  startTime = 08:49:33.617 (2026-10-30)
  sampledThread = "qtp971941110-93" (javaThreadId = 93)
  state = "STATE_RUNNABLE"
  stackTrace = [
    org.eclipse.jetty.server.internal.HttpConnection$HttpStreamOverHTTP1.consumeAvailable() line: 1279
    org.eclipse.jetty.ee8.nested.HttpChannel.handle() line: 458
  ]
}

jdk.ExecutionSample {
  startTime = 08:49:43.001 (2026-10-30)
  sampledThread = "neo4j.BoltWorker-1" (javaThreadId = 121)
  state = "STATE_RUNNABLE"
  stackTrace = [
    org.dozerdb.metrics.shaded.com.codahale.metrics.Counter.inc(long) line: 28
    org.dozerdb.metrics.collectors.BoltCollector$1.messageReceived() line: 70
  ]
}
"""


class ScheduleTests(unittest.TestCase):
    def test_two_configurations_alternate_a_b_b_a_a_b(self):
        order = overhead.schedule(['off', 'defaults'], 3)
        self.assertEqual([name for _, name in order], ['off', 'defaults', 'defaults', 'off', 'off', 'defaults'])
        self.assertEqual([block for block, _ in order], [0, 0, 1, 1, 2, 2])

    def test_three_configurations_reverse_every_other_block(self):
        order = overhead.schedule(['off', 'defaults', 'open'], 2)
        self.assertEqual([name for _, name in order], ['off', 'defaults', 'open', 'open', 'defaults', 'off'])

    def test_every_block_holds_every_configuration_once(self):
        order = overhead.schedule(['off', 'defaults', 'open'], 5)
        blocks = collections.defaultdict(list)
        for block, name in order:
            blocks[block].append(name)
        self.assertEqual(len(blocks), 5)
        for names in blocks.values():
            self.assertEqual(sorted(names), ['defaults', 'off', 'open'])

    def test_each_configuration_leads_equally_often_in_an_even_number_of_blocks(self):
        order = overhead.schedule(['off', 'defaults'], 4)
        firsts = collections.Counter(name for i, (_, name) in enumerate(order) if i % 2 == 0)
        self.assertEqual(firsts, {'off': 2, 'defaults': 2})

    def test_no_rounds_is_rejected(self):
        with self.assertRaises(ValueError):
            overhead.schedule(['off', 'defaults'], 0)


class StatisticsTests(unittest.TestCase):
    def test_t_quantiles_take_the_next_lower_tabulated_degrees_of_freedom(self):
        self.assertEqual(overhead.t95(1), 12.706)
        self.assertEqual(overhead.t95(4), 2.776)
        self.assertEqual(overhead.t95(35), overhead.t95(30))
        self.assertEqual(overhead.t95(5000), 1.960)
        with self.assertRaises(ValueError):
            overhead.t95(0)

    def test_summary_of_rounds(self):
        s = overhead.summarize([100.0, 102.0, 98.0])
        self.assertAlmostEqual(s['mean'], 100.0)
        self.assertAlmostEqual(s['stdev'], 2.0)
        self.assertAlmostEqual(s['cv_percent'], 2.0)
        self.assertEqual((s['min'], s['max'], s['n']), (98.0, 102.0, 3))

    def test_paired_delta_uses_per_block_ratios(self):
        # A common drift between blocks cancels: every block shows exactly -1 %.
        mean, low, high = overhead.paired_delta([100.0, 200.0, 50.0], [99.0, 198.0, 49.5])
        self.assertAlmostEqual(mean, -1.0)
        self.assertAlmostEqual(low, -1.0)
        self.assertAlmostEqual(high, -1.0)

    def test_paired_interval_is_mean_plus_minus_t_times_standard_error(self):
        mean, low, high = overhead.paired_delta([100.0, 100.0, 100.0], [99.0, 100.0, 101.0])
        half = 4.303 * statistics.stdev([-1.0, 0.0, 1.0]) / 3 ** 0.5
        self.assertAlmostEqual(mean, 0.0)
        self.assertAlmostEqual(low, -half)
        self.assertAlmostEqual(high, half)

    def test_paired_delta_of_one_pair_has_no_interval(self):
        self.assertEqual(overhead.paired_delta([100.0], [99.0])[1:], (None, None))
        with self.assertRaises(ValueError):
            overhead.paired_delta([100.0], [99.0, 98.0])

    def test_unpaired_delta_uses_welch(self):
        baseline, candidate = [100.0, 102.0, 98.0], [97.0, 99.0, 95.0, 97.0]
        mean, low, high = overhead.unpaired_delta(baseline, candidate)
        self.assertAlmostEqual(mean, -3.0)
        vb, vc = 4.0 / 3, statistics.variance(candidate) / 4
        df = (vb + vc) ** 2 / (vb ** 2 / 2 + vc ** 2 / 3)
        half = overhead.t95(int(df)) * (vb + vc) ** 0.5
        self.assertAlmostEqual(low, -3.0 - half)
        self.assertAlmostEqual(high, -3.0 + half)


class VerdictTests(unittest.TestCase):
    def test_met_when_the_worst_end_costs_less_than_the_budget(self):
        status, reason = overhead.verdict(-0.6, 0.2)
        self.assertEqual(status, 'MET')
        self.assertIn('below 0.60 %', reason)

    def test_exceeded_when_the_best_end_costs_more_than_the_budget(self):
        status, reason = overhead.verdict(-3.0, -1.5)
        self.assertEqual(status, 'EXCEEDED')
        self.assertIn('above 1.50 %', reason)

    def test_inconclusive_names_the_upper_bound_the_data_supports(self):
        status, reason = overhead.verdict(-2.4, 0.8)
        self.assertEqual(status, 'INCONCLUSIVE')
        self.assertIn('noise of this machine is larger than the 1 % budget', reason)
        self.assertIn('at most 2.40 %', reason)

    def test_single_round_is_inconclusive(self):
        self.assertEqual(overhead.verdict(None, None)[0], 'INCONCLUSIVE')


class WorkloadTests(unittest.TestCase):
    def test_the_operation_sequence_is_repeatable_for_a_seed(self):
        rng_a, rng_b = random.Random(7), random.Random(7)
        self.assertEqual([overhead.pick(rng_a) for _ in range(50)], [overhead.pick(rng_b) for _ in range(50)])

    def test_the_mix_is_eighty_percent_reads(self):
        rng = random.Random(25)
        kinds = collections.Counter(overhead.pick(rng)[0] for _ in range(20_000))
        self.assertAlmostEqual(kinds['read'] / 20_000, 0.8, delta=0.01)
        self.assertEqual(overhead.WEIGHTS, 100)

    def test_parameters_stay_inside_the_loaded_data(self):
        rng = random.Random(1)
        for _ in range(1000):
            _, _, parameters = overhead.pick(rng)
            self.assertTrue(0 <= parameters['id'] < overhead.PEOPLE)

    def test_request_bodies_of_both_endpoints(self):
        self.assertEqual(overhead.request_body('query', 'RETURN $id', {'id': 1}),
                         {'statement': 'RETURN $id', 'parameters': {'id': 1}})
        self.assertEqual(overhead.request_body('tx', 'RETURN $id', {'id': 1}),
                         {'statements': [{'statement': 'RETURN $id', 'parameters': {'id': 1}}]})


class ReportTests(unittest.TestCase):
    def test_round_line(self):
        line = overhead.format_round(2, 6, result(1, 'defaults', 1234.56, mbeans=97))
        self.assertTrue(line.startswith('ROUND 2/6 defaults    1234.6 ops/s'), line)
        self.assertIn('metrics MBeans 97', line)
        self.assertIn('halves 1481/988', line)  # 120 % then 80 % of the round's throughput
        self.assertIn('load 1.0->2.0', line)
        self.assertNotIn('scrapes', line)
        self.assertIn('scrapes 59', overhead.format_round(3, 9, result(1, 'open', 1000.0, scrapes=59)))
        share = overhead.metrics_share(overhead.jfr_stacks(JFR_PRINT))
        profiled = overhead.format_round(1, 2, result(0, 'defaults', 1000.0, profile=share)).splitlines()
        self.assertEqual(profiled[1], '  ' + overhead.format_profile('defaults', share))

    def test_summary_names_every_configuration_the_delta_and_the_verdict_last(self):
        rounds = [result(0, 'off', 1000.0), result(0, 'defaults', 998.0),
                  result(1, 'defaults', 1099.0), result(1, 'off', 1100.0),
                  result(2, 'off', 900.0), result(2, 'defaults', 899.0)]
        lines = overhead.format_summary(rounds)
        self.assertTrue(lines[0].startswith('CONFIG off      mean 1000.0 ops/s, stdev 100.0 (10.00 %)'), lines[0])
        self.assertIn('rounds 1000.0 1100.0 900.0', lines[0])
        self.assertTrue(lines[1].startswith('CONFIG defaults'), lines[1])
        self.assertTrue(lines[2].startswith('DELTA defaults vs off: -0.13 % throughput, paired 95 % CI '), lines[2])
        self.assertIn('unpaired 95 % CI', lines[2])
        # The rounds vary by 10 % between blocks, the pairs by a fraction of a percent: the paired verdict holds.
        self.assertTrue(lines[-1].startswith('RESULT MET defaults:'), lines[-1])

    def test_the_worst_case_is_information_not_the_result(self):
        rounds = [result(0, 'off', 1000.0), result(0, 'defaults', 999.0), result(0, 'open', 900.0),
                  result(1, 'open', 905.0), result(1, 'defaults', 1001.0), result(1, 'off', 1002.0)]
        lines = overhead.format_summary(rounds)
        self.assertTrue(lines[-1].startswith('RESULT '), lines)
        self.assertIn('defaults:', lines[-1])
        info = [line for line in lines if line.startswith('INFO EXCEEDED open:')]
        self.assertEqual(len(info), 1, lines)
        self.assertIn('worst case, outside the budget', info[0])
        self.assertEqual(sum(line.startswith('RESULT') for line in lines), 1)


class ProfileTests(unittest.TestCase):
    def test_stacks_are_read_top_frame_first_without_signatures(self):
        stacks = overhead.jfr_stacks(JFR_PRINT)
        self.assertEqual(len(stacks), 4)
        self.assertEqual(stacks[0][:2], ['java.util.concurrent.atomic.LongAdder.add',
                                         'org.dozerdb.metrics.shaded.com.codahale.metrics.Counter.inc'])
        self.assertNotIn('...', stacks[0])
        self.assertEqual(stacks[2], ['org.eclipse.jetty.server.internal.HttpConnection$HttpStreamOverHTTP1.consumeAvailable',
                                     'org.eclipse.jetty.ee8.nested.HttpChannel.handle'])

    def test_metrics_samples_are_attributed_to_their_topmost_module_frame(self):
        share = overhead.metrics_share(overhead.jfr_stacks(JFR_PRINT))
        self.assertEqual((share['samples'], share['metrics_samples'], share['dispatch_samples']), (4, 2, 1))
        self.assertAlmostEqual(share['metrics_percent'], 50.0)
        self.assertEqual(share['hottest'], [('org.dozerdb.metrics.shaded.com.codahale.metrics.Counter.inc', 2)])

    def test_no_samples_is_zero_percent(self):
        self.assertEqual(overhead.metrics_share([])['metrics_percent'], 0.0)

    def test_profile_line(self):
        line = overhead.format_profile('defaults', overhead.metrics_share(overhead.jfr_stacks(JFR_PRINT)))
        self.assertEqual(line, 'PROFILE defaults: 2 of 4 CPU samples (50.000 %) in metrics code, 1 (25.000 %) in '
                               'monitor dispatch; hottest: shaded.com.codahale.metrics.Counter.inc 2')

    def test_summary_merges_the_profiles_of_every_round(self):
        share = overhead.metrics_share(overhead.jfr_stacks(JFR_PRINT))
        rounds = [result(0, 'off', 1000.0), result(0, 'defaults', 999.0, profile=share),
                  result(1, 'defaults', 1001.0, profile=share), result(1, 'off', 1002.0)]
        lines = overhead.format_summary(rounds)
        self.assertIn('PROFILE defaults (all rounds): 4 of 8 CPU samples (50.000 %) in metrics code, 2 (25.000 %) '
                      'in monitor dispatch; hottest: shaded.com.codahale.metrics.Counter.inc 4', lines)
        self.assertTrue(lines[-1].startswith('RESULT '), lines)


class ConfigurationTests(unittest.TestCase):
    def test_every_configuration_has_the_same_memory(self):
        for name in ('off', 'defaults', 'open'):
            settings = overhead.round_settings(name)
            self.assertEqual(settings['server.memory.heap.initial_size'], settings['server.memory.heap.max_size'])
            self.assertEqual({k: v for k, v in settings.items() if k.startswith('server.memory.')}, overhead.MEMORY)

    def test_the_configurations_differ_only_in_metrics_settings(self):
        metrics = {name: {k: v for k, v in overhead.round_settings(name).items() if k.startswith('server.metrics.')}
                   for name in ('off', 'defaults', 'open')}
        self.assertEqual(metrics, {'off': {'server.metrics.enabled': 'false'}, 'defaults': {},
                                   'open': {'server.metrics.filter': '*'}})

    def test_metrics_settings_the_round_did_not_set_are_reported(self):
        conf = ('# server.metrics.csv.enabled=false\n'
                'server.metrics.enabled=false\n'
                'server.metrics.csv.interval=1s\n'
                'server.memory.heap.max_size=1g\n')
        self.assertEqual(overhead.foreign_metrics_settings(conf, {'server.metrics.enabled'}),
                         {'server.metrics.csv.interval': '1s'})
        self.assertEqual(overhead.foreign_metrics_settings(conf, {'server.metrics.enabled',
                                                                  'server.metrics.csv.interval'}), {})


class ArgumentTests(unittest.TestCase):
    def test_defaults_measure_the_distribution_for_a_minute_after_twenty_seconds(self):
        args = overhead.parse_args([])
        self.assertEqual((args.mode, args.rounds, args.warmup, args.duration, args.api, args.worst_case),
                         ('distribution', 3, 20, 60, 'query', False))

    def test_the_plugin_form_and_the_worst_case_are_selectable(self):
        args = overhead.parse_args(['plugin', '--worst-case', '--rounds', '5', '--duet'])
        self.assertEqual((args.mode, args.worst_case, args.rounds, args.duet), ('plugin', True, 5, True))
        self.assertFalse(overhead.parse_args([]).duet)


if __name__ == '__main__':
    unittest.main()
