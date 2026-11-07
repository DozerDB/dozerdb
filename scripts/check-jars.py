#!/usr/bin/env python3
"""Verify the packaging handoff: core, metrics, procedures, query-log and Browser JARs, their notices and service files,
and the exact contents of the shaded plugin.

The metrics artifact must hold the module's own output plus Dropwizard Metrics relocated under
org/dozerdb/metrics/shaded/ and nothing else; the procedures and query-log artifacts their own output and nothing else,
each with one service file (its extension factory); the plugin must hold exactly core, metrics, procedures, query log
and Browser."""
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import release_line

ROOT = Path(__file__).resolve().parent.parent
# Every DozerDB JAR, the plugin included, is named by the distribution version the pins derive.
VERSION = release_line.pinned_version(release_line.read_config())
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
SHADED = 'org/dozerdb/metrics/shaded/'
DROPWIZARD = 'com/codahale/metrics/'
NOTICE = 'META-INF/DOZERDB-NOTICE.txt'
SERVICES = 'META-INF/services/'
# The startup banner's version resource in the core JAR and the tracked template Maven filters into it.
VERSION_RESOURCE = 'org/neo4j/server/dozerdb-version.properties'
VERSION_TEMPLATE = ROOT / 'core/src/main/version/dozerdb-version.properties'
# Files the Neo4j parent gives every module at the same META-INF names; the plugin keeps core's copies.
PER_MODULE_FILES = {'META-INF/README.md', 'META-INF/PublicApi.txt', 'META-INF/LICENSE.txt', 'LICENSE.txt'}

def pom(component):
    return ET.parse(ROOT / component / 'pom.xml').getroot()

def artifact(component, version=VERSION):
    """A module's built JAR: its artifactId at the distribution version, which its POM must carry."""
    name = pom(component).findtext('m:artifactId', namespaces=NS)
    path = ROOT / component / 'target' / f'{name}-{version}.jar'
    if not path.is_file():
        raise SystemExit(f'{path.relative_to(ROOT)} is missing: {component}/pom.xml must build the artifact at the '
                         f'distribution version {version}')
    return path

def neo4j_parent_property(name):
    """A property of the pinned Neo4j parent POM, read from the checkout-local Maven repository."""
    parent = pom('.').find('m:parent', NS)
    group, artifact_id, version = (parent.findtext(f'm:{tag}', namespaces=NS) for tag in ('groupId', 'artifactId', 'version'))
    path = ROOT / '.build/m2' / group.replace('.', '/') / artifact_id / version / f'{artifact_id}-{version}.pom'
    return ET.parse(path).getroot().findtext(f'm:properties/m:{name}', namespaces=NS)

def dropwizard_artifacts():
    """The Dropwizard artifactIds the metrics module declares (metrics-core, metrics-jmx, metrics-graphite)."""
    names = dependency_artifacts(pom('metrics'), 'io.dropwizard.metrics')
    assert names, 'metrics/pom.xml declares no Dropwizard artifact'
    return names

def dropwizard_jars():
    """The Dropwizard artifacts the metrics module declares, at the version the Neo4j parent manages."""
    version = neo4j_parent_property('metrics.version')
    return version, [ROOT / '.build/m2/io/dropwizard/metrics' / name / version / f'{name}-{version}.jar'
                     for name in dropwizard_artifacts()]

def notice_names(notice, version, artifacts):
    """The components the DozerDB notice must name: the Dropwizard version with each of its artifacts, the Prometheus
    simpleclient the distribution patch ships, and the licence."""
    return [f'Dropwizard Metrics {version}', *artifacts, 'Prometheus simpleclient 0.16.0', 'Apache License, Version 2.0']

def dependency_artifacts(root, group):
    """The artifactIds a POM declares as dependencies in one group."""
    return [dep.findtext('m:artifactId', namespaces=NS) for dep in root.findall('m:dependencies/m:dependency', NS)
            if dep.findtext('m:groupId', namespaces=NS) == group]

def is_metadata(name):
    """A JAR entry that says nothing about what the JAR ships: a directory, the manifest, the index or Maven's descriptor."""
    return name.endswith('/') or name in ('META-INF/MANIFEST.MF', 'META-INF/INDEX.LIST') or name.startswith('META-INF/maven/')

def relocated(name):
    """Where the metrics shade places a Dropwizard class: com/codahale/metrics/... under org/dozerdb/metrics/shaded/."""
    if not name.startswith(DROPWIZARD):
        raise ValueError(f'not a Dropwizard Metrics class: {name}')
    return SHADED + name

