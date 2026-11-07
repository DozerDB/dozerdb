"""Relocation mapping, service-file parsing, metadata classification and the procedures, query-log and plugin content
checks of scripts/check-jars.py, the last three on synthetic JARs."""
import importlib.util
import pathlib
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))  # check-jars.py reads the pins through the release-line module beside it
SCRIPT = SCRIPTS / 'check-jars.py'
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


EXTENSION_FACTORY = 'META-INF/services/org.neo4j.kernel.extension.ExtensionFactory'
SETTINGS = 'META-INF/services/org.neo4j.configuration.SettingsDeclaration'
# What each module's build writes, as JAR entries; the Neo4j parent gives every module LICENSE.txt twice.
CORE = {'org/neo4j/server/DozerDbEntryPoint.class': b'core', 'META-INF/LICENSE.txt': b'core licence', 'LICENSE.txt': b'core licence',
        'META-INF/README.md': b'core readme', 'META-INF/PublicApi.txt': b'core api',
        SETTINGS: b'org.neo4j.configuration.DozerDbSettings\n'}
METRICS = {'org/dozerdb/metrics/adapter/MetricsExtensionFactory.class': b'metrics', 'META-INF/LICENSE.txt': b'metrics licence',
           'LICENSE.txt': b'metrics licence', 'META-INF/DOZERDB-NOTICE.txt': b'notice',
           'org/dozerdb/metrics/shaded/com/codahale/metrics/Gauge.class': b'gauge',
           EXTENSION_FACTORY: b'org.dozerdb.metrics.adapter.MetricsExtensionFactory\n',
           SETTINGS: b'org.dozerdb.metrics.settings.MetricsSettings\n'}
QUERY_LOG = {'org/dozerdb/querylog/adapter/QueryLogExtensionFactory.class': b'factory',
             'org/dozerdb/querylog/extension/QueryLogExtension.class': b'extension',
             'META-INF/LICENSE.txt': b'query-log licence', 'LICENSE.txt': b'query-log licence',
             EXTENSION_FACTORY: b'org.dozerdb.querylog.adapter.QueryLogExtensionFactory\n'}
PROCEDURES = {'org/dozerdb/procedures/adapter/ProceduresExtensionFactory.class': b'factory',
              'org/dozerdb/procedures/adapter/SetConfigValueProcedure.class': b'procedure',
              'META-INF/LICENSE.txt': b'procedures licence', 'LICENSE.txt': b'procedures licence',
              EXTENSION_FACTORY: b'org.dozerdb.procedures.adapter.ProceduresExtensionFactory\n'}
BROWSER = {'browser/index.html': b'<html>', 'browser/app-1.js': b'js', 'browser/LICENSE': b'browser licence'}


def shaded(*modules):
    """What the plugin shade produces from the module JARs: every file of the first (core) wins the per-module names,
    service files are merged, everything else is carried over."""
    plugin = {}
    for module in modules:
        for name, data in module.items():
            if name.startswith('META-INF/services/'):
                plugin[name] = plugin.get(name, b'') + data
            elif name in check_jars.PER_MODULE_FILES:
                plugin.setdefault(name, data)
            else:
                plugin[name] = data
    return plugin


class SyntheticJarTestCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.open = []

    def tearDown(self):
        for archive in self.open:
            archive.close()
        self.dir.cleanup()

    def jar(self, name, entries):
        path = pathlib.Path(self.dir.name, f'{name}.jar')
        with ZipFile(path, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\n')
            for entry, data in entries.items():
                archive.writestr(entry, data)
        archive = ZipFile(path)
        self.open.append(archive)
        return archive


class VersionResourceTests(SyntheticJarTestCase):
    """The core JAR's startup-banner resource carries the distribution version, filled by Maven from the template."""

    TEMPLATE = (pathlib.Path(__file__).resolve().parents[2] / 'core/src/main/version/dozerdb-version.properties').read_bytes()

    # The resource the bootstrapper reads, beside it in the core JAR.
    RESOURCE = 'org/neo4j/server/dozerdb-version.properties'

    def core(self, version):
        return self.jar('core', {self.RESOURCE: f'# Written by the build\nversion={version}\n'.encode()})

    def test_the_tracked_template_and_a_jar_at_the_distribution_version_pass(self):
        check_jars.check_version_resource(self.core(check_jars.VERSION), self.TEMPLATE)

    def test_another_version_in_the_jar_fails(self):
        with self.assertRaisesRegex(AssertionError, "carries '2026.10.1-1.1.0', expected the distribution version"):
            check_jars.check_version_resource(self.core('2026.10.1-1.1.0'), self.TEMPLATE)

    def test_an_unfiltered_resource_fails(self):
        with self.assertRaisesRegex(AssertionError, 'carries'):
            check_jars.check_version_resource(self.core('${project.version}'), self.TEMPLATE)

    def test_a_core_jar_without_the_resource_fails_naming_it(self):
        with self.assertRaisesRegex(AssertionError, f'lacks the version resource {self.RESOURCE}'):
            check_jars.check_version_resource(self.jar('core', {}), self.TEMPLATE)
        with self.assertRaisesRegex(AssertionError, 'lacks the version resource'):
            check_jars.check_version_resource(self.jar('core', {'dozerdb-version.properties': b'version=1.2.3-1.0.0\n'}),
                                              self.TEMPLATE)

    def test_a_hand_written_template_value_fails(self):
        with self.assertRaisesRegex(AssertionError, 'not a hand-written value'):
            check_jars.check_version_resource(self.core(check_jars.VERSION), f'version={check_jars.VERSION}\n'.encode())

    def test_comments_are_ignored_and_one_version_line_is_required(self):
        self.assertEqual(check_jars.resource_version(b'# version=old\nversion = 1.2.3-1.0.0\n'), '1.2.3-1.0.0')
        with self.assertRaisesRegex(AssertionError, 'found 2'):
            check_jars.resource_version(b'version=a-1.0.0\nversion=b-1.0.0\n')


class QueryLogArtifactTests(SyntheticJarTestCase):
    """The query-log artifact is the module's own output and nothing else, with one service file naming the
    extension factory (no notice: the module adds no library)."""

    def test_the_modules_own_output_with_its_service_file_passes(self):
        q = self.jar('query-log', QUERY_LOG)
        check_jars.check_query_log(q, set(q.namelist()), own=set(QUERY_LOG))

    def test_a_missing_service_file_fails(self):
        without = {name: data for name, data in QUERY_LOG.items() if name != EXTENSION_FACTORY}
        q = self.jar('query-log', without)
        with self.assertRaisesRegex(AssertionError, 'service file'):
            check_jars.check_query_log(q, set(q.namelist()), own=set(without))

    def test_a_service_file_without_the_factory_fails(self):
        wrong = {**QUERY_LOG, EXTENSION_FACTORY: b'org.dozerdb.querylog.adapter.Other\n'}
        q = self.jar('query-log', wrong)
        with self.assertRaisesRegex(AssertionError, 'QueryLogExtensionFactory'):
            check_jars.check_query_log(q, set(q.namelist()), own=set(wrong))

    def test_a_file_the_build_did_not_write_fails(self):
        q = self.jar('query-log', {**QUERY_LOG, 'org/slf4j/Logger.class': b'x'})
        with self.assertRaisesRegex(AssertionError, 'differs from its own output'):
            check_jars.check_query_log(q, set(q.namelist()), own=set(QUERY_LOG))

    def test_a_class_outside_the_dozerdb_package_fails(self):
        q = self.jar('query-log', {**QUERY_LOG, 'org/neo4j/Extra.class': b'x'})
        with self.assertRaisesRegex(AssertionError, 'outside the DozerDB package'):
            check_jars.check_query_log(q, set(q.namelist()), own=set(QUERY_LOG) | {'org/neo4j/Extra.class'})

    def test_a_markdown_document_fails(self):
        q = self.jar('query-log', {**QUERY_LOG, 'META-INF/README.md': b'# readme'})
        with self.assertRaisesRegex(AssertionError, 'Markdown'):
            check_jars.check_query_log(q, set(q.namelist()), own=set(QUERY_LOG) | {'META-INF/README.md'})


class ProceduresArtifactTests(SyntheticJarTestCase):
    """The procedures artifact is the module's own output and nothing else, with one service file naming the
    extension factory (no notice: the module adds no library)."""

    def test_the_modules_own_output_with_its_service_file_passes(self):
        p = self.jar('procedures', PROCEDURES)
        check_jars.check_procedures(p, set(p.namelist()), own=set(PROCEDURES))

    def test_a_service_file_without_the_factory_fails(self):
        wrong = {**PROCEDURES, EXTENSION_FACTORY: b'org.dozerdb.procedures.adapter.Other\n'}
        p = self.jar('procedures', wrong)
        with self.assertRaisesRegex(AssertionError, 'ProceduresExtensionFactory'):
            check_jars.check_procedures(p, set(p.namelist()), own=set(wrong))

    def test_a_class_outside_the_dozerdb_package_fails(self):
        p = self.jar('procedures', {**PROCEDURES, 'org/neo4j/Extra.class': b'x'})
        with self.assertRaisesRegex(AssertionError, 'outside the DozerDB package'):
            check_jars.check_procedures(p, set(p.namelist()), own=set(PROCEDURES) | {'org/neo4j/Extra.class'})

    def test_a_notice_fails(self):
        p = self.jar('procedures', {**PROCEDURES, 'META-INF/DOZERDB-NOTICE.txt': b'notice'})
        with self.assertRaisesRegex(AssertionError, 'notice'):
            check_jars.check_procedures(p, set(p.namelist()), own=set(PROCEDURES) | {'META-INF/DOZERDB-NOTICE.txt'})


class PluginContentsTests(SyntheticJarTestCase):
    """The plugin is exactly core, metrics, procedures, query log and Browser."""

    MODULES = (CORE, METRICS, PROCEDURES, QUERY_LOG, BROWSER)

    def sources(self):
        return tuple(self.jar(name, entries) for name, entries in
                     (('core', CORE), ('metrics', METRICS), ('procedures', PROCEDURES), ('query-log', QUERY_LOG),
                      ('browser', BROWSER)))

    def check(self, plugin_entries):
        sources = self.sources()
        p = self.jar('plugin', plugin_entries)
        check_jars.check_plugin(sources, p, set(p.namelist()))

    def test_the_shade_of_the_five_modules_passes(self):
        self.check(shaded(*self.MODULES))

    def test_a_plugin_without_the_query_log_classes_fails(self):
        plugin = {name: data for name, data in shaded(*self.MODULES).items()
                  if not name.startswith('org/dozerdb/querylog/')}
        with self.assertRaisesRegex(AssertionError, 'shaded transitive classes'):
            self.check(plugin)

    def test_a_plugin_without_the_procedures_classes_fails(self):
        plugin = {name: data for name, data in shaded(*self.MODULES).items()
                  if not name.startswith('org/dozerdb/procedures/')}
        with self.assertRaisesRegex(AssertionError, 'shaded transitive classes'):
            self.check(plugin)

    def test_a_merged_service_file_without_the_query_log_factory_fails(self):
        plugin = shaded(*self.MODULES)
        plugin[EXTENSION_FACTORY] = METRICS[EXTENSION_FACTORY] + PROCEDURES[EXTENSION_FACTORY]
        with self.assertRaisesRegex(AssertionError, 'merge every provider'):
            self.check(plugin)

    def test_a_merged_service_file_without_the_procedures_factory_fails(self):
        plugin = shaded(*self.MODULES)
        plugin[EXTENSION_FACTORY] = METRICS[EXTENSION_FACTORY] + QUERY_LOG[EXTENSION_FACTORY]
        with self.assertRaisesRegex(AssertionError, 'merge every provider'):
            self.check(plugin)

    def test_the_plugin_keeps_cores_licence_not_the_query_logs(self):
        plugin = shaded(*self.MODULES)
        plugin['META-INF/LICENSE.txt'] = QUERY_LOG['META-INF/LICENSE.txt']
        with self.assertRaisesRegex(AssertionError, 'META-INF/LICENSE.txt'):
            self.check(plugin)


if __name__ == '__main__':
    unittest.main()
