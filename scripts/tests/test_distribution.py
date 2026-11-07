"""Archive additions, required contents, the version pin check, the patch literal check and configuration parsing
of scripts/distribution.py."""
import gzip
import importlib.util
import io
import json
import pathlib
import sys
import tarfile
import tempfile
import time
import unittest
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))  # distribution.py reads the pins through the release-line module beside it
SCRIPT = SCRIPTS / 'distribution.py'
spec = importlib.util.spec_from_file_location('distribution', SCRIPT)
distribution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(distribution)

# The distribution version of the fixtures: upstream tag 1.2.3, DozerDB version 1.0.0.
VERSION = '1.2.3-1.0.0'
ROOT = f'dozerdb-{VERSION}'
MTIME = 1_700_000_000
ADDED = 1_800_000_000
# What the upstream assembly writes: Maven's user, 0644 files, 0755 directories and launchers.
UPSTREAM = [
    (f'{ROOT}/', 0o755, True, b''),
    (f'{ROOT}/lib/', 0o755, True, b''),
    (f'{ROOT}/LICENSE.txt', 0o644, False, b'GPL text'),
    (f'{ROOT}/bin/neo4j', 0o755, False, b'#!/bin/sh'),
    (f'{ROOT}/lib/dozerdb-core-1.2.3-1.0.0.jar', 0o644, False, b'core'),
    (f'{ROOT}/lib/neo4j-kernel-1.2.3.jar', 0o644, False, b'kernel'),
]
ADDITIONS = [
    distribution.Addition(f'{ROOT}/lib/dozerdb-core-1.2.3-1.0.0.jar', f'{ROOT}/lib/dozerdb-metrics-1.2.3-1.0.0.jar', b'metrics', ADDED),
    distribution.Addition(f'{ROOT}/lib/dozerdb-core-1.2.3-1.0.0.jar', f'{ROOT}/lib/dozerdb-procedures-1.2.3-1.0.0.jar', b'procedures', ADDED),
    distribution.Addition(f'{ROOT}/lib/dozerdb-core-1.2.3-1.0.0.jar', f'{ROOT}/lib/dozerdb-query-log-1.2.3-1.0.0.jar', b'query log', ADDED),
    distribution.Addition(f'{ROOT}/LICENSE.txt', f'{ROOT}/DOZERDB-NOTICE.txt', b'DozerDB notice', ADDED),
]


def upstream_tar(path):
    with open(path, 'wb') as out, gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=0) as gz, \
            tarfile.open(fileobj=gz, mode='w', format=tarfile.GNU_FORMAT) as tar:
        for name, mode, is_dir, data in UPSTREAM:
            info = tarfile.TarInfo(name.rstrip('/'))
            info.type = tarfile.DIRTYPE if is_dir else tarfile.REGTYPE
            info.mode, info.mtime, info.size = mode, MTIME, len(data)
            info.uid, info.gid, info.uname, info.gname = 501, 20, 'builder', 'staff'
            tar.addfile(info, io.BytesIO(data) if not is_dir else None)


def upstream_zip(path):
    with ZipFile(path, 'w') as archive:
        for name, mode, is_dir, data in UPSTREAM:
            info = ZipInfo(name, date_time=time.localtime(MTIME)[:6])
            info.create_system = 3
            info.external_attr = ((0o40000 | mode) << 16 | 0x10) if is_dir else (0o100000 | mode) << 16
            info.compress_type = ZIP_STORED if name.endswith(('.jar', '/')) else ZIP_DEFLATED
            archive.writestr(info, data)


class TarAdditionTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.source = pathlib.Path(self.dir.name, 'upstream-unix.tar.gz')
        self.dest = pathlib.Path(self.dir.name, 'bundled-unix.tar.gz')
        upstream_tar(self.source)

    def tearDown(self):
        self.dir.cleanup()

    def members(self, path):
        with tarfile.open(path, 'r:gz') as tar:
            return [(m.name, m.type, m.mode, m.uid, m.gid, m.uname, m.gname, m.mtime, m.size,
                     tar.extractfile(m).read() if m.isfile() else None) for m in tar.getmembers()]

    def test_upstream_entries_are_copied_unchanged_and_in_order(self):
        distribution.add_to_archive(self.source, self.dest, ADDITIONS)
        before, after = self.members(self.source), self.members(self.dest)
        self.assertEqual(after[:len(before)], before)

    def test_additions_follow_with_their_templates_owner_and_mode(self):
        distribution.add_to_archive(self.source, self.dest, ADDITIONS)
        added = self.members(self.dest)[len(UPSTREAM):]
        self.assertEqual(added, [
            (f'{ROOT}/lib/dozerdb-metrics-1.2.3-1.0.0.jar', tarfile.REGTYPE, 0o644, 501, 20, 'builder', 'staff', ADDED, 7, b'metrics'),
            (f'{ROOT}/lib/dozerdb-procedures-1.2.3-1.0.0.jar', tarfile.REGTYPE, 0o644, 501, 20, 'builder', 'staff', ADDED, 10, b'procedures'),
            (f'{ROOT}/lib/dozerdb-query-log-1.2.3-1.0.0.jar', tarfile.REGTYPE, 0o644, 501, 20, 'builder', 'staff', ADDED, 9, b'query log'),
            (f'{ROOT}/DOZERDB-NOTICE.txt', tarfile.REGTYPE, 0o644, 501, 20, 'builder', 'staff', ADDED, 14, b'DozerDB notice'),
        ])

    def test_gzip_header_carries_no_name_or_time_like_upstream(self):
        distribution.add_to_archive(self.source, self.dest, ADDITIONS)
        self.assertEqual(self.dest.read_bytes()[:10], b'\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\xff')

    def test_missing_template_entry_fails_the_build(self):
        with self.assertRaises(SystemExit):
            distribution.add_to_archive(self.source, self.dest,
                                        [distribution.Addition(f'{ROOT}/lib/absent.jar', f'{ROOT}/lib/x.jar', b'x', ADDED)])


class ZipAdditionTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.source = pathlib.Path(self.dir.name, 'upstream-windows.zip')
        self.dest = pathlib.Path(self.dir.name, 'bundled-windows.zip')
        upstream_zip(self.source)

    def tearDown(self):
        self.dir.cleanup()

    def entries(self, path):
        with ZipFile(path) as archive:
            return [(i.filename, i.external_attr, i.compress_type, i.create_system, i.date_time, archive.read(i))
                    for i in archive.infolist()]

    def test_upstream_entries_are_copied_unchanged_and_in_order(self):
        distribution.add_to_archive(self.source, self.dest, ADDITIONS)
        before, after = self.entries(self.source), self.entries(self.dest)
        self.assertEqual(after[:len(before)], before)

    def test_additions_follow_with_their_templates_attributes(self):
        distribution.add_to_archive(self.source, self.dest, ADDITIONS)
        added = self.entries(self.dest)[len(UPSTREAM):]
        self.assertEqual(added, [
            (f'{ROOT}/lib/dozerdb-metrics-1.2.3-1.0.0.jar', 0o100644 << 16, ZIP_STORED, 3, time.localtime(ADDED)[:6], b'metrics'),
            (f'{ROOT}/lib/dozerdb-procedures-1.2.3-1.0.0.jar', 0o100644 << 16, ZIP_STORED, 3, time.localtime(ADDED)[:6], b'procedures'),
            (f'{ROOT}/lib/dozerdb-query-log-1.2.3-1.0.0.jar', 0o100644 << 16, ZIP_STORED, 3, time.localtime(ADDED)[:6], b'query log'),
            (f'{ROOT}/DOZERDB-NOTICE.txt', 0o100644 << 16, ZIP_DEFLATED, 3, time.localtime(ADDED)[:6], b'DozerDB notice'),
        ])

    def test_missing_template_entry_fails_the_build(self):
        with self.assertRaises(SystemExit):
            distribution.add_to_archive(self.source, self.dest,
                                        [distribution.Addition(f'{ROOT}/absent.txt', f'{ROOT}/x.txt', b'x', ADDED)])


