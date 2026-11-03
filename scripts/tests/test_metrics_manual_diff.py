"""Reading the Operations Manual's metrics reference and comparing it with the inventory (scripts/metrics-manual-diff.py)."""
import importlib.util
import io
import pathlib
import tempfile
import unittest
from contextlib import redirect_stdout

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'metrics-manual-diff.py'
spec = importlib.util.spec_from_file_location('metrics_manual_diff', SCRIPT)
diff = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diff)

# The reference page's markup (Antora/Asciidoctor at 2026.10), trimmed to the shapes the reader handles.
PAGE = """
<h2 id="metrics-global">Global metrics</h2>
<table><tr><td>Not a metric table</td><td>(counter)</td></tr></table>
<h3 id="bolt-metrics"><a class="anchor" href="#bolt-metrics"></a>Bolt metrics</h3>
<table class="tableblock"><caption class="title">Table 1. Bolt metrics</caption>
<thead><tr><th>Name</th><th>Description</th></tr></thead>
<tbody>
<tr><td><p><code>&lt;prefix&gt;.dbms.bolt.connections_opened</code></p></td>
<td><p>The total number of Bolt connections opened. (counter)</p></td></tr>
<tr><td><p><code>&lt;prefix&gt;.dbms.bolt.accumulated_queue_time</code></p></td>
<td><p>(unsupported feature) When <code>internal.server.bolt.thread_pool_queue_size</code> is enabled, the time. (counter)</p></td></tr>
</tbody></table>
<h3 id="db-data-metrics"><a class="anchor" href="#db-data-metrics"></a>Database data metrics</h3>
<p><span class="label label--deprecated">Deprecated in 5.15</span></p>
<table class="tableblock"><thead><tr><th>Name</th><th>Description</th></tr></thead>
<tbody><tr><td><p><code>&lt;prefix&gt;.ids_in_use.node</code></p></td>
<td><p>The total number of nodes. (gauge)</p></td></tr></tbody></table>
<h3 id="db-neo4j-pool-metrics">Database neo4j pools metrics</h3>
<table class="tableblock"><thead><tr><th>Name</th><th>Description</th></tr></thead>
<tbody><tr><td><p><code>&lt;prefix&gt;.database.&lt;database&gt;.pool.&lt;pool&gt;.&lt;database&gt;.free</code></p></td>
<td><p>Available unused memory. (gauge)</p></td></tr></tbody></table>
<h3 id="raft-metrics">Raft metrics</h3>
<table class="tableblock"><thead><tr><th>Name</th><th>Description</th></tr></thead>
<tbody><tr><td><p><code>&lt;prefix&gt;.cluster.raft.message_processing_timer</code></p></td>
<td><p><span class="label label--new">Introduced in 2025.07</span> Metrics Timer. (counter, histogram)</p></td></tr></tbody></table>
<h3 id="cpu-usage-metrics">CPU usage metrics<span class="label label--new">Introduced in 2025.01</span></h3>
<table class="tableblock"><thead><tr><th>Name</th><th>Description</th></tr></thead>
<tbody><tr><td><p><code>&lt;prefix&gt;.vm.cpu_load.system</code></p></td>
<td><p>The recent CPU load. (gauge)</p></td></tr></tbody></table>
"""

BASE = 'https://neo4j.com/docs/operations-manual/2026.10/monitoring/metrics/reference/'
HEADER = 'section\tname_template\ttype\tlifecycle\tdocs_anchor\tnotes'


def inventory(*rows):
    return '# comment\n' + HEADER + '\n' + ''.join('\t'.join(row) + '\n' for row in rows)


MATCHING = [
    ('Bolt metrics', '<prefix>.dbms.bolt.connections_opened', 'counter', '-', BASE + '#bolt-metrics', ''),
    ('Bolt metrics', '<prefix>.dbms.bolt.accumulated_queue_time', 'counter', 'unsupported feature', BASE + '#bolt-metrics', ''),
    ('Database data metrics', '<prefix>.ids_in_use.node', 'gauge', 'Deprecated in 5.15', BASE + '#db-data-metrics', ''),
    ('Database neo4j pools metrics', '<prefix>.database.<db>.pool.<pool>.<db>.free', 'gauge', '-', BASE + '#db-neo4j-pool-metrics', ''),
    ('Raft metrics', '<prefix>.cluster.raft.message_processing_timer', 'timer', 'Introduced in 2025.07', BASE + '#raft-metrics', ''),
    ('CPU usage metrics', '<prefix>.vm.cpu_load.system', 'gauge', 'Introduced in 2025.01', BASE + '#cpu-usage-metrics', ''),
]


