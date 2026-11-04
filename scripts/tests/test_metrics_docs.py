"""The monitoring documentation generator, scripts/metrics-docs.py: inventory reading, rendering, and the check that
the committed pages are current."""
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'metrics-docs.py'
spec = importlib.util.spec_from_file_location('metrics_docs', SCRIPT)
metrics_docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics_docs)

REFERENCE = 'https://neo4j.com/docs/operations-manual/2026.10/monitoring/metrics/reference/'
HEADER = ('section\tname_template\ttype\tunit\tscope\tscope_inferred\tlifecycle\ttier\treason\tformat_provenance\t'
          'docs_anchor\tdeprecated\ttype_rule\tnotes')


def row(section='Bolt metrics', name='<prefix>.dbms.bolt.connections_opened', type_='counter', unit='-',
        scope='global', inferred='false', lifecycle='-', tier='Compatible', reason='', provenance='documented',
        anchor=REFERENCE + '#bolt-metrics', deprecated='false', rule='documented', notes='maintainer text'):
    return '\t'.join([section, name, type_, unit, scope, inferred, lifecycle, tier, reason, provenance, anchor,
                      deprecated, rule, notes])


def inventory(*rows):
    return '# comment line\n#\n' + HEADER + '\n' + '\n'.join(rows) + '\n'


class InventoryReadingTests(unittest.TestCase):
    def test_rows_are_read_by_column_name_and_comments_are_skipped(self):
        reordered = '\t'.join(['tier', 'notes', 'section', 'name_template', 'type', 'unit', 'scope', 'scope_inferred',
                               'lifecycle', 'reason', 'format_provenance', 'docs_anchor', 'deprecated', 'type_rule'])
        text = ('# header comment\n' + reordered + '\n' + '\t'.join(
            ['Partial', 'n', 'Bolt metrics', '<prefix>.dbms.bolt.response_success', 'counter', '-', 'global', 'false',
             'unsupported feature', 'Counts only with a setting', 'community-source', REFERENCE + '#bolt-metrics',
             'false', 'documented']) + '\n')
        [entry] = metrics_docs.read_inventory(text)
        self.assertEqual(entry.tier, 'Partial')
        self.assertEqual(entry.reason, 'Counts only with a setting')
        self.assertEqual(entry.lifecycle, 'unsupported feature')

    def test_the_notes_column_is_never_read(self):
        [entry] = metrics_docs.read_inventory(inventory(row(notes='private maintainer remark')))
        self.assertNotIn('private maintainer remark', repr(entry))

    def test_full_name_carries_the_scope_segment_whether_or_not_the_manual_writes_it(self):
        entries = metrics_docs.read_inventory(inventory(
            row(name='<prefix>.dbms.bolt.connections_opened'),
            row(section='Database transaction metrics', name='<prefix>.transaction.committed', scope='database'),
            row(section='JVM Heap metrics', name='<prefix>.vm.heap.used', type_='gauge'),
            row(section='Database neo4j pools metrics', name='<prefix>.database.<db>.pool.<pool>.<db>.free',
                scope='database')))
        self.assertEqual([e.full_name for e in entries], [
            '<prefix>.dbms.bolt.connections_opened',
            '<prefix>.database.<db>.transaction.committed',
            '<prefix>.dbms.vm.heap.used',
            '<prefix>.database.<db>.pool.<pool>.<db>.free'])

    def test_a_missing_column_is_an_error(self):
        with self.assertRaisesRegex(ValueError, 'docs_anchor'):
            metrics_docs.read_inventory('section\tname_template\n' + 'Bolt metrics\t<prefix>.dbms.x\n')

    def test_the_manual_version_is_the_one_every_anchor_names(self):
        entries = metrics_docs.read_inventory(inventory(row()))
        self.assertEqual(metrics_docs.manual_version(entries), '2026.10')
        mixed = metrics_docs.read_inventory(inventory(
            row(), row(name='<prefix>.dbms.bolt.connections_closed',
                       anchor='https://neo4j.com/docs/operations-manual/2026.11/monitoring/metrics/reference/#x')))
        with self.assertRaisesRegex(ValueError, '2026.11'):
            metrics_docs.manual_version(mixed)

    def test_a_manual_versioned_by_its_major_alone_is_read(self):
        # The Neo4j 5 manual lives under /operations-manual/5/, not under a dotted version.
        entries = metrics_docs.read_inventory(inventory(
            row(anchor='https://neo4j.com/docs/operations-manual/5/monitoring/metrics/reference/#bolt-metrics')))
        self.assertEqual(metrics_docs.manual_version(entries), '5')

    def test_a_link_outside_the_operations_manual_is_an_error(self):
        entries = metrics_docs.read_inventory(inventory(
            row(anchor='https://neo4j.com/docs/operations-manual/current/monitoring/metrics/reference/#x')))
        with self.assertRaisesRegex(ValueError, 'not an Operations Manual link'):
            metrics_docs.manual_version(entries)


