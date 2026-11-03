"""The line manifest: what the release line of this branch is and which release checks apply to it.

Every release branch keeps its own copy of release-line.toml at the repository root (release line, Neo4j tag,
DozerDB version, published image, local image, the Open GDS version its image installs, and every check of the release gate switched on or off). It
restates the pins of distribution/config.json and never replaces them; scripts/release-gate.py fails when they
disagree. The gate reads all of it; scripts/docker-build.py tags the local image from it, and the smoke, metrics
and plugin checks test that image when none is named.
"""
from dataclasses import dataclass
import dataclasses
import json
from pathlib import Path
import tomllib

import release_checks

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / 'release-line.toml'
CONFIG = ROOT / 'distribution/config.json'
# The manifest fields that restate a pin of distribution/config.json, with the key of that pin.
PINS = {'neo4j_tag': 'tag', 'dozerdb_version': 'distributionVersion'}

LINES = ('calendar', 'lts')


class ManifestError(Exception):
    """The manifest is missing, malformed, or a field is absent or wrong; each problem names the field."""

    def __init__(self, *problems):
        super().__init__('; '.join(problems))
        self.problems = problems


@dataclass(frozen=True)
class Manifest:
    """The release line manifest (release-line.toml at the repository root)."""
    line: str
    neo4j_tag: str
    dozerdb_version: str
    image: str  # the published image of this version
    local_image: str  # the latest local build of this line, <repository>:<line>-local
    gds_version: str  # the Open GDS version the image installs for this Neo4j version (registered in versions.json)
    checks: dict
    source: str = MANIFEST.name  # the file it was read from, named in the SKIP rows

    @property
    def local_build_image(self):
        """The exact local build, <repository>:<dozerdb_version>-local: it stays when the line moves on."""
        return f'{self.local_image.rpartition(":")[0]}:{self.dozerdb_version}-local'


def load_manifest(path):
    """The manifest in a file; an error names the file and then the field."""
    try:
        return dataclasses.replace(parse_manifest(path.read_text()), source=path.name)
    except FileNotFoundError:
        raise ManifestError(f'{path}: not found; every release branch keeps its line manifest there') from None
    except ManifestError as exc:
        raise ManifestError(f'{path}: {exc}') from None


def parse_manifest(text, known_checks=release_checks.NAMES):
    """The manifest in a text; [checks] must switch exactly the known checks, by default every check the gate
    defines (scripts/release_checks.py)."""
    try:
        data = tomllib.loads(text)
    except tomllib.TOMLDecodeError as exc:
        raise ManifestError(f'malformed TOML: {exc}') from None

    def string(field):
        if field not in data:
            raise ManifestError(f'{field}: missing')
        if not isinstance(data[field], str) or not data[field]:
            raise ManifestError(f'{field}: must be a non-empty string, found {data[field]!r}')
        return data[field]

    line, neo4j_tag, version, image, local_image, gds_version = (
        string(f) for f in ('line', 'neo4j_tag', 'dozerdb_version', 'image', 'local_image', 'gds_version'))
    if line not in LINES:
        raise ManifestError(f'line: must be calendar or lts, found {line!r}')
    if image.rpartition(':')[2] != version:
        raise ManifestError(f'image: {image} is not tagged with dozerdb_version {version}')
    repository, _, tag = local_image.rpartition(':')
    if not repository or tag != f'{line}-local':
        raise ManifestError(f'local_image: {local_image} is not <repository>:{line}-local for line {line}')
    checks = data.get('checks')
    if not isinstance(checks, dict):
        raise ManifestError('checks: missing table [checks]')
    for name in checks:
        if name not in known_checks:
            raise ManifestError(f'checks.{name}: unknown check; the gate runs {", ".join(known_checks)}')
    for name in known_checks:
        if name not in checks:
            raise ManifestError(f'checks.{name}: missing; set it to true or false')
        if not isinstance(checks[name], bool):
            raise ManifestError(f'checks.{name}: must be true or false, found {checks[name]!r}')
    return Manifest(line, neo4j_tag, version, image, local_image, gds_version,
                    {name: checks[name] for name in known_checks})


def pin_problems(manifest, config):
    """Where the manifest disagrees with the pins of distribution/config.json, one line per field."""
    return [f'{field}: {getattr(manifest, field)}, but distribution/config.json has {pin} {config.get(pin)}'
            for field, pin in PINS.items() if getattr(manifest, field) != config.get(pin)]


def read_config():
    """distribution/config.json, the build pins of the line (Neo4j tag and commit, component versions)."""
    return json.loads(CONFIG.read_text())


def checked_manifest(path=MANIFEST, config=None):
    """The manifest in a file, valid and in agreement with the pins (config, read from distribution/config.json
    when not given); else ManifestError with one problem per disagreement, each naming the file."""
    manifest = load_manifest(path)
    problems = pin_problems(manifest, read_config() if config is None else config)
    if problems:
        raise ManifestError(*(f'{path}: {problem}' for problem in problems))
    return manifest


def required_manifest(path=MANIFEST, config=None):
    """The checked manifest for a script that cannot go on without it: it stops naming the problems."""
    try:
        return checked_manifest(path, config)
    except ManifestError as exc:
        raise SystemExit(f'line manifest {exc}') from None


def default_image(image, path=MANIFEST):
    """The image a check script tests: the one named, else the line's latest local build (the manifest's
    local_image, which scripts/docker-build.py tags). The manifest is read only when no image is named."""
    return image or required_manifest(path).local_image
