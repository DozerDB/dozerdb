#!/usr/bin/env python3
# Copyright (c) DozerDB
# ALL RIGHTS RESERVED.
#
# DozerDb is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.

"""Generate the monitoring documentation from the metric inventory.

The inventory (metrics/src/main/resources/org/dozerdb/metrics/inventory/metrics-inventory.tsv) is the single
source for the compatibility matrix and the differences page. The monitoring pages are hand-written Markdown with
generated blocks between `<!-- BEGIN GENERATED: <block> -->` and `<!-- END GENERATED: <block> -->` lines; this
script rewrites those blocks and leaves everything else as it is. It also checks the settings table of the
enable-and-expose page against the settings declaration (MetricsSettings.java), and that manual references name
the version the inventory tracks without linking to the upstream site.

    python3 scripts/metrics-docs.py           # rewrite the generated blocks
    python3 scripts/metrics-docs.py --check   # exit 1 when a page is stale, the settings table disagrees, or a
                                              # page links upstream or names another manual version

Only the inventory's public columns are read. The `notes` column is maintainer text and is never read here.
"""
import dataclasses
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from generated_blocks import SHARED_PAGE_BLOCKS, foreign_blocks, check_pages, main as generator_main, update_page, write_pages  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
INVENTORY = ROOT / 'metrics/src/main/resources/org/dozerdb/metrics/inventory/metrics-inventory.tsv'
SETTINGS = ROOT / 'metrics/src/main/java/org/dozerdb/metrics/settings/MetricsSettings.java'
DISTRIBUTION_CONFIG = ROOT / 'distribution/config.json'

PUBLIC_COLUMNS = ('section', 'name_template', 'type', 'unit', 'scope', 'scope_inferred', 'lifecycle', 'tier',
                  'reason', 'docs_anchor', 'deprecated')
PREFIX = '<prefix>'
GLOBAL_SEGMENT = PREFIX + '.dbms.'
DATABASE_SEGMENT = PREFIX + '.database.<db>.'
# A link into the Operations Manual; group 1 is the path segment that names the manual: a version, which is a
# calendar version (2026.10) or a major alone (5), or an alias such as `current`, which is not a version.
MANUAL_LINK = re.compile(r'https://neo4j\.com/docs/operations-manual/([^/\s)]+)/')


@dataclasses.dataclass(frozen=True)
class Entry:
    """One inventory row, public columns only."""
    section: str
    name_template: str
    type: str
    unit: str
    scope: str
    scope_inferred: bool
    lifecycle: str
    tier: str
    reason: str
    docs_anchor: str
    deprecated: bool

    @property
    def metric_name(self):
        """The name without prefix and scope segment, for example `transaction.committed`."""
        for segment in (GLOBAL_SEGMENT, DATABASE_SEGMENT, PREFIX + '.'):
            if self.name_template.startswith(segment):
                return self.name_template[len(segment):]
        raise ValueError(f'inventory row {self.name_template}: the name does not start with {PREFIX}.')

    @property
    def full_name(self):
        """`<prefix>.dbms.<metric>` or `<prefix>.database.<db>.<metric>`, whatever the manual's spelling."""
        return (GLOBAL_SEGMENT if self.scope == 'global' else DATABASE_SEGMENT) + self.metric_name

    @property
    def emitted(self):
        return self.tier in ('Compatible', 'Partial')


def read_inventory(text):
    """The inventory rows in page order. Columns are looked up by name; `notes` is not read."""
    columns = None
    entries = []
    for line in text.splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        cells = line.split('\t')
        if columns is None:
            columns = {name: index for index, name in enumerate(cells)}
            missing = [c for c in PUBLIC_COLUMNS if c not in columns]
            if missing:
                raise ValueError(f'the inventory has no column {", ".join(missing)}')
            continue
        if len(cells) < len(columns):
            raise ValueError(f'inventory row has too few cells: {line!r}')
        value = {c: cells[columns[c]] for c in PUBLIC_COLUMNS}
        entries.append(Entry(
            section=value['section'], name_template=value['name_template'], type=value['type'], unit=value['unit'],
            scope=value['scope'], scope_inferred=value['scope_inferred'] == 'true', lifecycle=value['lifecycle'],
            tier=value['tier'], reason=value['reason'], docs_anchor=value['docs_anchor'],
            deprecated=value['deprecated'] == 'true'))
    return entries


