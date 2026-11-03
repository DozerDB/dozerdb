"""The database metrics lifecycle a flow asserts through Prometheus, read from one shared fixture.

scripts/fixtures/database-metrics-lifecycle.json lists, per lifecycle step of a flow's database (created,
written, dropped), the PromQL a monitoring administrator would run and the outcome it must reach; its
description field states the semantics, so a flow written in another language evaluates the file alike. The
flows hold no PromQL of their own; scripts/cypher-flow.py checks the steps through this module and
scripts/browser-flow.mjs through its Node twin, scripts/metrics-lifecycle.mjs, which keeps the same rules and
messages: a change to the semantics is made in the fixture's description and in both modules.
"""
from dataclasses import dataclass
import json
import math
import re
import time
import urllib.error
import urllib.parse
import urllib.request


DATABASE = '${database}'
EXPECTATIONS = ('present', 'increased', 'absent')
FIXTURE_FIELDS = {'description', 'timeout_scrape_intervals', 'steps', 'assertions'}
STEP_FIELDS = {'name', 'when'}
ASSERTION_FIELDS = {'step', 'expect', 'query', 'description', 'since'}


class FixtureError(Exception):
    """The fixture is unreadable or a field is missing or wrong; the message names the field."""


@dataclass(frozen=True)
class Assertion:
    """One expectation of the fixture: at step, query (with ${database} in it) reaches expect; an increased
    expectation compares with the value the query had at the earlier step since."""
    step: str
    expect: str
    query: str
    description: str
    since: str = None


@dataclass(frozen=True)
class Fixture:
    steps: dict  # step name: when the flow reaches it, in the flow's order
    assertions: tuple
    timeout_scrape_intervals: float


def parse_fixture(data, flow_steps):
    """The fixture's assertions, checked field by field against the steps the flow runs, in its order."""
    def fields(value, where, allowed, required):
        path = (lambda name: f'{where}.{name}') if where else (lambda name: name)
        if not isinstance(value, dict):
            raise FixtureError(f'{where or "fixture"}: must be an object, found {value!r}')
        for name in value:
            if name not in allowed:
                raise FixtureError(f'{path(name)}: unknown field; known are {", ".join(sorted(allowed))}')
        for name in sorted(required):
            if name not in value:
                raise FixtureError(f'{path(name)}: missing')
        return value

    def string(value, where):
        if not isinstance(value, str) or not value:
            raise FixtureError(f'{where}: must be a non-empty string, found {value!r}')
        return value

    def items(value, where):
        if not isinstance(value, list) or not value:
            raise FixtureError(f'{where}: must be a non-empty list, found {value!r}')
        return value

    fields(data, '', FIXTURE_FIELDS, FIXTURE_FIELDS)
    timeout = data['timeout_scrape_intervals']
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or timeout <= 0:
        raise FixtureError(f'timeout_scrape_intervals: must be a positive number, found {timeout!r}')
    steps = {}
    for index, step in enumerate(items(data['steps'], 'steps')):
        where = f'steps[{index}]'
        fields(step, where, STEP_FIELDS, STEP_FIELDS)
        steps[string(step['name'], f'{where}.name')] = string(step['when'], f'{where}.when')
    if tuple(steps) != tuple(flow_steps):
        raise FixtureError(f'steps: must be the steps the flow runs, in its order: {", ".join(flow_steps)}; '
                           f'found {", ".join(steps)}')
    order = list(steps)
    assertions = []
    for index, raw in enumerate(items(data['assertions'], 'assertions')):
        where = f'assertions[{index}]'
        fields(raw, where, ASSERTION_FIELDS, ASSERTION_FIELDS - {'since'})
        step = string(raw['step'], f'{where}.step')
        if step not in steps:
            raise FixtureError(f'{where}.step: {step!r} is not one of the steps {", ".join(order)}')
        expect = string(raw['expect'], f'{where}.expect')
        if expect not in EXPECTATIONS:
            raise FixtureError(f'{where}.expect: {expect!r} is not one of {", ".join(EXPECTATIONS)}')
        query = string(raw['query'], f'{where}.query')
        if DATABASE not in query:
            raise FixtureError(f'{where}.query: must name the flow database as {DATABASE}, found {query!r}')
        since = raw.get('since')
        if expect == 'increased':
            if since is None:
                raise FixtureError(f'{where}.since: missing; increased compares with an earlier step')
            if string(since, f'{where}.since') not in steps or order.index(since) >= order.index(step):
                raise FixtureError(f'{where}.since: {since!r} is not a step earlier than {step!r}')
        elif since is not None:
            raise FixtureError(f'{where}.since: only an increased expectation compares with an earlier step')
        assertions.append(Assertion(step, expect, query, string(raw['description'], f'{where}.description'),
                                    since))
    return Fixture(steps, tuple(assertions), timeout)