def service_lines(data):
    """The provider class names of a META-INF/services file, ignoring blank and comment lines."""
    return {line.strip() for line in data.decode().splitlines() if line.strip() and not line.lstrip().startswith('#')}

def resource_version(data):
    """The version a dozerdb-version.properties resource carries: the value of its one version= line."""
    values = [line.split('=', 1)[1].strip() for line in data.decode().splitlines()
              if line.strip() and not line.lstrip().startswith('#') and line.split('=', 1)[0].strip() == 'version']
    assert len(values) == 1, f'{VERSION_RESOURCE} must carry one version= line, found {len(values)}'
    return values[0]

def check_version_resource(c, template):
    """The banner's version resource is the build's: the tracked template holds only Maven's ${project.version}, and
    the resource in the built core JAR carries the distribution version."""
    assert resource_version(template) == '${project.version}', \
        f'{VERSION_TEMPLATE.relative_to(ROOT)} must hold version=${{project.version}} for Maven to fill, not a hand-written value'
    assert VERSION_RESOURCE in c.namelist(), f'Core JAR lacks the version resource {VERSION_RESOURCE}'
    built = resource_version(c.read(VERSION_RESOURCE))
    assert built == VERSION, f'Core JAR version resource carries {built!r}, expected the distribution version {VERSION}'

def classes(names):
    return {name for name in names if name.endswith('.class')}

def files(names):
    return {name for name in names if not is_metadata(name)}

def compiled_output(component):
    """Every file the module's build wrote to target/classes, as JAR entry names."""
    base = ROOT / component / 'target/classes'
    return {path.relative_to(base).as_posix() for path in base.rglob('*') if path.is_file()}

def check_metrics(m, mn):
    """The metrics artifact holds the module's own output and the relocated library, nothing else."""
    version, jars = dropwizard_jars()
    library = set()
    for jar in jars:
        with ZipFile(jar) as z:
            library |= {relocated(name) for name in classes(z.namelist())}
    own = compiled_output('metrics')
    assert files(mn) == own | library, (f'Metrics artifact differs from its own output plus relocated Dropwizard: '
                                        f'extra {sorted(files(mn) - own - library)}, missing {sorted((own | library) - files(mn))}')
    assert all(name.startswith('org/dozerdb/metrics/') for name in classes(mn)), 'Metrics class outside the DozerDB package'
    assert not any(name.startswith(('com/codahale/', 'org/slf4j/', 'com/rabbitmq/')) for name in mn), 'Unrelocated library class in the metrics artifact'
    assert not any(name.endswith('.md') for name in mn), 'A Markdown document (the module README is a maintainer document) in the metrics artifact'
    providers = {
        'org.neo4j.kernel.extension.ExtensionFactory': 'org.dozerdb.metrics.adapter.MetricsExtensionFactory',
        'org.neo4j.configuration.SettingsDeclaration': 'org.dozerdb.metrics.settings.MetricsSettings',
        'org.neo4j.kernel.diagnostics.DiagnosticsOfflineReportProvider': 'org.dozerdb.metrics.adapter.MetricsReportProvider',
    }
    assert {name for name in files(mn) if name.startswith(SERVICES)} == {SERVICES + service for service in providers}, 'Metrics service files differ from the three expected'
    for service, provider in providers.items():
        assert provider in service_lines(m.read(SERVICES + service)), f'Metrics service file {service} lacks {provider}'
    notice = m.read(NOTICE)
    assert notice == (ROOT / 'metrics/src/main/resources/META-INF' / NOTICE.rsplit('/', 1)[1]).read_bytes(), 'Notice differs from the tracked file'
    for component in notice_names(notice.decode(), version, dropwizard_artifacts()):
        assert component in notice.decode(), f'Notice does not mention {component}'