def is_manual_version(segment):
    """Whether a manual path segment is a version (`2026.10`, `5`) rather than an alias such as `current`."""
    return all(part.isdecimal() for part in segment.split('.'))


def manual_version(entries):
    """The Operations Manual version every row's reference link names; more than one is an error."""
    versions = set()
    for entry in entries:
        match = MANUAL_LINK.match(entry.docs_anchor)
        if not match or not is_manual_version(match.group(1)):
            raise ValueError(f'inventory row {entry.name_template}: {entry.docs_anchor} is not an Operations Manual '
                             'link naming a version')
        versions.add(match.group(1))
    if len(versions) != 1:
        raise ValueError(f'the inventory links to more than one manual version: {sorted(versions)}')
    return versions.pop()


# The settings declaration ---------------------------------------------------------------------------------------

BYTE_UNITS = ('B', 'KiB', 'MiB', 'GiB', 'TiB')
DURATION_UNITS = {'ofMillis': 'ms', 'ofSeconds': 's', 'ofMinutes': 'm'}


def call_arguments(text, open_paren):
    """The top-level arguments of the call whose opening parenthesis is at `open_paren`, and the end index."""
    depth, quoted, start, arguments = 0, False, open_paren + 1, []
    for index in range(open_paren, len(text)):
        char = text[index]
        if quoted:
            quoted = char != '"' or text[index - 1] == '\\'
        elif char == '"':
            quoted = True
        elif char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
            if depth == 0:
                arguments.append(text[start:index].strip())
                return arguments, index
        elif char == ',' and depth == 1:
            arguments.append(text[start:index].strip())
            start = index + 1
    raise ValueError('unbalanced parentheses in the settings declaration')


def default_display(expression):
    """How Neo4j's settings reference renders a declared default; an unknown expression is an error."""
    expression = ' '.join(expression.split())
    if expression in ('true', 'false') or re.fullmatch(r'[0-9]+', expression):
        return expression
    if expression == 'List.of()':
        return 'empty'
    if expression == 'listOf(GLOBBING_PATTERN).parse(DEFAULT_FILTER)':
        return 'the default filter below'
    if match := re.fullmatch(r'"([^"]*)"', expression):
        return match.group(1)
    if match := re.fullmatch(r'Path\.of\("([^"]*)"\)', expression):
        return match.group(1)
    if match := re.fullmatch(r'Duration\.(ofMillis|ofSeconds|ofMinutes)\(([0-9]+)\)', expression):
        return match.group(2) + DURATION_UNITS[match.group(1)]
    if match := re.fullmatch(r'[0-9]+L?( \* [0-9]+L?)*', expression):
        value = 1
        for factor in expression.split('*'):
            value *= int(factor.strip().rstrip('L'))
        unit = 0
        while unit + 1 < len(BYTE_UNITS) and value >= 1024 ** (unit + 1):
            unit += 1
        return f'{value / 1024 ** unit:.2f}{BYTE_UNITS[unit]}'
    if match := re.fullmatch(r'CompressionOption\.([A-Z]+)', expression):
        return match.group(1)
    if match := re.fullmatch(r'new SocketAddress\(([0-9]+)\)', expression):
        return ':' + match.group(1)
    if match := re.fullmatch(r'new SocketAddress\("([^"]+)", ([0-9]+)\)', expression):
        return f'{match.group(1)}:{match.group(2)}'
    raise ValueError(f'cannot render the default {expression!r}; teach default_display() how Neo4j shows it')


