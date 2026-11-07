#!/usr/bin/env python3
"""Release-gate check of DozerDB's query log in every delivery form.

Usage: python3 scripts/query-log-check.py [plugin] [distribution] [docker] [--image IMAGE] [--server-logs-xml FILE]

Each form (all three by default) is started through the smoke helper (scripts/smoke.py) with two query log settings
configured the way an operator configures them (db.logs.query.enabled=INFO and a one-second threshold, written to
neo4j.conf on the plugin and distribution forms and passed to the docker form as NEO4J_db_logs_query_* variables),
and the probes below run against it in order, each named in the output. The first probe proves those settings reach
query.log and resets them to Neo4j's defaults, under which the other probes run; a probe that changes a setting does
so with CALL dbms.setConfigValue and resets it after. With --server-logs-xml FILE the plugin and distribution forms
start with that whole logging configuration installed as conf/server-logs.xml (the overhead mitigations' fixtures,
scripts/fixtures/server-logs-*.xml), so the probes prove the entries it writes; the json-fields probe then edits that
file.

  startup-settings   the configured INFO level and one-second threshold are in force from the start: a fast query
                     is hidden and a failure is written at INFO; on the docker form the NEO4J_* variables are also
                     seen in the container's neo4j.conf; then both are reset to Neo4j's defaults
  verbose-start-end  a query over the transactional endpoint writes one start entry and one success entry at
                     VERBOSE, both of the inventory's shape, with its text, the database and the user
  http-source        the source segment over HTTP: the transactional endpoint's end entry carries Community's HTTP
                     connection details ending in the request path, and a query over the Query API (served over an
                     in-process Bolt channel) carries the Bolt shape naming neo4j-query-api/v2 as the client
  transaction        at transaction.enabled=VERBOSE a committed transaction writes its start and commit entries,
                     the commit carrying the {app: '<marker>'} metadata the client set with CALL tx.setMetaData
  failure            a failing query writes one failed-query entry at ERROR, the reason last, the stack trace after
  dynamic-info       INFO with a one-second threshold hides a fast query and still writes a failure, at INFO
  off                no query entry for a query and a failure at OFF; the committed transaction that ran the query
                     (transaction logging on) is the sentinel
  obfuscate          obfuscate_literals after CALL db.clearQueryCaches() masks the literal in both entries
  json-fields        (distribution form only) the shipped conf/server-logs.xml edited as the manual tells a user,
                     the server restarted: the start, success and failed-query entries are JSON objects with the
                     inventory's keys for their kind and detail level

The entries are matched against the field inventory, scripts/fixtures/query-log-entries.json, and nothing else:
the matcher derives the plain-text grammar of every entry shape (query or transaction entry, kind, detail level)
from the inventory's rows and patterns, so the file that the renderer tests and the documentation generator read is
the one this check reads. Every expectation polls the file; an absence is asserted only after a sentinel entry has
appeared. Exit status 0 and `RESULT PASS` mean every probe of every form passed; `RESULT FAIL` names the failing
probes.
"""
import argparse
import json
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.parse
import uuid
from dataclasses import dataclass
from pathlib import Path

from probes import Probes
import release_line
import smoke

FORMS = ('plugin', 'distribution', 'docker')
INVENTORY = smoke.ROOT / 'scripts/fixtures/query-log-entries.json'


# ---------------------------------------------------------------- the field inventory

