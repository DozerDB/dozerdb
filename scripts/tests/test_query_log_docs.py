"""The query log documentation generator, scripts/query-log-docs.py: the release line, rendering from the field
inventory, the check that no query-log page names neo4j.com, and the check that the committed pages are current."""
import copy
import importlib.util
import json
import pathlib
import shutil
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/query-log-docs.py'
spec = importlib.util.spec_from_file_location('query_log_docs', SCRIPT)
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)


def status(calendar='compatible', lts=None, reason='Reason.', lts_reason=None):
    return {'calendar': {'tier': calendar, 'reason': reason},
            'lts': {'tier': lts or calendar, 'reason': lts_reason or reason}}


def setting(name='db.logs.query.enabled', default='VERBOSE', lts_default='VERBOSE', dynamic=True, **kw):
    return {'name': name, 'default': {'calendar': default, 'lts': lts_default}, 'dynamic': dynamic,
            'status': status(**kw), 'notes': 'maintainer text'}


def field(name, type_='query', json_key=None, plain=None, levels=('INFO', 'VERBOSE'), entries=('start', 'success', 'fail'),
          optional=False, pattern='.*', examples=(), **kw):
    return {'name': name, 'type': type_, 'jsonKey': json_key, 'plain': plain, 'levels': list(levels),
            'entries': list(entries), 'optional': optional, 'pattern': pattern, 'examples': list(examples),
            'status': status(**kw), 'provenance': 'documented', 'notes': 'maintainer text'}


def inventory():
    return {
        'description': 'test inventory',
        'lines': {'calendar': {'manual': '2026.10', 'description': 'main'}, 'lts': {'manual': '5', 'description': 'lts'}},
        'tiers': ['compatible', 'partial', 'unsupported', 'not-applicable'],
        'provenance': ['documented', 'community-source', 'historical-open-source', 'pending', 'never-emitted'],
        'settings': [
            setting(reason='VERBOSE writes a start entry and an end entry; OFF costs one read per event.'),
            setting('db.logs.query.annotation_data_format', 'JSON', 'CYPHER',
                    reason='JSON is the default of this line.', lts_reason='CYPHER is the default of this line.'),
            setting('db.logs.query.obfuscate_errors', 'false', 'false', lts='partial',
                    reason='Neo4j\'s obfuscated description.', lts_reason='The standard description stands in.'),
            setting('db.logs.query.annotation_data_as_json_enabled', None, 'false', calendar='unsupported',
                    reason='Not read; use db.logs.query.annotation_data_format.'),
        ],
        'fields': [
            field('level', 'common', 'level', {'segment': '<level>', 'position': -1, 'separator': 'layout'},
                  reason='Written by Neo4j\'s layout.'),
            field('id', 'query', 'id', {'segment': 'id:<id>', 'position': 1, 'separator': ' - '}, levels=('VERBOSE',),
                  reason='The query id at VERBOSE only.'),
            field('planning', 'query', 'planning',
                  {'segment': '(planning: <planning>, waiting: <waiting>)', 'position': 4, 'separator': ': '},
                  reason='Compilation time | always.'),
            field('cpu', 'query', 'cpu', None, entries=('success', 'fail'), optional=True, calendar='partial',
                  examples=('end-cpu',), reason='JSON only, with db.track_query_cpu_time=true.'),
            field('queryLang', 'query', 'queryLang', None, lts='not-applicable', reason='CYPHER 5 or CYPHER 25.',
                  lts_reason='Neo4j 5.26 has no query language accessor.'),
            field('transaction.openTimeMs', 'transaction', None,
                  {'segment': '<openTimeMs> ms', 'position': 2, 'separator': ' - '}, entries=('start', 'commit', 'rollback'),
                  calendar='partial', examples=('tx-commit',), reason='Plain text only, DozerDB\'s shape.'),
        ],
        'examples': [
            {'id': 'end-cpu', 'kind': 'success', 'level': 'INFO', 'detailLevel': 'VERBOSE',
             'source': 'DozerDB\'s cpu field: the plain line is unchanged', 'stacktrace': False,
             'line': 'id:12 - transaction id:14 - 7 ms: (planning: 2, waiting: 0) - embedded-session\t\tneo4j',
             'record': {}},
            {'id': 'tx-commit', 'type': 'transaction', 'kind': 'commit', 'level': 'INFO', 'detailLevel': 'INFO',
             'source': 'DozerDB\'s transaction entries: a commit', 'stacktrace': False,
             'line': 'Transaction committed: transaction id:12 - 41 ms - embedded-session\t\tneo4j - neo4j - {}',
             'record': {}},
        ],
        'shapes': [
            {'id': 'cpu', 'title': '`cpu` only when Neo4j tracks it', 'rows': ['cpu'], 'examples': ['end-cpu'],
             'lines': [], 'text': 'JSON only; never in the plain line.'},
            {'id': 'transaction-entries', 'title': 'Transaction entries', 'rows': ['transaction.openTimeMs'],
             'examples': ['tx-commit'], 'lines': [], 'text': 'The plain-text line is DozerDB\'s.'},
            {'id': 'fallback-entry', 'title': 'Fallback entry', 'rows': [], 'examples': [],
             'lines': ['Query log entry could not be written: id:34 - java.lang.IllegalStateException: renderer broke'],
             'text': 'Written in place of an entry that could not be rendered or written.'},
        ],
    }