DECLARATION = '''
public final class MetricsSettings implements SettingsDeclaration {
    private static final String DEFAULT_FILTER =
            "*bolt.connections*,*vm.memory.pool.g1_eden_space,"
                    + "*pool.transaction.*.total_used,*.neo4j.count.*";

    public static final Setting<Boolean> metrics_enabled =
            newBuilder("server.metrics.enabled", BOOL, true).build();
    public static final Setting<String> metrics_prefix =
            newBuilder("server.metrics.prefix", STRING, "neo4j").build();
    public static final Setting<List<GlobbingPattern>> metrics_filter = newBuilder(
                    "server.metrics.filter",
                    listOf(GLOBBING_PATTERN),
                    listOf(GLOBBING_PATTERN).parse(DEFAULT_FILTER))
            .build();
    public static final Setting<List<GlobbingPattern>> metrics_deny_filter = newBuilder(
                    "server.metrics.deny_filter", listOf(GLOBBING_PATTERN), List.of())
            .build();
    public static final Setting<Path> csv_path = newBuilder("server.directories.metrics", PATH, Path.of("metrics"))
            .setDependency(neo4j_home)
            .build();
    public static final Setting<Duration> csv_interval = newBuilder(
                    "server.metrics.csv.interval", DURATION, Duration.ofSeconds(30))
            .addConstraint(min(Duration.ofMillis(1)))
            .build();
    public static final Setting<Long> csv_rotation_threshold = newBuilder(
                    "server.metrics.csv.rotation.size", BYTES, 10L * 1024 * 1024)
            .build();
    public static final Setting<Integer> csv_max_archives = newBuilder(
                    "server.metrics.csv.rotation.keep_number", INT, 7)
            .build();
    public static final Setting<CompressionOption> csv_archives_compression = newBuilder(
                    "server.metrics.csv.rotation.compression", ofEnum(CompressionOption.class), CompressionOption.ZIP)
            .build();
    public static final Setting<SocketAddress> graphite_server = newBuilder(
                    "server.metrics.graphite.server", SOCKET_ADDRESS, new SocketAddress(2003))
            .build();
    public static final Setting<SocketAddress> prometheus_endpoint = newBuilder(
                    "server.metrics.prometheus.endpoint", SOCKET_ADDRESS, new SocketAddress("localhost", 2004))
            .build();
}
'''


class SettingsDeclarationTests(unittest.TestCase):
    def test_every_declared_setting_with_its_default_as_neo4j_renders_it(self):
        self.assertEqual(metrics_docs.declared_settings(DECLARATION), [
            ('server.metrics.enabled', 'true'),
            ('server.metrics.prefix', 'neo4j'),
            ('server.metrics.filter', 'the default filter below'),
            ('server.metrics.deny_filter', 'empty'),
            ('server.directories.metrics', 'metrics'),
            ('server.metrics.csv.interval', '30s'),
            ('server.metrics.csv.rotation.size', '10.00MiB'),
            ('server.metrics.csv.rotation.keep_number', '7'),
            ('server.metrics.csv.rotation.compression', 'ZIP'),
            ('server.metrics.graphite.server', ':2003'),
            ('server.metrics.prometheus.endpoint', 'localhost:2004')])

    def test_the_default_filter_is_the_concatenated_literal(self):
        self.assertEqual(metrics_docs.default_filter(DECLARATION), [
            '*bolt.connections*', '*vm.memory.pool.g1_eden_space', '*pool.transaction.*.total_used',
            '*.neo4j.count.*'])

    def test_an_unknown_default_expression_is_an_error_rather_than_a_guess(self):
        with self.assertRaisesRegex(ValueError, 'Duration.ofHours'):
            metrics_docs.declared_settings(
                'newBuilder("server.metrics.x.interval", DURATION, Duration.ofHours(1)).build();')


