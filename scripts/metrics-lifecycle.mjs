// The database metrics lifecycle a flow asserts through Prometheus, read from one shared fixture.
//
// scripts/fixtures/database-metrics-lifecycle.json lists, per lifecycle step of a flow's database (created,
// written, dropped), the PromQL a monitoring administrator would run and the outcome it must reach; its
// description field states the semantics. This module evaluates the file for scripts/browser-flow.mjs exactly as
// scripts/metrics_lifecycle.py does for scripts/cypher-flow.py, with the same rules and the same messages, so
// the two flows check the same thing and neither holds PromQL of its own. A change to the semantics is made in
// the fixture's description and in both modules.
import { readFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

const DATABASE = '${database}';
const EXPECTATIONS = ['present', 'increased', 'absent'];
const FIXTURE_FIELDS = ['description', 'timeout_scrape_intervals', 'steps', 'assertions'];
const STEP_FIELDS = ['name', 'when'];
const ASSERTION_FIELDS = ['step', 'expect', 'query', 'description', 'since'];

/** The fixture is unreadable or a field is missing or wrong; the message names the field. */
export class FixtureError extends Error {}

/** Prometheus did not answer with data; waiting may help. */
export class PrometheusError extends Error {}

/** Prometheus answered the query with an error of the query itself (bad_data): waiting cannot help. */
export class QueryRejected extends PrometheusError {}

/** A value as the fixture errors of the Python module show it. */
function repr(value) {
  if (typeof value === 'string') return `'${value}'`;
  if (value === null || value === undefined) return 'None';
  if (value === true || value === false) return value ? 'True' : 'False';
  return JSON.stringify(value);
}

const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value);

/** The fixture's steps and assertions, checked field by field against the steps the flow runs, in its order. */
export function parseFixture(data, flowSteps) {
  function fields(value, where, allowed, required) {
    const path = (name) => (where ? `${where}.${name}` : name);
    if (!isObject(value)) throw new FixtureError(`${where || 'fixture'}: must be an object, found ${repr(value)}`);
    for (const name of Object.keys(value)) {
      if (!allowed.includes(name)) {
        throw new FixtureError(`${path(name)}: unknown field; known are ${[...allowed].sort().join(', ')}`);
      }
    }
    for (const name of [...required].sort()) {
      if (!(name in value)) throw new FixtureError(`${path(name)}: missing`);
    }
    return value;
  }
  function string(value, where) {
    if (typeof value !== 'string' || !value) {
      throw new FixtureError(`${where}: must be a non-empty string, found ${repr(value)}`);
    }
    return value;
  }
  function items(value, where) {
    if (!Array.isArray(value) || !value.length) {
      throw new FixtureError(`${where}: must be a non-empty list, found ${repr(value)}`);
    }
    return value;
  }

  fields(data, '', FIXTURE_FIELDS, FIXTURE_FIELDS);
  const timeout = data.timeout_scrape_intervals;
  if (typeof timeout !== 'number' || !(timeout > 0)) {
    throw new FixtureError(`timeout_scrape_intervals: must be a positive number, found ${repr(timeout)}`);
  }
  const steps = new Map();
  items(data.steps, 'steps').forEach((step, index) => {
    const where = `steps[${index}]`;
    fields(step, where, STEP_FIELDS, STEP_FIELDS);
    steps.set(string(step.name, `${where}.name`), string(step.when, `${where}.when`));
  });
  const order = [...steps.keys()];
  if (order.join('\n') !== flowSteps.join('\n')) {
    throw new FixtureError(`steps: must be the steps the flow runs, in its order: ${flowSteps.join(', ')}; ` +
      `found ${order.join(', ')}`);
  }
  const assertions = items(data.assertions, 'assertions').map((raw, index) => {
    const where = `assertions[${index}]`;
    fields(raw, where, ASSERTION_FIELDS, ASSERTION_FIELDS.filter((name) => name !== 'since'));
    const step = string(raw.step, `${where}.step`);
    if (!steps.has(step)) throw new FixtureError(`${where}.step: ${repr(step)} is not one of the steps ${order.join(', ')}`);
    const expect = string(raw.expect, `${where}.expect`);
    if (!EXPECTATIONS.includes(expect)) {
      throw new FixtureError(`${where}.expect: ${repr(expect)} is not one of ${EXPECTATIONS.join(', ')}`);
    }
    const query = string(raw.query, `${where}.query`);
    if (!query.includes(DATABASE)) {
      throw new FixtureError(`${where}.query: must name the flow database as ${DATABASE}, found ${repr(query)}`);
    }
    const since = raw.since ?? null;
    if (expect === 'increased') {
      if (since === null) throw new FixtureError(`${where}.since: missing; increased compares with an earlier step`);
      if (!steps.has(string(since, `${where}.since`)) || order.indexOf(since) >= order.indexOf(step)) {
        throw new FixtureError(`${where}.since: ${repr(since)} is not a step earlier than ${repr(step)}`);
      }
    } else if (since !== null) {
      throw new FixtureError(`${where}.since: only an increased expectation compares with an earlier step`);
    }
    return { step, expect, query, description: string(raw.description, `${where}.description`), since };
  });
  return { steps, assertions, timeoutScrapeIntervals: timeout };
}

