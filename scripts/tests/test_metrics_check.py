"""Settings, Prometheus configuration, fixture selection, CSV headers, Graphite lines and Prometheus API handling
of scripts/metrics-check.py."""
import importlib.util
import json
import pathlib
import re
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))  # metrics-check.py imports the smoke helper beside it
spec = importlib.util.spec_from_file_location('metrics_check', SCRIPTS / 'metrics-check.py')
metrics_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics_check)


class MetricsSettingsTests(unittest.TestCase):
    def test_csv_at_a_short_interval_and_graphite_at_the_listener(self):
        self.assertEqual(metrics_check.metrics_settings('host.docker.internal', 40123), {
            'server.metrics.csv.interval': '1s',
            'server.metrics.graphite.enabled': 'true',
            'server.metrics.graphite.server': 'host.docker.internal:40123',
            'server.metrics.graphite.interval': '1s',
        })


class HostAccessTests(unittest.TestCase):
    def test_docker_desktop_reaches_the_host_loopback_through_host_docker_internal(self):
        access = metrics_check.host_access('darwin')
        self.assertEqual(access.listen_host, '127.0.0.1')
        self.assertEqual(access.from_container, 'host.docker.internal')
        self.assertEqual(access.scrape_host, 'host.docker.internal')
        self.assertEqual(access.server_docker_args, [])
        self.assertEqual(access.prometheus_docker_args(9090, 41000), ['--publish', '127.0.0.1:41000:9090'])
        self.assertEqual(access.prometheus_listen(9090), '0.0.0.0:9090')

    def test_linux_runs_prometheus_on_the_host_network_and_maps_the_gateway_for_the_server(self):
        access = metrics_check.host_access('linux')
        self.assertEqual(access.listen_host, '0.0.0.0')
        self.assertEqual(access.from_container, 'host.docker.internal')
        self.assertEqual(access.scrape_host, '127.0.0.1')
        self.assertEqual(access.server_docker_args, ['--add-host', 'host.docker.internal:host-gateway'])
        self.assertEqual(access.prometheus_docker_args(9090, 41000), ['--network', 'host'])
        self.assertEqual(access.prometheus_listen(41000), '127.0.0.1:41000')


class PrometheusConfigTests(unittest.TestCase):
    def test_one_static_target_scraped_at_the_given_interval(self):
        self.assertEqual(metrics_check.prometheus_config('host.docker.internal:40500', 2), '\n'.join([
            'global:',
            '  scrape_interval: 2s',
            '  scrape_timeout: 2s',
            '  evaluation_interval: 2s',
            'scrape_configs:',
            '  - job_name: "neo4j"',
            '    metrics_path: "/metrics"',
            '    static_configs:',
            '      - targets: ["host.docker.internal:40500"]',
            '']))


def expression(expect, expr, rewrite=None, names=(), verbatim=False, regexes=()):
    return {'dashboard': 12046, 'revision': 2, 'panel': 'Panel', 'expr': expr, 'expr_2026_10_form': rewrite,
            'names_2026_10_form': list(names), 'verbatim_is_2026_10_form': verbatim,
            'name_regexes': list(regexes), 'expect': expect}