class ReleaseLineTests(unittest.TestCase):
    def test_the_line_follows_the_pinned_neo4j_version(self):
        self.assertEqual(docs.release_line('2026.10.1'), 'calendar')
        self.assertEqual(docs.release_line('2027.01.0'), 'calendar')
        self.assertEqual(docs.release_line('5.26.32'), 'lts')

    def test_the_manual_version_is_the_lines(self):
        self.assertEqual(docs.manual_version(inventory(), 'calendar'), '2026.10')
        self.assertEqual(docs.manual_version(inventory(), 'lts'), '5')


class InventoryReadingTests(unittest.TestCase):
    def test_the_inventory_is_read_and_a_missing_array_is_an_error(self):
        data = docs.read_inventory(json.dumps(inventory()))
        self.assertEqual([s['name'] for s in data['settings']][:1], ['db.logs.query.enabled'])
        without = inventory()
        del without['settings']
        with self.assertRaisesRegex(ValueError, 'settings'):
            docs.read_inventory(json.dumps(without))

    def test_a_tier_outside_the_vocabulary_is_an_error(self):
        broken = inventory()
        broken['fields'][0]['status']['lts']['tier'] = 'mostly'
        with self.assertRaisesRegex(ValueError, "level.*mostly"):
            docs.read_inventory(json.dumps(broken))


