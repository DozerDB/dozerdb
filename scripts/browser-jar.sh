#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Both staging and Maven resources must be replaced to remove obsolete hashed assets.
rm -rf "$ROOT/browser/mvn" "$ROOT/browser/target/classes"
"$ROOT/scripts/browser.sh" prepare-jar
"$ROOT/scripts/maven.sh" -f "$ROOT/browser/pom.xml" install -DskipTests