class DefaultFilterTests(unittest.TestCase):
    FILTER = ['*bolt.connections*', '*vm.memory.pool.g1_eden_space', '*pool.transaction.*.total_used',
              '*.neo4j.count.*', '*vm.gc.time.*', '*cypher.cache*', '*protocol*']

    def status(self, name, scope, section='S'):
        [entry] = metrics_docs.read_inventory(inventory(row(section=section, name=name, scope=scope)))
        return metrics_docs.default_filter_status(entry, self.FILTER)

    def test_a_name_the_filter_matches_is_enabled(self):
        self.assertEqual(self.status('<prefix>.dbms.bolt.connections_opened', 'global'), 'yes')
        self.assertEqual(self.status('<prefix>.neo4j.count.node', 'database'), 'yes')

    def test_a_name_the_filter_misses_is_not(self):
        self.assertEqual(self.status('<prefix>.dbms.bolt.messages_done', 'global'), 'no')
        self.assertEqual(self.status('<prefix>.cypher_cache_ast_hits_total', 'database'), 'no')

    def test_a_wildcard_over_an_instance_placeholder_enables_every_instance(self):
        self.assertEqual(self.status('<prefix>.vm.gc.time.<gc>', 'global'), 'yes')

    def test_a_filter_naming_particular_instances_enables_some(self):
        self.assertEqual(self.status('<prefix>.vm.memory.pool.<pool>', 'global'), 'some')
        self.assertEqual(self.status('<prefix>.database.<db>.pool.<pool>.<db>.total_used', 'database'), 'some')
        self.assertEqual(self.status('<prefix>.database.<db>.pool.<pool>.<db>.free', 'database'), 'no')

    def test_a_pattern_that_names_no_instance_does_not_count_as_naming_one(self):
        # '*protocol*' would admit a pool called 'protocol', which no JVM has.
        self.assertEqual(self.status('<prefix>.vm.gc.count.<gc>', 'global'), 'no')

    def test_globs_follow_neo4j_semantics(self):
        self.assertTrue(metrics_docs.glob_matches('*check_point.*', 'neo4j.database.neo4j.check_point.events'))
        self.assertTrue(metrics_docs.glob_matches('neo4j.dbms.vm.heap.use?', 'neo4j.dbms.vm.heap.used'))
        self.assertFalse(metrics_docs.glob_matches('neo4j.dbms.vm.heap', 'neo4j.dbms.vm.heap.used'))
        self.assertFalse(metrics_docs.glob_matches('*cypher.cache*', 'neo4j.database.neo4j.cypher_cache_ast_hits'))


