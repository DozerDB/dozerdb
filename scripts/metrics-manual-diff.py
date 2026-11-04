#!/usr/bin/env python3
"""Compare the Operations Manual's metrics reference page with the metric inventory.

Run it when a release line moves to a new Neo4j version:

    python3 scripts/metrics-manual-diff.py --version 2026.11
    python3 scripts/metrics-manual-diff.py --html reference.html   # a page saved earlier

It reads every metric table of the reference page (section, name, type marker, lifecycle label, anchor) and
prints each difference from metrics/src/main/resources/org/dozerdb/metrics/inventory/metrics-inventory.tsv:
metrics the page added or removed, and changed sections, types, lifecycle labels and anchors. It also says
when the inventory's anchors pin another manual version. It ends with RESULT PASS when nothing differs and
RESULT DIFF otherwise (exit status 1). Standard library only."""
import argparse
import csv
import re
import sys
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INVENTORY = ROOT / 'metrics/src/main/resources/org/dozerdb/metrics/inventory/metrics-inventory.tsv'
REFERENCE_URL = 'https://neo4j.com/docs/operations-manual/{version}/monitoring/metrics/reference/'
# The page's type marker ends each description; "(counter, histogram)" marks a Metrics Timer (inventory header).
TYPES = {'counter': 'counter', 'gauge': 'gauge', 'histogram': 'histogram', 'counter, histogram': 'timer'}
TYPE_MARKER = re.compile(r'\((counter|gauge|histogram|counter, histogram)\)\s*$')
UNSUPPORTED = '(unsupported feature)'
COMPARED = ('section', 'type', 'lifecycle', 'anchor')


class ReferencePage(HTMLParser):
    """The metric rows of the reference page: one dict per table row whose first header cell is "Name"."""

    def __init__(self):
        super().__init__()
        self.rows = []
        self._anchor = self._section = self._section_label = None
        self._text = self._label = None
        self._in_heading = self._in_header = self._in_label = False
        self._metric_table = False
        self._cells = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'h3':
            self._in_heading, self._anchor, self._text, self._label = True, attrs.get('id'), [], []
        elif tag == 'table':
            self._metric_table, self._text = False, None
        elif tag == 'thead':
            self._in_header = True
        elif tag == 'tr' and not self._in_header:
            self._cells = []
        elif tag in ('td', 'th'):
            self._text, self._label = [], []
        elif tag == 'span' and 'label' in (attrs.get('class') or '').split():
            self._in_label = True

    def handle_endtag(self, tag):
        if tag == 'h3':
            self._in_heading = False
            self._section = ''.join(self._text).strip()
            self._section_label = ''.join(self._label).strip() or None
        elif tag == 'span':
            self._in_label = False
        elif tag == 'thead':
            self._in_header = False
        elif tag == 'th' and self._in_header and not self._metric_table:
            self._metric_table = ''.join(self._text).strip() == 'Name'
        elif tag == 'td' and self._cells is not None:
            self._cells.append((' '.join(''.join(self._text).split()), ''.join(self._label).strip()))
        elif tag == 'tr' and self._cells is not None:
            if self._metric_table and len(self._cells) >= 2:
                self.rows.append(self._row(*self._cells[:2]))
            self._cells = None

    def handle_data(self, data):
        if self._in_label and not (self._in_heading or self._cells is not None or self._in_header):
            # A label in a paragraph between the heading and its table applies to the whole section.
            self._section_label = ((self._section_label or '') + data).strip() or None
        elif self._text is not None and (self._in_heading or self._cells is not None or self._in_header):
            (self._label if self._in_label else self._text).append(data)

    def _row(self, name_cell, description_cell):
        description, label = description_cell
        marker = TYPE_MARKER.search(description)
        lifecycle = label or ('unsupported feature' if description.startswith(UNSUPPORTED) else None)
        return {
            'name': name_cell[0].replace('<database>', '<db>'),  # the inventory's one normalisation
            'section': self._section,
            'type': TYPES[marker.group(1)] if marker else '?',
            'lifecycle': lifecycle or self._section_label or '-',
            'anchor': self._anchor,
        }


def page_rows(html):
    parser = ReferencePage()
    parser.feed(html)
    return parser.rows


def inventory_rows(text):
    lines = [line for line in text.splitlines() if line and not line.startswith('#')]
    rows = []
    for row in csv.DictReader(lines, delimiter='\t', quoting=csv.QUOTE_NONE):
        url, _, anchor = row['docs_anchor'].partition('#')
        rows.append({'name': row['name_template'], 'section': row['section'], 'type': row['type'],
                     'lifecycle': row['lifecycle'], 'anchor': anchor, 'url': url})
    return rows


def differences(page, inventory):
    """Human-readable differences between the page's rows and the inventory's, in page order."""
    documented = {row['name']: row for row in page}
    inventoried = {row['name']: row for row in inventory}
    found = []
    for row in page:
        mine = inventoried.get(row['name'])
        if mine is None:
            found.append(f"added in the manual: {row['name']} ({row['section']}, {row['type']}, {row['lifecycle']})")
            continue
        for field in COMPARED:
            if mine[field] != row[field]:
                found.append(f"changed {row['name']}: {field} {mine[field]!r} in the inventory, {row[field]!r} in the manual")
    for row in inventory:
        if row['name'] not in documented:
            found.append(f"removed from the manual: {row['name']} ({row['section']})")
    return found


def pinned_versions(inventory):
    return sorted({re.sub(r'.*/operations-manual/([^/]+)/.*', r'\1', row['url']) for row in inventory})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--version', help='manual version to fetch, for example 2026.11')
    source.add_argument('--html', type=Path, help='a saved copy of the reference page')
    parser.add_argument('--inventory', type=Path, default=INVENTORY)
    args = parser.parse_args(argv)

    if args.version:
        url = REFERENCE_URL.format(version=args.version)
        # The site answers Python's default User-Agent with 403.
        request = urllib.request.Request(url, headers={'User-Agent': 'dozerdb-metrics-manual-diff'})
        with urllib.request.urlopen(request, timeout=60) as response:
            html = response.read().decode('utf-8')
        print(f'reference page: {url}')
    else:
        html = args.html.read_text(encoding='utf-8')
        print(f'reference page: {args.html}')
    page = page_rows(html)
    inventory = inventory_rows(args.inventory.read_text(encoding='utf-8'))
    print(f'{len(page)} metrics on the page, {len(inventory)} in the inventory')
    if not page:
        print('RESULT DIFF: no metric table found; the page layout changed, compare by hand')
        return 1
    found = differences(page, inventory)
    pins = pinned_versions(inventory)
    if args.version and pins != [args.version]:
        found.append(f"the inventory's docs_anchor URLs pin {', '.join(pins)}, not {args.version}")
    for line in found:
        print(line)
    print('RESULT PASS' if not found else f"RESULT DIFF: {len(found)} difference{'s' if len(found) > 1 else ''}")
    return 1 if found else 0


if __name__ == '__main__':
    sys.exit(main())