class ReferencePageTests(unittest.TestCase):
    def test_reads_every_metric_row_with_section_type_lifecycle_and_anchor(self):
        rows = diff.page_rows(PAGE)
        self.assertEqual([(r['section'], r['name'], r['type'], r['lifecycle'], r['anchor']) for r in rows], [
            ('Bolt metrics', '<prefix>.dbms.bolt.connections_opened', 'counter', '-', 'bolt-metrics'),
            ('Bolt metrics', '<prefix>.dbms.bolt.accumulated_queue_time', 'counter', 'unsupported feature', 'bolt-metrics'),
            ('Database data metrics', '<prefix>.ids_in_use.node', 'gauge', 'Deprecated in 5.15', 'db-data-metrics'),
            ('Database neo4j pools metrics', '<prefix>.database.<db>.pool.<pool>.<db>.free', 'gauge', '-',
             'db-neo4j-pool-metrics'),
            ('Raft metrics', '<prefix>.cluster.raft.message_processing_timer', 'timer', 'Introduced in 2025.07',
             'raft-metrics'),
            ('CPU usage metrics', '<prefix>.vm.cpu_load.system', 'gauge', 'Introduced in 2025.01', 'cpu-usage-metrics'),
        ])


class DifferenceTests(unittest.TestCase):
    def test_a_matching_inventory_has_no_differences(self):
        self.assertEqual(diff.differences(diff.page_rows(PAGE), diff.inventory_rows(inventory(*MATCHING))), [])

    def test_added_removed_and_changed_metrics_are_each_reported(self):
        rows = list(MATCHING[1:])  # connections_opened is missing from the inventory
        rows[0] = rows[0][:2] + ('gauge',) + rows[0][3:]  # accumulated_queue_time typed differently
        rows.append(('Bolt metrics', '<prefix>.dbms.bolt.gone', 'counter', '-', BASE + '#bolt-metrics', ''))
        found = diff.differences(diff.page_rows(PAGE), diff.inventory_rows(inventory(*rows)))
        self.assertEqual(found, [
            'added in the manual: <prefix>.dbms.bolt.connections_opened (Bolt metrics, counter, -)',
            "changed <prefix>.dbms.bolt.accumulated_queue_time: type 'gauge' in the inventory, 'counter' in the manual",
            'removed from the manual: <prefix>.dbms.bolt.gone (Bolt metrics)',
        ])

    def test_the_inventorys_pinned_manual_version_is_read_from_its_anchors(self):
        self.assertEqual(diff.pinned_versions(diff.inventory_rows(inventory(*MATCHING))), ['2026.10'])


class MainTests(unittest.TestCase):
    def run_main(self, page, rows):
        with tempfile.TemporaryDirectory() as tmp:
            html, tsv = pathlib.Path(tmp, 'reference.html'), pathlib.Path(tmp, 'inventory.tsv')
            html.write_text(page, encoding='utf-8')
            tsv.write_text(inventory(*rows), encoding='utf-8')
            out = io.StringIO()
            with redirect_stdout(out):
                status = diff.main(['--html', str(html), '--inventory', str(tsv)])
        return status, out.getvalue()

    def test_passes_when_nothing_differs(self):
        status, out = self.run_main(PAGE, MATCHING)
        self.assertEqual(status, 0)
        self.assertTrue(out.rstrip().endswith('RESULT PASS'), out)

    def test_fails_when_something_differs(self):
        status, out = self.run_main(PAGE, MATCHING[1:])
        self.assertEqual(status, 1)
        self.assertIn('RESULT DIFF: 1 difference', out)
        self.assertIn('added in the manual: <prefix>.dbms.bolt.connections_opened', out)

    def test_a_page_without_metric_tables_fails(self):
        status, out = self.run_main('<html><body>moved</body></html>', MATCHING)
        self.assertEqual(status, 1)
        self.assertIn('no metric table found', out)


if __name__ == '__main__':
    unittest.main()
