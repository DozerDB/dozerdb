#!/usr/bin/env python3
"""Generate the query log documentation from the field inventory.

The inventory (scripts/fixtures/query-log-entries.json) is the one record of every documented query log setting,
field and plain-text segment and of the shapes DozerDB defines where the manual is silent; the module's renderer tests
and the release-gate check read the same file. The query log pages of the monitoring documentation are hand-written
Markdown with generated blocks between `<!-- BEGIN GENERATED: <block> -->` and `<!-- END GENERATED: <block> -->` lines;
this script rewrites those blocks for the release line that distribution/config.json pins (Neo4j major 5 is the LTS
line, a calendar version the calendar line) and leaves everything else as it is. Neo4j's documentation is mentioned by
manual version and page title and never linked, so the check also fails on any neo4j.com URL in a query log page.

    python3 scripts/query-log-docs.py           # rewrite the generated blocks
    python3 scripts/query-log-docs.py --check   # exit 1 when a page is stale or names neo4j.com

Only the inventory's public text is read: a row's status reason per line and a shape's text. The `notes` of a row are
maintainer text and are never rendered.
"""
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from generated_blocks import SHARED_PAGE_BLOCKS, check_pages, foreign_blocks, main as generator_main, write_pages  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
INVENTORY = ROOT / 'scripts/fixtures/query-log-entries.json'
DISTRIBUTION_CONFIG = ROOT / 'distribution/config.json'

TOP_LEVEL = ('lines', 'tiers', 'settings', 'fields', 'examples', 'shapes')
TIER_LABEL = {'compatible': 'Compatible', 'partial': 'Partial', 'unsupported': 'Unsupported',
              'not-applicable': 'Not applicable'}
TIERS = tuple(TIER_LABEL)
SHAPES_ANCHOR = 'shapes-dozerdb-defines'
TIER_MEANING = {
    'compatible': "The Operations Manual's description holds on DozerDB unchanged: the same name, default, placement "
                  "and value.",
    'partial': "Honoured or written with a recorded difference: a shape the manual leaves open and DozerDB defined, or "
               "a value this release line cannot supply the same way. The reason is in the Note column and the shape "
               "under [Shapes DozerDB defines]({page}#" + SHAPES_ANCHOR + ").",
    'unsupported': "Not read although DozerDB could read it, with the reason recorded.",
    'not-applicable': "A value that does not exist on this release line, which DozerDB therefore never writes.",
}
NEO4J_COM = re.compile(r'neo4j\.com')
# A setting name in a reason, backticked on the page; a procedure such as db.clearQueryCaches() is not one.
SETTING_NAME = re.compile(r'(?<![`\w.])((?:internal|server|dbms|db)\.[a-z0-9_.]*[a-z0-9_](?:=[A-Za-z0-9]+)?)(?![A-Za-z(])')


def release_line(neo4j_version):
    """`lts` for a Neo4j 5 version, `calendar` otherwise."""
    return 'lts' if neo4j_version.split('.')[0] == '5' else 'calendar'


def manual_version(inventory, line):
    return inventory['lines'][line]['manual']


def read_inventory(text):
    """The inventory, checked for what the generator reads: the arrays, and a status per line with a known tier."""
    data = json.loads(text)
    missing = [key for key in TOP_LEVEL if key not in data]
    if missing:
        raise ValueError(f'the inventory has no {", ".join(missing)}')
    for kind in ('settings', 'fields'):
        for row in data[kind]:
            for line in data['lines']:
                tier = row['status'][line]['tier']
                if tier not in TIER_LABEL:
                    raise ValueError(f"{kind[:-1]} {row['name']}: the tier {tier!r} on the {line} line is not one "
                                     f"of {list(TIER_LABEL)}")
    return data


# Rendering ------------------------------------------------------------------------------------------------------

def cell(text):
    return text.replace('|', '\\|')


def prose(text):
    """A reason as the page shows it: setting names in backticks, pipes escaped for a table cell."""
    return cell(SETTING_NAME.sub(r'`\1`', text))


def tier_of(row, line):
    return row['status'][line]['tier']