def declared_settings(java):
    """(name, default as the settings reference shows it) for every `newBuilder(...)` call, in declaration order."""
    settings = []
    for match in re.finditer(r'\bnewBuilder\s*\(', java):
        arguments, _ = call_arguments(java, match.end() - 1)
        name = re.fullmatch(r'"([^"]+)"', arguments[0])
        if not name or len(arguments) != 3:
            raise ValueError(f'unexpected setting declaration {arguments}')
        settings.append((name.group(1), default_display(arguments[2])))
    return settings


def default_filter(java):
    """The patterns of DEFAULT_FILTER, the default of server.metrics.filter."""
    match = re.search(r'\bString DEFAULT_FILTER\s*=(.*?);', java, re.DOTALL)
    if not match:
        raise ValueError('the settings declaration has no DEFAULT_FILTER')
    return ''.join(re.findall(r'"([^"]*)"', match.group(1))).split(',')


# The default filter ---------------------------------------------------------------------------------------------

DEFAULT_PREFIX = 'neo4j'
NEUTRAL_DATABASE = 'zzdatabase'
NEUTRAL_INSTANCE = 'zzinstance'
INSTANCE_PLACEHOLDER = re.compile(r'<(pool|gc|bufferpool)>')

def glob_matches(pattern, name):
    """Neo4j's GlobbingPattern: `*` any sequence, `?` one character, the whole name must match."""
    regex = ''.join('.*' if c == '*' else '.' if c == '?' else re.escape(c) for c in pattern)
    return re.fullmatch(regex, name) is not None


def default_filter_status(entry, patterns):
    """`yes` when the default filter admits the metric, `some` when it admits only particular JVM or memory pool
    instances (`*vm.memory.pool.g1_eden_space`), `no` otherwise. Names use the default prefix and a database
    name no pattern mentions."""
    template = DEFAULT_PREFIX + entry.full_name[len(PREFIX):].replace('<db>', NEUTRAL_DATABASE)

    def admitted(instance):
        name = INSTANCE_PLACEHOLDER.sub(instance, template)
        return any(glob_matches(p, name) for p in patterns)

    if admitted(NEUTRAL_INSTANCE):
        return 'yes'
    if INSTANCE_PLACEHOLDER.search(template):
        # An instance a pattern names is one of its dot-separated segments without wildcards.
        candidates = {segment for p in patterns for segment in p.split('.') if re.fullmatch(r'[A-Za-z0-9_]+', segment)}
        if any(admitted(candidate) for candidate in candidates):
            return 'some'
    return 'no'


# Rendering ------------------------------------------------------------------------------------------------------

TIERS = ('Compatible', 'Partial', 'Unsupported', 'Not applicable')
TIER_MEANING = {
    'Compatible': 'Emitted with the documented name and type; a test provokes the real event and observes the change.',
    'Partial': 'Emitted with the documented name and type, but the value differs from Neo4j, stays at zero on '
               'DozerDB, or could not be verified.',
    'Unsupported': 'Not emitted although a single server could have it.',
    'Not applicable': 'Not emitted because it measures a capability a single-instance server does not have '
                      '(clustering).',
}
REASON_ON_DIFFERENCES_PAGE = ('Partial', 'Unsupported')
SETTING_NAME = re.compile(r'(?<![`\w.])((?:internal|server|dbms|db)\.[a-z0-9_.]*[a-z0-9_](?:=[a-z0-9]+)?)')


def cell(text):
    return text.replace('|', '\\|')


def sentence(text):
    text = text.strip()
    return text if text.endswith('.') else text + '.'


def reason_text(reason):
    """The inventory's reason as a sentence, with setting names as code."""
    return SETTING_NAME.sub(r'`\1`', sentence(reason))


def anchor(heading):
    """The anchor GitHub gives a Markdown heading."""
    return re.sub(r'[^a-z0-9 _-]', '', heading.lower()).replace(' ', '-')


