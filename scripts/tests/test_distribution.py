"""Archive additions, required contents and configuration parsing of scripts/distribution.py."""
import gzip
import importlib.util
import io
import pathlib
import tarfile
import tempfile
import time
import unittest
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'distribution.py'
spec = importlib.util.spec_from_file_location('distribution', SCRIPT)
distribution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(distribution)

ROOT = 'dozerdb-1.2.3.0'
MTIME = 1_700_000_000
ADDED = 1_800_000_000
# What the upstream assembly writes: Maven's user, 0644 files, 0755 directories and launchers.
UPSTREAM = [
    (f'{ROOT}/', 0o755, True, b''),
    (f'{ROOT}/lib/', 0o755, True, b''),
    (f'{ROOT}/LICENSE.txt', 0o644, False, b'GPL text'),
    (f'{ROOT}/bin/neo4j', 0o755, False, b'#!/bin/sh'),
    (f'{ROOT}/lib/dozerdb-core-1.2.3-1.1.0.jar', 0o644, False, b'core'),
    (f'{ROOT}/lib/neo4j-kernel-1.2.3.jar', 0o644, False, b'kernel'),
]
ADDITIONS = [
    distribution.Addition(f'{ROOT}/lib/dozerdb-core-1.2.3-1.1.0.jar', f'{ROOT}/lib/dozerdb-metrics-1.2.3-1.1.0.jar', b'metrics', ADDED),
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
            (f'{ROOT}/lib/dozerdb-metrics-1.2.3-1.1.0.jar', tarfile.REGTYPE, 0o644, 501, 20, 'builder', 'staff', ADDED, 7, b'metrics'),
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
            (f'{ROOT}/lib/dozerdb-metrics-1.2.3-1.1.0.jar', 0o100644 << 16, ZIP_STORED, 3, time.localtime(ADDED)[:6], b'metrics'),
            (f'{ROOT}/DOZERDB-NOTICE.txt', 0o100644 << 16, ZIP_DEFLATED, 3, time.localtime(ADDED)[:6], b'DozerDB notice'),
        ])

    def test_missing_template_entry_fails_the_build(self):
        with self.assertRaises(SystemExit):
            distribution.add_to_archive(self.source, self.dest,
                                        [distribution.Addition(f'{ROOT}/absent.txt', f'{ROOT}/x.txt', b'x', ADDED)])


class ContentsTests(unittest.TestCase):
    VERSIONS = {'coreVersion': '1.2.3-1.1.0', 'browserVersion': '1.2.3.0', 'metricsVersion': '1.2.3-1.1.0'}

    def test_unix_archive_needs_the_launchers_dozerdb_jars_and_notice(self):
        required = distribution.required_entries(ROOT, self.VERSIONS, windows=False)
        for entry in ('bin/neo4j', 'bin/neo4j-admin', 'lib/dozerdb-core-1.2.3-1.1.0.jar', 'lib/dozerdb-browser-1.2.3.0.jar',
                      'lib/dozerdb-metrics-1.2.3-1.1.0.jar', 'lib/simpleclient-0.16.0.jar', 'DOZERDB-NOTICE.txt', 'web/'):
            self.assertIn(f'{ROOT}/{entry}', required)
        self.assertNotIn(f'{ROOT}/bin/neo4j.ps1', required)

    def test_windows_archive_needs_the_powershell_and_batch_launchers(self):
        required = distribution.required_entries(ROOT, self.VERSIONS, windows=True)
        for entry in ('bin/neo4j.ps1', 'bin/neo4j.bat', 'bin/neo4j-admin.ps1', 'lib/dozerdb-metrics-1.2.3-1.1.0.jar', 'DOZERDB-NOTICE.txt'):
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