class RenderingTests(unittest.TestCase):
    def test_version_banner_names_the_manual_and_the_line_without_a_link(self):
        self.assertEqual(docs.render_version('2026.10', '2026.10.1'),
                         'This page tracks the Neo4j Operations Manual **2026.10** for the DozerDB **2026.10** release '
                         'line: DozerDB releases built on Neo4j `2026.10.1` (`2026.10.1-<dozerdb-version>`).\n')
        self.assertEqual(docs.render_version('5', '5.26.32'),
                         'This page tracks the Neo4j Operations Manual **5** for the DozerDB **5.26** release line: '
                         'DozerDB releases built on Neo4j `5.26.32` (`5.26.32-<dozerdb-version>`).\n')

    def test_settings_table_shows_the_lines_default_and_the_dozerdb_column(self):
        table = docs.render_settings(inventory(), 'calendar')
        self.assertIn('| Setting | Default | Dynamic | DozerDB |\n| --- | --- | --- | --- |\n', table)
        self.assertIn('| `db.logs.query.enabled` | `VERBOSE` | yes | VERBOSE writes a start entry and an end entry; '
                      'OFF costs one read per event. |\n', table)
        self.assertIn('| `db.logs.query.annotation_data_format` | `JSON` | yes | JSON is the default of this line. |\n', table)
        self.assertIn('| `db.logs.query.annotation_data_as_json_enabled` | - | - | Not read; use '
                      '`db.logs.query.annotation_data_format`. |\n', table)
        lts = docs.render_settings(inventory(), 'lts')
        self.assertIn('| `db.logs.query.annotation_data_format` | `CYPHER` | yes | CYPHER is the default of this line. |\n', lts)
        self.assertIn('| `db.logs.query.annotation_data_as_json_enabled` | `false` | yes | Not read; use '
                      '`db.logs.query.annotation_data_format`. |\n', lts)

    def test_settings_matrix_carries_the_tier_of_the_line(self):
        calendar = docs.render_settings_matrix(inventory(), 'calendar')
        self.assertIn('| Setting | Default | Dynamic | Tier | Note |\n', calendar)
        self.assertIn('| `db.logs.query.obfuscate_errors` | `false` | yes | Compatible | Neo4j\'s obfuscated description. |\n',
                      calendar)
        self.assertIn('| `db.logs.query.annotation_data_as_json_enabled` | - | - | Unsupported | Not read; use '
                      '`db.logs.query.annotation_data_format`. |\n', calendar)
        lts = docs.render_settings_matrix(inventory(), 'lts')
        self.assertIn('| `db.logs.query.obfuscate_errors` | `false` | yes | Partial | The standard description stands in. |\n',
                      lts)

    def test_fields_matrix_has_a_section_per_entry_type_with_layout_appearance_and_tier(self):
        calendar = docs.render_fields_matrix(inventory(), 'calendar')
        every, rest = calendar.split('### Fields of a query entry\n')
        query, transaction = rest.split('### Fields of a transaction entry\n')
        self.assertIn('### Fields of every entry\n', every)
        self.assertIn('| Field | Layout | Appears in | Tier | Note |\n', every)
        self.assertIn('| `level` / `<level>` | both | start, success, fail (INFO, VERBOSE) | Compatible | Written by '
                      'Neo4j\'s layout. |\n', every)
        self.assertIn('| `id` / `id:<id>` | both | start, success, fail (VERBOSE) | Compatible | The query id at VERBOSE only. |\n',
                      query)
        self.assertIn('| `planning` / `(planning: <planning>, waiting: <waiting>)` | both | start, success, fail (INFO, '
                      'VERBOSE) | Compatible | Compilation time \\| always. |\n', query)
        self.assertIn('| `cpu` | json | success, fail (INFO, VERBOSE); optional | Partial | JSON only, with '
                      '`db.track_query_cpu_time=true`. |\n', query)
        self.assertIn('| `queryLang` | json | start, success, fail (INFO, VERBOSE) | Compatible | CYPHER 5 or CYPHER 25. |\n',
                      query)
        self.assertIn('| `<openTimeMs> ms` | plain | start, commit, rollback (INFO, VERBOSE) | Partial | Plain text only, '
                      'DozerDB\'s shape. |\n', transaction)
        lts = docs.render_fields_matrix(inventory(), 'lts')
        self.assertIn('| `queryLang` | json | start, success, fail (INFO, VERBOSE) | Not applicable | Neo4j 5.26 has no '
                      'query language accessor. |\n', lts)

    def test_tier_summary_counts_settings_and_fields_per_tier_of_the_line(self):
        summary = docs.render_tier_summary(inventory(), 'calendar', 'query-log-compatibility.md')
        self.assertIn('| Tier | Settings | Fields and segments | Meaning |\n', summary)
        self.assertIn('| Compatible | 3 | 4 |', summary)
        self.assertIn('| Partial | 0 | 2 |', summary)
        self.assertIn('| Unsupported | 1 | 0 |', summary)
        self.assertIn('| Not applicable | 0 | 0 |', summary)
        self.assertIn('| **All documented** | **4** | **6** | |', summary)
        self.assertIn('[Shapes DozerDB defines](query-log-compatibility.md#shapes-dozerdb-defines)', summary)
        self.assertIn('| Partial | 1 | 2 |', docs.render_tier_summary(inventory(), 'lts', ''))
        self.assertIn('| Not applicable | 0 | 1 |', docs.render_tier_summary(inventory(), 'lts', ''))
        self.assertIn('[Shapes DozerDB defines](#shapes-dozerdb-defines)', docs.render_tier_summary(inventory(), 'lts', ''))

    def test_shapes_section_lists_every_shape_with_its_rows_and_its_lines_in_code_blocks(self):
        shapes = docs.render_shapes(inventory(), 'calendar')
        self.assertIn('### `cpu` only when Neo4j tracks it\n\nJSON only; never in the plain line.\n\nRows: `cpu`.\n\n'
                      'DozerDB\'s cpu field: the plain line is unchanged (`INFO` at `VERBOSE`):\n\n```text\n'
                      'id:12 - transaction id:14 - 7 ms: (planning: 2, waiting: 0) - embedded-session\t\tneo4j\n```\n',
                      shapes)
        self.assertIn('### Transaction entries\n', shapes)
        self.assertIn('(`INFO` at `INFO`):\n\n```text\nTransaction committed:', shapes)
        self.assertIn('### Fallback entry\n\nWritten in place of an entry that could not be rendered or written.\n\n'
                      '```text\nQuery log entry could not be written: id:34 - java.lang.IllegalStateException: renderer broke\n'
                      '```\n', shapes)

    def test_a_shape_with_a_partial_setting_and_a_stack_trace_names_both(self):
        data = inventory()
        data['shapes'][0]['rows'].append('db.logs.query.obfuscate_errors')
        data['examples'][0]['stacktrace'] = True
        data['examples'][0]['level'] = 'ERROR'
        shapes = docs.render_shapes(data, 'lts')
        self.assertIn('Rows: `cpu`, `db.logs.query.obfuscate_errors`.\n', shapes)
        self.assertIn('(`ERROR` at `VERBOSE`, the stack trace on the lines after):', shapes)

    def test_a_partial_row_no_shape_shows_is_an_error(self):
        data = inventory()
        data['shapes'][1]['rows'] = []
        with self.assertRaisesRegex(ValueError, 'transaction.openTimeMs'):
            docs.render_shapes(data, 'calendar')
        data = inventory()
        data['settings'][2]['status']['lts']['tier'] = 'partial'
        with self.assertRaisesRegex(ValueError, 'obfuscate_errors'):
            docs.render_shapes(data, 'lts')
        self.assertIn('### Transaction entries', docs.render_shapes(data, 'calendar'))