def load_fixture(path, flow_steps):
    """The fixture in a file; an error names the file and then the field."""
    try:
        with open(path, encoding='utf-8') as handle:
            return parse_fixture(json.load(handle), flow_steps)
    except json.JSONDecodeError as exc:
        raise FixtureError(f'{path}: not JSON: {exc}') from None
    except FixtureError as exc:
        raise FixtureError(f'{path}: {exc}') from None


def bind(query, database):
    """The query for one database: ${database} replaced by the database name in the form its metric names carry
    in Prometheus, every character outside [A-Za-z0-9:] replaced by _ (my-db.v2 is my_db_v2)."""
    return query.replace(DATABASE, re.sub(r'[^A-Za-z0-9:]', '_', database))


def samples(data):
    """(labels, value) of every series in the data member of a Prometheus instant-query answer; a scalar is one
    series without labels."""
    if data['resultType'] == 'scalar':
        return [({}, data['result'][1])]
    return [(series['metric'], series['value'][1]) for series in data['result']]


def total(data):
    """The sum of the answer's sample values, None when it holds no series."""
    values = [float(value) for _, value in samples(data)]
    return sum(values) if values else None


def numeric_total(data):
    """The total of the answer when it is a number: None without a series or when it is NaN."""
    value = total(data)
    return None if value is None or math.isnan(value) else value


def met(expect, data, baseline=None):
    """Whether a Prometheus answer reaches the expectation; increased compares its total with baseline."""
    if expect == 'present':
        return bool(samples(data))
    if expect == 'absent':
        return not samples(data)
    value = numeric_total(data)
    return value is not None and value > baseline


def scraped_after(moment):
    """Whether an answer to timestamp(<query>) shows every series scraped after moment (Prometheus time)."""
    return lambda data: bool(samples(data)) and all(float(value) > moment for _, value in samples(data))


SHOWN = 3


def answer(data):
    """What Prometheus returned, as a failure reports it: the number of series and the first few in exposition
    form."""
    if data['resultType'] == 'scalar':
        return f'scalar {data["result"][1]}'
    found = samples(data)
    if not found:
        return 'no series'

    def series(labels, value):
        others = ', '.join(f'{name}="{text}"' for name, text in sorted(labels.items()) if name != '__name__')
        return f'{labels.get("__name__", "")}{{{others}}} {value}'

    text = f'{len(found)} series: ' + ', '.join(series(*sample) for sample in found[:SHOWN])
    return text + (f' and {len(found) - SHOWN} more' if len(found) > SHOWN else '')


class PrometheusError(Exception):
    """Prometheus did not answer with data; waiting may help."""


class QueryRejected(PrometheusError):
    """Prometheus answered the query with an error of the query itself (bad_data): waiting cannot help."""


@dataclass(frozen=True)
class Result:
    """The outcome of one assertion at a step, or of recording a baseline, as a line for the flow's output."""
    passed: bool
    message: str


