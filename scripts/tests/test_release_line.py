"""The line manifest (release-line.toml) as scripts/release_line.py reads it."""
import json
import pathlib
import re
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import release_checks  # noqa: E402
import release_line  # noqa: E402

CALENDAR = '''
line = "calendar"
neo4j_tag = "2026.10.0"
dozerdb_version = "1.0.0"
image = "graphstack/dozerdb:2026.10.0-1.0.0"
docker_series = "2026.10"
local_image = "dozerdb:calendar-local"
gds_version = "2026.10.0"

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


PINS = {'tag': '2026.10.0', 'dozerdbVersion': '1.0.0', 'distributionVersion': '2026.10.0-1.0.0'}


def with_the_checkout_pins(text):
    """A manifest text whose versions are this checkout's pins (distribution/config.json), so it passes the pin
    check on every release line."""
    config = release_line.read_config()
    return (text.replace('2026.10.0-1.0.0', config['distributionVersion'])
            .replace('dozerdb_version = "1.0.0"', f'dozerdb_version = "{config["dozerdbVersion"]}"')
            .replace('neo4j_tag = "2026.10.0"', f'neo4j_tag = "{config["tag"]}"'))


class ManifestTests(unittest.TestCase):
    def test_the_line_versions_image_and_check_switches_are_read(self):
        manifest = release_line.parse_manifest(CALENDAR)
        self.assertEqual(manifest.line, 'calendar')
        self.assertEqual(manifest.neo4j_tag, '2026.10.0')
        self.assertEqual(manifest.dozerdb_version, '1.0.0')
        self.assertEqual(manifest.image, 'graphstack/dozerdb:2026.10.0-1.0.0')
        self.assertEqual(manifest.docker_series, '2026.10')
        self.assertEqual(manifest.gds_version, '2026.10.0')
        self.assertEqual(manifest.checks, {
            'jars': True, 'smoke-plugin': True, 'smoke-distribution': True, 'smoke-docker': True,
            'metrics': False, 'query-log': True, 'docker-plugins': True, 'stack-metrics': False, 'cypher-flow': True,
            'browser-flow': True})

    def test_the_distribution_version_is_the_tag_and_the_dozerdb_version_joined_by_a_hyphen(self):
        self.assertEqual(release_line.parse_manifest(CALENDAR).distribution_version, '2026.10.0-1.0.0')

    def test_the_local_build_is_tagged_for_the_line_and_for_the_exact_version(self):
        manifest = release_line.parse_manifest(CALENDAR)
        self.assertEqual(manifest.local_image, 'dozerdb:calendar-local')
        self.assertEqual(manifest.local_build_image, 'dozerdb:2026.10.0-1.0.0-local')

    def test_an_lts_line_has_its_own_local_tags_and_series(self):
        lts = (CALENDAR.replace('line = "calendar"', 'line = "lts"').replace('2026.10.0', '5.26.32')
               .replace('docker_series = "2026.10"', 'docker_series = "5.26"')
               .replace('dozerdb:calendar-local', 'dozerdb:lts-local'))
        manifest = release_line.parse_manifest(lts)
        self.assertEqual(manifest.local_image, 'dozerdb:lts-local')
        self.assertEqual(manifest.local_build_image, 'dozerdb:5.26.32-1.0.0-local')
        self.assertEqual(manifest.docker_series, '5.26')
        self.assertEqual(manifest.distribution_version, '5.26.32-1.0.0')

    def test_a_suffixed_upstream_tag_is_kept_verbatim(self):
        suffixed = CALENDAR.replace('2026.10.0', '2026.10.0.LTS')
        manifest = release_line.parse_manifest(suffixed)
        self.assertEqual(manifest.distribution_version, '2026.10.0.LTS-1.0.0')
        self.assertEqual(manifest.local_build_image, 'dozerdb:2026.10.0.LTS-1.0.0-local')


class ManifestErrorTests(unittest.TestCase):
    """A manifest the gate cannot trust fails the run with a message that names the field."""

    def assertRejected(self, text, *named):
        with self.assertRaises(release_line.ManifestError) as raised:
            release_line.parse_manifest(text)
        for name in named:
            self.assertIn(name, str(raised.exception))

    def test_a_missing_field_is_named(self):
        self.assertRejected(CALENDAR.replace('neo4j_tag = "2026.10.0"\n', ''), 'neo4j_tag', 'missing')

    def test_the_open_gds_version_the_image_must_install_is_required(self):
        self.assertRejected(CALENDAR.replace('gds_version = "2026.10.0"\n', ''), 'gds_version', 'missing')

    def test_a_field_of_the_wrong_type_is_named(self):
        self.assertRejected(CALENDAR.replace('dozerdb_version = "1.0.0"', 'dozerdb_version = 1'),
                            'dozerdb_version', 'string')

    def test_the_docker_series_the_line_moves_is_required(self):
        self.assertRejected(CALENDAR.replace('docker_series = "2026.10"\n', ''), 'docker_series', 'missing')
        self.assertRejected(CALENDAR.replace('docker_series = "2026.10"', 'docker_series = ""'),
                            'docker_series', 'non-empty string')

    def test_the_docker_series_is_a_neo4j_major_minor_series(self):
        for series in ('latest', '2026.10.1', 'abc', '2026', '5.26-local'):
            with self.subTest(series=series):
                self.assertRejected(CALENDAR.replace('docker_series = "2026.10"', f'docker_series = "{series}"'),
                                    'docker_series', 'major.minor', repr(series))
        for series in ('5.26', '2026.10'):
            with self.subTest(series=series):
                manifest = release_line.parse_manifest(CALENDAR.replace('docker_series = "2026.10"',
                                                                        f'docker_series = "{series}"'))
                self.assertEqual(manifest.docker_series, series)

    def test_the_line_is_calendar_or_lts(self):
        self.assertRejected(CALENDAR.replace('line = "calendar"', 'line = "nightly"'), 'line', 'calendar', 'lts')

    def test_the_dozerdb_version_is_major_minor_patch(self):
        self.assertRejected(CALENDAR.replace('dozerdb_version = "1.0.0"', 'dozerdb_version = "2026.10.0.0"'),
                            'dozerdb_version', 'MAJOR.MINOR.PATCH', "'2026.10.0.0'")
        self.assertRejected(CALENDAR.replace('dozerdb_version = "1.0.0"', 'dozerdb_version = "1.0.0-rc.1"'),
                            'dozerdb_version', 'pre-release')
        self.assertRejected(CALENDAR.replace('dozerdb_version = "1.0.0"', 'dozerdb_version = "1.0"'),
                            'dozerdb_version', 'three fields')

    def test_the_neo4j_tag_is_a_bare_upstream_tag(self):
        self.assertRejected(CALENDAR.replace('neo4j_tag = "2026.10.0"', 'neo4j_tag = "v2026.10.0"'),
                            'neo4j_tag', 'start with a digit', "'v2026.10.0'")
        self.assertRejected(CALENDAR.replace('neo4j_tag = "2026.10.0"', 'neo4j_tag = "2026.10.0-1.0.0"'),
                            'neo4j_tag', 'hyphen')

    def test_the_image_is_tagged_with_the_distribution_version(self):
        self.assertRejected(CALENDAR.replace('graphstack/dozerdb:2026.10.0-1.0.0', 'graphstack/dozerdb:latest'),
                            'image', 'graphstack/dozerdb:latest', '2026.10.0-1.0.0')
        self.assertRejected(CALENDAR.replace('graphstack/dozerdb:2026.10.0-1.0.0', 'graphstack/dozerdb:1.0.0'),
                            'image', 'graphstack/dozerdb:1.0.0', '2026.10.0-1.0.0')
        self.assertRejected(CALENDAR.replace('graphstack/dozerdb:2026.10.0-1.0.0', 'graphstack/dozerdb:2026.10.0.0'),
                            'image', 'graphstack/dozerdb:2026.10.0.0', '2026.10.0-1.0.0')

    def test_the_local_image_is_tagged_with_the_line(self):
        self.assertRejected(CALENDAR.replace('dozerdb:calendar-local', 'dozerdb:lts-local'),
                            'local_image', 'dozerdb:lts-local', 'calendar-local')

    def test_the_local_image_names_a_repository_and_a_tag(self):
        self.assertRejected(CALENDAR.replace('dozerdb:calendar-local', 'calendar-local'),
                            'local_image', 'calendar-local', '<repository>:calendar-local')

    def test_a_check_the_gate_runs_but_the_manifest_omits_is_named(self):
        self.assertRejected(CALENDAR.replace('\nmetrics = false\n', '\n'), 'checks.metrics', 'missing')

    def test_a_check_the_gate_does_not_know_is_named(self):
        self.assertRejected(CALENDAR + 'security = true\n', 'checks.security', 'unknown')

    def test_the_known_checks_are_those_the_gate_defines(self):
        self.assertEqual(tuple(release_line.parse_manifest(CALENDAR).checks), release_checks.NAMES)
        with_security = CALENDAR + 'security = false\n'
        manifest = release_line.parse_manifest(with_security, known_checks=(*release_checks.NAMES, 'security'))
        self.assertIs(manifest.checks['security'], False)

    def test_a_check_switch_is_a_boolean(self):
        self.assertRejected(CALENDAR.replace('jars = true', 'jars = "yes"'), 'checks.jars', 'true or false')

    def test_the_checks_table_is_required(self):
        self.assertRejected(CALENDAR.split('[checks]')[0], 'checks', 'missing')

    def test_malformed_toml_is_reported_with_its_position(self):
        self.assertRejected(CALENDAR.replace('line = "calendar"', 'line = calendar'), 'malformed', 'line 2')

    def test_a_loaded_manifest_knows_its_file_name(self):
        self.assertEqual(release_line.load_manifest(release_line.MANIFEST).source, 'release-line.toml')

    def test_an_error_in_a_file_names_the_file_and_the_field(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        variant = pathlib.Path(directory.name) / 'release-line.toml'
        variant.write_text(CALENDAR.replace('jars = true', 'jars = 1'))
        with self.assertRaises(release_line.ManifestError) as raised:
            release_line.load_manifest(variant)
        self.assertEqual(str(raised.exception), f'{variant}: checks.jars: must be true or false, found 1')

    def test_a_missing_file_is_named(self):
        missing = pathlib.Path(__file__).parent / 'no-such-release-line.toml'
        with self.assertRaises(release_line.ManifestError) as raised:
            release_line.load_manifest(missing)
        self.assertIn(str(missing), str(raised.exception))


class PinnedVersionTests(unittest.TestCase):
    """The pins of distribution/config.json name a release by two inputs, tag and dozerdbVersion; the distribution
    version is derived from them and the restated distributionVersion must equal it."""

    def test_the_distribution_version_is_derived_from_the_two_inputs(self):
        self.assertEqual(release_line.pinned_version(PINS), '2026.10.0-1.0.0')
        self.assertEqual(release_line.config_problems(PINS), [])

    def test_a_restated_distribution_version_that_differs_from_the_derivation_stops_the_script(self):
        with self.assertRaises(SystemExit) as raised:
            release_line.pinned_version({**PINS, 'distributionVersion': '2026.10.0.0'})
        self.assertEqual(str(raised.exception), 'distribution/config.json distributionVersion: 2026.10.0.0, but tag '
                                                 'and dozerdbVersion derive 2026.10.0-1.0.0')

    def test_a_missing_input_is_named(self):
        with self.assertRaises(SystemExit) as raised:
            release_line.pinned_version({'tag': '2026.10.0', 'distributionVersion': '2026.10.0-1.0.0'})
        self.assertEqual(str(raised.exception), 'distribution/config.json dozerdbVersion: missing')
        with self.assertRaises(SystemExit) as raised:
            release_line.pinned_version({'dozerdbVersion': '1.0.0'})
        self.assertEqual(str(raised.exception), 'distribution/config.json tag: missing')

    def test_an_input_that_breaks_the_grammar_is_named_with_the_rule(self):
        with self.assertRaises(SystemExit) as raised:
            release_line.pinned_version({**PINS, 'dozerdbVersion': '1.0'})
        self.assertEqual(str(raised.exception), "distribution/config.json tag '2026.10.0' and dozerdbVersion '1.0': "
                                                 "DozerDB version must be MAJOR.MINOR.PATCH (three fields), got 2: '1.0'")
        with self.assertRaises(SystemExit) as raised:
            release_line.pinned_version({**PINS, 'tag': 'v2026.10.0'})
        self.assertIn("upstream tag must start with a digit", str(raised.exception))

    def test_the_checkout_pins_derive_their_distribution_version(self):
        config = release_line.read_config()
        self.assertEqual(release_line.pinned_version(config), f'{config["tag"]}-{config["dozerdbVersion"]}')
        self.assertEqual(config['distributionVersion'], release_line.pinned_version(config))
        # The two inputs, the derived version and the upstream facts; no per-component version key. pomVersion and
        # its count are present together only on a tag whose POMs carry the previous release's version, as
        # scripts/distribution.py reads them; a line whose tag's POMs carry <tag>-SNAPSHOT has neither.
        optional = {'pomVersion', 'pomVersionOccurrences'}
        self.assertEqual(set(config) - optional, {'repository', 'tag', 'commit', 'neo4jVersion', 'dozerdbVersion',
                                                  'distributionVersion', 'patch', 'snapshotOccurrences',
                                                  'pluginTestArchive'})
        self.assertIn(optional & set(config), (set(), optional))


class PinTests(unittest.TestCase):
    """The manifest restates the line's pins in distribution/config.json; it never replaces them."""

    def test_versions_that_agree_with_the_pins_pass(self):
        manifest = release_line.parse_manifest(CALENDAR)
        self.assertEqual(release_line.pin_problems(manifest, PINS), [])

    def test_each_field_that_disagrees_with_its_pin_is_named(self):
        manifest = release_line.parse_manifest(CALENDAR)
        pins = {'tag': '2026.11.0', 'dozerdbVersion': '1.1.0', 'distributionVersion': '2026.11.0-1.1.0'}
        self.assertEqual(release_line.pin_problems(manifest, pins), [
            'neo4j_tag: 2026.10.0, but distribution/config.json has tag 2026.11.0',
            'dozerdb_version: 1.0.0, but distribution/config.json has dozerdbVersion 1.1.0',
            'image: graphstack/dozerdb:2026.10.0-1.0.0 is not tagged with the distribution version 2026.11.0-1.1.0 '
            'that distribution/config.json derives'])

    def test_pins_whose_restated_version_is_not_their_derivation_are_named(self):
        manifest = release_line.parse_manifest(CALENDAR)
        pins = {**PINS, 'distributionVersion': '2026.10.0.0'}
        self.assertEqual(release_line.pin_problems(manifest, pins), [
            'distribution/config.json distributionVersion: 2026.10.0.0, but tag and dozerdbVersion derive '
            '2026.10.0-1.0.0'])

    def test_pins_of_the_retired_shape_are_named(self):
        manifest = release_line.parse_manifest(CALENDAR)
        pins = {'tag': '2026.10.0', 'distributionVersion': '2026.10.0.0'}
        self.assertEqual(release_line.pin_problems(manifest, pins), [
            'dozerdb_version: 1.0.0, but distribution/config.json has dozerdbVersion None',
            'distribution/config.json dozerdbVersion: missing'])

    def test_the_repository_manifest_is_valid_and_agrees_with_the_pins(self):
        self.assertEqual(release_line.checked_manifest().source, 'release-line.toml')

    def test_the_pins_are_read_from_distribution_config(self):
        config = json.loads((release_line.ROOT / 'distribution/config.json').read_text())
        self.assertEqual(release_line.read_config(), config)


class CheckedManifestTests(unittest.TestCase):
    """Every script that relies on the manifest loads it the same way: valid, and in agreement with the pins."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = pathlib.Path(directory.name) / 'release-line.toml'
        self.path.write_text(CALENDAR)

    def test_a_manifest_that_agrees_with_the_pins_is_loaded(self):
        manifest = release_line.checked_manifest(self.path, PINS)
        self.assertEqual(manifest.local_image, 'dozerdb:calendar-local')

    def test_every_disagreement_is_one_problem_naming_the_file_and_the_field(self):
        with self.assertRaises(release_line.ManifestError) as raised:
            release_line.checked_manifest(self.path, {'tag': '2026.11.0', 'dozerdbVersion': '1.0.0',
                                                      'distributionVersion': '2026.11.0-1.0.0'})
        self.assertEqual(raised.exception.problems, (
            f'{self.path}: neo4j_tag: 2026.10.0, but distribution/config.json has tag 2026.11.0',
            f'{self.path}: image: graphstack/dozerdb:2026.10.0-1.0.0 is not tagged with the distribution version '
            '2026.11.0-1.0.0 that distribution/config.json derives'))

    def test_a_broken_manifest_is_one_problem(self):
        self.path.write_text(CALENDAR.replace('jars = true', 'jars = 1'))
        with self.assertRaises(release_line.ManifestError) as raised:
            release_line.checked_manifest(self.path, {})
        self.assertEqual(raised.exception.problems, (f'{self.path}: checks.jars: must be true or false, found 1',))

    def test_a_script_that_needs_the_manifest_stops_naming_the_problems(self):
        with self.assertRaises(SystemExit) as raised:
            release_line.required_manifest(self.path, {'tag': '2026.11.0', 'dozerdbVersion': '1.0.0',
                                                       'distributionVersion': '2026.11.0-1.0.0'})
        self.assertEqual(str(raised.exception), f'line manifest {self.path}: neo4j_tag: 2026.10.0, but '
                                                 'distribution/config.json has tag 2026.11.0; '
                                                 f'{self.path}: image: graphstack/dozerdb:2026.10.0-1.0.0 is not tagged '
                                                 'with the distribution version 2026.11.0-1.0.0 that '
                                                 'distribution/config.json derives')



class DefaultImageTests(unittest.TestCase):
    """The image a check script tests when none is named: the line's latest local build."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.manifest = pathlib.Path(directory.name) / 'release-line.toml'

    def test_a_named_image_is_tested_without_reading_the_manifest(self):
        self.assertEqual(release_line.default_image('graphstack/dozerdb:2026.10.0-1.0.0', self.manifest),
                         'graphstack/dozerdb:2026.10.0-1.0.0')

    def test_without_a_name_the_line_local_image(self):
        self.manifest.write_text(with_the_checkout_pins(CALENDAR))
        self.assertEqual(release_line.default_image(None, self.manifest), 'dozerdb:calendar-local')

    def test_a_broken_manifest_stops_the_script_naming_the_file_and_field(self):
        self.manifest.write_text(CALENDAR.replace('local_image = "dozerdb:calendar-local"\n', ''))
        with self.assertRaises(SystemExit) as raised:
            release_line.default_image(None, self.manifest)
        self.assertEqual(str(raised.exception), f'line manifest {self.manifest}: local_image: missing')

    def test_no_script_or_nx_target_falls_back_to_the_retired_shared_tag(self):
        retired = re.compile(r'dozerdb:local\b')
        files = [*release_line.ROOT.glob('scripts/*.*'), *release_line.ROOT.glob('*/project.json')]
        self.assertEqual([path.relative_to(release_line.ROOT).as_posix() for path in sorted(files)
                          if retired.search(path.read_text())], [])


if __name__ == '__main__':
    unittest.main()
