#!/usr/bin/env node
// Drive the maintainer's database and constraint flow through the real Browser UI.
//
// Usage: node scripts/browser-flow.mjs <http-origin> <password> [--database browserflow]
//        [--headed] [--channel chrome] [--timeout 30000] [--prometheus http://127.0.0.1:9090]
//
// Opens <http-origin>/browser/ in Playwright's Chromium, logs in over Bolt through the
// connect form, checks the "Enhanced by DozerDB" badge, then types every command into the
// Browser editor and reads the result frames: CREATE DATABASE, SHOW DATABASES, four
// constraint kinds with one accepted write and one rejected write each, STOP and START
// with data retained, and DROP DATABASE. Screenshots and a transcript go to
// .build/logs/browser-flow/. Exit status 0 means PASS. Typical use: hold a server with
// `python3 scripts/smoke.py distribution --hold`, then pass its origin and password here.
//
// With --prometheus, the URL of a Prometheus that scrapes the server, the flow also asserts what a monitoring
// administrator sees there while the Browser takes the database through its lifecycle, as
// scripts/fixtures/database-metrics-lifecycle.json lists it and scripts/cypher-flow.py checks it too: after
// CREATE DATABASE the database's metrics are scraped, after the data writes its committed write-transaction
// counter and its node count have risen above their values after the constraints, and after DROP DATABASE no
// series of it remains. Each is polled within a bound of the Prometheus scrape interval; a failure names the step,
// the query and what Prometheus returned, and the flow goes on so the database is still dropped. The option also has the flow open :sysinfo in the editor while the database is in
// use and check that the frame shows a number in every Store Size, Page Cache and Transactions cell; the option
// stands for a server that emits metrics, which a line without them leaves off; without it the flow prints a SKIP
// line for the lifecycle assertions and one for :sysinfo.
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import {
  errorMessageWithCause, FixtureError, formatLikePythonG, Lifecycle, loadFixture, prometheus
} from './metrics-lifecycle.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const LOG_DIR = resolve(ROOT, '.build/logs/browser-flow');
const LIFECYCLE = resolve(ROOT, 'scripts/fixtures/database-metrics-lifecycle.json');
// The points of the flow at which the lifecycle fixture's assertions are checked, in the order it reaches them.
const LIFECYCLE_STEPS = ['created', 'constrained', 'written', 'dropped'];
// The :sysinfo tables that must show a number in every cell: the database's store size and transactions, read
// over JMX (dbms.queryJmx) by the Browser, and the server's page cache.
const SYSINFO_TABLES = ['Store Size', 'Page Cache', 'Transactions'];
// A number as :sysinfo renders one: a count, a byte size with its unit, or a ratio in percent; a missing value
// is shown as -.
const SYSINFO_NUMBER = /^-?\d+(\.\d+)?(e[+-]?\d+)?( (B|KiB|MiB|GiB|TiB)|%)?$/;
// How often a frame is read again while the flow waits for what it should show.
const POLL_MS = 250;

function parseArgs(argv) {
  const options = { database: 'browserflow', headed: false, channel: undefined, timeout: 30000, prometheus: undefined };
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--database') options.database = argv[++i];
    else if (arg === '--headed') options.headed = true;
    else if (arg === '--channel') options.channel = argv[++i];
    else if (arg === '--timeout') options.timeout = Number(argv[++i]);
    else if (arg === '--prometheus') options.prometheus = (argv[++i] ?? '').replace(/\/+$/, '');
    else positional.push(arg);
  }
  if (positional.length !== 2 || options.prometheus === '') {
    console.error('Usage: node scripts/browser-flow.mjs <http-origin> <password> [--database name] [--headed] [--channel chrome] [--timeout ms] [--prometheus url]');
    process.exit(2);
  }
  const [origin, password] = positional;
  return { ...options, origin: origin.replace(/\/+$/, ''), password };
}

/** A SHOW DATABASES frame that lists system but no longer the database. */
const gone = (database) => new RegExp(`^(?!.*\\b${database}\\b).*system`);

