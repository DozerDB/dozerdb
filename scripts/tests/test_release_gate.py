"""Result lines, report, readiness waits, Compose commands and run orchestration of scripts/release-gate.py."""
import contextlib
import importlib.util
import io
import os
import pathlib
import re
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))  # release-gate.py imports the smoke helper beside it
spec = importlib.util.spec_from_file_location('release_gate', SCRIPTS / 'release-gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
import release_checks  # noqa: E402  (found through the path above)
import release_line  # noqa: E402

class OutcomeTests(unittest.TestCase):
    """A check passes when its exit status is 0 and, for a script that prints one, its last RESULT line agrees."""

    def test_exit_zero_and_result_pass_is_a_pass(self):
        output = 'ok       one\nok       two\nRESULT PASS\n'
        self.assertEqual(gate.outcome(output, 0, result_line=True), gate.Outcome('PASS'))

    def test_the_failing_result_line_is_the_detail(self):
        output = 'MISMATCH expect_fail=True  got_error=False CREATE (:Person)\nRESULT FAIL (1 mismatches)\n'
        self.assertEqual(gate.outcome(output, 1, result_line=True), gate.Outcome('FAIL', 'RESULT FAIL (1 mismatches)'))

    def test_the_last_result_line_counts(self):
        output = 'RESULT FAIL (from an earlier attempt)\nRESULT PASS\n'
        self.assertEqual(gate.outcome(output, 0, result_line=True), gate.Outcome('PASS'))

    def test_a_failing_result_line_fails_even_with_exit_status_zero(self):
        self.assertEqual(gate.outcome('RESULT FAIL (docker csv)\n', 0, result_line=True),
                         gate.Outcome('FAIL', 'RESULT FAIL (docker csv), yet exit status 0'))

    def test_a_non_zero_exit_status_fails_after_result_pass(self):
        self.assertEqual(gate.outcome('RESULT PASS\nTraceback (most recent call last):\nOSError: closed\n', 3,
                                      result_line=True),
                         gate.Outcome('FAIL', 'exit status 3: OSError: closed'))

    def test_a_script_that_promises_a_result_line_and_prints_none_fails(self):
        self.assertEqual(gate.outcome('PASS docker probe\n', 0, result_line=True),
                         gate.Outcome('FAIL', 'exit status 0 without a RESULT line'))

    def test_a_script_without_result_line_passes_on_exit_status_zero(self):
        self.assertEqual(gate.outcome('PASS docker: authentication, queries\n', 0, result_line=False),
                         gate.Outcome('PASS'))

    def test_a_failure_without_result_line_shows_the_last_line_of_output(self):
        output = 'Traceback (most recent call last):\n  File "check-jars.py"\nAssertionError: Notice differs\n\n'
        self.assertEqual(gate.outcome(output, 1, result_line=False),
                         gate.Outcome('FAIL', 'exit status 1: AssertionError: Notice differs'))

    def test_a_failure_without_any_output_says_so(self):
        self.assertEqual(gate.outcome('', 1, result_line=False), gate.Outcome('FAIL', 'exit status 1, no output'))


class ReportTests(unittest.TestCase):
    """One table of check, result, duration and log path, then one RESULT line."""

    LOGS = gate.ROOT / '.build/logs/release-gate/r1'

    def test_every_check_with_its_result_time_and_log_relative_to_the_repository(self):
        rows = [gate.Row('jars', gate.Outcome('PASS'), 4.2, self.LOGS / 'jars.log'),
                gate.Row('metrics', gate.Outcome('SKIP', 'disabled in release-line.toml')),
                gate.Row('cypher-flow', gate.Outcome('FAIL', 'RESULT FAIL (2 mismatches)'), 35.0,
                         self.LOGS / 'cypher-flow.log')]
        self.assertEqual(gate.report(rows), '\n'.join([
            'CHECK        RESULT   TIME  LOG',
            'jars         PASS       4s  .build/logs/release-gate/r1/jars.log',
            'metrics      SKIP        -  disabled in release-line.toml',
            'cypher-flow  FAIL      35s  .build/logs/release-gate/r1/cypher-flow.log  RESULT FAIL (2 mismatches)',
            'RESULT FAIL (cypher-flow)']))

    def test_skipped_checks_do_not_fail_the_run(self):
        rows = [gate.Row('jars', gate.Outcome('PASS'), 61.6, self.LOGS / 'jars.log'),
                gate.Row('stack-metrics', gate.Outcome('SKIP', 'disabled in release-line.toml'))]
        self.assertEqual(gate.report(rows).splitlines()[-1], 'RESULT PASS')
        self.assertIn('jars           PASS      62s', gate.report(rows))

    def test_every_failed_check_is_named_in_the_result_line(self):
        rows = [gate.Row('stack', gate.Outcome('FAIL', 'DozerDB not ready'), 180, self.LOGS / 'stack.log'),
                gate.Row('browser-flow', gate.Outcome('FAIL', 'not run: the stack is not ready'))]
        self.assertEqual(gate.report(rows).splitlines()[-1], 'RESULT FAIL (stack, browser-flow)')


class FakeClock:
    """A monotonic clock that only moves when the code under test sleeps."""

    def __init__(self):
        self.now = 1000.0
        self.sleeps = []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class Probe:
    """Fails with the given errors in turn, then answers."""

    def __init__(self, *errors, answer='ready'):
        self.errors = list(errors)
        self.answer = answer
        self.calls = 0

    def __call__(self):
        self.calls += 1
        if self.errors:
            raise self.errors.pop(0)
        return self.answer


class WaitTests(unittest.TestCase):
    """Every wait of the gate is bounded polling: probe, sleep, probe again, until the answer or the deadline."""

    def test_an_answer_on_the_first_probe_returns_at_once(self):
        clock = FakeClock()
        self.assertEqual(gate.wait_until(Probe(), 60, 'DozerDB', clock=clock, sleep=clock.sleep), 'ready')
        self.assertEqual(clock.sleeps, [])

    def test_failed_probes_are_retried_at_the_interval(self):
        clock = FakeClock()
        probe = Probe(ConnectionRefusedError('refused'), AssertionError('target down'))
        self.assertEqual(gate.wait_until(probe, 60, 'Prometheus', clock=clock, sleep=clock.sleep, interval=2), 'ready')
        self.assertEqual((probe.calls, clock.sleeps), (3, [2, 2]))

    def test_the_deadline_raises_naming_what_was_awaited_and_the_last_error(self):
        clock = FakeClock()
        probe = Probe(*[OSError('refused')] * 10, AssertionError('no neo4j.dbms.vm.heap.used yet'))
        with self.assertRaises(gate.NotReady) as raised:
            gate.wait_until(probe, 10, 'Graphite', clock=clock, sleep=clock.sleep, interval=1)
        self.assertEqual(str(raised.exception), 'Graphite not ready after 10 s: no neo4j.dbms.vm.heap.used yet')
        self.assertEqual(probe.calls, 11)

    def test_the_last_sleep_ends_at_the_deadline(self):
        clock = FakeClock()
        with self.assertRaises(gate.NotReady):
            gate.wait_until(Probe(*[OSError('refused')] * 10), 5, 'DozerDB', clock=clock, sleep=clock.sleep, interval=2)
        self.assertEqual(clock.sleeps, [2, 2, 1])

    def test_an_error_without_message_is_named_by_its_type(self):
        clock = FakeClock()
        with self.assertRaises(gate.NotReady) as raised:
            gate.wait_until(Probe(*[ConnectionResetError()] * 5), 2, 'DozerDB', clock=clock, sleep=clock.sleep)
        self.assertEqual(str(raised.exception), 'DozerDB not ready after 2 s: ConnectionResetError')


class FakeDocker:
    """Answers docker's listing commands (ps, volume ls, network ls with a label filter) from (kind, Compose
    project, id) objects."""

    KINDS = {'ps': 'containers', 'volume': 'volumes', 'network': 'networks'}

    def __init__(self, *objects):
        self.objects = objects

    def list_ids(self, command):
        kind = self.KINDS[command[1]]
        labels = [command[i + 1] for i, word in enumerate(command) if word == '--filter']
        return [id for k, project, id in self.objects
                if k == kind and all(label == f'label=com.docker.compose.project={project}' for label in labels)]


class ComposeTests(unittest.TestCase):
    """The stack runs as its own Compose project, named by the run, from the checked-in definition."""

    PORTS = gate.Ports(http=50101, bolt=50102, prometheus=50103, graphite=50104)

    def test_the_project_is_named_by_the_run_id(self):
        self.assertEqual(gate.run_id(1793368800.0, 'a1b2c3'), '20261030-140000-a1b2c3')
        self.assertEqual(gate.project_name('20261030-140000-a1b2c3'), 'dozerdb-gate-20261030-140000-a1b2c3')

    def test_commands_name_the_project_and_the_stack_file_and_add_the_metrics_profile_when_on(self):
        stack = str(gate.ROOT / 'scripts/stack/compose.yaml')
        self.assertEqual(gate.compose_command('dozerdb-gate-r1', True, 'up', '--detach'),
                         ['docker', 'compose', '--project-name', 'dozerdb-gate-r1', '--file', stack,
                          '--profile', 'metrics', 'up', '--detach'])
        self.assertEqual(gate.compose_command('dozerdb-gate-r1', False, 'up', '--detach'),
                         ['docker', 'compose', '--project-name', 'dozerdb-gate-r1', '--file', stack, 'up', '--detach'])

    def test_teardown_takes_down_the_run_project_with_its_volumes_and_orphans(self):
        command = gate.compose_command('dozerdb-gate-r1', True, *gate.DOWN)
        self.assertEqual(command[command.index('--project-name') + 1], 'dozerdb-gate-r1')
        self.assertIn('down', command)
        self.assertLessEqual({'--volumes', '--remove-orphans'}, set(command[command.index('down'):]))

    def test_leftovers_are_what_docker_still_lists_of_this_project_by_kind(self):
        docker = FakeDocker(('containers', 'dozerdb-gate-r1', 'c1'), ('volumes', 'dozerdb-gate-r1', 'v1'),
                            ('volumes', 'dozerdb-gate-r1', 'v2'), ('networks', 'dozerdb-gate-r2', 'n9'),
                            ('containers', 'dozerdb-metrics', 'm1'))
        self.assertEqual(gate.leftovers('dozerdb-gate-r1', docker.list_ids), ['containers: c1', 'volumes: v1 v2'])

    def test_a_clean_teardown_leaves_nothing_of_the_project(self):
        docker = FakeDocker(('networks', 'dozerdb-gate-r2', 'n9'))
        self.assertEqual(gate.leftovers('dozerdb-gate-r1', docker.list_ids), [])

    def test_the_environment_carries_the_image_password_and_host_ports(self):
        self.assertEqual(gate.stack_environment('dozerdb:calendar-local', 'secret', self.PORTS, metrics=True), {
            'DOZERDB_IMAGE': 'dozerdb:calendar-local', 'DOZERDB_PASSWORD': 'secret',
            'DOZERDB_HTTP_PORT': '50101', 'DOZERDB_BOLT_PORT': '50102',
            'PROMETHEUS_PORT': '50103', 'GRAPHITE_PORT': '50104', 'STACK_METRICS': 'on'})

    def test_metrics_off_leaves_the_switch_empty_so_no_metrics_setting_reaches_dozerdb(self):
        # The image's entrypoint writes no setting for an empty NEO4J_* variable.
        environment = gate.stack_environment('dozerdb:calendar-local', 's', self.PORTS, metrics=False)
        self.assertEqual(environment['STACK_METRICS'], '')

    def test_the_stack_definition_uses_exactly_the_variables_the_gate_sets(self):
        text = (gate.ROOT / 'scripts/stack/compose.yaml').read_text()
        used = set(re.findall(r'\$\{([A-Z_]+)', text))
        self.assertEqual(used, set(gate.stack_environment('i', 'p', self.PORTS, metrics=True)))


def manifest(**switches):
    """The calendar manifest with every check on, except the switches given (underscores for hyphens)."""
    checks = {name: True for name in release_checks.NAMES}
    checks.update({name.replace('_', '-'): value for name, value in switches.items()})
    return release_line.Manifest('calendar', '2026.10.0', '1.0.0', 'graphstack/dozerdb:2026.10.0-1.0.0', '2026.10',
                                 'dozerdb:calendar-local', '2026.10.0', checks)


class HeaderTests(unittest.TestCase):
    """The gate's first printed line names the release as the server's startup banner does."""

    def test_the_header_names_the_release_from_the_manifest(self):
        line = gate.header('20261106-120000-abcdef', manifest(), 'dozerdb:calendar-local',
                           gate.ROOT / '.build/logs/release-gate/20261106-120000-abcdef')
        self.assertEqual(line, 'Release gate 20261106-120000-abcdef: DozerDB 1.0.0 (Neo4j 2026.10.0), calendar line, '
                               'image dozerdb:calendar-local, logs in .build/logs/release-gate/20261106-120000-abcdef')

    def test_the_release_name_takes_both_halves_verbatim(self):
        variant = release_line.Manifest('lts', '5.26.32', '1.2.0', 'graphstack/dozerdb:5.26.32-1.2.0', '5.26',
                                        'dozerdb:lts-local', '2.13.4', {})
        self.assertEqual(gate.release_name(variant), 'DozerDB 1.2.0 (Neo4j 5.26.32)')


class ImageTests(unittest.TestCase):
    """Which image the gate tests: the line's local build unless told otherwise."""

    def test_the_line_local_image_by_default(self):
        self.assertEqual(gate.image_under_test(manifest(), gate.parse_arguments([])), 'dozerdb:calendar-local')

    def test_the_published_image_of_the_version_after_the_push(self):
        self.assertEqual(gate.image_under_test(manifest(), gate.parse_arguments(['--published'])),
                         'graphstack/dozerdb:2026.10.0-1.0.0')

    def test_any_image_when_named(self):
        arguments = gate.parse_arguments(['--image', 'dozerdb:2026.10.0-1.0.0-local'])
        self.assertEqual(gate.image_under_test(manifest(), arguments), 'dozerdb:2026.10.0-1.0.0-local')

    def test_published_and_a_named_image_exclude_each_other(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            gate.parse_arguments(['--published', '--image', 'dozerdb:calendar-local'])


EXACT = 'sha256:' + 'a' * 64
OTHER = 'sha256:' + 'b' * 64


def lts_manifest():
    return release_line.Manifest('lts', '5.26.32', '1.0.0', 'graphstack/dozerdb:5.26.32-1.0.0', '5.26',
                                 'dozerdb:lts-local', '2.13.14', {name: True for name in release_checks.NAMES})


class FloatingTagTests(unittest.TestCase):
    """--published: the floating tags the push moved resolve, through the registry, to the exact image's digest."""

    def check(self, manifest, registry):
        return gate.floating_tags(manifest.image, manifest.floating_images, registry.get)

    def test_calendar_series_and_latest_with_the_exact_digest_pass(self):
        row = self.check(manifest(), {'graphstack/dozerdb:2026.10.0-1.0.0': EXACT, 'graphstack/dozerdb:2026.10': EXACT,
                                      'graphstack/dozerdb:latest': EXACT})
        self.assertEqual(row.name, 'floating-tags')
        self.assertEqual(row.outcome.status, 'PASS')
        self.assertIn('graphstack/dozerdb:2026.10, graphstack/dozerdb:latest = graphstack/dozerdb:2026.10.0-1.0.0',
                      row.outcome.detail)

    def test_lts_checks_its_series_and_never_latest(self):
        asked = []
        registry = {'graphstack/dozerdb:5.26.32-1.0.0': EXACT, 'graphstack/dozerdb:5.26': EXACT,
                    'graphstack/dozerdb:latest': OTHER}
        row = gate.floating_tags(lts_manifest().image, lts_manifest().floating_images,
                                 lambda tag: asked.append(tag) or registry.get(tag))
        self.assertEqual(row.outcome.status, 'PASS')
        self.assertEqual(asked, ['graphstack/dozerdb:5.26.32-1.0.0', 'graphstack/dozerdb:5.26'])

    def test_a_floating_tag_with_another_digest_fails_naming_it(self):
        row = self.check(manifest(), {'graphstack/dozerdb:2026.10.0-1.0.0': EXACT, 'graphstack/dozerdb:2026.10': EXACT,
                                      'graphstack/dozerdb:latest': OTHER})
        self.assertEqual(row.outcome, gate.Outcome(
            'FAIL', f'graphstack/dozerdb:latest is {OTHER}, not {EXACT} of graphstack/dozerdb:2026.10.0-1.0.0'))

    def test_a_missing_floating_tag_fails_naming_it(self):
        row = self.check(lts_manifest(), {'graphstack/dozerdb:5.26.32-1.0.0': EXACT})
        self.assertEqual(row.outcome, gate.Outcome('FAIL', 'graphstack/dozerdb:5.26 is not in the registry'))

    def test_a_missing_exact_image_fails_naming_it(self):
        row = self.check(manifest(), {'graphstack/dozerdb:2026.10': EXACT, 'graphstack/dozerdb:latest': EXACT})
        self.assertEqual(row.outcome, gate.Outcome('FAIL', 'graphstack/dozerdb:2026.10.0-1.0.0 is not in the registry'))

    def test_an_unreachable_registry_fails_the_row(self):
        def unreachable(tag):
            raise OSError('no route to registry-1.docker.io')
        row = gate.floating_tags(manifest().image, manifest().floating_images, unreachable)
        self.assertEqual(row.outcome.status, 'FAIL')
        self.assertIn('no route to registry-1.docker.io', row.outcome.detail)


class PublishedRunTests(unittest.TestCase):
    """Only --published asks the registry, and its floating-tags row comes first in the table."""

    def run_gate(self, *argv):
        asked = []
        logs = tempfile.TemporaryDirectory()
        self.addCleanup(logs.cleanup)
        stdout = io.StringIO()
        with mock.patch.object(gate, 'checked_manifest', lambda path: manifest()), \
                mock.patch.object(gate, 'LOGS', pathlib.Path(logs.name)), \
                mock.patch.object(gate, 'registry_digest', lambda tag: asked.append(tag) or EXACT), \
                mock.patch.object(gate, 'run_all', lambda *args, **kwargs: [gate.Row('jars', gate.Outcome('PASS'))]), \
                mock.patch.object(gate.signal, 'signal'), contextlib.redirect_stdout(stdout):
            status = gate.main(list(argv))
        return status, asked, stdout.getvalue()

    def test_published_checks_the_floating_tags_first(self):
        status, asked, output = self.run_gate('--published')
        self.assertEqual(status, 0)
        self.assertEqual(asked, ['graphstack/dozerdb:2026.10.0-1.0.0', 'graphstack/dozerdb:2026.10',
                                 'graphstack/dozerdb:latest'])
        table = output[output.index('CHECK'):].splitlines()
        self.assertTrue(table[1].startswith('floating-tags'), table)
        self.assertTrue(table[2].startswith('jars'), table)

    def test_a_local_run_never_asks_the_registry(self):
        status, asked, output = self.run_gate()
        self.assertEqual(status, 0)
        self.assertEqual(asked, [])
        self.assertNotIn('floating-tags', output)


class FakeResponse:
    def __init__(self, body=b'', headers=None):
        self.body, self.headers = body, headers or {}

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class RegistryTests(unittest.TestCase):
    """The registry seam: an anonymous pull token, then a HEAD request for the tag's manifest."""

    def registry(self, answer):
        self.requests = []

        def urlopen(request, timeout):
            self.requests.append(request)
            if isinstance(request, str):
                return FakeResponse(b'{"token": "t0k"}')
            return answer(request)
        return urlopen

    def test_the_digest_of_a_tag_is_the_registry_content_digest(self):
        urlopen = self.registry(lambda request: FakeResponse(headers={'Docker-Content-Digest': EXACT}))
        self.assertEqual(gate.registry_digest('graphstack/dozerdb:2026.10', urlopen), EXACT)
        token, head = self.requests
        self.assertIn('scope=repository%3Agraphstack%2Fdozerdb%3Apull', token)
        self.assertEqual(head.get_method(), 'HEAD')
        self.assertEqual(head.full_url, 'https://registry-1.docker.io/v2/graphstack/dozerdb/manifests/2026.10')
        self.assertEqual(head.get_header('Authorization'), 'Bearer t0k')
        self.assertIn('application/vnd.oci.image.index.v1+json', head.get_header('Accept'))

    def test_a_missing_tag_has_no_digest(self):
        def missing(request):
            raise gate.urllib.error.HTTPError(request.full_url, 404, 'Not Found', {}, None)
        self.assertIsNone(gate.registry_digest('graphstack/dozerdb:nope', self.registry(missing)))

    def test_other_registry_errors_are_raised(self):
        def refused(request):
            raise gate.urllib.error.HTTPError(request.full_url, 401, 'Unauthorized', {}, None)
        with self.assertRaises(gate.urllib.error.HTTPError):
            gate.registry_digest('graphstack/dozerdb:latest', self.registry(refused))


@unittest.skipUnless(os.environ.get('DOZERDB_LIVE_REGISTRY'),
                     'opt-in: DOZERDB_LIVE_REGISTRY="<exact image> <floating tag>..." compares them on Docker Hub')
class LiveRegistryTests(unittest.TestCase):
    """The floating-tags row against the live Docker Hub registry, read-only and anonymous. Run with
    DOZERDB_LIVE_REGISTRY="graphstack/dozerdb:2026.10.1.0 graphstack/dozerdb:2026.10 graphstack/dozerdb:latest"."""

    def test_the_floating_tags_have_the_exact_image_digest(self):
        exact, *floating = os.environ['DOZERDB_LIVE_REGISTRY'].split()
        row = gate.floating_tags(exact, floating, gate.registry_digest)
        gate.announce(row)
        self.assertEqual(row.outcome.status, 'PASS', row.outcome.detail)


class FakeStack:
    def __init__(self, metrics, ready=True, leftovers=(), start_error=None, close_error=None):
        self.metrics = metrics
        self.is_ready = ready
        self.leftovers = list(leftovers)
        self.start_error = start_error
        self.close_error = close_error
        self.closed = False
        self.context = release_checks.Context(image='dozerdb:calendar-local', origin='http://127.0.0.1:50101', password='pw',
                                    prometheus_url='http://127.0.0.1:50103' if metrics else None)

    def start(self):
        if self.start_error:
            raise self.start_error
        rows = [gate.Row('stack', gate.Outcome('PASS' if self.is_ready else 'FAIL',
                                                '' if self.is_ready else 'DozerDB not ready after 180 s: refused'))]
        if self.metrics:
            rows.append(gate.Row('stack-metrics', gate.Outcome('PASS')))
        return rows

    def close(self):
        self.closed = True
        if self.close_error:
            raise self.close_error
        return self.leftovers


class Recorder:
    """Runs checks by recording them; a check named in fail fails, one named in interrupt is Ctrl-C."""

    def __init__(self, fail=(), interrupt=(), crash=()):
        self.ran = []
        self.contexts = {}
        self.fail = fail
        self.interrupt = interrupt
        self.crash = crash
        self.stacks = []

    def execute(self, check, context):
        if check.name in self.interrupt:
            raise KeyboardInterrupt
        if check.name in self.crash:
            raise FileNotFoundError(2, 'No such file or directory', 'node')
        self.ran.append(check.name)
        self.contexts[check.name] = context
        return gate.Row(check.name, gate.Outcome('FAIL' if check.name in self.fail else 'PASS'), 1.0)

    def stack_opener(self, ready=True, leftovers=(), **errors):
        def opener(metrics):
            stack = FakeStack(metrics, ready, leftovers, **errors)
            self.stacks.append(stack)
            return stack
        return opener


class RunTests(unittest.TestCase):
    """The order of the run, what is skipped, and that the stack is always removed."""

    def run_gate(self, manifest, recorder, stack_ready=True, leftovers=(), hold=None, **stack_errors):
        return gate.run_all(manifest, 'dozerdb:calendar-local', recorder.execute,
                            recorder.stack_opener(stack_ready, leftovers, **stack_errors), hold=hold)

    def statuses(self, rows):
        return [(row.name, row.outcome.status) for row in rows]

    def test_every_check_runs_in_order_with_the_stack_checks_last(self):
        recorder = Recorder()
        rows = self.run_gate(manifest(), recorder)
        self.assertEqual(self.statuses(rows), [(name, 'PASS') for name in (
            'jars', 'smoke-plugin', 'smoke-distribution', 'smoke-docker', 'metrics', 'query-log', 'docker-plugins',
            'stack', 'stack-metrics', 'cypher-flow', 'browser-flow')])
        self.assertTrue(recorder.stacks[0].metrics)
        self.assertTrue(recorder.stacks[0].closed)

    def test_the_help_lists_every_check_in_the_order_the_gate_runs_them(self):
        out = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stdout(out):
            gate.parse_arguments(['--help'])
        lines = out.getvalue().splitlines()
        found = [next(i for i, line in enumerate(lines) if line.split()[:1] == [check.name] and check.summary in line)
                 for check in release_checks.CHECKS]
        self.assertEqual(found, sorted(found))

    def test_checks_outside_the_stack_get_the_image_and_flows_get_the_stack(self):
        recorder = Recorder()
        self.run_gate(manifest(), recorder)
        self.assertEqual(recorder.contexts['smoke-docker'],
                         release_checks.Context(image='dozerdb:calendar-local', gds_version='2026.10.0'))
        self.assertEqual(recorder.contexts['cypher-flow'], recorder.stacks[0].context)

    def test_the_query_log_check_tests_the_image_on_every_form_and_prints_a_result_line(self):
        recorder = Recorder()
        self.run_gate(manifest(), recorder)
        check = release_checks.CHECKS[release_checks.NAMES.index('query-log')]
        self.assertEqual(check.command(recorder.contexts['query-log'])[1:],
                         ['scripts/query-log-check.py', '--image', 'dozerdb:calendar-local'])
        self.assertTrue(check.result_line)
        self.assertFalse(check.stack)

    def test_the_plugin_check_expects_the_open_gds_version_of_the_line(self):
        recorder = Recorder()
        self.run_gate(manifest(), recorder)
        command = release_checks.CHECKS[release_checks.NAMES.index('docker-plugins')].command
        arguments = command(recorder.contexts['docker-plugins'])
        self.assertEqual(arguments[arguments.index('--gds-version') + 1], '2026.10.0')
        self.assertEqual(arguments[arguments.index('--image') + 1], 'dozerdb:calendar-local')

    def test_the_cypher_flow_asks_the_stack_prometheus_when_the_stack_has_one(self):
        command = release_checks.CHECKS[release_checks.NAMES.index('cypher-flow')].command
        stack = release_checks.Context(image='dozerdb:calendar-local', origin='http://127.0.0.1:50101', password='pw',
                             prometheus_url='http://127.0.0.1:50103')
        self.assertEqual(command(stack)[1:], ['scripts/cypher-flow.py', 'http://127.0.0.1:50101', 'pw',
                                              '--prometheus', 'http://127.0.0.1:50103'])
        without_metrics = release_checks.Context(image='dozerdb:calendar-local', origin='http://127.0.0.1:50101', password='pw')
        self.assertEqual(command(without_metrics)[1:], ['scripts/cypher-flow.py', 'http://127.0.0.1:50101', 'pw'])

    def test_the_browser_flow_asks_the_stack_prometheus_when_the_stack_has_one(self):
        command = release_checks.CHECKS[release_checks.NAMES.index('browser-flow')].command
        stack = release_checks.Context(image='dozerdb:calendar-local', origin='http://127.0.0.1:50101', password='pw',
                             prometheus_url='http://127.0.0.1:50103')
        self.assertEqual(command(stack), ['node', 'scripts/browser-flow.mjs', 'http://127.0.0.1:50101', 'pw',
                                          '--prometheus', 'http://127.0.0.1:50103'])
        without_metrics = release_checks.Context(image='dozerdb:calendar-local', origin='http://127.0.0.1:50101', password='pw')
        self.assertEqual(command(without_metrics), ['node', 'scripts/browser-flow.mjs', 'http://127.0.0.1:50101', 'pw'])

    def test_both_flows_say_what_they_leave_unchecked_without_the_stack_prometheus(self):
        flows = {check.name: check for check in release_checks.CHECKS if getattr(check, 'without_prometheus', None)}
        self.assertEqual(sorted(flows), ['browser-flow', 'cypher-flow'])
        self.assertIn('metrics', flows['cypher-flow'].without_prometheus)
        self.assertIn(':sysinfo', flows['browser-flow'].without_prometheus)

    def test_a_disabled_check_is_skipped_without_running_and_does_not_fail_the_run(self):
        recorder = Recorder()
        rows = self.run_gate(manifest(metrics=False, docker_plugins=False), recorder)
        self.assertNotIn('metrics', recorder.ran)
        self.assertIn(('metrics', 'SKIP'), self.statuses(rows))
        self.assertIn(('docker-plugins', 'SKIP'), self.statuses(rows))
        self.assertEqual(rows[4].outcome.detail, 'disabled in release-line.toml')
        self.assertEqual(gate.failed(rows), [])

    def test_a_skip_names_the_manifest_that_disabled_the_check(self):
        recorder = Recorder()
        variant = release_line.Manifest('lts', '5.26.32', '1.0.0', 'graphstack/dozerdb:5.26.32-1.0.0', '5.26',
                                        'dozerdb:lts-local', '2.13.14', manifest(metrics=False).checks,
                                        source='lts-dry-run.toml')
        rows = self.run_gate(variant, recorder)
        self.assertEqual(rows[4], gate.Row('metrics', gate.Outcome('SKIP', 'disabled in lts-dry-run.toml')))

    def test_with_the_stack_metrics_off_the_stack_starts_without_them(self):
        recorder = Recorder()
        rows = self.run_gate(manifest(metrics=False, stack_metrics=False), recorder)
        self.assertFalse(recorder.stacks[0].metrics)
        self.assertIn(('stack-metrics', 'SKIP'), self.statuses(rows))
        self.assertIsNone(recorder.contexts['browser-flow'].prometheus_url)

    def test_no_stack_starts_when_no_check_needs_it(self):
        recorder = Recorder()
        rows = self.run_gate(manifest(stack_metrics=False, cypher_flow=False, browser_flow=False), recorder)
        self.assertEqual(recorder.stacks, [])
        self.assertNotIn('stack', [row.name for row in rows])

    def test_a_stack_that_is_not_ready_fails_its_checks_without_running_them_and_is_removed(self):
        recorder = Recorder()
        rows = self.run_gate(manifest(), recorder, stack_ready=False)
        self.assertNotIn('cypher-flow', recorder.ran)
        self.assertIn(gate.Row('cypher-flow', gate.Outcome('FAIL', 'not run: the stack is not ready')), rows)
        self.assertTrue(recorder.stacks[0].closed)

    def test_a_failed_check_does_not_stop_the_others(self):
        recorder = Recorder(fail=('smoke-plugin',))
        rows = self.run_gate(manifest(), recorder)
        self.assertEqual(gate.failed(rows), ['smoke-plugin'])
        self.assertIn('browser-flow', recorder.ran)

    def test_ctrl_c_stops_the_run_removes_the_stack_and_fails(self):
        recorder = Recorder(interrupt=('cypher-flow',))
        rows = self.run_gate(manifest(), recorder)
        self.assertNotIn('browser-flow', recorder.ran)
        self.assertTrue(recorder.stacks[0].closed)
        self.assertEqual(rows[-1], gate.Row('interrupted', gate.Outcome('FAIL', 'stopped by Ctrl-C or SIGTERM')))

    def test_a_check_that_crashes_fails_alone_and_the_run_goes_on(self):
        recorder = Recorder(crash=('browser-flow', 'jars'))
        rows = self.run_gate(manifest(), recorder)
        self.assertEqual(gate.failed(rows), ['jars', 'browser-flow'])
        self.assertIn(gate.Row('browser-flow', gate.Outcome(
            'FAIL', "crashed: FileNotFoundError: [Errno 2] No such file or directory: 'node'")), rows)
        self.assertIn('cypher-flow', recorder.ran)
        self.assertTrue(recorder.stacks[0].closed)

    def test_a_stack_that_crashes_while_starting_fails_its_checks_and_is_removed(self):
        recorder = Recorder()
        rows = self.run_gate(manifest(), recorder, start_error=FileNotFoundError(2, 'No such file', 'docker'))
        self.assertEqual(gate.failed(rows), ['stack', 'stack-metrics', 'cypher-flow', 'browser-flow'])
        self.assertEqual(rows[7].outcome.detail, "crashed: FileNotFoundError: [Errno 2] No such file: 'docker'")
        self.assertTrue(recorder.stacks[0].closed)

    def test_a_stack_that_cannot_even_be_set_up_fails_its_checks(self):
        def opener(metrics):
            raise OSError('no free port')
        rows = gate.run_all(manifest(), 'dozerdb:calendar-local', Recorder().execute, opener)
        self.assertEqual(gate.failed(rows), ['stack', 'stack-metrics', 'cypher-flow', 'browser-flow'])
        self.assertEqual(rows[7].outcome.detail, 'crashed: OSError: no free port')

    def test_a_teardown_that_crashes_is_a_failed_row(self):
        recorder = Recorder()
        rows = self.run_gate(manifest(), recorder, close_error=OSError('docker daemon gone'))
        self.assertEqual(rows[-1], gate.Row('teardown', gate.Outcome('FAIL', 'crashed: OSError: docker daemon gone')))

    def test_what_the_teardown_left_behind_fails_the_run(self):
        recorder = Recorder()
        rows = self.run_gate(manifest(), recorder, leftovers=['volumes: 3f2a'])
        self.assertEqual(rows[-1], gate.Row('teardown', gate.Outcome('FAIL', 'left volumes: 3f2a')))

    def test_hold_sees_the_results_while_the_stack_still_runs(self):
        recorder = Recorder()
        seen = []
        self.run_gate(manifest(), recorder, hold=lambda rows, stack: seen.append((len(rows), stack.closed)))
        self.assertEqual(seen, [(11, False)])
        self.assertTrue(recorder.stacks[0].closed)


class ExecuteTests(unittest.TestCase):
    """A check whose process cannot even start is a failed row with the error in its log, never a crashed gate."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.logs = pathlib.Path(directory.name)

    def execute(self, command):
        check = release_checks.Check('lost', 'a check whose program is missing', command, True, 10)
        with contextlib.redirect_stdout(io.StringIO()):
            return gate.execute(check, release_checks.Context(), self.logs)

    def test_a_missing_program_fails_the_check_with_the_error_in_its_log(self):
        row = self.execute(lambda context: [str(self.logs / 'no-such-program')])
        self.assertEqual((row.name, row.outcome.status), ('lost', 'FAIL'))
        self.assertIn('FileNotFoundError', row.outcome.detail)
        self.assertIn('FileNotFoundError', (self.logs / 'lost.log').read_text())

    def flow(self, context):
        check = release_checks.Check('flow', 'a flow that passes', lambda c: [sys.executable, '-c',
                                     'print("SKIP     metrics lifecycle: no --prometheus"); print("RESULT PASS")'],
                                     True, 30, stack=True, without_prometheus='metrics assertions skipped')
        with contextlib.redirect_stdout(io.StringIO()):
            return gate.execute(check, context, self.logs)

    def test_a_flow_run_without_the_stack_prometheus_passes_saying_what_it_skipped(self):
        row = self.flow(release_checks.Context(origin='http://127.0.0.1:1', password='pw'))
        self.assertEqual(row.outcome, gate.Outcome('PASS', 'metrics assertions skipped: stack-metrics off'))

    def test_a_flow_run_with_the_stack_prometheus_passes_plainly(self):
        row = self.flow(release_checks.Context(origin='http://127.0.0.1:1', password='pw',
                                               prometheus_url='http://127.0.0.1:2'))
        self.assertEqual(row.outcome, gate.Outcome('PASS'))

    def test_a_command_that_cannot_be_built_fails_the_check(self):
        row = self.execute(lambda context: [context.origin + '/x'])
        self.assertEqual(row.outcome.status, 'FAIL')
        self.assertIn('TypeError', (self.logs / 'lost.log').read_text())


def targets(*active):
    return {'status': 'success', 'data': {'activeTargets': list(active), 'droppedTargets': []}}


def target(health, job='neo4j', error=''):
    return {'labels': {'instance': 'dozerdb:2004', 'job': job}, 'health': health, 'lastError': error}


def vector(*series):
    return {'status': 'success', 'data': {'resultType': 'vector', 'result': list(series)}}


HEAP_SERIES = {'metric': {'__name__': 'neo4j_dbms_vm_heap_used', 'instance': 'dozerdb:2004', 'job': 'neo4j'},
               'value': [1793368800.0, '123456789']}


class StackMetricsReadinessTests(unittest.TestCase):
    """The stack's monitoring is ready when Prometheus scrapes DozerDB and Graphite holds a value from it."""

    def test_an_up_target_with_the_heap_gauge_scraped_is_ready(self):
        self.assertIsNone(gate.prometheus_problem(targets(target('up')), vector(HEAP_SERIES)))

    def test_no_target_of_the_neo4j_job_yet(self):
        self.assertEqual(gate.prometheus_problem(targets(), vector()), 'no target of job neo4j yet')
        self.assertEqual(gate.prometheus_problem(targets(target('up', job='other')), vector(HEAP_SERIES)),
                         'no target of job neo4j yet')

    def test_a_target_that_is_not_up_is_named_with_its_error(self):
        self.assertEqual(gate.prometheus_problem(targets(target('down', error='connection refused')), vector()),
                         'target dozerdb:2004 down: connection refused')

    def test_an_up_target_without_the_heap_gauge(self):
        self.assertEqual(gate.prometheus_problem(targets(target('up')), vector()),
                         'no neo4j_dbms_vm_heap_used series yet')

    def test_graphite_storing_series_under_the_prefix_is_ready(self):
        # The find API's answer for neo4j.*; carbon creates a series only when a value for it arrives.
        found = [{'text': 'dbms', 'id': 'neo4j.dbms', 'allowChildren': 1, 'expandable': 1, 'leaf': 0}]
        self.assertIsNone(gate.graphite_problem(found))

    def test_graphite_without_series_under_the_prefix(self):
        self.assertEqual(gate.graphite_problem([]), 'no series under neo4j. stored yet')


if __name__ == '__main__':
    unittest.main()