class ReplayTests(unittest.TestCase):
    """What the replay evaluates for an expression (the fixture's description) and what it expects (its tag)."""

    def test_a_verbatim_expression_runs_as_written(self):
        e = expression('data-with-default-filter', 'neo4j_dbms_vm_heap_used', 'neo4j_dbms_vm_heap_used',
                       ['neo4j_dbms_vm_heap_used'], verbatim=True)
        self.assertEqual(metrics_check.replay_queries(e), ['neo4j_dbms_vm_heap_used'])

    def test_an_older_form_runs_as_its_2026_10_rewrite(self):
        e = expression('data-with-default-filter', 'neo4j_vm_heap_used', 'neo4j_dbms_vm_heap_used',
                       ['neo4j_dbms_vm_heap_used'])
        self.assertEqual(metrics_check.replay_queries(e), ['neo4j_dbms_vm_heap_used'])

    def test_a_regex_expression_without_rewrite_runs_each_name_it_matched(self):
        e = expression('data-with-default-filter', '{__name__=~"neo4j_vm_gc_time_g1_.+_generation_total"}', None,
                       ['neo4j_dbms_vm_gc_time_g1_old_generation_total',
                        'neo4j_dbms_vm_gc_time_g1_young_generation_total'],
                       regexes=['neo4j_vm_gc_time_g1_.+_generation_total'])
        self.assertEqual(metrics_check.replay_queries(e), ['neo4j_dbms_vm_gc_time_g1_old_generation_total',
                                                           'neo4j_dbms_vm_gc_time_g1_young_generation_total'])

    def test_a_verbatim_regex_expression_runs_as_written(self):
        expr = '{__name__=~"neo4j_dbms_vm_gc_time_g1_.+_generation_total"}'
        e = expression('data-with-default-filter', expr, None, ['neo4j_dbms_vm_gc_time_g1_old_generation_total'],
                       verbatim=True, regexes=['neo4j_dbms_vm_gc_time_g1_.+_generation_total'])
        self.assertEqual(metrics_check.replay_queries(e), [expr])

    def test_expectations_follow_the_tags(self):
        self.assertEqual(metrics_check.expectation(expression('data-with-default-filter', 'x', 'x')), 'data')
        self.assertEqual(metrics_check.expectation(expression('not-applicable-clustering', 'x', 'x')), 'empty')
        for tag in ('data-only-if-filter-widened', 'not-a-neo4j-metric', 'stale-name-no-2026-10-equivalent',
                    'regex-matches-nothing'):
            self.assertIsNone(metrics_check.expectation(expression(tag, 'x', 'x')), tag)

    def test_a_comparison_with_the_other_cluster_members_has_no_series_on_a_single_instance(self):
        # Dashboard 10371's "Leader Vs Others" panels: $leader is the one instance, so "others" select nothing.
        others = 'sum(rate(neo4j_database_neo4j_transaction_committed_read_total{job="$job", instance!="$leader"}[$interval]))'
        self.assertEqual(metrics_check.expectation(expression('data-with-default-filter', others, others)), 'empty')

    def test_template_variables_are_substituted_and_label_replace_references_kept(self):
        expr = ('label_replace(irate(neo4j_x{job="$job", instance=~"$neo4j_instance"}[$interval]), '
                '"label", "$1", "__name__", "neo4j_(.+)") + on() neo4j_y{instance="$leader"}')
        variables = {'job': 'neo4j', 'neo4j_instance': 'host.docker.internal:4000',
                     'leader': 'host.docker.internal:4000', 'interval': '30s'}
        self.assertEqual(metrics_check.substitute(expr, variables),
                         'label_replace(irate(neo4j_x{job="neo4j", instance=~"host.docker.internal:4000"}[30s]), '
                         '"label", "$1", "__name__", "neo4j_(.+)") + on() neo4j_y{instance="host.docker.internal:4000"}')

    def test_the_interval_is_the_first_option_of_the_dashboards_interval_variable(self):
        dashboard = {'template_variables': [
            {'name': 'job', 'type': 'query', 'query': 'label_values(job)', 'regex': ''},
            {'name': 'interval', 'type': 'interval', 'query': '\t30s,1m,2m,5m', 'regex': None}]}
        self.assertEqual(metrics_check.interval_option(dashboard), '30s')

    def test_the_fixture_selects_its_researched_counts(self):
        fixture = json.loads((SCRIPTS / 'fixtures' / 'dashboard-promql.json').read_text())
        plan = metrics_check.replay_plan(fixture, 'host.docker.internal:4000')
        # The recorded analysis: 43 + 41 + 39 data-with-default-filter, 4 not-applicable-clustering; the two
        # "Leader Vs Others" expressions of 10371 move from data to empty on a single instance.
        self.assertEqual(sum(1 for item in plan if item.expect == 'data'), 123 - 2)
        self.assertEqual(sum(1 for item in plan if item.expect == 'empty'), 4 + 2)
        self.assertEqual(sum(1 for item in plan if item.expect is None), 190 - 123 - 4)
        for item in plan:
            self.assertTrue(item.queries, item.expression['expr'])
            for query in item.queries:
                self.assertNotRegex(query, r'\$(job|neo4j_instance|leader|interval)\b')


