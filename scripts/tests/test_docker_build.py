"""Command assembly for scripts/docker-build.py."""
import importlib.util
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
dozerdb_version = "5.26.32.0"
image = "graphstack/dozerdb:5.26.32.0"
local_image = "dozerdb:lts-local"
gds_version = "2.13.14"

[checks]
jars = true
smoke-plugin = true
smoke-distribution = true
smoke-docker = true
metrics = false
docker-plugins = true
stack-metrics = false
cypher-flow = true
browser-flow = true
'''
PINS = {'tag': '5.26.32', 'distributionVersion': '5.26.32.0'}


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
        command = docker_build.build_command(self.parse(), 'dozerdb-1.2.3.0-unix.tar.gz')
        self.assertEqual(command[:4], ['docker', 'buildx', 'build', '--load'])
        self.assertEqual(tags(command), ['dozerdb:5.26.32.0-local', 'dozerdb:lts-local'])
        self.assertIn('--build-arg', command)
        self.assertEqual(command[command.index('--build-arg') + 1], 'NEO4J_TARBALL=dozerdb-1.2.3.0-unix.tar.gz')
        self.assertNotIn('--push', command)
        self.assertNotIn('--platform', command)

    def test_a_manifest_that_disagrees_with_the_archive_version_builds_nothing(self):
        with self.assertRaises(SystemExit) as raised:
            self.parse(config={'tag': '5.26.33', 'distributionVersion': '5.26.33.0'})
        self.assertIn('dozerdb_version: 5.26.32.0, but distribution/config.json has distributionVersion 5.26.33.0',
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

    def test_push_with_several_tags_replaces_load(self):
        args = self.parse('--push', '--platform', 'linux/amd64,linux/arm64',
                          '--tag', 'graphstack/dozerdb:1.2.3.0', '--tag', 'graphstack/dozerdb:latest')
        command = docker_build.build_command(args, 'x.tar.gz')
        self.assertIn('--push', command)
        self.assertNotIn('--load', command)
        self.assertEqual(tags(command), ['graphstack/dozerdb:1.2.3.0', 'graphstack/dozerdb:latest'])

    def test_push_requires_an_explicit_tag(self):
        with self.assertRaises(SystemExit):
            self.parse('--push')


if __name__ == '__main__':
    unittest.main()