/** The fixture in a file; an error names the file and then the field. */
export function loadFixture(path, flowSteps) {
  let data;
  try {
    data = JSON.parse(readFileSync(path, 'utf-8'));
  } catch (error) {
    throw new FixtureError(`${path}: ${error instanceof SyntaxError ? 'not JSON: ' : ''}${error.message}`);
  }
  try {
    return parseFixture(data, flowSteps);
  } catch (error) {
    if (error instanceof FixtureError) throw new FixtureError(`${path}: ${error.message}`);
    throw error;
  }
}

/** The query for one database: ${database} replaced by the database name in the form its metric names carry in
 * Prometheus, every character outside [A-Za-z0-9:] replaced by _ (my-db.v2 is my_db_v2). split and join, so no
 * $ pattern of a replacement string applies. */
export function bind(query, database) {
  return query.split(DATABASE).join(database.replace(/[^A-Za-z0-9:]/g, '_'));
}

/** [labels, value] of every series in the data member of a Prometheus instant-query answer; a scalar is one
 * series without labels. */
export function samples(data) {
  if (data.resultType === 'scalar') return [[{}, data.result[1]]];
  return data.result.map((series) => [series.metric, series.value[1]]);
}

/** A sample value as Prometheus writes it (a float, NaN, +Inf, -Inf). */
function sampleValue(value) {
  const infinite = /^([+-]?)inf(inity)?$/i.exec(String(value).trim());
  if (infinite) return infinite[1] === '-' ? -Infinity : Infinity;
  const parsed = Number(value);
  if (Number.isNaN(parsed) && !/^[+-]?nan$/i.test(String(value).trim())) throw new Error(`not a number: ${value}`);
  return parsed;
}

/** The sum of the answer's sample values, null when it holds no series. */
export function total(data) {
  const values = samples(data).map(([, value]) => sampleValue(value));
  return values.length ? values.reduce((sum, value) => sum + value, 0) : null;
}

/** The total of the answer when it is a number: null without a series or when it is NaN. */
export function numericTotal(data) {
  const value = total(data);
  return value === null || Number.isNaN(value) ? null : value;
}

/** Whether a Prometheus answer reaches the expectation; increased compares its total with baseline. */
export function met(expect, data, baseline = null) {
  if (expect === 'present') return samples(data).length > 0;
  if (expect === 'absent') return samples(data).length === 0;
  const value = numericTotal(data);
  return value !== null && value > baseline;
}

/** Whether an answer to timestamp(<query>) shows every series scraped after moment (Prometheus time). */
export function scrapedAfter(moment) {
  return (data) => samples(data).length > 0 && samples(data).every(([, value]) => sampleValue(value) > moment);
}

/** A number as Python's format spec g writes it: six significant digits, exponent form outside 1e-4 to 1e6. */
export function formatLikePythonG(value) {
  if (Number.isNaN(value)) return 'nan';
  if (!Number.isFinite(value)) return value > 0 ? 'inf' : '-inf';
  if (value === 0) return Object.is(value, -0) ? '-0' : '0';
  const strip = (text) => (text.includes('.') ? text.replace(/0+$/, '').replace(/\.$/, '') : text);
  const [mantissa, exponentText] = value.toExponential(5).split('e');
  const exponent = Number(exponentText);
  if (exponent < -4 || exponent >= 6) {
    return `${strip(mantissa)}e${exponent < 0 ? '-' : '+'}${String(Math.abs(exponent)).padStart(2, '0')}`;
  }
  return strip(value.toFixed(5 - exponent));
}