class CsvHeaderTests(unittest.TestCase):
    def test_a_gauge_file_and_a_counter_file_with_their_dropwizard_headers_pass(self):
        files = {'neo4j.dbms.vm.heap.used.csv': 't,value\n1761800000,123\n',
                 'neo4j.database.neo4j.transaction.committed.csv': 't,count\n1761800000,4\n'}
        self.assertEqual(metrics_check.csv_problems(files), [])

    def test_a_missing_file_or_a_wrong_header_is_named(self):
        files = {'neo4j.dbms.vm.heap.used.csv': 't,count\n1761800000,123\n'}
        self.assertEqual(metrics_check.csv_problems(files), [
            'neo4j.dbms.vm.heap.used.csv starts with "t,count", expected "t,value"',
            'neo4j.database.neo4j.transaction.committed.csv missing'])


class GraphiteLineTests(unittest.TestCase):
    def test_a_plaintext_line_is_name_value_and_epoch_seconds(self):
        self.assertEqual(metrics_check.parse_graphite_line('neo4j.dbms.vm.heap.used 68654720 1761800000'),
                         ('neo4j.dbms.vm.heap.used', 68654720.0, 1761800000))
        self.assertEqual(metrics_check.parse_graphite_line('neo4j.dbms.page_cache.hit_ratio 0.98 1761800000'),
                         ('neo4j.dbms.page_cache.hit_ratio', 0.98, 1761800000))

    def test_malformed_lines_are_rejected(self):
        for line in ('neo4j.dbms.vm.heap.used 68654720', 'neo4j.dbms.vm.heap.used x 1761800000',
                     'neo4j.dbms.vm.heap.used 1 1761800000000.5', 'a b c d', ''):
            with self.subTest(line=line):
                self.assertIsNone(metrics_check.parse_graphite_line(line))

    def test_names_must_carry_the_prefix_once_and_include_the_heap_gauge(self):
        lines = ['neo4j.dbms.vm.heap.used 68654720 1761800000',
                 'neo4j.database.neo4j.transaction.committed.count 4 1761800000']
        self.assertEqual(metrics_check.graphite_problems(lines, 1761799990, 1761800010), [])

    def test_a_doubled_prefix_a_stale_epoch_and_a_missing_heap_gauge_are_named(self):
        lines = ['neo4j.neo4j.dbms.vm.threads 30 1761800000', 'neo4j.dbms.vm.threads 30 1761700000', 'garbage']
        self.assertEqual(metrics_check.graphite_problems(lines, 1761799990, 1761800010), [
            'name without the prefix once: "neo4j.neo4j.dbms.vm.threads 30 1761800000"',
            'epoch outside the run: "neo4j.dbms.vm.threads 30 1761700000"',
            'malformed line "garbage"',
            'no positive neo4j.dbms.vm.heap.used line'])

    def test_no_lines_at_all_is_a_problem(self):
        self.assertEqual(metrics_check.graphite_problems([], 0, 1), ['no lines received'])


SYSINFO_QUERIES_IT = SCRIPTS.parent / 'metrics/src/test/java/org/dozerdb/metrics/SysInfoQueriesIT.java'


def client_list(constant):
    """One client's (name, database metric) list, read from its own List.of block of the Java fixture."""
    block = metrics_check.SYSINFO.read_text().split(f'List<Metric> {constant} = List.of(', 1)[1].split(');', 1)[0]
    return [(name, flag == 'true') for name, flag in re.findall(r'new Metric\("([^"]+)", (true|false)\)', block)]


class SysInfoFixtureTests(unittest.TestCase):
    def test_both_clients_lists_are_read_from_the_java_fixture_once_each(self):
        metrics = metrics_check.sysinfo_metrics()
        self.assertIn(('page_cache.hit_ratio', False), metrics)
        self.assertIn(('store.size.database', True), metrics)
        self.assertIn(('ids_in_use.property', True), metrics)
        self.assertEqual(len(metrics), len(set(metrics)))
        cypher_shell, browser = client_list('CYPHER_SHELL'), client_list('BROWSER')
        self.assertGreater(len(cypher_shell), 10)
        self.assertGreater(len(browser), 10)
        self.assertEqual(set(metrics), set(cypher_shell) | set(browser))

    def test_the_object_name_follows_the_clients(self):
        self.assertEqual(metrics_check.sysinfo_object_name('ids_in_use.node', True),
                         'neo4j.metrics:name=neo4j.database.neo4j.ids_in_use.node')
        self.assertEqual(metrics_check.sysinfo_object_name('page_cache.hits', False),
                         'neo4j.metrics:name=neo4j.dbms.page_cache.hits')