class Inventory:
    """The field inventory read for matching: its field rows by name and the example lines."""

    def __init__(self, data):
        self.data = data
        self.fields = {field['name']: field for field in data['fields']}
        self.examples = data['examples']
        self.validate()

    @classmethod
    def load(cls, path=INVENTORY):
        return cls(json.loads(Path(path).read_text()))

    def pattern(self, name):
        return self.fields[name]['pattern']

    def validate(self):
        """The part of the inventory's shape the matcher reads, checked the way the Java reader checks the whole: a
        problem names the row. A plain segment at position 1 or later carries a `<name>` placeholder for each of its
        rows, rows at one position share the segment and the separator, every pattern compiles, and an event row's
        segment has at most one marker per kind of its pattern."""
        def require(condition, message):
            if not condition:
                raise ValueError(message)
        for field in self.data['fields']:
            row = f"field '{field['name']}'"
            require(isinstance(field.get('optional'), bool), f"{row}: 'optional' is true or false")
            if field.get('pattern') is not None:
                try:
                    re.compile(field['pattern'])
                except re.error as exc:
                    raise ValueError(f"{row}: 'pattern' is not a regular expression: {exc}") from None
            plain = field.get('plain')
            if plain is None:
                continue
            require(isinstance(plain.get('segment'), str), f"{row}: 'plain.segment' is a string")
            require(isinstance(plain.get('position'), int), f"{row}: 'plain.position' is an integer")
            require(isinstance(plain.get('separator'), str), f"{row}: 'plain.separator' is a string")
            placeholder = f"<{field['name'].rsplit('.', 1)[-1]}>"
            require(plain['position'] <= 0 or placeholder in plain['segment'],
                    f"{row}: segment {plain['segment']!r} has no {placeholder} placeholder")
        for type_, event in EVENT_ROW.items():
            if event in self.fields:
                require(len(self.fields[event]['plain']['segment'].split('|')) <= len(self.kinds(type_)),
                        f"field '{event}': the segment has more alternatives than the pattern has kinds")
            first = {}
            for field in self.rows(type_):
                plain = field['plain']
                other = first.setdefault(plain['position'], field)
                require((plain['segment'], plain['separator']) == (other['plain']['segment'], other['plain']['separator']),
                        f"field '{field['name']}': position {plain['position']} has another segment or separator "
                        f"than field '{other['name']}'")

    def kinds(self, type_):
        """The entry kinds of a type in their order: the alternatives of its event row's pattern; none without it."""
        event = self.fields.get(EVENT_ROW[type_])
        return tuple(event['pattern'].split('|')) if event else ()

    def rows(self, type_):
        """The rows that shape an entry of the type: its own and the common ones, those with a plain segment."""
        return [field for field in self.data['fields']
                if field['type'] in (type_, 'common') and field['plain'] is not None]

    def shapes(self):
        """Every entry shape the inventory describes, one grammar each; detail levels whose grammar is the same
        (a transaction entry has no segment that depends on the level) are one shape."""
        shapes = []
        for type_ in EVENT_ROW:
            for kind in self.kinds(type_):
                by_grammar = {}
                for level in DETAIL_LEVELS:
                    by_grammar.setdefault(self.grammar(type_, kind, level), []).append(level)
                for grammar, levels in by_grammar.items():
                    shapes.append(Shape(type_, kind, frozenset(levels), LOG_LEVELS.get((type_, kind), ('INFO',)),
                                        re.compile(grammar)))
        return shapes

    def grammar(self, type_, kind, level):
        """The regular expression of the %m of an entry of the shape, from the segments the inventory says appear in
        it (its rows with the kind among their entries and the level among their levels), in position order.

        Rows at one position share one segment text (planning and waiting, page hits and faults) and each `<name>`
        in it is the row's value pattern. The marker at position 0 is the alternative of the event row's segment that
        stands at the kind's index in the pattern (start|commit|rollback); a kind without one opens with no marker.
        A segment's separator is the text before it; the first segment after the marker has none. An optional row
        (its presence depends on a detail flag, or on what Neo4j reported) may be absent with its separator.
        """
        segments = {}
        for field in self.rows(type_):
            plain = field['plain']
            if plain['position'] < 0 or kind not in field['entries'] or level not in field['levels']:
                continue
            segments.setdefault(plain['position'], []).append(field)
        parts, preceded = [], False
        for position in sorted(segments):
            fields = segments[position]
            segment, separator = fields[0]['plain']['segment'], fields[0]['plain']['separator']
            if position == 0:
                markers = dict(zip(self.kinds(type_), segment.split('|')))
                parts.append(re.escape(markers.get(kind, '')))
                continue
            template = re.escape(segment)
            for field in fields:
                placeholder = re.escape(f"<{field['name'].rsplit('.', 1)[-1]}>")
                if placeholder not in template:
                    raise ValueError(f"{field['name']}: segment {segment!r} has no placeholder for it")
                template = template.replace(placeholder, f"(?P<{group_name(field['name'])}>{scoped(field['pattern'])})")
            part = (re.escape(separator) if preceded else '') + template
            parts.append(f'(?:{part})?' if any(field['optional'] for field in fields) else part)
            preceded = True
        return ''.join(parts)

    def matches(self, log_level, message):
        """Every shape whose grammar the %m fits and whose log level admits the entry; one for a well-formed entry."""
        found = []
        for shape in self.shapes():
            if log_level not in shape.log_levels:
                continue
            match = shape.grammar.fullmatch(message)
            if match:
                found.append(Match(shape, {field_name(name): value for name, value in match.groupdict().items()}))
        return found


