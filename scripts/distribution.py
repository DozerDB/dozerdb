#!/usr/bin/env python3
"""Assemble pinned upstream Neo4j with checkout-local DozerDB artifacts and collect the release bundle.

The bundle in distribution/target is the complete set of files later published to the
distribution host and used to build the Docker image: the Unix archive, the Windows archive and
the shaded plugin JAR, each with a SHA-256 sidecar."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
CONFIG = json.loads((ROOT / 'distribution/config.json').read_text())
BUILD = ROOT / '.build'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def run(*args, **kw):
    subprocess.run([str(a) for a in args], check=True, **kw)

def prepare():
    BUILD.mkdir(exist_ok=True)
    for component, key in [('core', 'coreVersion'), ('browser', 'browserVersion')]:
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
    old, new = CONFIG['neo4jVersion']+'-SNAPSHOT', CONFIG['neo4jVersion']
    count = 0
    for pom in source.rglob('pom.xml'):
        text = pom.read_text()
        count += text.count(old)
        if old in text:
            pom.write_text(text.replace(old, new))
    if count != CONFIG['snapshotOccurrences']:
        raise SystemExit(f'Expected {CONFIG["snapshotOccurrences"]} snapshot coordinates, found {count}')
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
    env = {**os.environ, 'MAVEN_OPTS': upstream_jvm.replace('\n', ' ')+' '+os.environ.get('MAVEN_OPTS', '-Xmx3048m')}
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

def verify_windows_archive(archive):
    """Static check of the Windows zip: no Windows host runs here, so verify the shipped contents."""
    root = f'dozerdb-{CONFIG["distributionVersion"]}'
    with ZipFile(archive) as zf:
        names = set(zf.namelist())
        for required in (f'{root}/bin/neo4j.ps1', f'{root}/bin/neo4j.bat', f'{root}/bin/neo4j-admin.ps1',
                         f'{root}/lib/dozerdb-core-{CONFIG["coreVersion"]}.jar',
                         f'{root}/lib/dozerdb-browser-{CONFIG["browserVersion"]}.jar',
                         f'{root}/lib/simpleclient-0.16.0.jar', f'{root}/web/'):
            if required not in names:
                raise SystemExit(f'Windows archive is missing {required}')
        if any(n.startswith(f'{root}/lib/dozerdb-plugin') for n in names):
            raise SystemExit('Windows archive must not contain the shaded plugin')
        conf = zf.read(f'{root}/conf/neo4j.conf').decode()
        defaults = dict(line.split('=', 1) for line in conf.splitlines()
                        if '=' in line and not line.lstrip().startswith('#'))
        if defaults.get('dbms.usage_report.enabled') != 'false' or defaults.get('dbms.max_databases') != '100':
            raise SystemExit('Windows archive does not carry the DozerDB configuration defaults')

def bundle(source):
    """Place every publishable artifact of this release in distribution/target with a SHA-256 sidecar."""
    target = ROOT / 'distribution/target'
    target.mkdir(exist_ok=True)
    version = CONFIG['distributionVersion']
    outputs = source / 'packaging/standalone/target'
    plugin = ROOT / 'plugin/target' / f'dozerdb-plugin-{CONFIG["browserVersion"]}.jar'
    if not plugin.is_file():
        raise SystemExit(f'Shaded plugin missing; run the plugin build first: {plugin}')
    artifacts = [outputs / f'dozerdb-{version}-unix.tar.gz', outputs / f'dozerdb-{version}-windows.zip', plugin]
    for artifact in artifacts:
        if not artifact.is_file():
            raise SystemExit(f'Expected release artifact missing: {artifact}')
    verify_windows_archive(artifacts[1])
    for artifact in artifacts:
        dest = target / artifact.name
        shutil.copy2(artifact, dest)
        print(f'Bundled {dest} (sha256 {checksum(dest)})')

if __name__ == '__main__':
    main()
