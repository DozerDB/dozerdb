#!/usr/bin/env python3
"""Verify the packaging handoff, including notices and service discovery."""
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
def artifact(component):
    pom = ET.parse(ROOT / component / 'pom.xml').getroot()
    name = pom.findtext('m:artifactId', namespaces=NS)
    version = pom.findtext('m:version', namespaces=NS)
    return ROOT / component / 'target' / f'{name}-{version}.jar'

core, browser, plugin = map(artifact, ('core', 'browser', 'plugin'))
with ZipFile(core) as c, ZipFile(browser) as b, ZipFile(plugin) as p:
    cn, bn, pn = map(lambda z: set(z.namelist()), (c, b, p))
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
    assert {n for n in pn if n.startswith('browser/')} == {n for n in bn if n.startswith('browser/')}, 'Stale or missing shaded Browser assets'
    for z, names in ((c, cn), (b, bn)):
        for name in names:
            if name.endswith('/') or name in ('META-INF/MANIFEST.MF', 'META-INF/INDEX.LIST') or name.startswith('META-INF/maven/'):
                continue
            assert name in pn and z.read(name) == p.read(name), f'Shade lost or changed {name}'
    assert {n for n in pn if n.endswith('.class')} == {n for n in cn if n.endswith('.class')}, 'Unexpected shaded transitive classes'
print(f'PASS: core services/classes, Browser assets/notices, and exact shaded contents: {plugin}')