def manual_reference(entry):
    version = MANUAL_LINK.match(entry.docs_anchor).group(1)
    return f'Operations Manual {version}, *Metrics reference*, {entry.section}'


def note(entry):
    parts = []
    if entry.lifecycle == 'unsupported feature':
        parts.append('Neo4j labels it an unsupported feature.')
    elif entry.lifecycle != '-':
        parts.append(sentence(entry.lifecycle))
    if entry.deprecated and 'Deprecated' not in entry.lifecycle:
        parts.append('Deprecated.')
    if entry.scope_inferred:
        parts.append('The manual does not state the scope.')
    if entry.reason:
        parts.append(reason_text(entry.reason))
    return ' '.join(parts)


def sections(entries):
    grouped = {}
    for entry in entries:
        grouped.setdefault(entry.section, []).append(entry)
    return grouped


def render_version(manual, neo4j_version):
    line = '.'.join(neo4j_version.split('.')[:2])
    return (f'This page tracks the Neo4j Operations Manual **{manual}** '
            f'(*Monitoring* page) for the DozerDB **{line}** release line: '
            f'DozerDB releases built on Neo4j `{neo4j_version}` (`{neo4j_version}-<dozerdb-version>`).\n')


def render_tier_summary(entries):
    lines = ['| Tier | Metrics | Meaning |', '| --- | --- | --- |']
    for tier in TIERS:
        meaning = TIER_MEANING[tier]
        if tier in REASON_ON_DIFFERENCES_PAGE:
            meaning += ' The reason is on the [differences page](differences.md).'
        lines.append(f'| {tier} | {sum(e.tier == tier for e in entries)} | {meaning} |')
    lines.append(f'| **All documented metrics** | **{len(entries)}** | |')
    return '\n'.join(lines) + '\n'


def render_matrix(entries, patterns):
    grouped = sections(entries)
    lines = ['| Section | ' + ' | '.join(TIERS) + ' |', '| --- |' + ' --- |' * len(TIERS)]
    for section, rows in grouped.items():
        counts = ' | '.join(str(sum(e.tier == tier for e in rows)) for tier in TIERS)
        lines.append(f'| [{section}](#{anchor(section)}) | {counts} |')
    totals = ' | '.join(f'**{sum(e.tier == tier for e in entries)}**' for tier in TIERS)
    lines.append(f'| **Total** | {totals} |')
    for section, rows in grouped.items():
        lines += ['', f'## {section}', '',
                  '| Metric | Type | Scope | Tier | Default filter | Note | Neo4j reference |',
                  '| --- | --- | --- | --- | --- | --- | --- |']
        for entry in rows:
            kind = entry.type if entry.unit == '-' else f'{entry.type} ({entry.unit})'
            enabled = default_filter_status(entry, patterns) if entry.emitted else '-'
            lines.append(f'| `{entry.full_name}` | {kind} | {entry.scope} | {entry.tier} | {enabled} | '
                         f'{cell(note(entry))} | {manual_reference(entry)} |')
    return '\n'.join(lines) + '\n'


def render_differences(entries):
    lines = []
    for tier in TIERS[1:]:
        rows = [e for e in entries if e.tier == tier]
        lines += [f'### {tier} ({len(rows)})', '', TIER_MEANING[tier], '']
        if not rows:
            lines += ['No metric of this release line has this tier.', '']
        by_reason = {}
        for entry in rows:
            by_reason.setdefault(reason_text(entry.reason), []).append(entry)
        for reason, group in by_reason.items():
            lines += [f'**{cell(reason)}**', '']
            lines += [f'- `{e.full_name}` ({manual_reference(e)})' for e in group]
            lines.append('')
    return '\n'.join(lines).rstrip('\n') + '\n'


def render_default_filter(patterns):
    return '```properties\nserver.metrics.filter=' + ','.join(patterns) + '\n```\n'


# Pages ----------------------------------------------------------------------------------------------------------

