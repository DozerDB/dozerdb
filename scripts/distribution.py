#!/usr/bin/env python3
"""Assemble pinned upstream Neo4j with checkout-local DozerDB artifacts and collect the release bundle.

The bundle in distribution/target is the complete set of files later published to the
distribution host and used to build the Docker image: the Unix archive, the Windows archive and
the shaded plugin JAR, each with a SHA-256 sidecar.

The upstream assembly places core and Browser in lib/ through the distribution patch. The metrics
procedures and query-log artifacts and the DozerDB third-party notice join each archive here, after the
assembly, so that the patch and the upstream source stay untouched.

One identity names the release: the distribution version <Neo4j tag>-<DozerDB version> that the pins
of distribution/config.json derive. Every DozerDB artifact carries it, the Maven POMs and the Browser package
restate it by hand and are checked against it before anything is built, and the patch's literals are checked
against it before the patch is applied."""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import time
from typing import NamedTuple
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo

import release_line

ROOT = Path(__file__).resolve().parent.parent
CONFIG = release_line.read_config()
# The distribution version the pins derive; the build stops here when they disagree.
VERSION = release_line.pinned_version(CONFIG)
BUILD = ROOT / '.build'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
NOTICE = 'DOZERDB-NOTICE.txt'
# The DozerDB components the archives carry in lib/, every JAR named by the distribution version.
COMPONENTS = ('core', 'metrics', 'procedures', 'query-log', 'browser')
# The components whose artifacts join the archives after the assembly (the patch places core and Browser in lib/).
ADDED = ('metrics', 'procedures', 'query-log')
# Every DozerDB POM restates the distribution version as its own version; the four modules under the root POM also
# as their parent's version, and the root and plugin POMs as their cross-module dozerdb-*.version properties.
POMS = ('pom.xml', 'core/pom.xml', 'metrics/pom.xml', 'procedures/pom.xml', 'query-log/pom.xml', 'plugin/pom.xml',
        'browser/pom.xml')
MODULE_POMS = ('core/pom.xml', 'metrics/pom.xml', 'procedures/pom.xml', 'query-log/pom.xml')
PACKAGE = 'browser/package.json'
VERSION_PROPERTY = re.compile(r'dozerdb-[a-z-]+\.version')
# The Dockerfile's default archive, dozerdb-<distribution version>-unix.tar.gz, restates the version by hand too
# (scripts/docker-build.py passes the built archive as a build argument; the default is what a hand-run build takes).
DOCKERFILE = 'docker/Dockerfile'
TARBALL_ARG = re.compile(r'^ARG NEO4J_TARBALL=(\S+)', re.MULTILINE)

def component_jar(component, version=VERSION):
    """A component's built artifact under its module's target/, named by the distribution version."""
    return ROOT / component / 'target' / f'dozerdb-{component}-{version}.jar'

def run(*args, **kw):
    subprocess.run([str(a) for a in args], check=True, **kw)

def rewrite_pom_versions(source, config):
    """Rewrite the exported tag's own POM coordinates to the Neo4j version, as Neo4j's release build does. Some tags
    carry <tag>-SNAPSHOT (the default), others the previous release's version, named by pomVersion (the 2026.10.1 tag
    carries 2026.10.0); the expected count guards against a coordinate that is not the tag's own."""
    old = config.get('pomVersion', config['neo4jVersion'] + '-SNAPSHOT')
    expected = config['pomVersionOccurrences'] if 'pomVersion' in config else config['snapshotOccurrences']
    count = 0
    for pom in source.rglob('pom.xml'):
        text = pom.read_text()
        count += text.count(old)
        if old in text:
            pom.write_text(text.replace(old, config['neo4jVersion']))
    if count != expected:
        raise SystemExit(f'Expected {expected} POM coordinates {old}, found {count}')

def dockerfile_archive_version(text):
    """The distribution version the Dockerfile's default archive names (ARG NEO4J_TARBALL=dozerdb-<version>-unix.tar.gz);
    a default of another shape, or none, is returned as it stands so the pin check names it."""
    match = TARBALL_ARG.search(text)
    default = match.group(1) if match else None
    archive = re.fullmatch(r'dozerdb-(.+)-unix\.tar\.gz', default or '')
    return archive.group(1) if archive else default