class Flow {
  constructor(page, options, log, metrics) {
    this.page = page;
    this.options = options;
    this.log = log; // prints a line and adds it to the transcript
    this.metrics = metrics; // the lifecycle checker with --prometheus, else null
    this.failedMetricSteps = [];
    this.step = 0;
  }

  /** Screenshots the page, or only element when given, as the next numbered file of the transcript directory. */
  async snapshot(slug, element) {
    this.step += 1;
    const file = resolve(LOG_DIR, `${String(this.step).padStart(2, '0')}-${slug.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.png`);
    await (element ? element.screenshot({ path: file }) : this.page.screenshot({ path: file, fullPage: false }));
  }

  /** The lifecycle fixture's assertions at this step. A failed one is a mismatch: the flow goes on, so the
   * database is still dropped, and the RESULT line names the step. */
  async checkMetrics(step) {
    if (!this.metrics) return;
    const results = await this.metrics.check(step);
    for (const result of results) this.log(`${(result.passed ? 'ok' : 'MISMATCH').padEnd(8)} ${result.message}`);
    if (results.some((result) => !result.passed)) this.failedMetricSteps.push(step);
  }

  async connect() {
    const { page, options } = this;
    await page.goto(`${options.origin}/browser/`, { waitUntil: 'domcontentloaded' });
    await page.getByTestId('username').waitFor({ timeout: options.timeout });
    const boltAddress = await page.getByTestId('boltaddress').inputValue();
    await page.getByTestId('username').fill('neo4j');
    await page.getByTestId('password').fill(options.password);
    await page.getByTestId('connect').click();
    await page.getByText('You are connected as user').waitFor({ timeout: options.timeout });
    await page.getByText('Enhanced by DozerDB').waitFor({ timeout: options.timeout });
    this.connected = true;
    this.log(`ok       connected over bolt to ${boltAddress}; badge "Enhanced by DozerDB" shown`);
    await this.snapshot('connected');
  }

  /** Types a command into the main editor, runs it, and returns the frame it produced. */
  async run(command) {
    const { page, options } = this;
    // Mark the current newest frame so the one this command adds can be told apart even
    // when the stream has reached its frame limit and the count no longer grows.
    await page.evaluate(() => {
      const newest = document.querySelector('[data-testid="frame"]');
      if (newest) newest.setAttribute('data-flow-seen', '1');
    });
    await page.getByTestId('activeEditor').click();
    const textarea = page.locator('[id^=monaco-] textarea').first();
    await textarea.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A');
    await textarea.press('Backspace');
    await textarea.pressSequentially(command);
    await page.getByTestId('editor-Run').click();
    // The newest frame is prepended to the stream.
    const expected = command.replace(/\s+/g, ' ').trim();
    await page.waitForFunction(
      (wanted) => {
        const newest = document.querySelector('[data-testid="frame"]');
        if (!newest || newest.hasAttribute('data-flow-seen')) return false;
        const shown = newest.querySelector('[data-testid="frameCommand"]');
        return !!shown && shown.innerText.replace(/\s+/g, ' ').trim().includes(wanted);
      },
      expected,
      { timeout: options.timeout }
    );
    return page.getByTestId('frame').first();
  }