# An inventory in the checked-in format: comments, a header naming the columns, the scope segment written into some
# names and omitted from others, every tier. The reference link is not read by the check.
def inventory_row(section, name, type_, unit, scope, inferred, tier, reason=''):
    return '\t'.join([section, name, type_, unit, scope, inferred, '-', tier, reason, 'documented',
                      'https://neo4j.com/docs/operations-manual/<version>/monitoring/metrics/reference/', 'false',
                      'documented', ''])


INVENTORY = '\n'.join([
    '# DozerDB metric inventory',
    '# tier: Compatible | Partial | Unsupported | Not applicable',
    'section\tname_template\ttype\tunit\tscope\tscope_inferred\tlifecycle\ttier\treason\tformat_provenance\t'
    'docs_anchor\tdeprecated\ttype_rule\tnotes',
    inventory_row('Page cache', '<prefix>.page_cache.hits', 'counter', '-', 'global', 'false', 'Compatible'),
    inventory_row('Bolt', '<prefix>.dbms.bolt.connections_opened', 'counter', '-', 'global', 'false', 'Compatible'),
    inventory_row('Store size', '<prefix>.store.size.full', 'gauge', 'bytes', 'database', 'true', 'Compatible'),
    inventory_row('Data', '<prefix>.ids_in_use.node', 'gauge', '-', 'database', 'false', 'Partial', 'approximate'),
    inventory_row('Transactions', '<prefix>.database.<db>.transaction.committed', 'counter', '-', 'database', 'false',
                  'Compatible'),
    inventory_row('JVM', '<prefix>.vm.threads.virtual', 'gauge', '-', 'global', 'false', 'Unsupported', 'no hook'),
    inventory_row('Raft', '<prefix>.database.<db>.cluster.raft.is_leader', 'gauge', '-', 'database', 'false',
                  'Not applicable', 'cluster'),
    ''])


class EmittedMetricsTests(unittest.TestCase):
    """What DozerDB emits comes from the line's inventory, so the jmx probe holds no line's metric names."""

    def test_compatible_and_partial_rows_are_emitted_by_name_and_scope(self):
        self.assertEqual(metrics_check.emitted_metrics(INVENTORY), {
            ('page_cache.hits', False), ('bolt.connections_opened', False), ('store.size.full', True),
            ('ids_in_use.node', True), ('transaction.committed', True)})

    def test_an_inventory_without_a_column_the_check_reads_is_rejected(self):
        without_tier = INVENTORY.replace('\ttier\t', '\tclass\t')
        with self.assertRaisesRegex(ValueError, 'no column tier'):
            metrics_check.emitted_metrics(without_tier)

    def test_a_name_without_the_prefix_placeholder_is_rejected(self):
        unprefixed = INVENTORY.replace('<prefix>.page_cache.hits', 'neo4j.page_cache.hits')
        with self.assertRaisesRegex(ValueError, 'neo4j.page_cache.hits: the name does not start with <prefix>.'):
            metrics_check.emitted_metrics(unprefixed)

    def test_a_sysinfo_metric_without_an_emitted_row_of_its_scope_is_not_emitted(self):
        sysinfo = [('store.size.total', True), ('store.size.full', True), ('page_cache.hits', False),
                   ('ids_in_use.node', False), ('vm.threads.virtual', False)]
        self.assertEqual(metrics_check.not_emitted(sysinfo, metrics_check.emitted_metrics(INVENTORY)),
                         ['store.size.total', 'ids_in_use.node', 'vm.threads.virtual'])

    def test_the_line_leaves_out_exactly_the_names_sysinfo_queries_it_lists_as_not_emitted(self):
        # SysInfoQueriesIT keeps its NOT_YET by hand and fails when it disagrees with the inventory; the check derives
        # the same set from the inventory, on every line.
        literal = re.search(r'NOT_YET = Set\.of\(([^)]*)\)', SYSINFO_QUERIES_IT.read_text()).group(1)
        expected = set(re.findall(r'"([^"]+)"', literal))
        emitted = metrics_check.emitted_metrics(metrics_check.INVENTORY.read_text(encoding='utf-8'))
        self.assertEqual(set(metrics_check.not_emitted(metrics_check.sysinfo_metrics(), emitted)), expected)