def check_own_output(label, component, package, factory, z, names, own):
    """A library-free artifact holds the module's own output and nothing else: no library, no notice, and one service
    file naming its extension factory. own is the build's output (target/classes) unless a test supplies it."""
    own = compiled_output(component) if own is None else own
    assert files(names) == own, (f'{label} artifact differs from its own output: '
                                 f'extra {sorted(files(names) - own)}, missing {sorted(own - files(names))}')
    assert all(name.startswith(package) for name in classes(names)), f'{label} class outside the DozerDB package'
    assert not any(name.endswith('.md') for name in names), f'A Markdown document (the module README is a maintainer document) in the {label} artifact'
    service = 'org.neo4j.kernel.extension.ExtensionFactory'
    assert {name for name in files(names) if name.startswith(SERVICES)} == {SERVICES + service}, f'{label} service files differ from the one expected: the extension factory service file'
    assert factory in service_lines(z.read(SERVICES + service)), f'{label} service file {service} lacks {factory}'
    assert NOTICE not in names, f'The {label} artifact must not carry a notice: the metrics JAR is the notice vehicle'

def check_procedures(p, pn, own=None):
    """The procedures artifact: its own output, one service file naming the extension factory, no notice."""
    check_own_output('Procedures', 'procedures', 'org/dozerdb/procedures/',
                     'org.dozerdb.procedures.adapter.ProceduresExtensionFactory', p, pn, own)

def check_query_log(q, qn, own=None):
    """The query-log artifact: its own output, one service file naming the extension factory, no notice."""
    check_own_output('Query-log', 'query-log', 'org/dozerdb/querylog/',
                     'org.dozerdb.querylog.adapter.QueryLogExtensionFactory', q, qn, own)

def check_plugin(sources, p, pn):
    """The plugin is exactly core, metrics, procedures, query log and Browser: every file carried over byte for byte,
    service files merged, the per-module files from core, and no class from anywhere else."""
    core, metrics, procedures, query_log, browser = sources
    cn, mn, rn, qn, bn = (set(z.namelist()) for z in sources)
    assert classes(pn) == classes(cn) | classes(mn) | classes(rn) | classes(qn), 'Unexpected shaded transitive classes'
    assert not any(name.startswith(('com/codahale/', 'org/slf4j/', 'com/rabbitmq/')) for name in pn), 'Unrelocated library class in the plugin'
    assert {n for n in pn if n.startswith('browser/')} == {n for n in bn if n.startswith('browser/')}, 'Stale or missing shaded Browser assets'
    services = {}
    for z, names in zip(sources, (cn, mn, rn, qn, bn)):
        for name in files(names):
            if name.startswith(SERVICES):
                services.setdefault(name, set()).update(service_lines(z.read(name)))
            elif z is not core and name in PER_MODULE_FILES:
                assert p.read(name) == core.read(name), f'Plugin must keep core\'s {name}'
            else:
                assert name in pn and z.read(name) == p.read(name), f'Shade lost or changed {name}'
    for name, providers in services.items():
        assert service_lines(p.read(name)) == providers, f'Shade did not merge every provider of {name}'

def main():
    core, metrics, procedures, query_log, browser, plugin = map(artifact, ('core', 'metrics', 'procedures', 'query-log', 'browser', 'plugin'))
    with ZipFile(core) as c, ZipFile(metrics) as m, ZipFile(procedures) as r, ZipFile(query_log) as q, ZipFile(browser) as b, \
            ZipFile(plugin) as p:
        cn, mn, rn, qn, bn, pn = (set(z.namelist()) for z in (c, m, r, q, b, p))
        required = {
            'org/neo4j/server/DozerDbEntryPoint.class',
            'org/neo4j/kernel/impl/constraints/DozerDbConstraintSemantics.class',
            'org/neo4j/cypher/internal/DozerDbAdministrationCommandRuntime.class',
            'META-INF/services/org.neo4j.server.startup.EntryPoint',
        }
        assert required <= cn, f'Core missing {required-cn}'
        assert b'org.neo4j.server.DozerDbEntryPoint' in c.read('META-INF/services/org.neo4j.server.startup.EntryPoint')
        for name in ('index.html', 'LICENSE', 'LICENSES.txt', 'NOTICE.txt'):
            assert f'browser/{name}' in bn, f'Browser missing {name}'
        assert any(n.startswith('browser/app-') and n.endswith('.js') for n in bn)
        check_version_resource(c, VERSION_TEMPLATE.read_bytes())
        check_metrics(m, mn)
        check_procedures(r, rn)
        check_query_log(q, qn)
        check_plugin((c, m, r, q, b), p, pn)
    print(f'PASS: core services/classes, core version resource {VERSION}, metrics artifact (own output plus relocated Dropwizard, services, notice), '
          f'procedures and query-log artifacts (own output, one service file each), Browser assets/notices, and exact '
          f'shaded contents: {plugin}')

if __name__ == '__main__':
    main()