class RenderingTests(unittest.TestCase):
    def entries(self):
        return metrics_docs.read_inventory(inventory(
            row(),
            row(name='<prefix>.dbms.bolt.response_success', tier='Partial', lifecycle='unsupported feature',
                reason='Counts only with the internal setting internal.x.y=true; 0 under default settings'),
            row(section='Database data metrics', name='<prefix>.ids_in_use.node', type_='gauge', scope='database',
                lifecycle='Deprecated in 5.15', deprecated='true', anchor=REFERENCE + '#db-data-metrics'),
            row(section='Database index metrics', name='<prefix>.index.text.queried', scope='database',
                inferred='true', anchor=REFERENCE + '#db-index-metrics'),
            row(section='Raft metrics', name='<prefix>.cluster.raft.term', scope='database', tier='Not applicable',
                reason='Clustering metric; a single-instance server never emits it', provenance='never-emitted',
                anchor=REFERENCE + '#raft-metrics'),
            row(section='Raft metrics', name='<prefix>.cluster.raft.is_leader', type_='gauge', scope='database',
                tier='Not applicable', reason='Clustering metric; a single-instance server never emits it',
                provenance='never-emitted', anchor=REFERENCE + '#raft-metrics'),
            row(section='JVM threads metrics', name='<prefix>.vm.threads.virtual', type_='gauge',
                lifecycle='Introduced in 2025.05', tier='Unsupported', reason='Java 21 exposes no count',
                provenance='pending', anchor=REFERENCE + '#jvm-threads-metrics'),
            row(section='GC metrics', name='<prefix>.vm.gc.time.<gc>', unit='ms', anchor=REFERENCE + '#gc-metrics')))

    def test_version_banner_names_the_manual_and_the_release_line(self):
        self.assertEqual(metrics_docs.render_version('2026.10', '2026.10.0'),
                         'This page tracks the Neo4j Operations Manual **2026.10** '
                         '([monitoring](https://neo4j.com/docs/operations-manual/2026.10/monitoring/)) for the DozerDB '
                         '**2026.10** release line: DozerDB `2026.10.0.<revision>`, built on Neo4j `2026.10.0`.\n')

    def test_matrix_rows_carry_type_scope_tier_filter_note_and_the_neo4j_link(self):
        matrix = metrics_docs.render_matrix(self.entries(), ['*bolt.connections*', '*ids_in_use*'])
        self.assertIn('## Bolt metrics\n', matrix)
        self.assertIn('| `<prefix>.dbms.bolt.connections_opened` | counter | global | Compatible | yes |  | '
                      f'[Bolt metrics]({REFERENCE}#bolt-metrics) |\n', matrix)
        self.assertIn('| `<prefix>.dbms.bolt.response_success` | counter | global | Partial | no | Neo4j labels it an '
                      'unsupported feature. Counts only with the internal setting `internal.x.y=true`; 0 under default '
                      'settings. | ',
                      matrix)
        self.assertIn('| `<prefix>.database.<db>.ids_in_use.node` | gauge | database | Compatible | yes | '
                      'Deprecated in 5.15. | ', matrix)
        self.assertIn('| `<prefix>.database.<db>.index.text.queried` | counter | database | Compatible | no | '
                      'The manual does not state the scope. | ', matrix)
        self.assertIn('| `<prefix>.database.<db>.cluster.raft.term` | counter | database | Not applicable | - | ', matrix)
        self.assertIn('| `<prefix>.dbms.vm.gc.time.<gc>` | counter (ms) | global | Compatible | no | ', matrix)

    def test_matrix_opens_with_a_tally_per_section_linking_to_each_section(self):
        matrix = metrics_docs.render_matrix(self.entries(), [])
        self.assertIn('| [Bolt metrics](#bolt-metrics) | 1 | 1 | 0 | 0 |\n', matrix)
        self.assertIn('| [Raft metrics](#raft-metrics) | 0 | 0 | 0 | 2 |\n', matrix)
        self.assertIn('| **Total** | **4** | **1** | **1** | **2** |\n', matrix)

    def test_differences_group_metrics_by_tier_and_reason(self):
        differences = metrics_docs.render_differences(self.entries())
        self.assertNotIn('connections_opened', differences)
        self.assertNotIn('ids_in_use', differences)
        partial, rest = differences.split('### Unsupported (1)')
        unsupported, not_applicable = rest.split('### Not applicable (2)')
        self.assertIn('### Partial (1)', partial)
        self.assertIn('**Counts only with the internal setting `internal.x.y=true`; 0 under default settings.**\n\n'
                      f'- `<prefix>.dbms.bolt.response_success` ([Bolt metrics]({REFERENCE}#bolt-metrics))\n',
                      partial)
        self.assertIn('**Java 21 exposes no count.**', unsupported)
        self.assertEqual(not_applicable.count('**Clustering metric; a single-instance server never emits it.**'), 1)
        self.assertIn(f'- `<prefix>.database.<db>.cluster.raft.term` ([Raft metrics]({REFERENCE}#raft-metrics))\n'
                      f'- `<prefix>.database.<db>.cluster.raft.is_leader`', not_applicable)

    def test_a_tier_no_metric_has_says_so(self):
        entries = [e for e in self.entries() if e.tier != 'Unsupported']
        differences = metrics_docs.render_differences(entries)
        self.assertIn('### Unsupported (0)\n\nNot emitted although a single server could have it.\n\n'
                      'No metric of this release line has this tier.\n\n### Not applicable (2)', differences)

    def test_tier_summary_counts_every_tier(self):
        summary = metrics_docs.render_tier_summary(self.entries())
        self.assertIn('| Compatible | 4 |', summary)
        self.assertIn('| Partial | 1 |', summary)
        self.assertIn('could not be verified. The reason is on the [differences page](differences.md). |', summary)
        self.assertIn('| Unsupported | 1 |', summary)
        self.assertIn('| Not applicable | 2 |', summary)
        self.assertIn('| **All documented metrics** | **8** |', summary)

    def test_default_filter_block_is_a_neo4j_conf_line(self):
        self.assertEqual(metrics_docs.render_default_filter(['*a*', '*b.c']),
                         '```properties\nserver.metrics.filter=*a*,*b.c\n```\n')