def render_version(manual, neo4j_version):
    line = '.'.join(neo4j_version.split('.')[:2])
    return (f'This page tracks the Neo4j Operations Manual **{manual}** for the DozerDB **{line}** release line: '
            f'DozerDB releases built on Neo4j `{neo4j_version}` (`{neo4j_version}-<dozerdb-version>`).\n')


def setting_cells(setting, line):
    """The default and dynamic cells of a setting on a line; `-` for both where that line's Neo4j does not declare it."""
    default = setting['default'][line]
    if default is None:
        return '-', '-'
    return f'`{default}`', 'yes' if setting['dynamic'] else 'no'


def settings_table(inventory, line, headers, cells):
    """One row per setting: its name, the line's default and dynamic cells, then `cells(setting)` under `headers`."""
    lines = ['| Setting | Default | Dynamic | ' + ' | '.join(headers) + ' |', '| --- | --- | --- | ' + ' | '.join('---' for _ in headers) + ' |']
    for setting in inventory['settings']:
        default, dynamic = setting_cells(setting, line)
        lines.append(f"| `{setting['name']}` | {default} | {dynamic} | " + ' | '.join(cells(setting)) + ' |')
    return '\n'.join(lines) + '\n'


def render_settings(inventory, line):
    return settings_table(inventory, line, ('DozerDB',), lambda s: (prose(s['status'][line]['reason']),))


def render_settings_matrix(inventory, line):
    return settings_table(inventory, line, ('Tier', 'Note'),
                          lambda s: (TIER_LABEL[tier_of(s, line)], prose(s['status'][line]['reason'])))


def field_name(field):
    """The JSON key and the plain segment as the matrix names the row: `key` / `segment`, or whichever it has."""
    parts = []
    if field['jsonKey'] is not None:
        parts.append(f"`{field['jsonKey']}`")
    if field['plain'] is not None:
        parts.append(f"`{field['plain']['segment']}`")
    return ' / '.join(parts)


def layout_of(field):
    if field['jsonKey'] is not None and field['plain'] is not None:
        return 'both'
    return 'json' if field['jsonKey'] is not None else 'plain'


def appears_in(field):
    text = f"{', '.join(field['entries'])} ({', '.join(field['levels'])})"
    return text + '; optional' if field['optional'] else text


# Headed so that no section shares its anchor with a shape ("Transaction entries" is one).
FIELD_SECTIONS = (('common', 'Fields of every entry'), ('query', 'Fields of a query entry'),
                  ('transaction', 'Fields of a transaction entry'))


def render_fields_matrix(inventory, line):
    lines = []
    for type_, title in FIELD_SECTIONS:
        lines += [f'### {title}', '', '| Field | Layout | Appears in | Tier | Note |', '| --- | --- | --- | --- | --- |']
        for field in inventory['fields']:
            if field['type'] != type_:
                continue
            lines.append(f"| {cell(field_name(field))} | {layout_of(field)} | {appears_in(field)} | "
                         f"{TIER_LABEL[tier_of(field, line)]} | {prose(field['status'][line]['reason'])} |")
        lines.append('')
    return '\n'.join(lines).rstrip('\n') + '\n'


def render_tier_summary(inventory, line, page):
    """The tier counts of the line; `page` is the compatibility page's path relative to the page the block is on
    (empty on that page itself), for the link to the shapes."""
    lines = ['| Tier | Settings | Fields and segments | Meaning |', '| --- | --- | --- | --- |']
    for tier in TIERS:
        settings = sum(1 for s in inventory['settings'] if tier_of(s, line) == tier)
        fields = sum(1 for f in inventory['fields'] if tier_of(f, line) == tier)
        lines.append(f'| {TIER_LABEL[tier]} | {settings} | {fields} | {TIER_MEANING[tier].format(page=page)} |')
    lines.append(f"| **All documented** | **{len(inventory['settings'])}** | **{len(inventory['fields'])}** | |")
    return '\n'.join(lines) + '\n'


def partial_rows(inventory, line):
    return [row['name'] for kind in ('settings', 'fields') for row in inventory[kind] if tier_of(row, line) == 'partial']


