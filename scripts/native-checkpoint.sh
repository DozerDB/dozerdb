#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
scripts/maven.sh -pl core -am install -DskipTests
scripts/browser.sh install --frozen-lockfile
CI=true scripts/browser.sh build
scripts/browser-jar.sh
scripts/maven.sh -pl plugin -am package -DskipTests
python3 scripts/check-jars.py