class PageTests(unittest.TestCase):
    PAGE = ('# Title\n\nHand-written.\n\n<!-- BEGIN GENERATED: matrix -->\nold\n<!-- END GENERATED: matrix -->\n\n'
            'More hand-written text.\n')

    def test_a_generated_block_is_replaced_and_the_rest_is_kept(self):
        self.assertEqual(metrics_docs.update_page(self.PAGE, {'matrix': 'new\n'}),
                         '# Title\n\nHand-written.\n\n<!-- BEGIN GENERATED: matrix -->\nnew\n'
                         '<!-- END GENERATED: matrix -->\n\nMore hand-written text.\n')

    def test_a_missing_or_unknown_block_is_an_error(self):
        with self.assertRaisesRegex(ValueError, 'differences'):
            metrics_docs.update_page(self.PAGE, {'matrix': 'x\n', 'differences': 'y\n'})
        with self.assertRaisesRegex(ValueError, 'matrix'):
            metrics_docs.update_page(self.PAGE, {})

    def test_the_settings_table_must_list_every_declared_setting_with_its_default(self):
        page = ('| Setting | Default | DozerDB |\n| --- | --- | --- |\n'
                '| `server.metrics.enabled` | `true` | as Neo4j |\n'
                '| `server.metrics.filter` | [the default filter below](#default-filter) | as Neo4j |\n')
        declared = [('server.metrics.enabled', 'true'), ('server.metrics.filter', 'the default filter below')]
        self.assertEqual(metrics_docs.settings_problems(page, declared), [])
        self.assertEqual(metrics_docs.settings_problems(page, [('server.metrics.enabled', 'false')] + declared[1:]),
                         ['server.metrics.enabled: the page shows the default true, the declaration false'])
        self.assertEqual(metrics_docs.settings_problems(page, declared + [('server.metrics.jmx.enabled', 'true')]),
                         ['server.metrics.jmx.enabled: declared but missing from the page'])
        self.assertEqual(metrics_docs.settings_problems(page, declared[:1]),
                         ['server.metrics.filter: on the page but not declared'])


class ManualLinkTests(unittest.TestCase):
    def test_links_to_the_tracked_manual_are_fine(self):
        page = ('[Metrics](https://neo4j.com/docs/operations-manual/5/monitoring/metrics/) and '
                '[settings](https://neo4j.com/docs/operations-manual/5/configuration/configuration-settings/)\n')
        self.assertEqual(metrics_docs.manual_link_problems(page, '5'), [])

    def test_a_hand_written_link_to_another_manual_version_is_reported(self):
        page = ('line one\n'
                'see [Expose](https://neo4j.com/docs/operations-manual/2026.10/monitoring/metrics/expose/)\n'
                'and [current](https://neo4j.com/docs/operations-manual/current/monitoring/)\n')
        self.assertEqual(metrics_docs.manual_link_problems(page, '5'), [
            'line 2: links to the Operations Manual 2026.10, the inventory tracks 5',
            'line 3: links to the Operations Manual current, the inventory tracks 5'])

    def test_a_longer_version_with_the_same_start_is_another_version(self):
        page = 'https://neo4j.com/docs/operations-manual/5.26/monitoring/\n'
        self.assertEqual(metrics_docs.manual_link_problems(page, '5'),
                         ['line 1: links to the Operations Manual 5.26, the inventory tracks 5'])


class CommittedPagesTests(unittest.TestCase):
    def test_the_committed_monitoring_pages_are_current(self):
        problems = metrics_docs.check()
        self.assertEqual(problems, [], 'run python3 scripts/metrics-docs.py to regenerate the monitoring pages')


if __name__ == '__main__':
    unittest.main()