  /** Calls read() every POLL_MS until done(its value) or timeoutMs have passed; resolves to the last value and
   * whether it was the awaited one. Every wait of the flow on what a frame shows goes through here. */
  async poll(read, done, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const value = await read();
      if (done(value)) return { value, met: true };
      const remaining = deadline - Date.now();
      if (remaining <= 0) return { value, met: false };
      await this.page.waitForTimeout(Math.min(POLL_MS, remaining));
    }
  }

  /** Reads the newest frame's contents and status bar without waiting on any element. */
  async contentsOf(frame) {
    return frame.evaluate((el) => {
      const text = (id) => Array.from(el.querySelectorAll(`[data-testid="${id}"]`)).map((e) => e.innerText).join(' ');
      return {
        text: `${text('frameContents')} ${text('frameStatusbar')}`.replace(/\s+/g, ' ').trim(),
        hasError: el.querySelector('[data-testid="cypherFrameErrorMessage"]') !== null
      };
    });
  }

  /** Runs a command and waits until its frame's contents match. */
  async expect(command, pattern, description, { error = false } = {}) {
    const frame = await this.run(command);
    const matches = (contents) => pattern.test(contents.text) && contents.hasError === error;
    const { value: { text }, met } = await this.poll(() => this.contentsOf(frame), matches, this.options.timeout);
    if (!met) {
      await this.snapshot(`failed-${description}`);
      throw new Error(`${description}: expected ${error ? 'an error' : 'a result'} matching ${pattern}, got "${text.slice(0, 400)}"`);
    }
    this.log(`ok       ${error ? 'rejected' : 'accepted'} ${description}`);
    await this.snapshot(description);
  }

  /** Opens :sysinfo in the editor and waits until the frame shows a number in every cell of the Store Size, Page
   * Cache and Transactions tables (the frame fills them once its JMX query answers); screenshots the frame. */
  async expectSysinfo() {
    const { options } = this;
    const description = `sysinfo for ${options.database}`;
    const frame = await this.run(':sysinfo');
    const read = () => frame.evaluate((el) => ({
      tables: Array.from(el.querySelectorAll('table')).map((table) => ({
        header: (table.querySelector('thead')?.innerText ?? '').trim(),
        cells: Array.from(table.querySelectorAll('tbody tr')).map((row) =>
          Array.from(row.querySelectorAll('td')).map((cell) => cell.innerText.replace(/\s+/g, ' ').trim()))
      })),
      status: Array.from(el.querySelectorAll('[data-testid="frameStatusbar"]')).map((e) => e.innerText).join(' ')
        .replace(/\s+/g, ' ').trim()
    }));
    const examine = ({ tables }) => {
      const shown = [];
      const problems = [];
      for (const name of SYSINFO_TABLES) {
        const table = tables.find((t) => t.header === name);
        if (!table) problems.push(`no ${name} table`);
        else if (!table.cells.length) problems.push(`${name} has no rows`);
        else {
          shown.push(`${name}: ${table.cells.map(([label, value]) => `${label} ${value}`).join(', ')}`);
          const blank = table.cells.filter(([, value]) => !SYSINFO_NUMBER.test(value ?? ''));
          if (blank.length) problems.push(`${name} ${blank.map(([label, value]) => `${label} ${JSON.stringify(value ?? '')}`).join(', ')}`);
        }
      }
      return { shown, problems };
    };
    const { value: seen } = await this.poll(read, (value) => !examine(value).problems.length, options.timeout);
    const { shown, problems } = examine(seen);
    if (problems.length) {
      await this.snapshot(`failed-${description}`, frame);
      throw new Error(`${description}: expected a number in every ${SYSINFO_TABLES.join(', ')} cell within ` +
        `${options.timeout} ms, but ${problems.join('; ')} (status bar: "${seen.status}")`);
    }
    this.log(`ok       ${description} shows numbers: ${shown.join('; ')}`);
    await this.snapshot(description, frame);
  }

  /** Runs a command again and again until its frame matches, within twice --timeout (a database takes a moment
   * to change status). A frame shows the answer of its own run only, so each run's frame is read until it
   * matches or has settled (the same text twice), and a settled answer that is not the awaited one is asked
   * again at once. */
  async expectEventually(command, pattern, description) {
    const { options } = this;
    const deadline = Date.now() + 2 * options.timeout;
    const matches = (contents) => pattern.test(contents.text) && !contents.hasError;
    for (;;) {
      const frame = await this.run(command);
      let previous = null;
      const settled = ({ text }) => {
        const same = text !== '' && text === previous;
        previous = text;
        return same;
      };
      const answered = (contents) => matches(contents) || settled(contents);
      const { value } = await this.poll(() => this.contentsOf(frame), answered, Math.max(0, deadline - Date.now()));
      if (matches(value)) {
        this.log(`ok       accepted ${description}`);
        await this.snapshot(description);
        return;
      }
      if (Date.now() >= deadline) {
        await this.snapshot(`failed-${description}`);
        throw new Error(`${description}: expected a result matching ${pattern} within ${2 * options.timeout} ms, ` +
          `got "${value.text.slice(0, 400)}"`);
      }
    }
  }

  async main() {
    const db = this.options.database;
    const status = (requested, current) =>
      new RegExp(`"?${db}"?\\s+"?${requested}"?\\s+"?${current}"?`);
    const showDb = `SHOW DATABASES YIELD name, requestedStatus, currentStatus WHERE name = '${db}' RETURN name, requestedStatus, currentStatus`;
    const rejected = (message) => new RegExp(`ConstraintValidationFailed[\\s\\S]*${message}`);

    await this.connect();
    await this.expect(':use system', /using the database\s+system/, 'use system');
    // A previous failed run may have left the flow database behind.
    await this.expect(`DROP DATABASE ${db} IF EXISTS`, /system update|no changes, no records/, `drop stale ${db}`);
    await this.expect(`CREATE DATABASE ${db}`, /1 system update/, `create database ${db}`);
    await this.expectEventually(showDb, status('online', 'online'), 'show databases after create');
    await this.checkMetrics('created');
    await this.expect(`:use ${db}`, new RegExp(`using the database\\s+${db}`), `use ${db}`);

    await this.expect('CREATE CONSTRAINT bf_exists FOR (p:Person) REQUIRE p.email IS NOT NULL', /Added 1 constraint/, 'existence constraint');
    await this.expect('CREATE CONSTRAINT bf_unique FOR (p:Person) REQUIRE p.email IS UNIQUE', /Added 1 constraint/, 'uniqueness constraint');
    await this.expect('CREATE CONSTRAINT bf_nodekey FOR (a:Account) REQUIRE (a.tenant, a.ext) IS NODE KEY', /Added 1 constraint/, 'node key constraint');
    await this.expect('CREATE CONSTRAINT bf_type FOR (p:Person) REQUIRE p.age IS :: INTEGER', /Added 1 constraint/, 'property type constraint');
    await this.expect('SHOW CONSTRAINTS YIELD name RETURN name', /bf_exists[\s\S]*bf_nodekey[\s\S]*bf_type[\s\S]*bf_unique/, 'show constraints lists four');
    await this.checkMetrics('constrained');

    await this.expect("CREATE (:Person {email: 'a@x', age: 30}), (:Account {tenant: 't1', ext: 'e1'}) RETURN 'valid' AS outcome", /valid/, 'write satisfying every constraint');
    await this.expect("CREATE (:Person {age: 1})", rejected('must have the property `email`'), 'node without the required property', { error: true });
    await this.expect("CREATE (:Person {email: 'a@x', age: 2})", rejected('already exists with label `Person` and property `email`'), 'duplicate unique value', { error: true });
    await this.expect("CREATE (:Account {tenant: 't1'})", rejected('must have the properties \\(`tenant`, `ext`\\)'), 'partial node key', { error: true });
    await this.expect("MATCH (p:Person {email: 'a@x'}) SET p.age = 'old'", rejected('required the property `age` to be of type `INTEGER`'), 'typed property set to a string', { error: true });
    await this.expect('MATCH (p:Person) RETURN p.email AS email, p.age AS age', /a@x[\s\S]*30/, 'stored row unchanged after the rejections');
    await this.checkMetrics('written');
    // :sysinfo reads the database in use and the server over JMX; a server that emits metrics shows them all.
    if (this.metrics) await this.expectSysinfo();

    await this.expect(':use system', /using the database\s+system/, 'use system');
    await this.expect(`STOP DATABASE ${db}`, /1 system update/, `stop database ${db}`);
    await this.expectEventually(showDb, status('offline', 'offline'), 'show databases after stop');
    await this.expect(`START DATABASE ${db}`, /1 system update/, `start database ${db}`);
    await this.expectEventually(showDb, status('online', 'online'), 'show databases after start');
    await this.expect(`:use ${db}`, new RegExp(`using the database\\s+${db}`), `use ${db}`);
    await this.expectEventually('MATCH (n) RETURN labels(n)[0] AS label, count(n) AS retained ORDER BY label', /Account[\s\S]*1[\s\S]*Person[\s\S]*1/, 'data retained after the restart');

    await this.expect(':use system', /using the database\s+system/, 'use system');
    await this.expect(`DROP DATABASE ${db}`, /1 system update/, `drop database ${db}`);
    await this.expectEventually('SHOW DATABASES YIELD name RETURN name', gone(db), 'show databases after drop');
    await this.checkMetrics('dropped');
  }

  /** After a failed run, drops the flow database and waits until SHOW DATABASES no longer lists it, each within
   * the flow's bounds, so a held server is left without it before the browser closes. */
  async cleanup() {
    if (!this.connected) return;
    const db = this.options.database;
    try {
      await this.expect(':use system', /using the database\s+system/, 'cleanup use system');
      await this.expect(`DROP DATABASE ${db} IF EXISTS`, /system update|no changes, no records/, `cleanup drop ${db}`);
      await this.expectEventually('SHOW DATABASES YIELD name RETURN name', gone(db),
        `cleanup ${db} gone`);
      this.log(`cleanup  dropped ${db} if it still existed`);
    } catch (error) {
      this.log(`cleanup  could not drop ${db}: ${error.message}`);
    }
  }
}