const SHOWN = 3;

/** What Prometheus returned, as a failure reports it: the number of series and the first few in exposition
 * form. */
export function answer(data) {
  if (data.resultType === 'scalar') return `scalar ${data.result[1]}`;
  const found = samples(data);
  if (!found.length) return 'no series';
  const series = (labels, value) => {
    const others = Object.keys(labels).filter((name) => name !== '__name__').sort()
      .map((name) => `${name}="${labels[name]}"`).join(', ');
    return `${labels.__name__ ?? ''}{${others}} ${value}`;
  };
  const text = `${found.length} series: ` + found.slice(0, SHOWN).map(([labels, value]) => series(labels, value)).join(', ');
  return text + (found.length > SHOWN ? ` and ${found.length - SHOWN} more` : '');
}

const answerText = (data) => (data !== null && data !== undefined ? answer(data) : 'nothing');

/** Checks the fixture's assertions for one database, step by step, against one Prometheus.
 *
 * query(promql) resolves to the data member of Prometheus's instant-query answer, rejects with QueryRejected when
 * Prometheus rejects the query and with anything else when it cannot be asked. Each assertion is polled every half
 * scrape interval until it is met or timeoutScrapeIntervals scrape intervals have passed. */
export class Lifecycle {
  constructor(fixture, database, query, scrapeInterval, { clock = () => performance.now() / 1000, wait = sleep } = {}) {
    this.fixture = fixture;
    this.database = database;
    this.query = query;
    this.timeout = fixture.timeoutScrapeIntervals * scrapeInterval;
    this.interval = scrapeInterval / 2;
    this.clock = clock;
    this.wait = (seconds) => wait(seconds * 1000);
    this.baselines = new Map(); // assertion: total its query returned at its since step
  }

  /** Check every assertion of the step, then record the baselines later increases compare with; resolves to
   * [{passed, message}]. */
  async check(step) {
    const results = [];
    for (const assertion of this.fixture.assertions.filter((a) => a.step === step)) {
      results.push(await this.assertOne(assertion));
    }
    for (const assertion of this.fixture.assertions.filter((a) => a.since === step)) {
      results.push(await this.recordBaseline(assertion));
    }
    return results;
  }

  /** Ask query until done(answer); resolve to [answer, null] or, at the bound, [last answer, what went wrong,
   * worded to follow the expectation]. A rejected query ends at once. */
  async poll(query, done) {
    const deadline = this.clock() + this.timeout;
    let data = null;
    let error = null;
    for (;;) {
      try {
        data = await this.query(query);
        error = null;
        if (done(data)) return [data, null];
      } catch (exc) {
        if (exc instanceof QueryRejected) return [null, `, but Prometheus rejected ${query}: ${exc.message}`];
        error = exc; // Prometheus not reachable or not answering: retried until the bound
      }
      const remaining = deadline - this.clock();
      if (remaining <= 0) {
        const waited = ` within ${formatLikePythonG(this.timeout)} s ` +
          `(${formatLikePythonG(this.fixture.timeoutScrapeIntervals)} scrape intervals ` +
          `of ${formatLikePythonG(this.interval * 2)} s)`;
        if (error !== null) {
          return [data, `${waited}: Prometheus could not be asked ${query}: ${errorMessageWithCause(error)}`];
        }
        return [data, `${waited}: ${query} returned ${answerText(data)}`];
      }
      await this.wait(Math.min(this.interval, remaining));
    }
  }

  heading(assertion) {
    return `metrics ${assertion.step} (${this.fixture.steps.get(assertion.step)}): ${assertion.description}`;
  }