def restated_versions(root=ROOT):
    """Every hand-pinned restatement of the distribution version in the tree, as (file, what, value): the version of
    each DozerDB POM, the parent version of each module under the root POM, every dozerdb-*.version property, the
    Browser package version and the version the Dockerfile's default archive names."""
    statements = []
    for path in POMS:
        project = ET.parse(root / path).getroot()
        statements.append((path, 'version', project.findtext('m:version', namespaces=NS)))
        if path in MODULE_POMS:
            statements.append((path, 'parent version', project.findtext('m:parent/m:version', namespaces=NS)))
        for prop in project.findall('m:properties/*', NS):
            name = prop.tag.rsplit('}', 1)[-1]
            if VERSION_PROPERTY.fullmatch(name):
                statements.append((path, f'property {name}', prop.text))
    statements.append((PACKAGE, 'version', json.loads((root / PACKAGE).read_text()).get('version')))
    statements.append((DOCKERFILE, 'default archive (ARG NEO4J_TARBALL) version',
                       dockerfile_archive_version((root / DOCKERFILE).read_text())))
    return statements

def check_pins(version, restated):
    """Every restatement equals the distribution version the pins derive; else the build stops naming each file,
    the value it holds and the value expected."""
    wrong = [f'{path} {what} is {value}, expected the distribution version {version}'
             for path, what, value in restated if value != version]
    if wrong:
        raise SystemExit('\n'.join(wrong))

# The hand-edited literals of the distribution patch and the line each one is added on; the launcher classpath is
# the first entry of the bootstrap line the patch adds to the unix template.
PATCH_LITERALS = {
    'finalName': 'assembly finalName',
    'dozerdb-core': 'dozerdb-core dependency version',
    'dozerdb-browser': 'dozerdb-browser dependency version',
    'classpath': 'launcher classpath',
}

def patch_literals(text):
    """The literals of a distribution patch, keyed as PATCH_LITERALS, each with the number of the patch line
    adding it: the assembly finalName, the version each DozerDB dependency is added with, and the launcher
    classpath line of the unix template."""
    found, artifact = {}, None
    for number, line in enumerate(text.splitlines(), 1):
        if not line.startswith('+') or line.startswith('+++'):
            continue
        body = line[1:].strip()
        if match := re.fullmatch(r'<finalName>(.*)</finalName>', body):
            found['finalName'] = (number, match.group(1))
        elif match := re.fullmatch(r'<artifactId>(.*)</artifactId>', body):
            artifact = match.group(1) if match.group(1) in PATCH_LITERALS else None
        elif (match := re.fullmatch(r'<version>(.*)</version>', body)) and artifact:
            found[artifact] = (number, match.group(1))
            artifact = None
        elif body.startswith('CLASSPATH='):
            found['classpath'] = (number, body)
    return found

def patch_problems(text, version, name):
    """Where the distribution patch's literals disagree with the distribution version, one line per literal naming
    the patch line and the expected value; a literal the patch does not add is named too."""
    expected = {'finalName': f'dozerdb-{version}', 'dozerdb-core': version, 'dozerdb-browser': version,
                'classpath': f'CLASSPATH="$BASEDIR/lib/dozerdb-core-{version}.jar:$CLASSPATH"'}
    found = patch_literals(text)
    problems = []
    for key, what in PATCH_LITERALS.items():
        if key not in found:
            problems.append(f'{name} adds no {what} line; expected {expected[key]}')
        elif found[key][1] != expected[key]:
            number, value = found[key]
            problems.append(f'{name} line {number}: {what} is {value}, expected {expected[key]}')
    return problems

def check_patch(patch, version):
    """The patch's literals agree with the distribution version, else the build stops before applying it."""
    problems = patch_problems(patch.read_text(), version, patch.relative_to(ROOT).as_posix())
    if problems:
        raise SystemExit('\n'.join(problems))

def prepare():
    BUILD.mkdir(exist_ok=True)
    check_pins(VERSION, restated_versions())
    repo = Path(os.environ.get('NEO4J_SOURCE_REPOSITORY', BUILD / 'neo4j-git')).resolve()
    if not repo.exists():
        run('git', 'clone', '--depth=1', '--single-branch', '--branch', CONFIG['tag'], CONFIG['repository'], repo)
    elif subprocess.run(['git', '-C', str(repo), 'rev-parse', '--verify', '--quiet', CONFIG['tag']+'^{commit}'],
                        stdout=subprocess.DEVNULL).returncode != 0:
        # An earlier pin's shallow clone lacks later tags; fetch only the pinned tag.
        run('git', '-C', repo, 'fetch', '--depth=1', CONFIG['repository'], 'tag', CONFIG['tag'], '--no-tags')
    commit = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', CONFIG['tag']+'^{commit}'], text=True).strip()
    if commit != CONFIG['commit']:
        raise SystemExit(f'Pinned Neo4j tag mismatch: expected {CONFIG["commit"]}, got {commit}')
    source = BUILD / 'neo4j-source'
    if source.exists():
        shutil.rmtree(source)
    source.mkdir()
    archive = BUILD / 'neo4j-source.tar'
    run('git', '-C', repo, 'archive', '--format=tar', '--output', archive, CONFIG['commit'])
    run('tar', '-xf', archive, '-C', source)
    archive.unlink()
    rewrite_pom_versions(source, CONFIG)
    patch = ROOT / 'distribution' / CONFIG['patch']
    check_patch(patch, VERSION)
    # --check refuses context drift. GIT_CEILING_DIRECTORIES prevents using the enclosing monorepo.
    env = {**os.environ, 'GIT_CEILING_DIRECTORIES': str(BUILD)}
    run('git', 'apply', '--check', patch, cwd=source, env=env)
    run('git', 'apply', patch, cwd=source, env=env)
    web = source / 'packaging/standalone/standalone-community/src/main/distribution/text/community/web'
    if not web.is_dir():
        raise SystemExit('Pinned upstream web directory is missing')
    return source