EVENT_ROW = {'query': 'event', 'transaction': 'transaction.event'}
DETAIL_LEVELS = ('INFO', 'VERBOSE')
# The Log4j level an entry is written at (the inventory's level row): ERROR carries the throwable of a failed-query
# entry or of a rollback Neo4j reported with one; every other entry is INFO.
LOG_LEVELS = {('query', 'fail'): ('ERROR',), ('transaction', 'rollback'): ('INFO', 'ERROR')}
INLINE_FLAGS = re.compile(r'^\(\?([aimsux]+)\)(.*)$', re.DOTALL)


def scoped(pattern):
    """A row's pattern with its leading inline flags scoped to it, so it can sit inside a larger expression."""
    match = INLINE_FLAGS.match(pattern)
    return f'(?{match.group(1)}:{match.group(2)})' if match else pattern


def group_name(field):
    return field.replace('.', '__')


def field_name(group):
    return group.replace('__', '.')


@dataclass(frozen=True)
class Shape:
    """One entry shape: the type of entry, its kind, the detail levels it is written at, the Log4j levels it may
    carry and the grammar of its %m."""
    type: str
    kind: str
    levels: frozenset
    log_levels: tuple
    grammar: re.Pattern

    @property
    def name(self):
        return f"{self.type} {self.kind} {'/'.join(sorted(self.levels))}"


@dataclass(frozen=True)
class Match:
    shape: Shape
    fields: dict


# ---------------------------------------------------------------- the JSON layout

JSON_TEMPLATE_LAYOUT = '<JsonTemplateLayout eventTemplateUri="classpath:org/neo4j/logging/QueryLogJsonLayout.json"/>'
QUERY_LOG_APPENDER = re.compile(r'(<RollingRandomAccessFile name="QueryLog".*?)<PatternLayout [^>]*/>(.*?</RollingRandomAccessFile>)',
                                re.DOTALL)


def json_layout(server_logs_xml):
    """The shipped server-logs.xml with the manual's edit for a JSON query log: the QueryLog appender's PatternLayout
    replaced by the JsonTemplateLayout with Neo4j's QueryLogJsonLayout.json template, nothing else touched."""
    edited, count = QUERY_LOG_APPENDER.subn(lambda m: m.group(1) + JSON_TEMPLATE_LAYOUT + m.group(2), server_logs_xml)
    if count != 1:
        raise ValueError('server-logs.xml has no QueryLog appender with a PatternLayout to replace')
    return edited


def json_problems(entry, kind, detail_level, line, inventory, type_='query'):
    """What is wrong with one entry of the JSON layout (one object per line) for an entry of the kind written at the
    detail level on the release line: a key the inventory has no row for, a key whose row is not written on the kind
    or at the level or is not applicable on the line, a value outside its row's pattern (an object where the row has
    none), a level other than the kind's, and a missing key whose row is neither optional nor inapplicable."""
    rows = {field['jsonKey']: field for field in inventory.data['fields']
            if field['jsonKey'] is not None and field['type'] in (type_, 'common')}
    problems = []
    for key, value in entry.items():
        row = rows.get(key)
        if row is None:
            problems.append(f'{key}: not a {type_} log field')
        elif kind not in row['entries']:
            problems.append(f'{key}: not written on a {kind} entry')
        elif detail_level not in row['levels']:
            problems.append(f'{key}: not written at {detail_level}')
        elif row['status'][line]['tier'] == 'not-applicable':
            problems.append(f'{key}: not applicable on the {line} line')
        elif key == 'level' and value not in LOG_LEVELS.get((type_, kind), ('INFO',)):
            problems.append(f'{key}: {value!r} is not the level of a {kind} entry')
        elif row['pattern'] is None:
            if not isinstance(value, dict):
                problems.append(f'{key}: {value!r} is not an object')
        elif not re.fullmatch(row['pattern'], str(value)):
            problems.append(f"{key}: {value!r} does not match {row['pattern']}")
    for key, row in rows.items():
        if (key not in entry and kind in row['entries'] and detail_level in row['levels']
                and row['status'][line]['tier'] != 'not-applicable' and not row['optional']):
            problems.append(f'{key}: missing')
    return problems


# ---------------------------------------------------------------- the plain layout

@dataclass(frozen=True)
class PlainEntry:
    """One entry of a plain query.log: the layout's timestamp and level, then the %m with its continuation lines."""
    time: str
    level: str
    message: str


LEVEL_WIDTH = 5  # the layout's %-5p pads the level to five characters before the space and the message