class Lifecycle:
    """Checks the fixture's assertions for one database, step by step, against one Prometheus.

    query(promql) returns the data member of Prometheus's instant-query answer, raises QueryRejected when
    Prometheus rejects the query and anything else when it cannot be asked. Each assertion is polled every half
    scrape interval until it is met or timeout_scrape_intervals scrape intervals have passed."""

    def __init__(self, fixture, database, query, scrape_interval, clock=time.monotonic, sleep=time.sleep):
        self.fixture = fixture
        self.database = database
        self.query = query
        self.timeout = fixture.timeout_scrape_intervals * scrape_interval
        self.interval = scrape_interval / 2
        self.clock = clock
        self.sleep = sleep
        self.baselines = {}  # assertion: total its query returned at its since step

    def check(self, step):
        """Check every assertion of the step, then record the baselines later increases compare with."""
        results = [self.assert_one(assertion) for assertion in self.fixture.assertions if assertion.step == step]
        results += [self.record_baseline(assertion) for assertion in self.fixture.assertions
                    if assertion.since == step]
        return results

    def poll(self, query, done):
        """Ask query until done(answer); return (answer, None) or, at the bound, (last answer, what went wrong,
        worded to follow the expectation). A rejected query ends at once."""
        deadline = self.clock() + self.timeout
        answer = error = None
        while True:
            try:
                answer, error = self.query(query), None
                if done(answer):
                    return answer, None
            except QueryRejected as exc:
                return None, f', but Prometheus rejected {query}: {exc}'
            except Exception as exc:  # Prometheus not reachable or not answering: retried until the bound
                error = exc
            remaining = deadline - self.clock()
            if remaining <= 0:
                waited = (f' within {self.timeout:g} s ({self.fixture.timeout_scrape_intervals:g} scrape intervals '
                          f'of {self.interval * 2:g} s)')
                if error is not None:
                    return answer, f'{waited}: Prometheus could not be asked {query}: {error}'
                return answer, f'{waited}: {query} returned {answer_text(answer)}'
            self.sleep(min(self.interval, remaining))

    def heading(self, assertion):
        return f'metrics {assertion.step} ({self.fixture.steps[assertion.step]}): {assertion.description}'

    def assert_one(self, assertion):
        query = bind(assertion.query, self.database)
        baseline = None
        if assertion.expect == 'increased':
            if assertion not in self.baselines:
                return Result(False, f'{self.heading(assertion)}: expected increased, but no baseline of {query} '
                                     f'was recorded at {assertion.since}')
            baseline = self.baselines[assertion]
        expected = assertion.expect + (f' above {baseline:g} recorded at {assertion.since}' if baseline is not None
                                       else '')
        answer, problem = self.poll(query, lambda data: met(assertion.expect, data, baseline))
        if problem:
            return Result(False, f'{self.heading(assertion)}: expected {expected}{problem}')
        seen = f'{len(samples(answer))} series' + (f' totalling {total(answer):g}' if baseline is not None else '')
        return Result(True, f'{self.heading(assertion)}: {expected}: {query} returned {seen}')

    def record_baseline(self, assertion):
        """Record the total the assertion's query has at its since step, from a scrape Prometheus made after the
        flow reached the step: Prometheus's time() then, every series of the query scraped after it, its value."""
        query = bind(assertion.query, self.database)
        heading = (f'metrics {assertion.since} ({self.fixture.steps[assertion.since]}): baseline for '
                   f'{assertion.step}, {assertion.description}')
        reached, problem = self.poll('time()', lambda data: numeric_total(data) is not None)
        if not problem:
            _, problem = self.poll(f'timestamp({query})', scraped_after(numeric_total(reached)))
        if not problem:
            answer, problem = self.poll(query, lambda data: numeric_total(data) is not None)
        if problem:
            return Result(False, f'{heading}: expected a value scraped after the step{problem}')
        self.baselines[assertion] = total(answer)
        return Result(True, f'{heading}: {query} = {total(answer):g}')


def answer_text(data):
    return answer(data) if data is not None else 'nothing'


UNITS = {'y': 365 * 86400, 'w': 7 * 86400, 'd': 86400, 'h': 3600, 'm': 60, 's': 1, 'ms': 0.001}
DURATION = re.compile(r'(?:(\d+)y)?(?:(\d+)w)?(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?')


def duration(text):
    """Seconds in a duration as Prometheus writes it (2s, 500ms, 1m30s)."""
    match = DURATION.fullmatch(text)
    if not text or not match:
        raise ValueError(f'not a Prometheus duration: {text!r}')
    return sum(int(count) * seconds for count, seconds in zip(match.groups(), UNITS.values()) if count)


def scrape_interval(targets):
    """The longest scrape interval of the active targets in an /api/v1/targets answer, in seconds: within it,
    every target the database's metrics can come from has been scraped once."""
    intervals = [duration(target['scrapeInterval']) for target in targets['data']['activeTargets']]
    if not intervals:
        raise PrometheusError('Prometheus has no active scrape target, so it cannot see the database')
    return max(intervals)


def interpret(status, body):
    """The data member of an /api/v1/query answer with HTTP status and body; QueryRejected for a query
    Prometheus cannot parse, PrometheusError for anything else that is not data."""
    try:
        document = json.loads(body)
    except ValueError:
        raise PrometheusError(f'HTTP {status}: {body[:200].decode(errors="replace")}') from None
    if document.get('status') == 'success':
        return document['data']
    error = f'{document.get("errorType", f"HTTP {status}")}: {document.get("error", "")}'
    raise QueryRejected(error) if document.get('errorType') == 'bad_data' else PrometheusError(error)


def http(url, timeout=10):
    """Status and body of a GET, an error status included."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def prometheus(url):
    """query(promql) and the scrape interval of the Prometheus at url, for a Lifecycle."""
    status, body = http(f'{url}/api/v1/targets?state=active')
    interval = scrape_interval(json.loads(body)) if status == 200 else None
    if interval is None:
        raise PrometheusError(f'{url}/api/v1/targets answered HTTP {status}')

    def query(promql):
        return interpret(*http(f'{url}/api/v1/query?' + urllib.parse.urlencode({'query': promql})))

    return query, interval
