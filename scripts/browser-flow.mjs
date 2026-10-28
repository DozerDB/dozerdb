#!/usr/bin/env node
// Drive the maintainer's database and constraint flow through the real Browser UI.
//
// Usage: node scripts/browser-flow.mjs <http-origin> <password> [--database browserflow]
//        [--headed] [--channel chrome] [--timeout 30000]
//
// Opens <http-origin>/browser/ in Playwright's Chromium, logs in over Bolt through the
// connect form, checks the "Enhanced by DozerDB" badge, then types every command into the
// Browser editor and reads the result frames: CREATE DATABASE, SHOW DATABASES, four
// constraint kinds with one accepted write and one rejected write each, STOP and START
// with data retained, and DROP DATABASE. Screenshots and a transcript go to
// .build/logs/browser-flow/. Exit status 0 means PASS. Typical use: hold a server with
// `python3 scripts/smoke.py distribution --hold`, then pass its origin and password here.
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const LOG_DIR = resolve(ROOT, '.build/logs/browser-flow');

function parseArgs(argv) {
  const options = { database: 'browserflow', headed: false, channel: undefined, timeout: 30000 };
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--database') options.database = argv[++i];
    else if (arg === '--headed') options.headed = true;
    else if (arg === '--channel') options.channel = argv[++i];
    else if (arg === '--timeout') options.timeout = Number(argv[++i]);
    else positional.push(arg);
  }
  if (positional.length !== 2) {
    console.error('Usage: node scripts/browser-flow.mjs <http-origin> <password> [--database name] [--headed] [--channel chrome] [--timeout ms]');
    process.exit(2);
  }
  const [origin, password] = positional;
  return { ...options, origin: origin.replace(/\/+$/, ''), password };
}

class Flow {
  constructor(page, options) {
    this.page = page;
    this.options = options;
    this.transcript = [];
    this.step = 0;
  }

  log(line) {
    this.transcript.push(line);
    console.log(line);
  }

  async snapshot(slug) {
    this.step += 1;
    const file = resolve(LOG_DIR, `${String(this.step).padStart(2, '0')}-${slug.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.png`);
    await this.page.screenshot({ path: file, fullPage: false });
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
    const { options } = this;
    const frame = await this.run(command);
    const deadline = Date.now() + options.timeout;
    let { text, hasError } = { text: '', hasError: false };
    while (Date.now() < deadline) {
      ({ text, hasError } = await this.contentsOf(frame));
      if (pattern.test(text) && hasError === error) break;
      await this.page.waitForTimeout(250);
    }
    if (!pattern.test(text) || hasError !== error) {
      await this.snapshot(`failed-${description}`);
      throw new Error(`${description}: expected ${error ? 'an error' : 'a result'} matching ${pattern}, got "${text.slice(0, 400)}"`);
    }
    this.log(`ok       ${error ? 'rejected' : 'accepted'} ${description}`);
    await this.snapshot(description);
  }

  /** Reruns a command until its frame matches; databases take a moment to change status. */
  async expectEventually(command, pattern, description, attempts = 15) {
    for (let attempt = 1; ; attempt++) {
      try {
        await this.expect(command, pattern, description);
        return;
      } catch (error) {
        if (attempt >= attempts) throw error;
        await this.page.waitForTimeout(2000);
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
    await this.expect(`:use ${db}`, new RegExp(`using the database\\s+${db}`), `use ${db}`);

    await this.expect('CREATE CONSTRAINT bf_exists FOR (p:Person) REQUIRE p.email IS NOT NULL', /Added 1 constraint/, 'existence constraint');
    await this.expect('CREATE CONSTRAINT bf_unique FOR (p:Person) REQUIRE p.email IS UNIQUE', /Added 1 constraint/, 'uniqueness constraint');
    await this.expect('CREATE CONSTRAINT bf_nodekey FOR (a:Account) REQUIRE (a.tenant, a.ext) IS NODE KEY', /Added 1 constraint/, 'node key constraint');
    await this.expect('CREATE CONSTRAINT bf_type FOR (p:Person) REQUIRE p.age IS :: INTEGER', /Added 1 constraint/, 'property type constraint');
    await this.expect('SHOW CONSTRAINTS YIELD name RETURN name', /bf_exists[\s\S]*bf_nodekey[\s\S]*bf_type[\s\S]*bf_unique/, 'show constraints lists four');

    await this.expect("CREATE (:Person {email: 'a@x', age: 30}), (:Account {tenant: 't1', ext: 'e1'}) RETURN 'valid' AS outcome", /valid/, 'write satisfying every constraint');
    await this.expect("CREATE (:Person {age: 1})", rejected('must have the property `email`'), 'node without the required property', { error: true });
    await this.expect("CREATE (:Person {email: 'a@x', age: 2})", rejected('already exists with label `Person` and property `email`'), 'duplicate unique value', { error: true });
    await this.expect("CREATE (:Account {tenant: 't1'})", rejected('must have the properties \\(`tenant`, `ext`\\)'), 'partial node key', { error: true });
    await this.expect("MATCH (p:Person {email: 'a@x'}) SET p.age = 'old'", rejected('required the property `age` to be of type `INTEGER`'), 'typed property set to a string', { error: true });
    await this.expect('MATCH (p:Person) RETURN p.email AS email, p.age AS age', /a@x[\s\S]*30/, 'stored row unchanged after the rejections');

    await this.expect(':use system', /using the database\s+system/, 'use system');
    await this.expect(`STOP DATABASE ${db}`, /1 system update/, `stop database ${db}`);
    await this.expectEventually(showDb, status('offline', 'offline'), 'show databases after stop');
    await this.expect(`START DATABASE ${db}`, /1 system update/, `start database ${db}`);
    await this.expectEventually(showDb, status('online', 'online'), 'show databases after start');
    await this.expect(`:use ${db}`, new RegExp(`using the database\\s+${db}`), `use ${db}`);
    await this.expectEventually('MATCH (n) RETURN labels(n)[0] AS label, count(n) AS retained ORDER BY label', /Account[\s\S]*1[\s\S]*Person[\s\S]*1/, 'data retained after the restart');

    await this.expect(':use system', /using the database\s+system/, 'use system');
    await this.expect(`DROP DATABASE ${db}`, /1 system update/, `drop database ${db}`);
    await this.expectEventually('SHOW DATABASES YIELD name RETURN name', new RegExp(`^(?!.*\\b${db}\\b).*system`), 'show databases after drop');
  }

  async cleanup() {
    // Best effort so a failed run does not leave the database behind.
    if (!this.connected) return;
    try {
      await this.run(':use system');
      await this.run(`DROP DATABASE ${this.options.database} IF EXISTS`);
      this.log(`cleanup  dropped ${this.options.database} if it still existed`);
    } catch (error) {
      this.log(`cleanup  could not drop ${this.options.database}: ${error.message}`);
    }
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  rmSync(LOG_DIR, { recursive: true, force: true });
  mkdirSync(LOG_DIR, { recursive: true });
  const browser = await chromium.launch({ headless: !options.headed, channel: options.channel });
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const flow = new Flow(page, options);
  let passed = false;
  try {
    await flow.main();
    passed = true;
  } catch (error) {
    flow.log(`FAIL     ${error.message}`);
    await flow.cleanup();
  } finally {
    flow.log(passed ? 'RESULT PASS' : 'RESULT FAIL');
    writeFileSync(resolve(LOG_DIR, 'transcript.txt'), flow.transcript.join('\n') + '\n');
    await browser.close();
  }
  process.exit(passed ? 0 : 1);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