class ProseTests(unittest.TestCase):
    def test_setting_names_are_backticked_and_a_procedure_call_is_not(self):
        self.assertEqual(docs.prose('Set db.logs.query.enabled=INFO, then CALL db.clearQueryCaches(); see db.track_query_cpu_time.'),
                         'Set `db.logs.query.enabled=INFO`, then CALL db.clearQueryCaches(); see `db.track_query_cpu_time`.')


class Neo4jComTests(unittest.TestCase):
    def test_a_neo4j_com_url_anywhere_on_a_page_is_reported_by_line(self):
        page = 'The Operations Manual 2026.10, *Query log* under Monitoring > Logging.\n' \
               'See [the manual](https://neo4j.com/docs/operations-manual/2026.10/monitoring/logging/).\n' \
               'and neo4j.com/docs again\n'
        self.assertEqual(docs.neo4j_com_problems(page), [
            'line 2: names neo4j.com; Neo4j\'s documentation is mentioned by manual version and page title, never linked',
            'line 3: names neo4j.com; Neo4j\'s documentation is mentioned by manual version and page title, never linked'])
        self.assertEqual(docs.neo4j_com_problems('Mentioned, never linked.\n'), [])


class CheckTests(unittest.TestCase):
    """`check(root)` over a tree laid out like the public tree (the two query-log pages and the monitoring README under
    docs/monitoring, which the generator finds by its page) with the committed inventory and the pinned Neo4j version."""

    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp())
        for relative in ('scripts/fixtures/query-log-entries.json', 'distribution/config.json'):
            (self.root / relative).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / relative, self.root / relative)
        self.directory = self.root / 'docs/monitoring'
        self.directory.mkdir(parents=True)
        for page, blocks in docs.PAGES.items():
            text = f'# {page}\n\n' + ''.join(f'<!-- BEGIN GENERATED: {b} -->\nold\n<!-- END GENERATED: {b} -->\n\n'
                                              for b in blocks)
            if page == 'README.md':
                text += ''.join(f'<!-- BEGIN GENERATED: {b} -->\nmetrics\n<!-- END GENERATED: {b} -->\n\n'
                                for b in docs.FOREIGN)
            (self.directory / page).write_text(text, encoding='utf-8')

    def test_stale_pages_are_reported_and_current_after_write(self):
        problems = docs.check(self.root)
        self.assertEqual(problems, [f'docs/monitoring/{page}: generated blocks are stale' for page in docs.PAGES])
        docs.write(self.root)
        self.assertEqual(docs.check(self.root), [])
        readme = (self.directory / 'README.md').read_text(encoding='utf-8')
        self.assertIn('<!-- BEGIN GENERATED: tier-summary -->\nmetrics\n', readme)
        self.assertIn('| Compatible |', readme)

    def test_an_edited_generated_block_fails_the_check(self):
        docs.write(self.root)
        page = self.directory / 'query-log-compatibility.md'
        page.write_text(page.read_text(encoding='utf-8').replace('| Compatible |', '| Compatible-ish |', 1), encoding='utf-8')
        self.assertEqual(docs.check(self.root),
                         ['docs/monitoring/query-log-compatibility.md: generated blocks are stale'])

    def test_a_neo4j_com_url_on_a_query_log_page_fails_the_check_and_the_readme_is_not_checked_for_it(self):
        docs.write(self.root)
        for page in ('query-logging.md', 'README.md'):
            path = self.directory / page
            path.write_text(path.read_text(encoding='utf-8') + 'https://neo4j.com/docs/\n', encoding='utf-8')
        [problem] = docs.check(self.root)
        self.assertRegex(problem, r"^docs/monitoring/query-logging\.md: line [0-9]+: names neo4j\.com; Neo4j's "
                                  r"documentation is mentioned by manual version and page title, never linked$")

    def test_the_pages_follow_the_pinned_line(self):
        config = self.root / 'distribution/config.json'
        data = json.loads(config.read_text())
        data['neo4jVersion'] = '5.26.32'
        config.write_text(json.dumps(data))
        docs.write(self.root)
        compatibility = (self.directory / 'query-log-compatibility.md').read_text(encoding='utf-8')
        self.assertIn('Operations Manual **5** for the DozerDB **5.26** release line', compatibility)
        self.assertIn('| `db.logs.query.annotation_data_format` | `CYPHER` |', compatibility)
        self.assertIn('| `queryLang` | json | start, success, fail (INFO, VERBOSE) | Not applicable |', compatibility)


class CommittedPagesTests(unittest.TestCase):
    def test_the_committed_query_log_pages_are_current(self):
        self.assertEqual(docs.check(), [], 'run python3 scripts/query-log-docs.py to regenerate the query log pages')


if __name__ == '__main__':
    unittest.main()