def main():
    source = prepare()
    # Assembly skips upstream tests, as the prior standalone recipe did. DozerDB tests run separately.
    upstream_jvm = (source / '.mvn/jvm.config').read_text()
    # Upstream stops tiered compilation at level 1, which shrinks the JVM's default code cache to 48 MB; the
    # 130-module reactor has exhausted it ("Out of space in CodeCache for adapters"), so reserve more.
    env = {**os.environ, 'MAVEN_OPTS': upstream_jvm.replace('\n', ' ')+' -XX:ReservedCodeCacheSize=256m '
                                       + os.environ.get('MAVEN_OPTS', '-Xmx3048m')}
    run(ROOT / 'scripts/maven.sh', f'-Dmaven.multiModuleProjectDirectory={source}', '-f', source / 'pom.xml', '-pl',
        'packaging/standalone/standalone-community', '-am', 'install', '-DfullBuild',
        '-Dlicense.skip=true', '-Dlicensing.skip=true', '-DskipTests',
        '-Dcheckstyle.skip=true', '-Dscalastyle.skip=true', '-Drevapi.skip=true',
        '-Dspotless.check.skip=true', env=env)
    bundle(source)

def checksum(path):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_name(path.name+'.sha256').write_text(f'{digest}  {path.name}\n')
    return digest

class Addition(NamedTuple):
    """An entry DozerDB appends to an upstream archive, taking owner, mode and compression from an existing entry."""
    template: str
    name: str
    data: bytes
    mtime: int

def dozerdb_additions(root, metrics_jar, procedures_jar, query_log_jar, version=VERSION):
    """The metrics, procedures and query-log artifacts beside core in lib/ and the DozerDB notice beside Neo4j's; the
    notice comes from the metrics JAR, the one artifact that adds a library (the other two add none)."""
    with ZipFile(metrics_jar) as jar:
        if any(name.startswith('com/codahale/') for name in jar.namelist()):
            raise SystemExit(f'Metrics artifact is not shaded: {metrics_jar}')
        notice = jar.read(f'META-INF/{NOTICE}')
    core = f'{root}/lib/dozerdb-core-{version}.jar'
    mtime = int(metrics_jar.stat().st_mtime)
    return [Addition(core, f'{root}/lib/{jar.name}', jar.read_bytes(), int(jar.stat().st_mtime))
            for jar in (metrics_jar, procedures_jar, query_log_jar)] + \
           [Addition(f'{root}/LICENSE.txt', f'{root}/{NOTICE}', notice, mtime)]

def add_to_archive(source, destination, additions):
    """Write a copy of an upstream archive with the additions appended; every existing entry is carried over unchanged."""
    if source.name.endswith('.tar.gz'):
        add_to_tar(source, destination, additions)
    elif source.suffix == '.zip':
        add_to_zip(source, destination, additions)
    else:
        raise ValueError(f'Not an archive the assembly produces: {source}')

def template(entries, addition, archive):
    if addition.template not in entries:
        raise SystemExit(f'{archive.name} has no entry {addition.template} to model {addition.name} on')
    return entries[addition.template]

def add_to_tar(source, destination, additions):
    # Same container as the assembly's: GNU tar, default deflate level, gzip header without name or time. One streaming pass.
    members = {}
    with tarfile.open(source, 'r|gz') as src, open(destination, 'wb') as out, \
            gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=0, compresslevel=6) as gz, \
            tarfile.open(fileobj=gz, mode='w', format=tarfile.GNU_FORMAT) as dest:
        for member in src:
            dest.addfile(member, src.extractfile(member) if member.isfile() else None)
            members[member.name] = member
        for addition in additions:
            model = template(members, addition, source)
            info = tarfile.TarInfo(addition.name)
            info.size, info.mtime = len(addition.data), addition.mtime
            info.mode, info.uid, info.gid, info.uname, info.gname = model.mode, model.uid, model.gid, model.uname, model.gname
            dest.addfile(info, io.BytesIO(addition.data))

