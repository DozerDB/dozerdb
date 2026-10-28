#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
# Native callers may select another repository explicitly; orchestration always uses this one.
exec python3 "$ROOT/scripts/with-maven-lock.py" "$@"
