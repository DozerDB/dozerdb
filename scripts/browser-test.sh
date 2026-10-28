#!/usr/bin/env bash
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
result=0
"$ROOT/scripts/browser.sh" tsc --noEmit || result=1
"$ROOT/scripts/browser.sh" eslint --ext .ts --ext .tsx ./ --quiet || result=1
"$ROOT/scripts/browser.sh" jest --runInBand --ci || result=1
exit "$result"