def add_to_zip(source, destination, additions):
    # Appending leaves the upstream entries byte for byte as the assembly wrote them.
    shutil.copy2(source, destination)
    with ZipFile(destination, 'a') as archive:
        entries = {info.filename: info for info in archive.infolist()}
        for addition in additions:
            model = template(entries, addition, source)
            info = ZipInfo(addition.name, date_time=time.localtime(addition.mtime)[:6])
            info.compress_type, info.external_attr, info.create_system = model.compress_type, model.external_attr, model.create_system
            info.create_version, info.extract_version = model.create_version, model.extract_version
            archive.writestr(info, addition.data)

def required_entries(root, version, windows):
    """What every bundled archive must carry: the launchers, every DozerDB JAR named by the distribution version,
    the notice and the web directory."""
    launchers = ('bin/neo4j.ps1', 'bin/neo4j.bat', 'bin/neo4j-admin.ps1') if windows else ('bin/neo4j', 'bin/neo4j-admin')
    jars = [f'lib/dozerdb-{component}-{version}.jar' for component in COMPONENTS]
    return [f'{root}/{entry}' for entry in (*launchers, *jars, 'lib/simpleclient-0.16.0.jar', NOTICE, 'web/')]

def configuration_defaults(conf):
    """The active key=value lines of a neo4j.conf."""
    return dict(line.split('=', 1) for line in conf.splitlines() if '=' in line and not line.lstrip().startswith('#'))

def archive_contents(archive, root):
    """Entry names (directories with a trailing slash) and the shipped neo4j.conf of a bundled archive."""
    conf = f'{root}/conf/neo4j.conf'
    if archive.suffix == '.zip':
        with ZipFile(archive) as zf:
            return set(zf.namelist()), zf.read(conf).decode()
    with tarfile.open(archive, 'r:gz') as tar:
        names = {m.name + '/' if m.isdir() else m.name for m in tar.getmembers()}
        return names, tar.extractfile(conf).read().decode()

def verify_archive(archive):
    """Static check of a bundled archive: launchers, DozerDB JARs and notice present, no shaded plugin, DozerDB defaults
    in neo4j.conf. The Windows archive gets no other check because no Windows host runs here."""
    root = f'dozerdb-{VERSION}'
    names, conf = archive_contents(archive, root)
    for required in required_entries(root, VERSION, windows=archive.suffix == '.zip'):
        if required not in names:
            raise SystemExit(f'{archive.name} is missing {required}')
    if any(n.startswith(f'{root}/lib/dozerdb-plugin') for n in names):
        raise SystemExit(f'{archive.name} must not contain the shaded plugin')
    defaults = configuration_defaults(conf)
    if defaults.get('dbms.usage_report.enabled') != 'false' or defaults.get('dbms.max_databases') != '100':
        raise SystemExit(f'{archive.name} does not carry the DozerDB configuration defaults')

def bundle(source):
    """Place every publishable artifact of this release in distribution/target with a SHA-256 sidecar."""
    target = ROOT / 'distribution/target'
    target.mkdir(exist_ok=True)
    version = VERSION
    outputs = source / 'packaging/standalone/target'
    plugin = ROOT / 'plugin/target' / f'dozerdb-plugin-{version}.jar'
    jars = [component_jar(component) for component in ADDED]
    for artifact, what in ((plugin, 'Shaded plugin'), *((jar, f'{component} artifact') for jar, component in zip(jars, ADDED))):
        if not artifact.is_file():
            raise SystemExit(f'{what} missing; run the plugin build first: {artifact}')
    archives = [outputs / f'dozerdb-{version}-unix.tar.gz', outputs / f'dozerdb-{version}-windows.zip']
    for archive in archives:
        if not archive.is_file():
            raise SystemExit(f'Expected release artifact missing: {archive}')
    additions = dozerdb_additions(f'dozerdb-{version}', *jars)
    for archive in archives:
        dest = target / archive.name
        add_to_archive(archive, dest, additions)
        verify_archive(dest)
        print(f'Bundled {dest} (sha256 {checksum(dest)})')
    dest = target / plugin.name
    shutil.copy2(plugin, dest)
    print(f'Bundled {dest} (sha256 {checksum(dest)})')

if __name__ == '__main__':
    main()
