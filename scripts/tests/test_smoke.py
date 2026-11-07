"""Environment-variable mapping, Prometheus settings, neo4j.conf parsing, the startup banner and dbms.components
assertions and the dbms.setConfigValue probe of scripts/smoke.py."""
import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))  # smoke.py imports the release-line and version modules beside it
SCRIPT = SCRIPTS / 'smoke.py'
spec = importlib.util.spec_from_file_location('smoke', SCRIPT)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class EnvironmentVariableTests(unittest.TestCase):
    """The image's mapping (docker-entrypoint.sh): NEO4J_ prefix, '_' in a setting doubled, '.' becomes '_'."""

    def test_metrics_settings_become_the_documented_variables(self):
        self.assertEqual(smoke.env_name('server.metrics.prometheus.enabled'), 'NEO4J_server_metrics_prometheus_enabled')
        self.assertEqual(smoke.env_name('server.metrics.prometheus.endpoint'), 'NEO4J_server_metrics_prometheus_endpoint')
        self.assertEqual(smoke.env_name('server.directories.metrics'), 'NEO4J_server_directories_metrics')

    def test_underscores_in_a_setting_are_doubled(self):
        # The entrypoint's own example: NEO4J_server_tx__log_rotation_retention__policy.
        self.assertEqual(smoke.env_name('server.tx_log.rotation.retention_policy'),
                         'NEO4J_server_tx__log_rotation_retention__policy')
        self.assertEqual(smoke.env_name('server.metrics.csv.rotation.keep_number'),
                         'NEO4J_server_metrics_csv_rotation_keep__number')


class ConfParsingTests(unittest.TestCase):
    def test_uncommented_assignments_are_read_and_the_last_value_wins(self):
        text = ('# server.metrics.enabled=false\n'
                'server.directories.metrics=metrics\n'
                '\n'
                'server.jvm.additional=-Xss1m\n'
                'server.directories.metrics=/metrics\n')
        conf = smoke.parse_conf(text)
        self.assertEqual(conf['server.directories.metrics'], '/metrics')
        self.assertEqual(conf['server.jvm.additional'], '-Xss1m')
        self.assertNotIn('server.metrics.enabled', conf)

    def test_surrounding_whitespace_is_ignored(self):
        self.assertEqual(smoke.parse_conf('  server.metrics.prometheus.enabled = true \n'),
                         {'server.metrics.prometheus.enabled': 'true'})


class PrometheusSettingsTests(unittest.TestCase):
    def test_the_two_settings_that_switch_the_endpoint_on(self):
        # The Operations Manual's "Expose metrics" page: enabled plus the host:port of the endpoint.
        self.assertEqual(smoke.prometheus_settings('0.0.0.0', 2004),
                         {'server.metrics.prometheus.enabled': 'true',
                          'server.metrics.prometheus.endpoint': '0.0.0.0:2004'})

    def test_the_settings_map_to_the_variables_the_image_documents(self):
        variables = {smoke.env_name(setting): value for setting, value in smoke.prometheus_settings('0.0.0.0', 2004).items()}
        self.assertEqual(variables, {'NEO4J_server_metrics_prometheus_enabled': 'true',
                                     'NEO4J_server_metrics_prometheus_endpoint': '0.0.0.0:2004'})


class EnvironmentArgumentsTests(unittest.TestCase):
    def test_each_setting_becomes_one_env_argument_pair(self):
        self.assertEqual(smoke.env_args({'server.metrics.csv.interval': '1s',
                                         'server.metrics.graphite.server': 'host.docker.internal:2003'}),
                         ['--env', 'NEO4J_server_metrics_csv_interval=1s',
                          '--env', 'NEO4J_server_metrics_graphite_server=host.docker.internal:2003'])


class SetConfigValueProbeTests(unittest.TestCase):
    """The SHOW PROCEDURES row of dbms.setConfigValue, as the HTTP API returns it, against Neo4j's signature and mode."""

    ROW = {'row': ['dbms.setConfigValue', 'dbms.setConfigValue(setting :: STRING, value :: STRING)', 'DBMS', True]}

    def test_the_one_row_is_read_by_column(self):
        self.assertEqual(smoke.listed_procedure([self.ROW]), smoke.SET_CONFIG_VALUE)

    def test_the_expected_row_is_neo4js_signature_mode_and_admin_marking(self):
        self.assertEqual(smoke.SET_CONFIG_VALUE, {'name': 'dbms.setConfigValue',
                                                  'signature': 'dbms.setConfigValue(setting :: STRING, value :: STRING)',
                                                  'mode': 'DBMS', 'admin': True})

    def test_a_missing_or_duplicated_procedure_fails(self):
        with self.assertRaisesRegex(AssertionError, '0 times'):
            smoke.listed_procedure([])
        with self.assertRaisesRegex(AssertionError, '2 times'):
            smoke.listed_procedure([self.ROW, self.ROW])


