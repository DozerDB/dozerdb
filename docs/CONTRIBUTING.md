# Contributing to DozerDB

Thank you for your interest in DozerDB.

## Reporting problems

Open an issue on <https://github.com/DozerDB/dozerdb/issues> with the DozerDB
version (from the Browser's About panel or the archive name), the way you run
it (Docker, standalone archive or plugin JAR), what you did, what you expected
and what happened. For security problems, follow [SECURITY.md](SECURITY.md)
instead of opening a public issue.

## Changes

- For anything beyond a small fix, open an issue first so the approach can be
  discussed before you spend time on it.
- Build and verify as described in [building.md](building.md). A pull request
  should pass `npx nx run-many -t test`, and changes that touch the server or
  Browser behaviour should also pass the Cypher and Browser flows against a
  held server.
- Keep core formatted with Spotless and Browser with the project's ESLint and
  Prettier settings.
- Write commit messages that describe the change and why it is needed.

DozerDB is built on a pinned Neo4j release. Changes to the Neo4j baseline are
made by the maintainers as part of a release; pull requests should target the
DozerDB code itself.

## License

By contributing you agree that your contribution is licensed under the
[GNU General Public License v3](../LICENSE), like the rest of the project.