  async assertOne(assertion) {
    const query = bind(assertion.query, this.database);
    let baseline = null;
    if (assertion.expect === 'increased') {
      if (!this.baselines.has(assertion)) {
        return { passed: false, message: `${this.heading(assertion)}: expected increased, but no baseline of ` +
          `${query} was recorded at ${assertion.since}` };
      }
      baseline = this.baselines.get(assertion);
    }
    const expected = assertion.expect +
      (baseline !== null ? ` above ${formatLikePythonG(baseline)} recorded at ${assertion.since}` : '');
    const [data, problem] = await this.poll(query, (d) => met(assertion.expect, d, baseline));
    if (problem) return { passed: false, message: `${this.heading(assertion)}: expected ${expected}${problem}` };
    const seen = `${samples(data).length} series` +
      (baseline !== null ? ` totalling ${formatLikePythonG(total(data))}` : '');
    return { passed: true, message: `${this.heading(assertion)}: ${expected}: ${query} returned ${seen}` };
  }

  /** Record the total the assertion's query has at its since step, from a scrape Prometheus made after the flow
   * reached the step: Prometheus's time() then, every series of the query scraped after it, its value. */
  async recordBaseline(assertion) {
    const query = bind(assertion.query, this.database);
    const heading = `metrics ${assertion.since} (${this.fixture.steps.get(assertion.since)}): baseline for ` +
      `${assertion.step}, ${assertion.description}`;
    const numeric = (d) => numericTotal(d) !== null;
    let [data, problem] = await this.poll('time()', numeric);
    if (!problem) [, problem] = await this.poll(`timestamp(${query})`, scrapedAfter(numericTotal(data)));
    if (!problem) [data, problem] = await this.poll(query, numeric);
    if (problem) return { passed: false, message: `${heading}: expected a value scraped after the step${problem}` };
    this.baselines.set(assertion, total(data));
    return { passed: true, message: `${heading}: ${query} = ${formatLikePythonG(total(data))}` };
  }
}

/** An error's message, with the cause fetch hides behind "fetch failed" (connection refused, for example). */
export function errorMessageWithCause(error) {
  const cause = error?.cause;
  return cause?.message ? `${error.message}: ${cause.message}` : String(error?.message ?? error);
}

const UNITS = [365 * 86400, 7 * 86400, 86400, 3600, 60, 1, 0.001]; // y w d h m s ms
const DURATION = /^(?:(\d+)y)?(?:(\d+)w)?(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?$/;

/** Seconds in a duration as Prometheus writes it (2s, 500ms, 1m30s). */
export function duration(text) {
  const match = DURATION.exec(text ?? '');
  if (!text || !match) throw new PrometheusError(`not a Prometheus duration: ${repr(text)}`);
  return match.slice(1).reduce((sum, count, index) => sum + (count ? Number(count) * UNITS[index] : 0), 0);
}

/** The longest scrape interval of the active targets in an /api/v1/targets answer, in seconds: within it, every
 * target the database's metrics can come from has been scraped once. */
export function scrapeInterval(targets) {
  const intervals = targets.data.activeTargets.map((target) => duration(target.scrapeInterval));
  if (!intervals.length) throw new PrometheusError('Prometheus has no active scrape target, so it cannot see the database');
  return Math.max(...intervals);
}

/** The data member of an /api/v1/query answer with HTTP status and body; QueryRejected for a query Prometheus
 * cannot parse, PrometheusError for anything else that is not data. */
export function interpret(status, body) {
  let document;
  try {
    document = JSON.parse(body);
  } catch {
    throw new PrometheusError(`HTTP ${status}: ${body.slice(0, 200)}`);
  }
  if (document.status === 'success') return document.data;
  const error = `${document.errorType ?? `HTTP ${status}`}: ${document.error ?? ''}`;
  throw document.errorType === 'bad_data' ? new QueryRejected(error) : new PrometheusError(error);
}

/** Status and body of a GET, an error status included. */
async function http(url, timeoutMs = 10000) {
  const response = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  return [response.status, await response.text()];
}

/** query(promql) and the scrape interval of the Prometheus at url, for a Lifecycle. */
export async function prometheus(url) {
  const [status, body] = await http(`${url}/api/v1/targets?state=active`);
  if (status !== 200) throw new PrometheusError(`${url}/api/v1/targets answered HTTP ${status}`);
  const interval = scrapeInterval(JSON.parse(body));
  const query = async (promql) => interpret(...(await http(`${url}/api/v1/query?${new URLSearchParams({ query: promql })}`)));
  return { query, interval };
}
