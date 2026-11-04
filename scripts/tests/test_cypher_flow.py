"""The order in which scripts/cypher-flow.py runs the statements of its constraint fixture."""
import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('cypher_flow', SCRIPTS / 'cypher-flow.py')
cypher_flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cypher_flow)

FLOW = '''
// cleanup
MATCH (n) DETACH DELETE n;
DROP CONSTRAINT a IF EXISTS;

CREATE CONSTRAINT a
FOR (p:A) REQUIRE p.x IS NOT NULL;

// --- SHOULD FAIL (missing x)
CREATE (:A {y: 1});

// --- SHOULD PASS
CREATE (:A {x: 1});

CREATE CONSTRAINT b FOR (p:B) REQUIRE p.x IS UNIQUE;
create index c for (n:C) on (n.x);
// --- SHOULD FAIL (duplicate)
CREATE (:B {x: 1});

SHOW CONSTRAINTS;
'''


class StatementOrderTests(unittest.TestCase):
    """The schema statements run first, so the data writes the flow expects to be accepted are the only writes
    after the lifecycle's constrained step."""

    def test_schema_statements_come_first_and_each_part_keeps_the_file_order(self):
        schema, data = cypher_flow.schema_then_data(cypher_flow.parse_statements(FLOW))
        self.assertEqual([statement.split('\n')[0] for statement, _ in schema],
                         ['DROP CONSTRAINT a IF EXISTS;', 'CREATE CONSTRAINT a',
                          'CREATE CONSTRAINT b FOR (p:B) REQUIRE p.x IS UNIQUE;',
                          'create index c for (n:C) on (n.x);'])
        self.assertEqual([statement for statement, _ in data],
                         ['MATCH (n) DETACH DELETE n;', 'CREATE (:A {y: 1});', 'CREATE (:A {x: 1});',
                          'CREATE (:B {x: 1});', 'SHOW CONSTRAINTS;'])

    def test_every_statement_keeps_its_expected_outcome(self):
        _, data = cypher_flow.schema_then_data(cypher_flow.parse_statements(FLOW))
        self.assertEqual([should_fail for _, should_fail in data], [False, True, False, True, False])

    def test_the_maintainer_fixture_creates_every_constraint_before_its_first_write(self):
        schema, data = cypher_flow.schema_then_data(cypher_flow.parse_statements(cypher_flow.FLOW.read_text()))
        self.assertEqual(sum(statement.startswith('CREATE CONSTRAINT') for statement, _ in schema), 7)
        self.assertFalse(any('CONSTRAINT' in statement.split('\n')[0] and 'SHOW' not in statement
                             for statement, _ in data))


class FakeClock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class PollTests(unittest.TestCase):
    """The flow waits for a database's status by asking again, within a bound, never by a fixed sleep."""

    def test_the_answer_that_is_awaited_returns_as_soon_as_it_is_seen(self):
        clock = FakeClock()
        answers = iter([('offline', 'online'), ('offline', 'online'), ('offline', 'offline')])
        seen = cypher_flow.poll(lambda: next(answers), lambda status: status == ('offline', 'offline'),
                                seconds=60, interval=0.5, clock=clock, sleep=clock.sleep)
        self.assertEqual(seen, ('offline', 'offline'))
        self.assertEqual(clock.sleeps, [0.5, 0.5])

    def test_at_the_bound_the_last_answer_is_returned_for_the_flow_to_report(self):
        clock = FakeClock()
        seen = cypher_flow.poll(lambda: ('offline', 'online'), lambda status: status == ('offline', 'offline'),
                                seconds=2, interval=0.5, clock=clock, sleep=clock.sleep)
        self.assertEqual(seen, ('offline', 'online'))
        self.assertEqual(clock.now, 2.0)


if __name__ == '__main__':
    unittest.main()
