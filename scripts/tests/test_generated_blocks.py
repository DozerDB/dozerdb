"""The generated-block module, scripts/generated_blocks.py: rewriting the `BEGIN/END GENERATED` blocks of a
hand-written page, and the `--check` comparison of the committed pages with what the generator would write."""
import contextlib
import io
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import generated_blocks  # noqa: E402  (a module beside the scripts, found through the path above)

PAGE = ('# Title\n\nHand-written.\n\n<!-- BEGIN GENERATED: matrix -->\nold\n<!-- END GENERATED: matrix -->\n\n'
        'More hand-written text.\n')


class UpdatePageTests(unittest.TestCase):
    def test_a_generated_block_is_replaced_and_the_rest_is_kept(self):
        self.assertEqual(generated_blocks.update_page(PAGE, {'matrix': 'new\n'}),
                         '# Title\n\nHand-written.\n\n<!-- BEGIN GENERATED: matrix -->\nnew\n'
                         '<!-- END GENERATED: matrix -->\n\nMore hand-written text.\n')


class ForeignBlockTests(unittest.TestCase):
    """A page two generators write (the monitoring README) carries blocks of each; a generator names the other's as
    foreign and leaves them as they are, while a block neither owns is still an error."""

    def test_each_generators_foreign_blocks_are_the_other_generators_shared_blocks(self):
        self.assertEqual(generated_blocks.foreign_blocks('metrics'), ('query-log-tier-summary',))
        self.assertEqual(generated_blocks.foreign_blocks('query-log'), ('version', 'tier-summary'))
        self.assertEqual(set(generated_blocks.foreign_blocks('metrics')) | set(generated_blocks.SHARED_PAGE_BLOCKS['metrics']),
                         {b for blocks in generated_blocks.SHARED_PAGE_BLOCKS.values() for b in blocks})
    SHARED = ('# R\n\n<!-- BEGIN GENERATED: version -->\nv\n<!-- END GENERATED: version -->\n\n'
              '<!-- BEGIN GENERATED: query-log-tier-summary -->\nq\n<!-- END GENERATED: query-log-tier-summary -->\n')

    def test_a_foreign_block_is_left_as_it_is(self):
        updated = generated_blocks.update_page(self.SHARED, {'version': 'new\n'}, foreign=('query-log-tier-summary',))
        self.assertIn('<!-- BEGIN GENERATED: version -->\nnew\n', updated)
        self.assertIn('<!-- BEGIN GENERATED: query-log-tier-summary -->\nq\n', updated)

    def test_a_block_neither_owned_nor_foreign_is_an_error(self):
        with self.assertRaisesRegex(ValueError, "unknown \\['query-log-tier-summary'\\]"):
            generated_blocks.update_page(self.SHARED, {'version': 'new\n'})

    def test_check_and_write_pass_the_foreign_blocks_on(self):
        root = pathlib.Path(tempfile.mkdtemp())
        directory = root / 'docs/monitoring'
        directory.mkdir(parents=True)
        (directory / 'README.md').write_text(self.SHARED, encoding='utf-8')
        pages = {'README.md': {'query-log-tier-summary': 'new\n'}}
        self.assertEqual(generated_blocks.check_pages(directory, pages, root, foreign=('version',)),
                         ['docs/monitoring/README.md: generated blocks are stale'])
        with contextlib.redirect_stdout(io.StringIO()):
            generated_blocks.write_pages(directory, pages, root, foreign=('version',))
        self.assertEqual(generated_blocks.check_pages(directory, pages, root, foreign=('version',)), [])
        self.assertIn('<!-- BEGIN GENERATED: version -->\nv\n', (directory / 'README.md').read_text(encoding='utf-8'))