def plain_entries(text, inventory=None):
    """The entries of a plain query.log text. An entry opens with the layout's timestamp and level (the inventory's
    time and level rows) and runs to the next such line, so a stack trace, a plan table and a line break inside the
    query text stay with their entry."""
    inventory = inventory or Inventory.load()
    opening = re.compile(f"^({inventory.pattern('time')}) ({inventory.pattern('level')}) ")
    entries = []
    current = None
    for line in text.split('\n'):
        match = opening.match(line)
        if match:
            if current:
                entries.append(current)
            time, level = match.group(1), match.group(2)
            current = [time, level, line[match.end() + LEVEL_WIDTH - len(level):]]
        elif current is not None:
            current[2] += '\n' + line
    if current:
        entries.append(current)
    return [PlainEntry(time, level, message.rstrip('\n')) for time, level, message in entries]


# Everything below runs servers; the functions above are pure and unit-tested.

class QueryLog:
    """The query.log of a checked server, read whole at every poll: the file under the server's home on the plugin
    and distribution forms, /logs/query.log inside the container on the docker form."""

    TIMEOUT = 30

    def __init__(self, server, inventory):
        self.server = server
        self.inventory = inventory

    def text(self):
        if self.server.mode == 'docker':
            try:
                return self.server.exec('cat', '/logs/query.log')
            except subprocess.CalledProcessError:
                return ''  # not written yet
        path = self.server.home / 'logs/query.log'
        return path.read_text() if path.exists() else ''

    def entries(self):
        return plain_entries(self.text(), self.inventory)

    def containing(self, text):
        return [entry for entry in self.entries() if text in entry.message]

    def await_entry(self, description, condition):
        """The first entry satisfying the condition, polled for until the timeout; then an error listing the file."""
        return self.await_first('entry', self.entries, description, condition)

    def await_first(self, what, entries, description, condition):
        deadline = time.monotonic() + self.TIMEOUT
        while True:
            found = [entry for entry in entries() if condition(entry)]
            if found:
                return found[0]
            if time.monotonic() >= deadline:
                raise AssertionError(f'no {what} with {description} within {self.TIMEOUT} s; the query.log holds:\n'
                                     + self.text()[-4000:])
            time.sleep(0.2)

    def json_entries(self):
        """Every entry of a file written by the JSON layout: one object per line; a line that is not one fails."""
        entries = []
        for line in self.text().splitlines():
            if line.strip():
                entry = json.loads(line)
                assert isinstance(entry, dict), f'not one JSON object per line: {line}'
                entries.append(entry)
        return entries

    def await_json_entry(self, description, condition):
        return self.await_first('JSON entry', self.json_entries, description, condition)

    def matched(self, entry):
        """The one shape of an entry with its fields, or an error naming the shapes it fits."""
        matches = self.inventory.matches(entry.level, entry.message)
        if len(matches) != 1:
            raise AssertionError(f"the entry fits {[m.shape.name for m in matches] or 'no shape of the inventory'}: "
                                 f'{entry.level} {entry.message[:300]!r}')
        return matches[0]

    def expect(self, entry, type_, kind, detail_level):
        """The fields of an entry that must be of the shape; else an error naming the shape it is."""
        match = self.matched(entry)
        shape = match.shape
        if (shape.type, shape.kind) != (type_, kind) or detail_level not in shape.levels:
            raise AssertionError(f'expected a {type_} {kind} entry at {detail_level}, found {shape.name}: '
                                 f'{entry.level} {entry.message[:300]!r}')
        return match.fields


def statement(server, text, parameters=None, database='neo4j'):
    """One statement through the transactional endpoint, auto-committed; the response, errors included."""
    body = {'statements': [{'statement': text, 'parameters': parameters or {}}]}
    return json.loads(server.request(f'/db/{database}/tx/commit', body))


def query_api(server, text, database='neo4j'):
    """One statement through the Query API (POST /db/{database}/query/v2), auto-committed; the response, which
    carries an errors array only when the statement failed."""
    response = json.loads(server.request(f'/db/{database}/query/v2', {'statement': text}))
    assert not response.get('errors'), response.get('errors')
    return response


def succeeding(server, text, parameters=None):
    response = statement(server, text, parameters)
    assert not response['errors'], response['errors']
    return response


def failing(server, text):
    response = statement(server, text)
    assert response['errors'], f'expected {text!r} to fail'
    return response['errors'][0]