class AdditionsTests(unittest.TestCase):
    """The DozerDB entries appended to every archive: the metrics, procedures and query-log JARs beside core, the
    notice beside Neo4j's licence, the notice read from the metrics JAR."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.metrics = pathlib.Path(self.dir.name, 'dozerdb-metrics-1.2.3-1.0.0.jar')
        self.procedures = pathlib.Path(self.dir.name, 'dozerdb-procedures-1.2.3-1.0.0.jar')
        self.query_log = pathlib.Path(self.dir.name, 'dozerdb-query-log-1.2.3-1.0.0.jar')
        self.jar(self.metrics, {'META-INF/DOZERDB-NOTICE.txt': b'DozerDB notice',
                                'org/dozerdb/metrics/shaded/com/codahale/metrics/Gauge.class': b'g'})
        self.jar(self.procedures, {'org/dozerdb/procedures/adapter/SetConfigValueProcedure.class': b'p'})
        self.jar(self.query_log, {'org/dozerdb/querylog/extension/QueryLogExtension.class': b'q'})

    def tearDown(self):
        self.dir.cleanup()

    def jar(self, path, entries):
        with ZipFile(path, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def test_the_three_jars_follow_core_and_the_notice_follows_the_licence(self):
        additions = distribution.dozerdb_additions(ROOT, self.metrics, self.procedures, self.query_log, VERSION)
        core = f'{ROOT}/lib/dozerdb-core-{VERSION}.jar'
        self.assertEqual([(a.template, a.name, a.data) for a in additions], [
            (core, f'{ROOT}/lib/dozerdb-metrics-1.2.3-1.0.0.jar', self.metrics.read_bytes()),
            (core, f'{ROOT}/lib/dozerdb-procedures-1.2.3-1.0.0.jar', self.procedures.read_bytes()),
            (core, f'{ROOT}/lib/dozerdb-query-log-1.2.3-1.0.0.jar', self.query_log.read_bytes()),
            (f'{ROOT}/LICENSE.txt', f'{ROOT}/DOZERDB-NOTICE.txt', b'DozerDB notice'),
        ])

    def test_an_unshaded_metrics_jar_fails_the_build(self):
        self.jar(self.metrics, {'META-INF/DOZERDB-NOTICE.txt': b'n', 'com/codahale/metrics/Gauge.class': b'g'})
        with self.assertRaisesRegex(SystemExit, 'not shaded'):
            distribution.dozerdb_additions(ROOT, self.metrics, self.procedures, self.query_log, VERSION)


POM_NS = 'xmlns="http://maven.apache.org/POM/4.0.0"'


def pom(version, parent=None, properties=()):
    """A DozerDB POM restating the version as its own, optionally as its parent's, and in dozerdb-*.version
    properties."""
    parent_xml = f'<parent><groupId>org.dozerdb</groupId><artifactId>dozerdb-core-parent</artifactId><version>{parent}</version></parent>' if parent else ''
    props = ''.join(f'<{name}>{value}</{name}>' for name, value in properties)
    return (f'<project {POM_NS}>{parent_xml}<groupId>org.dozerdb</groupId><artifactId>x</artifactId>'
            f'<version>{version}</version><properties><java.version>21</java.version>{props}</properties></project>')


def write_tree(root, version=VERSION, **overrides):
    """A checkout whose seven POMs, cross-module properties, Browser package and Dockerfile default archive restate
    one version, except the overrides (a file path, a property or package field, or 'Dockerfile ARG' for the whole
    ARG line's value, '' for an ARG without a default)."""
    value = lambda key: overrides.get(key, version)
    files = {
        'pom.xml': pom(value('pom.xml'), properties=[('dozerdb-core.version', value('root dozerdb-core.version'))]),
        'core/pom.xml': pom(value('core/pom.xml'), parent=value('core parent')),
        'metrics/pom.xml': pom(value('metrics/pom.xml'), parent=value('metrics parent')),
        'procedures/pom.xml': pom(value('procedures/pom.xml'), parent=value('procedures parent')),
        'query-log/pom.xml': pom(value('query-log/pom.xml'), parent=value('query-log parent')),
        'plugin/pom.xml': pom(value('plugin/pom.xml'), properties=[
            (name, value(f'plugin {name}')) for name in ('dozerdb-browser.version', 'dozerdb-core.version',
                                                         'dozerdb-metrics.version', 'dozerdb-procedures.version',
                                                         'dozerdb-query-log.version')]),
        'browser/pom.xml': pom(value('browser/pom.xml')),
        'browser/package.json': json.dumps({'name': 'dozerdb-browser', 'version': value('browser/package.json')}),
        'docker/Dockerfile': ('FROM debian:trixie-slim\n'
                              + 'ARG NEO4J_TARBALL=' + overrides.get('Dockerfile ARG', f"dozerdb-{value('docker/Dockerfile')}-unix.tar.gz")
                              + '\nENV NEO4J_TARBALL=${NEO4J_TARBALL}\nCOPY ${NEO4J_TARBALL} /\n'),
    }
    for path, text in files.items():
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_text(text)


class PinTests(unittest.TestCase):
    """The distribution version the pins derive is restated by hand in every DozerDB POM, the cross-module version
    properties and the Browser package; the build fails naming the file that disagrees and the value expected."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = pathlib.Path(directory.name)

    def test_every_dozerdb_jar_is_named_by_the_distribution_version(self):
        self.assertEqual(distribution.component_jar('query-log', VERSION),
                         distribution.ROOT / 'query-log/target/dozerdb-query-log-1.2.3-1.0.0.jar')
        self.assertEqual(distribution.component_jar('metrics', VERSION),
                         distribution.ROOT / 'metrics/target/dozerdb-metrics-1.2.3-1.0.0.jar')

    def test_the_checkout_names_every_jar_by_its_own_pins(self):
        self.assertEqual(distribution.VERSION, f'{distribution.CONFIG["tag"]}-{distribution.CONFIG["dozerdbVersion"]}')
        self.assertEqual(distribution.component_jar('core').name, f'dozerdb-core-{distribution.VERSION}.jar')

    def test_agreeing_restatements_pass(self):
        write_tree(self.root)
        distribution.check_pins(VERSION, distribution.restated_versions(self.root))

    def assertDriftFails(self, expected_message, **overrides):
        write_tree(self.root, **overrides)
        with self.assertRaises(SystemExit) as raised:
            distribution.check_pins(VERSION, distribution.restated_versions(self.root))
        self.assertEqual(str(raised.exception), expected_message)

    def test_a_pom_version_that_drifted_names_its_file_and_the_expected_value(self):
        for path in ('pom.xml', 'core/pom.xml', 'metrics/pom.xml', 'procedures/pom.xml', 'query-log/pom.xml',
                     'plugin/pom.xml', 'browser/pom.xml'):
            with self.subTest(path):
                self.assertDriftFails(f'{path} version is 1.2.3-1.1.0, expected the distribution version 1.2.3-1.0.0',
                                      **{path: '1.2.3-1.1.0'})

    def test_a_module_parent_version_that_drifted_names_its_file(self):
        for module in ('core', 'metrics', 'procedures', 'query-log'):
            with self.subTest(module):
                self.assertDriftFails(f'{module}/pom.xml parent version is 1.2.3-0.9.0, expected the distribution '
                                      'version 1.2.3-1.0.0', **{f'{module} parent': '1.2.3-0.9.0'})

    def test_a_cross_module_property_that_drifted_names_its_file_and_property(self):
        self.assertDriftFails('pom.xml property dozerdb-core.version is 1.2.3-1.1.0, expected the distribution '
                              'version 1.2.3-1.0.0', **{'root dozerdb-core.version': '1.2.3-1.1.0'})
        for name in ('dozerdb-browser', 'dozerdb-core', 'dozerdb-metrics', 'dozerdb-procedures', 'dozerdb-query-log'):
            with self.subTest(name):
                self.assertDriftFails(f'plugin/pom.xml property {name}.version is 1.2.3-1.1.0, expected the '
                                      'distribution version 1.2.3-1.0.0', **{f'plugin {name}.version': '1.2.3-1.1.0'})

    def test_a_browser_package_version_that_drifted_names_the_package(self):
        self.assertDriftFails('browser/package.json version is 1.2.3.0, expected the distribution version '
                              '1.2.3-1.0.0', **{'browser/package.json': '1.2.3.0'})

    def test_a_dockerfile_default_archive_that_drifted_names_the_file(self):
        self.assertDriftFails('docker/Dockerfile default archive (ARG NEO4J_TARBALL) version is 1.2.3-0.9.0, expected '
                              'the distribution version 1.2.3-1.0.0', **{'docker/Dockerfile': '1.2.3-0.9.0'})

    def test_a_dockerfile_default_archive_of_the_retired_shape_names_its_version(self):
        self.assertDriftFails('docker/Dockerfile default archive (ARG NEO4J_TARBALL) version is 1.2.3.0, expected the '
                              'distribution version 1.2.3-1.0.0', **{'Dockerfile ARG': 'dozerdb-1.2.3.0-unix.tar.gz'})

    def test_a_dockerfile_default_archive_of_another_shape_is_named_as_it_stands(self):
        self.assertDriftFails('docker/Dockerfile default archive (ARG NEO4J_TARBALL) version is neo4j-1.2.3-unix.tar.gz, '
                              'expected the distribution version 1.2.3-1.0.0', **{'Dockerfile ARG': 'neo4j-1.2.3-unix.tar.gz'})
        self.assertDriftFails('docker/Dockerfile default archive (ARG NEO4J_TARBALL) version is None, expected the '
                              'distribution version 1.2.3-1.0.0', **{'Dockerfile ARG': ''})

    def test_every_drift_is_named_at_once(self):
        self.assertDriftFails('core/pom.xml version is 1.2.3-1.1.0, expected the distribution version 1.2.3-1.0.0\n'
                              'plugin/pom.xml property dozerdb-browser.version is 1.2.3.0, expected the distribution '
                              'version 1.2.3-1.0.0',
                              **{'core/pom.xml': '1.2.3-1.1.0', 'plugin dozerdb-browser.version': '1.2.3.0'})

    def test_a_pinned_distribution_version_that_differs_from_its_derivation_fails_the_build(self):
        with self.assertRaises(SystemExit) as raised:
            distribution.release_line.pinned_version({'tag': '1.2.3', 'dozerdbVersion': '1.0.0',
                                                      'distributionVersion': '1.2.3.0'})
        self.assertEqual(str(raised.exception), 'distribution/config.json distributionVersion: 1.2.3.0, but tag and '
                                                 'dozerdbVersion derive 1.2.3-1.0.0')

    def test_the_checkout_restates_its_distribution_version_everywhere(self):
        distribution.check_pins(distribution.VERSION, distribution.restated_versions())


PATCH = '''--- a/packaging/standalone/standalone-community/pom.xml
+++ b/packaging/standalone/standalone-community/pom.xml
@@ -53,8 +53,24 @@
       <groupId>io.netty</groupId>
       <artifactId>netty-tcnative</artifactId>
       <classifier>osx-aarch_64</classifier>
+    </dependency>
+    <dependency>
+      <groupId>io.prometheus</groupId>
+      <artifactId>simpleclient</artifactId>
+      <version>0.16.0</version>
+    </dependency>
+    <dependency>
+      <groupId>org.dozerdb</groupId>
+      <artifactId>dozerdb-core</artifactId>
+      <version>1.2.3-1.0.0</version>
     </dependency>
     <dependency>
+      <groupId>org.dozerdb.client</groupId>
+      <artifactId>dozerdb-browser</artifactId>
+      <version>1.2.3-1.0.0</version>
+    </dependency>
+    <dependency>
       <groupId>org.neo4j</groupId>
       <artifactId>neo4j-bootcheck</artifactId>
       <version>${project.version}</version>
@@ -193,7 +209,7 @@
         <artifactId>maven-assembly-plugin</artifactId>
         <configuration>
           <attach>false</attach>
-          <finalName>neo4j-community-${project.version}</finalName>
+          <finalName>dozerdb-1.2.3-1.0.0</finalName>
           <appendAssemblyId>true</appendAssemblyId>
--- a/packaging/standalone/standalone-community/src/main/appassembler/unix-template
+++ b/packaging/standalone/standalone-community/src/main/appassembler/unix-template
@@ -99,6 +99,9 @@
   [ -n "$REPO" ] && REPO=`cygpath --path --windows "$REPO"`
 fi
 
+# Bootstrap DozerDB before the upstream server implementation.
+CLASSPATH="$BASEDIR/lib/dozerdb-core-1.2.3-1.0.0.jar:$CLASSPATH"
+
 exec "$JAVACMD" @EXTRA_JVM_ARGUMENTS@ \\
'''


class PatchTests(unittest.TestCase):
    """The distribution patch keeps hand-edited literals (the assembly finalName, the core and Browser dependency
    versions, the launcher classpath); each is checked against the distribution version before the patch is applied,
    and a disagreement names the patch line and the expected value."""

    NAME = 'distribution/patches/1.2.3.patch'

    def problems(self, text, version=VERSION):
        return distribution.patch_problems(text, version, self.NAME)

    def test_agreeing_literals_pass(self):
        self.assertEqual(self.problems(PATCH), [])

    def test_a_final_name_of_the_retired_shape_names_the_patch_line(self):
        text = PATCH.replace('<finalName>dozerdb-1.2.3-1.0.0</finalName>', '<finalName>dozerdb-${project.version}.0</finalName>')
        self.assertEqual(self.problems(text), [f'{self.NAME} line 32: assembly finalName is '
                                               'dozerdb-${project.version}.0, expected dozerdb-1.2.3-1.0.0'])

    def test_a_core_dependency_version_that_drifted_names_the_patch_line(self):
        text = PATCH.replace('<artifactId>dozerdb-core</artifactId>\n+      <version>1.2.3-1.0.0</version>',
                             '<artifactId>dozerdb-core</artifactId>\n+      <version>1.2.3-0.9.0</version>')
        self.assertEqual(self.problems(text), [f'{self.NAME} line 16: dozerdb-core dependency version is 1.2.3-0.9.0, '
                                               'expected 1.2.3-1.0.0'])

    def test_a_browser_dependency_version_that_drifted_names_the_patch_line(self):
        text = PATCH.replace('<artifactId>dozerdb-browser</artifactId>\n+      <version>1.2.3-1.0.0</version>',
                             '<artifactId>dozerdb-browser</artifactId>\n+      <version>1.2.3.0</version>')
        self.assertEqual(self.problems(text), [f'{self.NAME} line 21: dozerdb-browser dependency version is 1.2.3.0, '
                                               'expected 1.2.3-1.0.0'])

    def test_a_launcher_classpath_that_drifted_names_the_patch_line(self):
        text = PATCH.replace('dozerdb-core-1.2.3-1.0.0.jar:$CLASSPATH', 'dozerdb-core-1.2.3-0.9.0.jar:$CLASSPATH')
        self.assertEqual(self.problems(text), [f'{self.NAME} line 41: launcher classpath is '
                                               'CLASSPATH="$BASEDIR/lib/dozerdb-core-1.2.3-0.9.0.jar:$CLASSPATH", '
                                               'expected CLASSPATH="$BASEDIR/lib/dozerdb-core-1.2.3-1.0.0.jar:$CLASSPATH"'])

    def test_a_literal_the_patch_does_not_add_is_named(self):
        text = PATCH.replace('+CLASSPATH="$BASEDIR/lib/dozerdb-core-1.2.3-1.0.0.jar:$CLASSPATH"\n', '')
        self.assertEqual(self.problems(text), [f'{self.NAME} adds no launcher classpath line; expected '
                                               'CLASSPATH="$BASEDIR/lib/dozerdb-core-1.2.3-1.0.0.jar:$CLASSPATH"'])

    def test_every_disagreement_is_named_when_the_version_moves(self):
        self.assertEqual([p.split(':')[0] for p in self.problems(PATCH, '1.2.3-1.1.0')],
                         [f'{self.NAME} line 32', f'{self.NAME} line 16', f'{self.NAME} line 21', f'{self.NAME} line 41'])

    def test_the_build_stops_on_a_patch_that_disagrees(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        patch = distribution.ROOT / 'distribution/patches/test-1.2.3.patch'
        patch.write_text(PATCH)
        self.addCleanup(patch.unlink)
        distribution.check_patch(patch, VERSION)
        with self.assertRaises(SystemExit) as raised:
            distribution.check_patch(patch, '1.2.3-1.1.0')
        self.assertTrue(str(raised.exception).startswith('distribution/patches/test-1.2.3.patch line 32: assembly '
                                                         'finalName is dozerdb-1.2.3-1.0.0, expected dozerdb-1.2.3-1.1.0'))

    def test_the_checkout_patch_agrees_with_its_pins(self):
        patch = distribution.ROOT / 'distribution' / distribution.CONFIG['patch']
        self.assertEqual(distribution.patch_problems(patch.read_text(), distribution.VERSION, patch.name), [])


class ContentsTests(unittest.TestCase):
    def test_unix_archive_needs_the_launchers_dozerdb_jars_and_notice(self):
        required = distribution.required_entries(ROOT, VERSION, windows=False)
        for entry in ('bin/neo4j', 'bin/neo4j-admin', 'lib/dozerdb-core-1.2.3-1.0.0.jar', 'lib/dozerdb-browser-1.2.3-1.0.0.jar',
                      'lib/dozerdb-metrics-1.2.3-1.0.0.jar', 'lib/dozerdb-procedures-1.2.3-1.0.0.jar',
                      'lib/dozerdb-query-log-1.2.3-1.0.0.jar', 'lib/simpleclient-0.16.0.jar', 'DOZERDB-NOTICE.txt', 'web/'):
            self.assertIn(f'{ROOT}/{entry}', required)
        self.assertNotIn(f'{ROOT}/bin/neo4j.ps1', required)

    def test_windows_archive_needs_the_powershell_and_batch_launchers(self):
        required = distribution.required_entries(ROOT, VERSION, windows=True)
        for entry in ('bin/neo4j.ps1', 'bin/neo4j.bat', 'bin/neo4j-admin.ps1', 'lib/dozerdb-metrics-1.2.3-1.0.0.jar',
                      'lib/dozerdb-procedures-1.2.3-1.0.0.jar', 'lib/dozerdb-query-log-1.2.3-1.0.0.jar', 'DOZERDB-NOTICE.txt'):
            self.assertIn(f'{ROOT}/{entry}', required)
        self.assertNotIn(f'{ROOT}/bin/neo4j', required)

    def test_configuration_defaults_skip_comments(self):
        conf = '# comment\n#dbms.usage_report.enabled=true\ndbms.usage_report.enabled=false\n\ndbms.max_databases=100\n'
        self.assertEqual(distribution.configuration_defaults(conf),
                         {'dbms.usage_report.enabled': 'false', 'dbms.max_databases': '100'})



class PomVersionTests(unittest.TestCase):
    """The exported tag's POM coordinates are rewritten to the Neo4j version before the upstream build."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.source = pathlib.Path(self.dir.name)
        (self.source / 'module').mkdir()

    def tearDown(self):
        self.dir.cleanup()

    def pom(self, path, version):
        file = self.source / path / 'pom.xml'
        file.write_text(f'<project><parent><version>{version}</version></parent><version>{version}</version></project>')
        return file

    def test_snapshot_coordinates_become_the_tag_by_default(self):
        pom = self.pom('module', '1.2.3-SNAPSHOT')
        distribution.rewrite_pom_versions(self.source, {'neo4jVersion': '1.2.3', 'snapshotOccurrences': 2})
        self.assertNotIn('SNAPSHOT', pom.read_text())
        self.assertEqual(pom.read_text().count('<version>1.2.3</version>'), 2)

    def test_a_tag_cut_with_the_previous_version_is_rewritten_to_the_tag(self):
        root, module = self.pom('.', '1.2.2'), self.pom('module', '1.2.2')
        distribution.rewrite_pom_versions(self.source, {'neo4jVersion': '1.2.3', 'pomVersion': '1.2.2',
                                                        'pomVersionOccurrences': 4, 'snapshotOccurrences': 0})
        for pom in (root, module):
            self.assertEqual(pom.read_text().count('<version>1.2.3</version>'), 2)

    def test_a_different_count_fails_the_build(self):
        self.pom('module', '1.2.2')
        with self.assertRaisesRegex(SystemExit, 'Expected 3 POM coordinates 1.2.2, found 2'):
            distribution.rewrite_pom_versions(self.source, {'neo4jVersion': '1.2.3', 'pomVersion': '1.2.2',
                                                            'pomVersionOccurrences': 3})


if __name__ == '__main__':
    unittest.main()
