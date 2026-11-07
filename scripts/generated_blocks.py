"""Generated blocks of hand-written Markdown pages, shared by the documentation generators under scripts/.

A page is hand-written Markdown with blocks between `<!-- BEGIN GENERATED: <block> -->` and
`<!-- END GENERATED: <block> -->` lines; a generator rewrites those blocks and leaves everything else as it is.
The metrics generator (metrics-docs.py) and the query-log generator import this module for the rewriting, for the
`--check` comparison of the committed pages with what they would write, and for the command line both share. A page
both write (the monitoring README) carries blocks of each generator; a generator names the other's blocks as
`foreign` and leaves them as they are. Standard library only.
"""
import argparse
import re
import sys

# The blocks each generator writes on the page both write (the monitoring README), by generator; a generator's
# foreign blocks are the other generators' ones, so neither names the other's blocks by hand.
SHARED_PAGE_BLOCKS = {'metrics': ('version', 'tier-summary'), 'query-log': ('query-log-tier-summary',)}


def foreign_blocks(generator):
    """The shared page's blocks that belong to the generators other than `generator`."""
    return tuple(block for other, blocks in SHARED_PAGE_BLOCKS.items() if other != generator for block in blocks)


BLOCK = re.compile(r'(<!-- BEGIN GENERATED: ([a-z-]+) -->\n)(.*?)(<!-- END GENERATED: \2 -->)', re.DOTALL)


def update_page(text, blocks, foreign=()):
    """The page with each generated block's content replaced; every block must be there, and no other except a
    `foreign` block (another generator's), which is left as it is."""
    found = [m.group(2) for m in BLOCK.finditer(text)]
    missing = sorted(set(blocks) - set(found))
    unknown = sorted(set(found) - set(blocks) - set(foreign))
    if missing or unknown or len(found) != len(set(found)):
        raise ValueError(f'generated blocks: missing {missing}, unknown {unknown}, found {found}')
    return BLOCK.sub(lambda m: m.group(1) + blocks.get(m.group(2), m.group(3)) + m.group(4), text)


def check_pages(directory, pages, root, also=None, foreign=()):
    """Every reason the committed pages differ from what the generator would write; empty when they are current.

    `pages` maps a page's file name in `directory` to its blocks (name to content). Each problem is a
    `<path relative to root>: <reason>` line: a page whose blocks are stale, or whose blocks do not match the
    generator's; `also(text)` adds a generator's own checks of a page's hand-written parts, reported first; `foreign`
    names the blocks of another generator that a page may carry.
    """
    problems = []
    for page, blocks in pages.items():
        path = directory / page
        text = path.read_text(encoding='utf-8')
        if also is not None:
            problems += [f'{path.relative_to(root)}: {p}' for p in also(text)]
        try:
            if update_page(text, blocks, foreign) != text:
                problems.append(f'{path.relative_to(root)}: generated blocks are stale')
        except ValueError as error:
            problems.append(f'{path.relative_to(root)}: {error}')
    return problems


def write_pages(directory, pages, root, foreign=()):
    """Rewrite the generated blocks of every page whose blocks changed, naming each rewritten page."""
    for page, blocks in pages.items():
        path = directory / page
        text = path.read_text(encoding='utf-8')
        updated = update_page(text, blocks, foreign)
        if updated != text:
            path.write_text(updated, encoding='utf-8')
            print(f'updated {path.relative_to(root)}')


def main(argv, description, check, write, script, pages):
    """The command line of a generator: rewrite the pages and check them, or with --check only check.

    `check()` returns the problems (see check_pages), `write()` rewrites the pages; `script` is the generator's path
    for the hint a failed check prints, `pages` what it generates ("monitoring pages"). Returns the exit status.
    """
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('--check', action='store_true', help='fail when a page is stale instead of rewriting it')
    args = parser.parse_args(argv)
    if not args.check:
        write()
    problems = check()
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        if args.check:
            print(f'run python3 {script} to regenerate the {pages}', file=sys.stderr)
        return 1
    if args.check:
        print(f'{pages} are current')
    return 0