def set_config(server, setting, value):
    """Change a dynamic setting for the running server as an operator does; the empty value resets it."""
    succeeding(server, 'CALL dbms.setConfigValue($setting, $value)', {'setting': setting, 'value': value})


def transaction(server, marker, statements):
    """An explicit transaction over HTTP carrying {app: marker} as its metadata, the statements run in it, committed."""
    opened = json.loads(server.request('/db/neo4j/tx', {'statements': [
        {'statement': 'CALL tx.setMetaData($metadata)', 'parameters': {'metadata': {'app': marker}}}]}))
    assert not opened['errors'], opened['errors']
    committed = json.loads(server.request(urllib.parse.urlparse(opened['commit']).path,
                                          {'statements': [{'statement': text} for text in statements]}))
    assert not committed['errors'], committed['errors']
    return committed


def marker():
    return 'qlc-' + uuid.uuid4().hex[:12]


HTTP_SOURCE = 'server-session\thttp\t'
# Configured at start on every form (neo4j.conf, or the NEO4J_* variables of the docker form) and proven in the log by
# the first probe, then reset: the level and the threshold are the two settings whose effect one query shows.
STARTUP_SETTINGS = {'db.logs.query.enabled': 'INFO', 'db.logs.query.threshold': '1s'}
# Neo4j 2026 serves the Query API over an in-process Bolt channel, so its entries carry the Bolt shape of the source
# with the API as the client; the transactional endpoint is the one HTTP route whose entries carry the HTTP shape.
QUERY_API_SOURCE = 'bolt-session\tbolt\tneo4j-query-api/v2\t\tclientlocal:'
TRANSACTION_SETTING = 'db.logs.query.transaction.enabled'
ENABLED_SETTING = 'db.logs.query.enabled'


def startup_variables():
    """The NEO4J_* variables the docker form starts with, as the public page's table documents them."""
    return {smoke.env_name(setting): value for setting, value in STARTUP_SETTINGS.items()}


def startup_settings_problems(conf_text):
    """What is wrong with a container's neo4j.conf after the image mapped the startup variables into it."""
    conf = smoke.parse_conf(conf_text)
    problems = []
    for setting, expected in STARTUP_SETTINGS.items():
        if setting not in conf:
            problems.append(f'{setting} missing from the container, expected {expected}')
        elif conf[setting] != expected:
            problems.append(f'{setting}={conf[setting]} in the container, expected {expected}')
    return problems


def expect_info_with_threshold(log, server, prefix):
    """Under INFO with a one-second threshold a fast query is not written and a failure is, as a failed-query entry
    at INFO (no id: prefix); the failure is the sentinel the absence is judged after."""
    fast = f"RETURN '{marker()}' AS {prefix}_fast_under_threshold"
    succeeding(server, fast)
    variable = f'qlc_{prefix}_sentinel_' + uuid.uuid4().hex[:12]
    sentinel = f'MATCH (n) RETURN {variable}'
    failing(server, sentinel)
    entry = log.await_entry(f'the failed-query entry of the sentinel {sentinel!r}',
                            lambda e: sentinel in e.message and e.level == 'ERROR')
    fields = log.expect(entry, 'query', 'fail', 'INFO')
    assert fields['query'] == sentinel, fields['query']
    hidden = log.containing(fast)
    assert not hidden, f'a fast query under the threshold was written at INFO: {hidden[0].message[:200]!r}'


def reset_startup_settings(server):
    """Back to Neo4j's defaults (VERBOSE, 0s): the empty value drops the configured value, from neo4j.conf or the
    variables alike, so every later probe and a restarted server run under the defaults the manual describes."""
    for setting in STARTUP_SETTINGS:
        set_config(server, setting, '')


def check_startup_settings(log, server):
    """The settings the form started with reach query.log: the configured INFO level hides a fast query and writes a
    failure at INFO, before any CALL dbms.setConfigValue; on the docker form the variables are also seen mapped into
    the container's neo4j.conf. Both settings are then reset to Neo4j's defaults."""
    route = 'neo4j.conf'
    if server.mode == 'docker':
        route = ', '.join(f'{name}={value}' for name, value in startup_variables().items())
        problems = startup_settings_problems(server.exec('cat', '/var/lib/neo4j/conf/neo4j.conf'))
        assert not problems, '; '.join(problems)
    expect_info_with_threshold(log, server, 'startup')
    reset_startup_settings(server)
    return f'INFO with a 1 s threshold in force from {route}: a fast query hidden, a failure written at INFO; reset to the defaults'


