"""Command assembly for scripts/docker-build.py."""
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'docker-build.py'
spec = importlib.util.spec_from_file_location('docker_build', SCRIPT)
docker_build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docker_build)


class BuildCommandTests(unittest.TestCase):
    def test_default_builds_a_local_image(self):
        command = docker_build.build_command(docker_build.parse_args([]), 'dozerdb-1.2.3.0-unix.tar.gz')
        self.assertEqual(command[:4], ['docker', 'buildx', 'build', '--load'])
        self.assertIn('--tag', command)
        self.assertEqual(command[command.index('--tag') + 1], 'dozerdb:local')
        self.assertIn('--build-arg', command)
        self.assertEqual(command[command.index('--build-arg') + 1], 'NEO4J_TARBALL=dozerdb-1.2.3.0-unix.tar.gz')
        self.assertNotIn('--push', command)
        self.assertNotIn('--platform', command)

    def test_platform_is_passed_through(self):
        args = docker_build.parse_args(['--platform', 'linux/amd64'])
        command = docker_build.build_command(args, 'x.tar.gz')
        self.assertEqual(command[command.index('--platform') + 1], 'linux/amd64')

    def test_push_with_several_tags_replaces_load(self):
        args = docker_build.parse_args(['--push', '--platform', 'linux/amd64,linux/arm64',
                                        '--tag', 'graphstack/dozerdb:1.2.3.0', '--tag', 'graphstack/dozerdb:latest'])
        command = docker_build.build_command(args, 'x.tar.gz')
        self.assertIn('--push', command)
        self.assertNotIn('--load', command)
        tags = [command[i + 1] for i, word in enumerate(command) if word == '--tag']
        self.assertEqual(tags, ['graphstack/dozerdb:1.2.3.0', 'graphstack/dozerdb:latest'])

    def test_push_requires_an_explicit_tag(self):
        with self.assertRaises(SystemExit):
            docker_build.parse_args(['--push'])


if __name__ == '__main__':
    unittest.main()