PAGES = {
    'README.md': SHARED_PAGE_BLOCKS['metrics'],
    'enable-and-expose.md': ('version', 'default-filter'),
    'compatibility-matrix.md': ('version', 'matrix'),
    'differences.md': ('version', 'differences'),
}
SETTINGS_PAGE = 'enable-and-expose.md'
# The query log generator's block on the monitoring README, left as it is.
FOREIGN = foreign_blocks('metrics')
SETTING_ROW = re.compile(r'^\|\s*`(server\.[a-z_.]+)`\s*\|([^|]*)\|', re.MULTILINE)


def shown_default(text):
    text = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', text)
    return text.replace('`', '').strip()


def settings_problems(page, declared):
    """Disagreements between the page's settings table and the declaration, by name and default."""
    shown = {m.group(1): shown_default(m.group(2)) for m in SETTING_ROW.finditer(page)}
    problems = []
    for name, default in declared:
        if name not in shown:
            problems.append(f'{name}: declared but missing from the page')
        elif shown[name] != default:
            problems.append(f'{name}: the page shows the default {shown[name]}, the declaration {default}')
    declared_names = {name for name, _ in declared}
    problems += [f'{name}: on the page but not declared' for name in shown if name not in declared_names]
    return problems


def manual_link_problems(page, manual):
    """Reject upstream links and stale manual versions in public documentation."""
    problems = []
    for number, line in enumerate(page.splitlines(), start=1):
        if re.search(r'neo4j\.com', line, re.IGNORECASE):
            problems.append(f'line {number}: use the manual version and page title instead of an upstream link')
        for version in re.findall(r'Operations Manual (?:\*\*)?([0-9][0-9.]*|current)\b', line):
            if version != manual:
                problems.append(f'line {number}: names the Operations Manual {version}, the inventory tracks {manual}')
    return problems


def docs_directory(root=ROOT):
    """The monitoring pages' directory, found by its pages so the script works wherever the docs tree lives."""
    found = sorted(p.parent for p in (root / 'docs').glob('**/monitoring/compatibility-matrix.md'))
    if len(found) != 1:
        raise ValueError(f'expected one monitoring documentation directory under docs/, found {found}')
    return found[0]


def generated_blocks(root=ROOT):
    entries = read_inventory((root / INVENTORY.relative_to(ROOT)).read_text(encoding='utf-8'))
    java = (root / SETTINGS.relative_to(ROOT)).read_text(encoding='utf-8')
    neo4j_version = json.loads((root / DISTRIBUTION_CONFIG.relative_to(ROOT)).read_text())['neo4jVersion']
    patterns = default_filter(java)
    manual = manual_version(entries)
    content = {
        'version': render_version(manual, neo4j_version),
        'tier-summary': render_tier_summary(entries),
        'matrix': render_matrix(entries, patterns),
        'differences': render_differences(entries),
        'default-filter': render_default_filter(patterns),
    }
    pages = {page: {name: content[name] for name in names} for page, names in PAGES.items()}
    return pages, declared_settings(java), manual


def check(root=ROOT):
    """Every reason the committed pages are stale; empty when they are current."""
    directory = docs_directory(root)
    blocks, declared, manual = generated_blocks(root)
    problems = check_pages(directory, blocks, root, also=lambda text: manual_link_problems(text, manual),
                           foreign=FOREIGN)
    settings_page = (directory / SETTINGS_PAGE).read_text(encoding='utf-8')
    problems += [f'{SETTINGS_PAGE}: {p}' for p in settings_problems(settings_page, declared)]
    return problems


def write(root=ROOT):
    blocks, _, _ = generated_blocks(root)
    write_pages(docs_directory(root), blocks, root, foreign=FOREIGN)


def main(argv=None):
    return generator_main(argv, __doc__.splitlines()[0], check=check, write=write,
                          script='scripts/metrics-docs.py', pages='monitoring pages')


if __name__ == '__main__':
    sys.exit(main())