def check_verbose_start_end(log, server, found):
    """Neo4j's defaults: a query writes a start entry and a success entry at VERBOSE, both of the inventory's shape,
    one each, with the query text, the database and the executing user."""
    text = f"RETURN '{marker()}' AS verbose_start_end"
    succeeding(server, text)
    end = log.await_entry(f'the end of {text!r}', lambda e: text in e.message and not e.message.startswith('Query started: '))
    start = log.await_entry(f'the start of {text!r}', lambda e: text in e.message and e.message.startswith('Query started: '))
    entries = log.containing(text)
    assert len(entries) == 2, f'{len(entries)} entries carry the query text, expected the start and the end:\n' + \
        '\n'.join(e.message[:200] for e in entries)
    started, ended = log.expect(start, 'query', 'start', 'VERBOSE'), log.expect(end, 'query', 'success', 'VERBOSE')
    for fields, name in ((started, 'start'), (ended, 'end')):
        assert fields['query'] == text, f'{name} entry query text {fields["query"]!r}'
        assert fields['database'] == 'neo4j', f'{name} entry database {fields["database"]!r}'
        assert fields['executingUser'] == 'neo4j', f'{name} entry user {fields["executingUser"]!r}'
        assert fields['queryParameters'] == '{}', f'{name} entry parameters {fields["queryParameters"]!r}'
    assert started['id'] == ended['id'] and started['transactionId'] == ended['transactionId'], (started, ended)
    assert started['runtime'] == 'null' and ended['runtime'] != 'null', (started['runtime'], ended['runtime'])
    found['end'] = ended
    return (f"start and success entry at VERBOSE, id {ended['id']}, transaction id {ended['transactionId']}, "
            f"runtime {ended['runtime']}, {ended['elapsedTimeMs']} ms")


def check_http_source(log, server, found):
    """The source segment of a query over the transactional endpoint is Community's HTTP connection details ending in
    the request path, and a query over the Query API writes its end entry with the Bolt shape naming the API as the
    client, since Neo4j serves that API over an in-process Bolt channel."""
    assert 'end' in found, 'the verbose-start-end probe found no end entry'
    source = found['end']['source']
    assert source.startswith(HTTP_SOURCE), f'source {source!r} is not an HTTP source'
    assert source.endswith('\t/db/neo4j/tx/commit'), f'source {source!r} does not end with the request path'
    text = f"RETURN '{marker()}' AS query_api"
    query_api(server, text)
    end = log.await_entry(f'the end of {text!r}', lambda e: text in e.message and not e.message.startswith('Query started: '))
    api_source = log.expect(end, 'query', 'success', 'VERBOSE')['source']
    assert api_source.startswith(QUERY_API_SOURCE), f'Query API source {api_source!r} does not name the API over its Bolt channel'
    assert '\tserverlocal:' in api_source and api_source.endswith('>'), api_source
    return f'transactional endpoint source {source!r}, Query API source {api_source!r}'


def check_transaction(log, server):
    """With transaction logging at VERBOSE a committed transaction writes its start and commit entries of the
    inventory's shape, the commit carrying the metadata the client attached."""
    set_config(server, TRANSACTION_SETTING, 'VERBOSE')
    try:
        tag = marker()
        transaction(server, tag, ['RETURN 1'])
        commit = log.await_entry(f'the commit of the transaction tagged {tag}',
                                 lambda e: e.message.startswith('Transaction committed: ') and tag in e.message)
        fields = log.expect(commit, 'transaction', 'commit', 'VERBOSE')
        assert fields['transaction.annotationData'] == f"{{app: '{tag}'}}", fields['transaction.annotationData']
        assert fields['transaction.database'] == 'neo4j' and fields['transaction.executingUser'] == 'neo4j', fields
        assert fields['transaction.source'].startswith(HTTP_SOURCE), fields['transaction.source']
        transaction_id = fields['transaction.transactionId']
        start = log.await_entry(f'the start of transaction {transaction_id}',
                                lambda e: e.message.startswith(f'Transaction started: transaction id:{transaction_id} - '))
        log.expect(start, 'transaction', 'start', 'VERBOSE')
        return f"start and commit entry of transaction {transaction_id}, open {fields['transaction.openTimeMs']} ms, {fields['transaction.annotationData']}"
    finally:
        set_config(server, TRANSACTION_SETTING, '')


