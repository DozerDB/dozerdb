#!/usr/bin/env python3
"""Assemble pinned upstream Neo4j with checkout-local DozerDB artifacts and collect the release bundle.

The bundle in distribution/target is the complete set of files later published to the
distribution host and used to build the Docker image: the Unix archive, the Windows archive and
the shaded plugin JAR, each with a SHA-256 sidecar.

The upstream assembly places core and Browser in lib/ through the distribution patch. The metrics
artifact and the DozerDB third-party notice join each archive here, after the assembly, so that
the patch and the upstream source stay untouched."""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time
from typing import NamedTuple
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parent.parent
CONFIG = json.loads((ROOT / 'distribution/config.json').read_text())
BUILD = ROOT / '.build'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
NOTICE = 'DOZERDB-NOTICE.txt'

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

def prepare():
    BUILD.mkdir(exist_ok=True)
    for component, key in [('core', 'coreVersion'), ('metrics', 'metricsVersion'), ('browser', 'browserVersion')]:
        version = ET.parse(ROOT / component / 'pom.xml').getroot().findtext('m:version', namespaces=NS)
        if version != CONFIG[key]:
            raise SystemExit(f'{component} version {version} differs from distribution pin {CONFIG[key]}')
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

def dozerdb_additions(root, metrics_jar):
    """The metrics artifact beside core in lib/ and the DozerDB notice beside Neo4j's, both from the built metrics JAR."""
    with ZipFile(metrics_jar) as jar:
        if any(name.startswith('com/codahale/') for name in jar.namelist()):
            raise SystemExit(f'Metrics artifact is not shaded: {metrics_jar}')
        notice = jar.read(f'META-INF/{NOTICE}')
    mtime = int(metrics_jar.stat().st_mtime)
    return [Addition(f'{root}/lib/dozerdb-core-{CONFIG["coreVersion"]}.jar', f'{root}/lib/{metrics_jar.name}', metrics_jar.read_bytes(), mtime),
            Addition(f'{root}/LICENSE.txt', f'{root}/{NOTICE}', notice, mtime)]

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

def required_entries(root, versions, windows):
    """What every bundled archive must carry: the launchers, the DozerDB JARs, the notice and the web directory."""
    launchers = ('bin/neo4j.ps1', 'bin/neo4j.bat', 'bin/neo4j-admin.ps1') if windows else ('bin/neo4j', 'bin/neo4j-admin')
    return [f'{root}/{entry}' for entry in (
        *launchers, f'lib/dozerdb-core-{versions["coreVersion"]}.jar', f'lib/dozerdb-browser-{versions["browserVersion"]}.jar',
        f'lib/dozerdb-metrics-{versions["metricsVersion"]}.jar', 'lib/simpleclient-0.16.0.jar', NOTICE, 'web/')]

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
    root = f'dozerdb-{CONFIG["distributionVersion"]}'
    names, conf = archive_contents(archive, root)
    for required in required_entries(root, CONFIG, windows=archive.suffix == '.zip'):
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
    version = CONFIG['distributionVersion']
    outputs = source / 'packaging/standalone/target'
    plugin = ROOT / 'plugin/target' / f'dozerdb-plugin-{CONFIG["browserVersion"]}.jar'
    metrics = ROOT / 'metrics/target' / f'dozerdb-metrics-{CONFIG["metricsVersion"]}.jar'
    for artifact, what in ((plugin, 'Shaded plugin'), (metrics, 'Metrics artifact')):
        if not artifact.is_file():
            raise SystemExit(f'{what} missing; run the plugin build first: {artifact}')
    archives = [outputs / f'dozerdb-{version}-unix.tar.gz', outputs / f'dozerdb-{version}-windows.zip']
    for archive in archives:
        if not archive.is_file():
            raise SystemExit(f'Expected release artifact missing: {archive}')
    additions = dozerdb_additions(f'dozerdb-{version}', metrics)
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