class CheckTests(unittest.TestCase):
    """`--check`: every reason the committed pages differ from what the generator would write, as `path: reason`
    lines relative to the repository root; empty when the pages are current."""

    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.directory = self.root / 'docs/monitoring'
        self.directory.mkdir(parents=True)
        (self.directory / 'a.md').write_text(PAGE, encoding='utf-8')
        (self.directory / 'b.md').write_text('# B\n\n<!-- BEGIN GENERATED: x -->\nold\n<!-- END GENERATED: x -->\n',
                                             encoding='utf-8')

    def test_current_pages_have_no_problems(self):
        pages = {'a.md': {'matrix': 'old\n'}, 'b.md': {'x': 'old\n'}}
        self.assertEqual(generated_blocks.check_pages(self.directory, pages, self.root), [])

    def test_a_stale_block_and_a_structural_error_are_reported_by_page(self):
        pages = {'a.md': {'matrix': 'new\n'}, 'b.md': {'x': 'old\n', 'y': 'y\n'}}
        self.assertEqual(generated_blocks.check_pages(self.directory, pages, self.root), [
            'docs/monitoring/a.md: generated blocks are stale',
            "docs/monitoring/b.md: generated blocks: missing ['y'], unknown [], found ['x']"])

    def test_a_generator_adds_its_own_page_checks_before_the_block_comparison(self):
        pages = {'a.md': {'matrix': 'new\n'}, 'b.md': {'x': 'old\n'}}
        also = lambda text: ['hand-written part wrong'] if 'Hand-written' in text else []
        self.assertEqual(generated_blocks.check_pages(self.directory, pages, self.root, also), [
            'docs/monitoring/a.md: hand-written part wrong',
            'docs/monitoring/a.md: generated blocks are stale'])


class WriteTests(unittest.TestCase):
    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.directory = self.root / 'docs/monitoring'
        self.directory.mkdir(parents=True)
        (self.directory / 'a.md').write_text(PAGE, encoding='utf-8')
        (self.directory / 'b.md').write_text('# B\n\n<!-- BEGIN GENERATED: x -->\nold\n<!-- END GENERATED: x -->\n',
                                             encoding='utf-8')

    def test_stale_pages_are_rewritten_and_named_current_ones_untouched(self):
        pages = {'a.md': {'matrix': 'new\n'}, 'b.md': {'x': 'old\n'}}
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            generated_blocks.write_pages(self.directory, pages, self.root)
        self.assertEqual(out.getvalue(), 'updated docs/monitoring/a.md\n')
        self.assertEqual((self.directory / 'a.md').read_text(encoding='utf-8'),
                         '# Title\n\nHand-written.\n\n<!-- BEGIN GENERATED: matrix -->\nnew\n'
                         '<!-- END GENERATED: matrix -->\n\nMore hand-written text.\n')
        self.assertEqual(generated_blocks.check_pages(self.directory, pages, self.root), [])


class CommandLineTests(unittest.TestCase):
    """The command line both generators share: no argument rewrites then checks, --check only checks."""

    def run_main(self, argv, problems, written):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = generated_blocks.main(argv, 'Generate the pages.', check=lambda: problems,
                                           write=lambda: written.append('written'),
                                           script='scripts/x-docs.py', pages='x pages')
        return status, out.getvalue(), err.getvalue()

    def test_check_of_current_pages_passes_without_writing(self):
        written = []
        status, out, err = self.run_main(['--check'], [], written)
        self.assertEqual((status, out, err, written), (0, 'x pages are current\n', '', []))

    def test_check_of_stale_pages_fails_naming_the_problems_and_the_generator(self):
        written = []
        status, out, err = self.run_main(['--check'], ['docs/a.md: generated blocks are stale'], written)
        self.assertEqual(status, 1)
        self.assertEqual(err, 'docs/a.md: generated blocks are stale\n'
                              'run python3 scripts/x-docs.py to regenerate the x pages\n')
        self.assertEqual(written, [])

    def test_without_check_the_pages_are_written_then_checked(self):
        written = []
        status, out, err = self.run_main([], ['x.md: still wrong'], written)
        self.assertEqual((status, err, written), (1, 'x.md: still wrong\n', ['written']))
        self.assertEqual(self.run_main([], [], written)[0], 0)


if __name__ == '__main__':
    unittest.main()