class ExpositionTests(unittest.TestCase):
    def test_the_heap_gauge_and_a_total_suffixed_counter_family_pass(self):
        text = ('# HELP neo4j_database_neo4j_transaction_committed_total Generated from Dropwizard metric import\n'
                '# TYPE neo4j_database_neo4j_transaction_committed_total counter\n'
                'neo4j_database_neo4j_transaction_committed_total 4.0\n'
                '# TYPE neo4j_dbms_vm_heap_used gauge\n'
                'neo4j_dbms_vm_heap_used 6.865472E7\n')
        self.assertEqual(metrics_check.exposition_problems(text), [])

    def test_a_counter_without_the_suffix_and_a_missing_gauge_are_named(self):
        text = ('# TYPE neo4j_database_neo4j_transaction_committed gauge\n'
                'neo4j_database_neo4j_transaction_committed 4.0\n')
        self.assertEqual(metrics_check.exposition_problems(text), [
            'no "# TYPE neo4j_dbms_vm_heap_used gauge"',
            'no "# TYPE neo4j_database_neo4j_transaction_committed_total counter"'])


class PrometheusApiTests(unittest.TestCase):
    """Responses as Prometheus's HTTP API documents them (/api/v1/targets, /api/v1/query)."""

    def test_the_scraped_target_is_up_after_a_successful_scrape(self):
        response = {'status': 'success', 'data': {'activeTargets': [{
            'labels': {'instance': 'host.docker.internal:40500', 'job': 'neo4j'},
            'scrapeUrl': 'http://host.docker.internal:40500/metrics', 'lastError': '',
            'lastScrape': '2026-10-30T10:00:02.123Z', 'health': 'up'}], 'droppedTargets': []}}
        self.assertEqual(metrics_check.scraped_target(response), ('up', 'host.docker.internal:40500', ''))

    def test_a_target_not_scraped_yet_or_down_reports_its_state(self):
        unknown = {'status': 'success', 'data': {'activeTargets': [{
            'labels': {'instance': 'h:1', 'job': 'neo4j'}, 'lastError': '',
            'lastScrape': '0001-01-01T00:00:00Z', 'health': 'unknown'}]}}
        self.assertEqual(metrics_check.scraped_target(unknown), ('unknown', 'h:1', ''))
        down = {'status': 'success', 'data': {'activeTargets': [{
            'labels': {'instance': 'h:1', 'job': 'neo4j'}, 'lastError': 'connection refused', 'health': 'down'}]}}
        self.assertEqual(metrics_check.scraped_target(down), ('down', 'h:1', 'connection refused'))
        self.assertIsNone(metrics_check.scraped_target({'status': 'success', 'data': {'activeTargets': []}}))

    def test_a_vector_result_counts_its_series(self):
        response = {'status': 'success', 'data': {'resultType': 'vector', 'result': [
            {'metric': {'__name__': 'neo4j_dbms_vm_heap_used', 'instance': 'h:1', 'job': 'neo4j'},
             'value': [1761800000.1, '68654720']}]}}
        self.assertEqual(metrics_check.series_count(response), 1)
        self.assertEqual(metrics_check.series_count({'status': 'success',
                                                     'data': {'resultType': 'vector', 'result': []}}), 0)

    def test_a_scalar_result_is_one_series(self):
        self.assertEqual(metrics_check.series_count({'status': 'success',
                                                     'data': {'resultType': 'scalar', 'result': [1.5, '2']}}), 1)

    def test_an_error_response_raises_with_prometheus_message(self):
        response = {'status': 'error', 'errorType': 'bad_data', 'error': 'parse error at char 5'}
        with self.assertRaisesRegex(ValueError, 'bad_data: parse error at char 5'):
            metrics_check.series_count(response)


if __name__ == '__main__':
    unittest.main()