class StartupBannerTests(unittest.TestCase):
    """The server log of one start must carry exactly one line DozerDB <dozerdb> (Neo4j <tag>), as Neo4j logs it."""

    BANNER = 'DozerDB 1.2.0 (Neo4j 2026.10.1)'
    START = ('2026-11-06 22:45:08.838+0000 INFO  Logging config in use: File \'/var/lib/neo4j/conf/user-logs.xml\'\n'
             '2026-11-06 22:45:08.841+0000 INFO  Starting...\n')
    BOX = ('2026-11-06 22:45:08.841+0000 INFO  \n\n'
           '*****************************************************************************\n'
           ' *********************** Enhanced By DozerDB Plugin ***********************\n'
           '*****************************************************************************\n\n'
           '2026-11-06 22:45:10.112+0000 INFO  Started.\n')

    def log(self, *lines):
        return self.START + ''.join(f'2026-11-06 22:45:08.841+0000 {line}\n' for line in lines) + self.BOX

    def test_the_banner_splits_the_distribution_version_at_the_first_hyphen(self):
        self.assertEqual(smoke.banner('2026.10.1-1.2.0'), self.BANNER)
        self.assertEqual(smoke.banner('5.26.32-1.2.0'), 'DozerDB 1.2.0 (Neo4j 5.26.32)')

    def test_the_banner_is_built_from_the_pinned_distribution_version(self):
        tag, dozerdb = smoke.VERSION.split('-', 1)
        self.assertEqual(smoke.banner(smoke.VERSION), f'DozerDB {dozerdb} (Neo4j {tag})')

    def test_one_banner_line_before_the_old_box_passes(self):
        self.assertIsNone(smoke.banner_problem(self.log(f'INFO  {self.BANNER}'), self.BANNER))

    def test_a_log_without_the_banner_fails(self):
        self.assertEqual(smoke.banner_problem(self.log(), self.BANNER),
                         "no startup banner 'DozerDB 1.2.0 (Neo4j 2026.10.1)' in the server log")

    def test_a_differently_formatted_banner_fails(self):
        for message in ('DozerDB 2026.10.1-1.2.0', 'DozerDB 1.2.0 (Neo4j 2026.10.0)', 'DozerDB v1.2.0 (Neo4j 2026.10.1)',
                        'DozerDB 1.2.0 (Neo4j 2026.10.1) started', 'DozerDB  1.2.0 (Neo4j 2026.10.1)'):
            with self.subTest(message=message):
                problem = smoke.banner_problem(self.log(f'INFO  {message}'), self.BANNER)
                self.assertIn('found 1', problem)
                self.assertIn(message, problem)

    def test_a_banner_without_the_log_prefix_fails(self):
        problem = smoke.banner_problem(self.START + self.BANNER + '\n' + self.BOX, self.BANNER)
        self.assertIn('found 1', problem)

    def test_two_banner_lines_fail(self):
        problem = smoke.banner_problem(self.log(f'INFO  {self.BANNER}', f'INFO  {self.BANNER}'), self.BANNER)
        self.assertIn('found 2', problem)

    def test_a_correct_banner_beside_a_wrong_one_fails(self):
        problem = smoke.banner_problem(self.log(f'INFO  {self.BANNER}', 'INFO  DozerDB 1.1.0 (Neo4j 2026.10.1)'),
                                       self.BANNER)
        self.assertIn('found 2', problem)

    def test_the_bootstrappers_unreadable_version_warning_fails_and_is_shown(self):
        warning = 'WARN  DozerDB version unknown: resource dozerdb-version.properties is missing from the core JAR'
        problem = smoke.banner_problem(self.log(warning), self.BANNER)
        self.assertIn('found 1', problem)
        self.assertIn('is missing from the core JAR', problem)


class ComponentsTests(unittest.TestCase):
    """CALL dbms.components() returns Neo4j's own rows on every line: the Neo4j Kernel row at the upstream tag, Cypher
    rows with no edition (their versions are not asserted), and nothing DozerDB adds."""

    KERNEL = ['Neo4j Kernel', ['2026.10.1'], 'enterprise']

    def test_the_kernel_row_at_the_tag_with_cypher_rows_passes(self):
        for cypher in ([['Cypher', ['5', '25'], '']], [['Cypher', ['5'], '']], [['Cypher', ['5'], ''], ['Cypher', ['25'], '']], []):
            with self.subTest(cypher=cypher):
                self.assertIsNone(smoke.components_problem([self.KERNEL, *cypher], '2026.10.1'))

    def test_a_row_dozerdb_adds_fails_listing_the_rows(self):
        rows = [self.KERNEL, ['Cypher', ['5', '25'], ''], ['DozerDB', ['1.2.0'], '']]
        problem = smoke.components_problem(rows, '2026.10.1')
        self.assertIn('only the Neo4j Kernel row and Cypher rows', problem)
        self.assertIn(str(rows), problem)

    def test_a_cypher_row_with_an_edition_fails(self):
        problem = smoke.components_problem([self.KERNEL, ['Cypher', ['5'], 'enterprise']], '2026.10.1')
        self.assertIn('Cypher rows with no edition', problem)

    def test_a_kernel_row_at_another_version_or_edition_fails_naming_the_expected_row(self):
        for kernel in (['Neo4j Kernel', ['2026.10.0'], 'enterprise'], ['Neo4j Kernel', ['2026.10.1'], 'community'],
                       ['Neo4j Kernel', ['2026.10.1-1.2.0'], 'enterprise']):
            with self.subTest(kernel=kernel):
                problem = smoke.components_problem([kernel, ['Cypher', ['5', '25'], '']], '2026.10.1')
                self.assertIn("must report one row ['Neo4j Kernel', ['2026.10.1'], 'enterprise']", problem)
                self.assertIn(str(kernel), problem)

    def test_a_missing_or_duplicated_kernel_row_fails(self):
        self.assertIn('must report one row', smoke.components_problem([['Cypher', ['5', '25'], '']], '2026.10.1'))
        self.assertIn('must report one row', smoke.components_problem([self.KERNEL, self.KERNEL], '2026.10.1'))

    def test_rows_are_read_from_the_http_api_result(self):
        result = {'columns': ['name', 'versions', 'edition'],
                  'data': [{'row': ['Neo4j Kernel', ['2026.10.1'], 'enterprise'], 'meta': [None, None, None]},
                           {'row': ['Cypher', ['5', '25'], ''], 'meta': [None, None, None]}]}
        self.assertEqual(smoke.component_rows(result), [self.KERNEL, ['Cypher', ['5', '25'], '']])


if __name__ == '__main__':
    unittest.main()
