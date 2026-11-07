"""Command assembly for scripts/docker-build.py."""
import contextlib
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))  # docker-build.py reads the line manifest through the module beside it
SCRIPT = SCRIPTS / 'docker-build.py'
spec = importlib.util.spec_from_file_location('docker_build', SCRIPT)
docker_build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docker_build)


MANIFEST = '''
line = "lts"
neo4j_tag = "5.26.32"
dozerdb_version = "1.0.0"
image = "graphstack/dozerdb:5.26.32-1.0.0"
docker_series = "5.26"
local_image = "dozerdb:lts-local"
gds_version = "2.13.14"

[checks]
jars = true
smoke-plugin = true
smoke-distribution = true
smoke-docker = true
metrics = false
query-log = true
docker-plugins = true
stack-metrics = false
cypher-flow = true
browser-flow = true
'''
PINS = {'tag': '5.26.32', 'dozerdbVersion': '1.0.0', 'distributionVersion': '5.26.32-1.0.0'}
CALENDAR = (MANIFEST.replace('line = "lts"', 'line = "calendar"').replace('5.26.32', '2026.10.1')
            .replace('docker_series = "5.26"', 'docker_series = "2026.10"').replace('dozerdb:lts-local', 'dozerdb:calendar-local'))
CALENDAR_PINS = {'tag': '2026.10.1', 'dozerdbVersion': '1.0.0', 'distributionVersion': '2026.10.1-1.0.0'}


def tags(command):
    return [command[i + 1] for i, word in enumerate(command) if word == '--tag']


class BuildCommandTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.manifest = pathlib.Path(directory.name) / 'release-line.toml'
        self.manifest.write_text(MANIFEST)

    def parse(self, *argv, config=PINS):
        return docker_build.parse_args(list(argv), self.manifest, config)

    def test_default_builds_a_local_image_tagged_with_the_version_and_the_line(self):
        command = docker_build.build_command(self.parse(), 'dozerdb-5.26.32-1.0.0-unix.tar.gz')
        self.assertEqual(command[:4], ['docker', 'buildx', 'build', '--load'])
        self.assertEqual(tags(command), ['dozerdb:5.26.32-1.0.0-local', 'dozerdb:lts-local'])
        self.assertIn('--build-arg', command)
        self.assertEqual(command[command.index('--build-arg') + 1], 'NEO4J_TARBALL=dozerdb-5.26.32-1.0.0-unix.tar.gz')
        self.assertNotIn('--push', command)
        self.assertNotIn('--platform', command)

    def test_a_manifest_that_disagrees_with_the_archive_version_builds_nothing(self):
        with self.assertRaises(SystemExit) as raised:
            self.parse(config={'tag': '5.26.32', 'dozerdbVersion': '1.0.1', 'distributionVersion': '5.26.32-1.0.1'})
        self.assertIn('dozerdb_version: 1.0.0, but distribution/config.json has dozerdbVersion 1.0.1',
                      str(raised.exception))
        self.assertIn('image: graphstack/dozerdb:5.26.32-1.0.0 is not tagged with the distribution version 5.26.32-1.0.1',
                      str(raised.exception))

    def test_a_broken_manifest_is_named(self):
        self.manifest.write_text(MANIFEST.replace('dozerdb:lts-local', 'dozerdb:local'))
        with self.assertRaises(SystemExit) as raised:
            self.parse()
        self.assertIn(f'{self.manifest}: local_image', str(raised.exception))

    def test_explicit_tags_replace_the_local_ones_without_the_manifest(self):
        self.manifest.unlink()
        self.assertEqual(tags(docker_build.build_command(self.parse('--tag', 'dozerdb:amd64'), 'x.tar.gz')),
                         ['dozerdb:amd64'])

    def test_platform_is_passed_through(self):
        args = self.parse('--platform', 'linux/amd64')
        command = docker_build.build_command(args, 'x.tar.gz')
        self.assertEqual(command[command.index('--platform') + 1], 'linux/amd64')

    def test_push_on_an_lts_manifest_derives_the_exact_image_and_the_series_only(self):
        args = self.parse('--push', '--platform', 'linux/amd64,linux/arm64')
        command = docker_build.build_command(args, 'x.tar.gz')
        self.assertIn('--push', command)
        self.assertNotIn('--load', command)
        self.assertEqual(tags(command), ['graphstack/dozerdb:5.26.32-1.0.0', 'graphstack/dozerdb:5.26'])

    def test_push_on_a_calendar_manifest_also_moves_latest(self):
        self.manifest.write_text(CALENDAR)
        command = docker_build.build_command(self.parse('--push', config=CALENDAR_PINS), 'x.tar.gz')
        self.assertEqual(tags(command), ['graphstack/dozerdb:2026.10.1-1.0.0', 'graphstack/dozerdb:2026.10',
                                         'graphstack/dozerdb:latest'])

    def test_push_rejects_operator_typed_tags_naming_them(self):
        stderr = io.StringIO()
        with self.assertRaises(SystemExit) as raised, contextlib.redirect_stderr(stderr):
            self.parse('--push', '--tag', 'graphstack/dozerdb:latest')
        self.assertEqual(raised.exception.code, 2)
        self.assertIn('--push takes its tags from the line manifest', stderr.getvalue())
        self.assertIn('remove --tag graphstack/dozerdb:latest', stderr.getvalue())

    def test_push_with_a_manifest_that_disagrees_with_the_archive_pushes_nothing(self):
        with self.assertRaises(SystemExit) as raised:
            self.parse('--push', config={'tag': '5.26.32', 'dozerdbVersion': '1.0.1',
                                         'distributionVersion': '5.26.32-1.0.1'})
        self.assertIn('is not tagged with the distribution version 5.26.32-1.0.1', str(raised.exception))

    def test_the_help_describes_the_derived_tags(self):
        stdout = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stdout(stdout):
            self.parse('--help')
        self.assertIn('<repository>:latest, only when the manifest\'s line is calendar', stdout.getvalue())
        self.assertIn('never moves latest', stdout.getvalue())

if __name__ == '__main__':
    unittest.main()
