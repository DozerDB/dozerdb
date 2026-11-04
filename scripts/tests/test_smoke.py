"""Environment-variable mapping, Prometheus settings and neo4j.conf parsing of scripts/smoke.py."""
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'smoke.py'
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


if __name__ == '__main__':
    unittest.main()
