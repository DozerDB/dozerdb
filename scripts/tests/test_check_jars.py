"""Relocation mapping, service-file parsing and metadata classification of scripts/check-jars.py."""
import importlib.util
import pathlib
import unittest
import xml.etree.ElementTree as ET

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'check-jars.py'
spec = importlib.util.spec_from_file_location('check_jars', SCRIPT)
check_jars = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_jars)


class RelocationTests(unittest.TestCase):
    def test_dropwizard_classes_move_under_the_dozerdb_shaded_package(self):
        self.assertEqual(check_jars.relocated('com/codahale/metrics/MetricRegistry.class'),
                         'org/dozerdb/metrics/shaded/com/codahale/metrics/MetricRegistry.class')
        self.assertEqual(check_jars.relocated('com/codahale/metrics/jmx/JmxReporter$JmxGauge.class'),
                         'org/dozerdb/metrics/shaded/com/codahale/metrics/jmx/JmxReporter$JmxGauge.class')

    def test_only_dropwizard_classes_are_relocated(self):
        with self.assertRaises(ValueError):
            check_jars.relocated('org/slf4j/Logger.class')


class ServiceFileTests(unittest.TestCase):
    def test_providers_are_read_without_comments_and_blank_lines(self):
        data = b'# generated\norg.dozerdb.metrics.settings.MetricsSettings\n\n  org.neo4j.configuration.DozerDbSettings \n'
        self.assertEqual(check_jars.service_lines(data),
                         {'org.dozerdb.metrics.settings.MetricsSettings', 'org.neo4j.configuration.DozerDbSettings'})

    def test_merged_file_is_the_union_of_its_sources(self):
        core = check_jars.service_lines(b'org.neo4j.configuration.DozerDbSettings\n')
        metrics = check_jars.service_lines(b'org.dozerdb.metrics.settings.MetricsSettings\n')
        merged = check_jars.service_lines(b'org.neo4j.configuration.DozerDbSettings\norg.dozerdb.metrics.settings.MetricsSettings\n')
        self.assertEqual(merged, core | metrics)


class MetadataTests(unittest.TestCase):
    def test_manifest_directories_and_maven_descriptor_are_metadata(self):
        for name in ('META-INF/', 'META-INF/MANIFEST.MF', 'META-INF/INDEX.LIST',
                     'META-INF/maven/org.dozerdb/dozerdb-metrics/pom.xml', 'org/dozerdb/metrics/shaded/'):
            self.assertTrue(check_jars.is_metadata(name), name)

    def test_shipped_files_are_not_metadata(self):
        for name in ('META-INF/DOZERDB-NOTICE.txt', 'META-INF/services/org.neo4j.configuration.SettingsDeclaration',
                     'META-INF/LICENSE.txt', 'org/dozerdb/metrics/inventory/metrics-inventory.tsv',
                     'org/dozerdb/metrics/shaded/com/codahale/metrics/Gauge.class'):
            self.assertFalse(check_jars.is_metadata(name), name)


class DependencyTests(unittest.TestCase):
    POM = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>'
           '<dependency><groupId>io.dropwizard.metrics</groupId><artifactId>metrics-core</artifactId></dependency>'
           '<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><scope>provided</scope></dependency>'
           '<dependency><groupId>io.dropwizard.metrics</groupId><artifactId>metrics-graphite</artifactId>'
           '<exclusions><exclusion><groupId>com.rabbitmq</groupId><artifactId>amqp-client</artifactId></exclusion></exclusions>'
           '</dependency>'
           '<dependency><groupId>io.dropwizard.metrics</groupId><artifactId>metrics-jmx</artifactId></dependency>'
           '</dependencies></project>')

    def test_artifacts_of_one_group_in_declaration_order(self):
        self.assertEqual(check_jars.dependency_artifacts(ET.fromstring(self.POM), 'io.dropwizard.metrics'),
                         ['metrics-core', 'metrics-graphite', 'metrics-jmx'])
        self.assertEqual(check_jars.dependency_artifacts(ET.fromstring(self.POM), 'io.prometheus'), [])

    def test_notice_must_name_the_dropwizard_version_and_every_declared_artifact(self):
        names = check_jars.notice_names('ignored', '4.2.40', ['metrics-core', 'metrics-graphite', 'metrics-jmx'])
        self.assertEqual(names, ['Dropwizard Metrics 4.2.40', 'metrics-core', 'metrics-graphite', 'metrics-jmx',
                                 'Prometheus simpleclient 0.16.0', 'Apache License, Version 2.0'])


if __name__ == '__main__':
    unittest.main()
