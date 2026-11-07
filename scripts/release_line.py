"""The line manifest: what the release line of this branch is and which release checks apply to it.

Every release branch keeps its own copy of release-line.toml at the repository root (release line, Neo4j tag,
DozerDB version, Docker series, published image, local image, the Open GDS version its image installs, and every
check of the release gate switched on or off). It restates the pins of distribution/config.json and never replaces
them; scripts/release-gate.py fails when they disagree. The gate reads all of it; scripts/docker-build.py tags the
local image from it, and the smoke, metrics and plugin checks test that image when none is named.

The pins and the manifest name a release by two inputs, the Neo4j tag and the DozerDB version; the
distribution version <tag>-<DozerDB version> is derived from them (scripts/distribution_version.py) and every
restatement of it is checked against the derivation.
"""
from dataclasses import dataclass
import dataclasses
import json
from pathlib import Path
import re
import tomllib

import distribution_version
import release_checks

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / 'release-line.toml'
CONFIG = ROOT / 'distribution/config.json'
CONFIG_NAME = 'distribution/config.json'
# The manifest fields that restate a pin of distribution/config.json, with the key of that pin.
PINS = {'neo4j_tag': 'tag', 'dozerdb_version': 'dozerdbVersion'}

LINES = ('calendar', 'lts')
# The Docker series a line moves: a Neo4j major.minor series (2026.10, 5.26), so never latest or a full tag.
DOCKER_SERIES = re.compile(r'[0-9]+\.[0-9]+')


class ManifestError(Exception):
    """The manifest is missing, malformed, or a field is absent or wrong; each problem names the field."""

    def __init__(self, *problems):
        super().__init__('; '.join(problems))
        self.problems = problems


@dataclass(frozen=True)
class Manifest:
    """The release line manifest (release-line.toml at the repository root)."""
    line: str
    neo4j_tag: str  # the exact upstream Neo4j tag, the first half of the distribution version
    dozerdb_version: str  # the DozerDB version, MAJOR.MINOR.PATCH, the second half
    image: str  # the published image of this release, tagged with the distribution version
    docker_series: str  # the floating Neo4j major.minor tag this line moves (2026.10, 5.26); declared, never parsed
    local_image: str  # the latest local build of this line, <repository>:<line>-local
    gds_version: str  # the Open GDS version the image installs for this Neo4j version (registered in versions.json)
    checks: dict
    source: str = MANIFEST.name  # the file it was read from, named in the SKIP rows

    @property
    def distribution_version(self):
        """The release's identity, <neo4j_tag>-<dozerdb_version>."""
        return distribution_version.compose(self.neo4j_tag, self.dozerdb_version)

    @property
    def local_build_image(self):
        """The exact local build, <repository>:<distribution version>-local: it stays when the line moves on."""
        return f'{self.local_image.rpartition(":")[0]}:{self.distribution_version}-local'

    @property
    def floating_images(self):
        """The floating tags a publication of this release moves, in the image's repository: the Docker series
        (<repository>:2026.10, <repository>:5.26) and, on the calendar line only, <repository>:latest. An LTS line
        never moves latest or a calendar series."""
        repository = self.image.rpartition(':')[0]
        return [f'{repository}:{self.docker_series}'] + ([f'{repository}:latest'] if self.line == 'calendar' else [])

    @property
    def published_images(self):
        """Every tag a push of this release applies: the exact image first, then the floating tags."""
        return [self.image, *self.floating_images]


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

    line, neo4j_tag, version, image, docker_series, local_image, gds_version = (
        string(f) for f in ('line', 'neo4j_tag', 'dozerdb_version', 'image', 'docker_series', 'local_image',
                            'gds_version'))
    if line not in LINES:
        raise ManifestError(f'line: must be calendar or lts, found {line!r}')
    try:
        distribution_version.validate_upstream_tag(neo4j_tag)
    except distribution_version.DistributionVersionError as exc:
        raise ManifestError(f'neo4j_tag: {exc}') from None
    try:
        distribution_version.validate_dozerdb_version(version)
    except distribution_version.DistributionVersionError as exc:
        raise ManifestError(f'dozerdb_version: {exc}') from None
    derived = distribution_version.compose(neo4j_tag, version)
    if image.rpartition(':')[2] != derived:
        raise ManifestError(f'image: {image} is not tagged with the distribution version {derived} '
                            f'(neo4j_tag-dozerdb_version)')
    if not DOCKER_SERIES.fullmatch(docker_series):
        raise ManifestError(f'docker_series: must be the Neo4j major.minor series this line moves (such as 2026.10 or '
                            f'5.26), never latest or a full tag, found {docker_series!r}')
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
    return Manifest(line, neo4j_tag, version, image, docker_series, local_image, gds_version,
                    {name: checks[name] for name in known_checks})


def config_problems(config):
    """Where the pins of distribution/config.json fail to name one release: tag and dozerdbVersion must derive a
    distribution version, and the restated distributionVersion must equal it. One line per problem."""
    missing = [key for key in ('tag', 'dozerdbVersion') if not config.get(key)]
    if missing:
        return [f'{key}: missing' for key in missing]
    try:
        derived = distribution_version.compose(config['tag'], config['dozerdbVersion'])
    except distribution_version.DistributionVersionError as exc:
        return [f'tag {config["tag"]!r} and dozerdbVersion {config["dozerdbVersion"]!r}: {exc}']
    restated = config.get('distributionVersion')
    if restated != derived:
        return [f'distributionVersion: {restated}, but tag and dozerdbVersion derive {derived}']
    return []


def pinned_version(config, source=CONFIG_NAME):
    """The distribution version the pins derive, <tag>-<dozerdbVersion>, which distributionVersion restates; a
    script that names an artifact stops here when the pins disagree, naming the file and the expected value."""
    problems = config_problems(config)
    if problems:
        raise SystemExit('; '.join(f'{source} {problem}' for problem in problems))
    return config['distributionVersion']


def pin_problems(manifest, config):
    """Where the manifest disagrees with the pins of distribution/config.json, one line per field: neo4j_tag is
    the pinned tag, dozerdb_version the pinned dozerdbVersion, and the image is tagged with the distribution
    version the pins derive."""
    problems = [f'{field}: {getattr(manifest, field)}, but {CONFIG_NAME} has {pin} {config.get(pin)}'
                for field, pin in PINS.items() if getattr(manifest, field) != config.get(pin)]
    broken = config_problems(config)
    if broken:
        return problems + [f'{CONFIG_NAME} {problem}' for problem in broken]
    if manifest.image.rpartition(':')[2] != config['distributionVersion']:
        problems.append(f'image: {manifest.image} is not tagged with the distribution version '
                        f'{config["distributionVersion"]} that {CONFIG_NAME} derives')
    return problems


def read_config():
    """distribution/config.json, the build pins of the line: the Neo4j tag and commit, the DozerDB version and the
    distribution version derived from them."""
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