def check_failure(log, server):
    """A query that fails writes one failed-query entry at ERROR with the reason last and the stack trace after."""
    variable = 'qlc_undefined_' + uuid.uuid4().hex[:12]
    text = f'MATCH (n) RETURN {variable}'
    failing(server, text)
    entry = log.await_entry(f'the failed-query entry of {text!r}', lambda e: text in e.message and e.level == 'ERROR')
    fields = log.expect(entry, 'query', 'fail', 'VERBOSE')
    assert fields['query'] == text, fields['query']
    assert fields['failureReason'] and f'`{variable}` not defined' in fields['failureReason'], fields['failureReason']
    assert fields['stacktrace'] and '\n\tat ' in fields['stacktrace'], f'no stack trace after the entry: {entry.message[-300:]!r}'
    errors = [e for e in log.containing(text) if e.level == 'ERROR']
    assert len(errors) == 1, f'{len(errors)} failed-query entries for one failure'
    return (f"one failed-query entry at ERROR, reason {fields['failureReason'].splitlines()[0]!r}, "
            f"stack trace from {fields['stacktrace'].split(':', 1)[0]}")


def check_dynamic_info(log, server):
    """CALL dbms.setConfigValue switches the detail level to INFO with a one-second threshold: a fast query is not
    written, a failure is, as a failed-query entry at INFO (no id: prefix)."""
    for setting, value in STARTUP_SETTINGS.items():
        set_config(server, setting, value)
    try:
        expect_info_with_threshold(log, server, 'dynamic')
        return 'a fast query is hidden by the 1 s threshold, a failure is written at INFO without the id: prefix'
    finally:
        reset_startup_settings(server)


def check_off(log, server):
    """With the detail level OFF no query entry is written, a failure included, while transaction entries still are:
    the committed transaction that ran the query is the sentinel."""
    set_config(server, ENABLED_SETTING, 'OFF')
    set_config(server, TRANSACTION_SETTING, 'VERBOSE')
    try:
        variable = 'qlc_off_' + uuid.uuid4().hex[:12]
        failing(server, f'MATCH (n) RETURN {variable}')
        tag = marker()
        text = f"RETURN '{tag}' AS off_probe"
        transaction(server, tag, [text])
        log.await_entry(f'the commit of the transaction tagged {tag}',
                        lambda e: e.message.startswith('Transaction committed: ') and tag in e.message)
        written = log.containing(text) + log.containing(variable)
        assert not written, f'a query entry was written at OFF: {written[0].message[:200]!r}'
        return 'no query entry for a query and a failure at OFF; the transaction entries still arrive'
    finally:
        set_config(server, TRANSACTION_SETTING, '')
        set_config(server, ENABLED_SETTING, '')


def check_obfuscate(log, server):
    """db.logs.query.obfuscate_literals applies after CALL db.clearQueryCaches(): the literal is masked in the start
    and the end entry, so the entries are found by an alias."""
    set_config(server, 'db.logs.query.obfuscate_literals', 'true')
    try:
        succeeding(server, 'CALL db.clearQueryCaches()')
        secret, alias = marker(), 'qlc_alias_' + uuid.uuid4().hex[:12]
        text = f"RETURN '{secret}' AS {alias}"
        succeeding(server, text)
        end = log.await_entry(f'the end entry of the query aliased {alias}',
                              lambda e: alias in e.message and not e.message.startswith('Query started: '))
        fields = log.expect(end, 'query', 'success', 'VERBOSE')
        assert fields['query'] == f'RETURN ****** AS {alias}', fields['query']  # the literal, quotes included, is masked
        start = log.await_entry(f'the start entry of the query aliased {alias}',
                                lambda e: alias in e.message and e.message.startswith('Query started: '))
        assert '******' in start.message, start.message[:200]
        assert secret not in log.text(), 'the literal reached the query log'
        return f"literal masked in both entries: {fields['query']!r}"
    finally:
        set_config(server, 'db.logs.query.obfuscate_literals', '')