def render_shapes(inventory, line):
    """Every shape DozerDB defines, with the rows it fixes and its lines in code blocks; every Partial row of the line
    must be named by a shape that shows a line, so the page never tiers a row Partial without showing the shape."""
    examples = {example['id']: example for example in inventory['examples']}
    shown = set()
    lines = []
    for shape in inventory['shapes']:
        lines += [f"### {shape['title']}", '', shape['text'], '']
        if shape['rows']:
            lines += ['Rows: ' + ', '.join(f'`{row}`' for row in shape['rows']) + '.', '']
        if shape['examples'] or shape['lines']:
            shown.update(shape['rows'])
        for example_id in shape['examples']:
            example = examples[example_id]
            caption = f"{example['source']} (`{example['level']}` at `{example['detailLevel']}`"
            caption += ', the stack trace on the lines after):' if example['stacktrace'] else '):'
            lines += [caption, '', '```text', example['line'], '```', '']
        for literal in shape['lines']:
            lines += ['```text', literal, '```', '']
    unshown = [row for row in partial_rows(inventory, line) if row not in shown]
    if unshown:
        raise ValueError(f'Partial on the {line} line but shown by no shape with a line: {", ".join(unshown)}')
    return '\n'.join(lines).rstrip('\n') + '\n'


def neo4j_com_problems(page):
    """Every line of a page that names neo4j.com: Neo4j's documentation is mentioned, never linked."""
    return [f"line {number}: names neo4j.com; Neo4j's documentation is mentioned by manual version and page title, "
            f"never linked"
            for number, text in enumerate(page.splitlines(), start=1) if NEO4J_COM.search(text)]


# Pages ----------------------------------------------------------------------------------------------------------

COMPATIBILITY_PAGE = 'query-log-compatibility.md'
PAGES = {
    'query-logging.md': ('version', 'settings'),
    COMPATIBILITY_PAGE: ('version', 'tier-summary', 'settings-matrix', 'fields-matrix', 'shapes'),
    'README.md': SHARED_PAGE_BLOCKS['query-log'],
}
# The metrics generator's blocks on the monitoring README, left as they are.
FOREIGN = foreign_blocks('query-log')
QUERY_LOG_PAGES = ('query-logging.md', COMPATIBILITY_PAGE)


def docs_directory(root=ROOT):
    """The monitoring pages' directory, found by its page so the script works wherever the docs tree lives."""
    found = sorted(p.parent for p in (root / 'docs').glob(f'**/monitoring/{COMPATIBILITY_PAGE}'))
    if len(found) != 1:
        raise ValueError(f'expected one monitoring documentation directory under docs/, found {found}')
    return found[0]


def generated_blocks(root=ROOT):
    inventory = read_inventory((root / INVENTORY.relative_to(ROOT)).read_text(encoding='utf-8'))
    neo4j_version = json.loads((root / DISTRIBUTION_CONFIG.relative_to(ROOT)).read_text())['neo4jVersion']
    line = release_line(neo4j_version)
    content = {
        'version': render_version(manual_version(inventory, line), neo4j_version),
        'settings': render_settings(inventory, line),
        'tier-summary': render_tier_summary(inventory, line, ''),
        'query-log-tier-summary': render_tier_summary(inventory, line, COMPATIBILITY_PAGE),
        'settings-matrix': render_settings_matrix(inventory, line),
        'fields-matrix': render_fields_matrix(inventory, line),
        'shapes': render_shapes(inventory, line),
    }
    return {page: {name: content[name] for name in names} for page, names in PAGES.items()}


def check(root=ROOT):
    """Every reason the committed pages are stale or name neo4j.com; empty when they are current."""
    directory = docs_directory(root)
    problems = []
    for page in QUERY_LOG_PAGES:
        text = (directory / page).read_text(encoding='utf-8')
        problems += [f'{(directory / page).relative_to(root)}: {p}' for p in neo4j_com_problems(text)]
    return problems + check_pages(directory, generated_blocks(root), root, foreign=FOREIGN)


def write(root=ROOT):
    write_pages(docs_directory(root), generated_blocks(root), root, foreign=FOREIGN)


def main(argv=None):
    return generator_main(argv, __doc__.splitlines()[0], check=check, write=write,
                          script='scripts/query-log-docs.py', pages='query log pages')


if __name__ == '__main__':
    sys.exit(main())
