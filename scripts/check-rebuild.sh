#!/usr/bin/env bash
# Exercise removal across Browser staging, Maven resources, and repeated shading.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
PROBE=__dozer_rebuild_probe__.txt
scripts/browser-jar.sh
[[ ! -e "browser/mvn/browser/$PROBE" ]]
trap 'rm -f "browser/mvn/browser/$PROBE"' EXIT
printf 'temporary packaging regression probe\n' > "browser/mvn/browser/$PROBE"
scripts/maven.sh -f "$ROOT/browser/pom.xml" install -DskipTests
scripts/maven.sh -pl plugin -am package -DskipTests
python3 - <<'PY'
import sys
from zipfile import ZipFile
sys.path.insert(0, 'scripts')
import release_line
version = release_line.pinned_version(release_line.read_config())
with ZipFile(f'plugin/target/dozerdb-plugin-{version}.jar') as jar:
    assert 'browser/__dozer_rebuild_probe__.txt' in jar.namelist()
print('PASS: probe present in first shaded package')
PY
scripts/browser-jar.sh
scripts/maven.sh -pl plugin -am package -DskipTests
python3 scripts/check-jars.py
python3 - <<'PY'
import sys
from zipfile import ZipFile
sys.path.insert(0, 'scripts')
import release_line
version = release_line.pinned_version(release_line.read_config())
for path in [f'browser/target/dozerdb-browser-{version}.jar', f'plugin/target/dozerdb-plugin-{version}.jar']:
    with ZipFile(path) as jar:
        assert 'browser/__dozer_rebuild_probe__.txt' not in jar.namelist(), path
print('PASS: removed asset absent after repeat build without clean')
PY
