"""Shape and content of scripts/fixtures/dashboard-promql.json, the PromQL fixture of the Prometheus release gate.

The expected counts were recorded when the dashboards were analysed, not derived from the file.
"""
import json
import pathlib
import re
import unittest

FIXTURE = pathlib.Path(__file__).resolve().parents[1] / 'fixtures' / 'dashboard-promql.json'

TOP_LEVEL_KEYS = {'description', 'generated_from', 'selection_rule', 'expectation_tags',
                  'dashboards', 'expressions'}
DASHBOARD_KEYS = {'id', 'revision', 'title', 'uid', 'author_org', 'author_slug', 'downloads_all_revisions',
                  'created_at', 'updated_at', 'page_url', 'download_url', 'json_sha256', 'name_form_era',
                  'template_variables'}
EXPRESSION_KEYS = {'dashboard', 'revision', 'panel', 'panel_type', 'ref_id', 'expr', 'metric_names',
                   'name_regexes', 'expr_2026_10_form', 'names_2026_10_form', 'verbatim_is_2026_10_form',
                   'expect'}
EXPECTATION_TAGS = {'data-with-default-filter', 'data-only-if-filter-widened', 'not-applicable-clustering',
                    'not-a-neo4j-metric', 'stale-name-no-2026-10-equivalent', 'regex-matches-nothing'}
# Recorded when the dashboards were analysed: tag counts per dashboard revision, and expressions already in 2026.10 form.
RESEARCH_COUNTS = {
    (10371, 1): {'data-with-default-filter': 43, 'data-only-if-filter-widened': 19,
                 'not-applicable-clustering': 4, 'not-a-neo4j-metric': 3,
                 'stale-name-no-2026-10-equivalent': 1, 'regex-matches-nothing': 0},
    (12046, 2): {'data-with-default-filter': 41, 'data-only-if-filter-widened': 17,
                 'not-applicable-clustering': 0, 'not-a-neo4j-metric': 1,
                 'stale-name-no-2026-10-equivalent': 0, 'regex-matches-nothing': 1},
    (12046, 1): {'data-with-default-filter': 39, 'data-only-if-filter-widened': 19,
                 'not-applicable-clustering': 0, 'not-a-neo4j-metric': 1,
                 'stale-name-no-2026-10-equivalent': 1, 'regex-matches-nothing': 0},
}
RESEARCH_VERBATIM_IN_2026_10_FORM = {(10371, 1): (4, 70), (12046, 2): (33, 60), (12046, 1): (2, 60)}
# A 2026.10 Prometheus name: <prefix>_dbms_<metric> or <prefix>_database_<db>_<metric>, sanitised.
NAME_2026_10 = re.compile(r'^neo4j_(dbms|database)_[A-Za-z0-9_:]+$')
SHA256 = re.compile(r'^[0-9a-f]{64}$')


def load():
    with FIXTURE.open(encoding='utf-8') as handle:
        return json.load(handle)


class ShapeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = load()

    def test_top_level_shape_and_self_description(self):
        self.assertEqual(set(self.fixture), TOP_LEVEL_KEYS)
        for field in ('expressions', 'expr_2026_10_form', 'names_2026_10_form', 'expect', 'expectation_tags'):
            self.assertIn(field, self.fixture['description'], f'the description must explain {field}')

    def test_expectation_tag_vocabulary(self):
        self.assertEqual(set(self.fixture['expectation_tags']), EXPECTATION_TAGS)
        for tag, meaning in self.fixture['expectation_tags'].items():
            self.assertTrue(meaning.strip(), tag)

    def test_dashboards_are_the_researched_revisions(self):
        dashboards = self.fixture['dashboards']
        self.assertEqual([(d['id'], d['revision']) for d in dashboards], [(10371, 1), (12046, 2), (12046, 1)])
        for dashboard in dashboards:
            self.assertEqual(set(dashboard), DASHBOARD_KEYS, dashboard['id'])
            self.assertRegex(dashboard['json_sha256'], SHA256)
            self.assertTrue(dashboard['download_url'].startswith('https://grafana.com/api/dashboards/'))
            self.assertTrue(dashboard['name_form_era'].startswith('Neo4j '))
            for variable in dashboard['template_variables']:
                self.assertEqual(set(variable), {'name', 'type', 'query', 'regex'})

    def test_every_expression_is_well_formed(self):
        known = {(d['id'], d['revision']) for d in self.fixture['dashboards']}
        for expression in self.fixture['expressions']:
            with self.subTest(expr=expression['expr']):
                self.assertEqual(set(expression), EXPRESSION_KEYS)
                self.assertIn((expression['dashboard'], expression['revision']), known)
                self.assertIn(expression['expect'], EXPECTATION_TAGS)
                self.assertTrue(expression['expr'].strip())
                self.assertTrue(expression['panel'].strip())
                self.assertIsInstance(expression['verbatim_is_2026_10_form'], bool)
                self.assertIsInstance(expression['metric_names'], list)
                self.assertIsInstance(expression['name_regexes'], list)
                self.assertIsInstance(expression['names_2026_10_form'], list)

    def test_regex_expressions_carry_names_instead_of_a_rewrite(self):
        for expression in self.fixture['expressions']:
            with self.subTest(expr=expression['expr']):
                if expression['name_regexes']:
                    self.assertIsNone(expression['expr_2026_10_form'])
                else:
                    self.assertIsInstance(expression['expr_2026_10_form'], str)
                    for name in expression['names_2026_10_form']:
                        self.assertIn(name, expression['expr_2026_10_form'])

    def test_data_expectations_name_2026_10_metrics(self):
        for expression in self.fixture['expressions']:
            with self.subTest(expr=expression['expr']):
                if expression['expect'].startswith('data-') or expression['expect'] == 'not-applicable-clustering':
                    self.assertTrue(expression['names_2026_10_form'])
                for name in expression['names_2026_10_form']:
                    self.assertRegex(name, NAME_2026_10)

    def test_verbatim_flag_means_no_rewrite_was_needed(self):
        for expression in self.fixture['expressions']:
            with self.subTest(expr=expression['expr']):
                if expression['verbatim_is_2026_10_form'] and expression['expr_2026_10_form'] is not None:
                    self.assertEqual(expression['expr_2026_10_form'], expression['expr'])


class ResearchAgreementTests(unittest.TestCase):
    """The fixture carries exactly the expressions and expectations the research recorded."""

    @classmethod
    def setUpClass(cls):
        cls.fixture = load()

    def by_dashboard(self):
        groups = {}
        for expression in self.fixture['expressions']:
            groups.setdefault((expression['dashboard'], expression['revision']), []).append(expression)
        return groups

    def test_expectation_counts_match_the_recorded_analysis(self):
        for key, expressions in self.by_dashboard().items():
            counts = {tag: sum(1 for e in expressions if e['expect'] == tag) for tag in EXPECTATION_TAGS}
            self.assertEqual(counts, RESEARCH_COUNTS[key], key)

    def test_expression_totals_and_verbatim_counts_match_the_research(self):
        for key, expressions in self.by_dashboard().items():
            verbatim, total = RESEARCH_VERBATIM_IN_2026_10_FORM[key]
            self.assertEqual(len(expressions), total, key)
            self.assertEqual(sum(1 for e in expressions if e['verbatim_is_2026_10_form']), verbatim, key)


if __name__ == '__main__':
    unittest.main()