/** The lifecycle checker for the Prometheus at url, or a RESULT FAIL reason when the fixture is not valid or
 * Prometheus cannot be asked; checked before the Browser changes anything. */
async function metricsChecker(url, database, log) {
  let fixture;
  try {
    fixture = loadFixture(LIFECYCLE, LIFECYCLE_STEPS);
  } catch (error) {
    if (!(error instanceof FixtureError)) throw error;
    log(`FAIL     metrics fixture ${error.message}`);
    return { failure: 'metrics fixture' };
  }
  let prometheusApi;
  try {
    prometheusApi = await prometheus(url);
  } catch (error) {
    log(`FAIL     Prometheus at ${url}: ${errorMessageWithCause(error)}`);
    return { failure: 'Prometheus not available' };
  }
  const { query, interval } = prometheusApi;
  log(`metrics  ${fixture.assertions.length} lifecycle assertions from ${relative(ROOT, LIFECYCLE)}, Prometheus ` +
    `${url} scraping every ${formatLikePythonG(interval)} s, ` +
    `bound ${formatLikePythonG(fixture.timeoutScrapeIntervals * interval)} s`);
  return { checker: new Lifecycle(fixture, database, query, interval) };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  rmSync(LOG_DIR, { recursive: true, force: true });
  mkdirSync(LOG_DIR, { recursive: true });
  const transcript = [];
  const log = (line) => {
    transcript.push(line);
    console.log(line);
  };
  const saveTranscript = () => writeFileSync(resolve(LOG_DIR, 'transcript.txt'), transcript.join('\n') + '\n');
  let metrics = null;
  if (options.prometheus) {
    const { checker, failure } = await metricsChecker(options.prometheus, options.database, log);
    if (failure) {
      log(`RESULT FAIL (${failure})`);
      saveTranscript();
      process.exit(1);
    }
    metrics = checker;
  } else {
    log(`SKIP     metrics lifecycle (${LIFECYCLE_STEPS.join(', ')}): no --prometheus given, so what Prometheus shows of ` +
      'the database is not checked');
    log('SKIP     :sysinfo: no --prometheus given, so the server is not taken to emit the metrics the frame shows');
  }
  const browser = await chromium.launch({ headless: !options.headed, channel: options.channel });
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const flow = new Flow(page, options, log, metrics);
  let completed = false;
  try {
    await flow.main();
    completed = true;
  } catch (error) {
    flow.log(`FAIL     ${error.message}`);
    await flow.cleanup();
  }
  const passed = completed && flow.failedMetricSteps.length === 0;
  try {
    const metricsSteps = flow.failedMetricSteps.length ? ` (metrics ${flow.failedMetricSteps.join(', ')})` : '';
    flow.log(passed ? 'RESULT PASS' : `RESULT FAIL${metricsSteps}`);
    saveTranscript();
  } finally {
    await browser.close();
  }
  process.exit(passed ? 0 : 1);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