def check_json_fields(log, server, line):
    """The manual's JSON switch on the shipped conf/server-logs.xml: after a restart every entry is one JSON object
    whose keys and values are the inventory's for its kind and detail level."""
    server.stop_process()
    xml = server.home / 'conf/server-logs.xml'
    xml.write_text(json_layout(xml.read_text()))
    plain = server.home / 'logs/query.log'
    if plain.exists():
        plain.rename(plain.with_suffix('.log.plain'))
    server.start()
    reset_startup_settings(server)  # the restarted server read the startup settings from neo4j.conf again
    text = f"RETURN '{marker()}' AS json_fields"
    succeeding(server, text)
    variable = 'qlc_json_undefined_' + uuid.uuid4().hex[:12]
    failed_text = f'MATCH (n) RETURN {variable}'
    failing(server, failed_text)
    problems = []
    for kind, query in (('start', text), ('success', text), ('fail', failed_text)):
        entry = log.await_json_entry(f'event {kind} and query {query!r}',
                                     lambda e, kind=kind, query=query: e.get('event') == kind and e.get('query') == query)
        problems += [f'{kind}: {problem}' for problem in json_problems(entry, kind, 'VERBOSE', line, log.inventory)]
        if kind == 'fail':
            assert f'`{variable}` not defined' in entry.get('failureReason', ''), entry.get('failureReason')
            assert isinstance(entry.get('errorInfo'), dict) and 'GQLSTATUS' in entry['errorInfo'], entry.get('errorInfo')
            assert entry.get('stacktrace', '').startswith('org.neo4j.'), entry.get('stacktrace')
    assert not problems, '; '.join(problems)
    count = len(log.json_entries())
    return f'start, success and failed-query entry as JSON objects with the inventory\'s keys; {count} JSON entries'


def check_form(form, image, line, inventory, server_logs_xml=None):
    """Start one delivery form with Neo4j's defaults (a logging configuration installed as conf/server-logs.xml when
    given) and run every probe; return the failed probe names."""
    probes = Probes(form)
    server = None
    found = {}
    try:
        def start():
            nonlocal server
            server = smoke.Server(form, image, settings=STARTUP_SETTINGS)
            if server_logs_xml:
                shutil.copyfile(server_logs_xml, server.home / 'conf/server-logs.xml')
            server.start()
            return (f'answers Cypher, work directory {server.work}, started with '
                    + ', '.join(f'{k}={v}' for k, v in STARTUP_SETTINGS.items())
                    + (f', logging configuration {server_logs_xml}' if server_logs_xml else ''))
        if not probes.run('start', start):
            return probes.failures
        log = QueryLog(server, inventory)
        probes.run('startup-settings', lambda: check_startup_settings(log, server))
        probes.run('verbose-start-end', lambda: check_verbose_start_end(log, server, found))
        probes.run('http-source', lambda: check_http_source(log, server, found))
        probes.run('transaction', lambda: check_transaction(log, server))
        probes.run('failure', lambda: check_failure(log, server))
        probes.run('dynamic-info', lambda: check_dynamic_info(log, server))
        probes.run('off', lambda: check_off(log, server))
        probes.run('obfuscate', lambda: check_obfuscate(log, server))
        if form == 'distribution':
            probes.run('json-fields', lambda: check_json_fields(log, server, line))
        else:
            probes.skip('json-fields', 'the shipped conf/server-logs.xml is edited on the distribution form only')
    finally:
        if server:
            try:
                server.close()
            except Exception as exc:  # the probes' results stand
                print(f'WARN {form} cleanup: {exc}', flush=True)
    return probes.failures


def parse_arguments(argv):
    """The forms to check (all three by default; the two with a conf/ directory when a logging configuration is
    given, which the docker form cannot take), the image and the logging configuration."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('forms', nargs='*', metavar='form',
                        help='plugin, distribution or docker; all three when none is given')
    parser.add_argument('--image', help="DozerDB image of the docker form (default: the line manifest's local_image)")
    parser.add_argument('--server-logs-xml', type=Path, metavar='FILE',
                        help='a whole logging configuration installed as conf/server-logs.xml before the plugin and '
                             'distribution forms start (the overhead mitigations under scripts/fixtures/); the '
                             'docker form is then not checked')
    args = parser.parse_args(argv)
    for form in args.forms:
        if form not in FORMS:
            parser.error(f'unknown form {form!r}; choose from {", ".join(FORMS)}')
    if args.server_logs_xml:
        if 'docker' in args.forms:
            parser.error('--server-logs-xml applies to the plugin and distribution forms, not to docker')
        args.forms = args.forms or [form for form in FORMS if form != 'docker']
    args.forms = args.forms or list(FORMS)
    return args


def main(argv=None):
    args = parse_arguments(argv)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit(143)))
    manifest = release_line.required_manifest()
    image = release_line.default_image(args.image) if 'docker' in args.forms else None
    inventory = Inventory.load()
    failures = []
    for form in args.forms:
        failures += check_form(form, image, manifest.line, inventory, args.server_logs_xml)
    print('RESULT', 'PASS' if not failures else f'FAIL ({", ".join(failures)})', flush=True)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
