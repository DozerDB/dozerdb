#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BROWSER_NODE=${BROWSER_NODE:-"$ROOT/.build/browser-tools/node_modules/node/bin/node"}
BROWSER_YARN=${BROWSER_YARN:-"$ROOT/.build/browser-tools/node_modules/yarn/bin/yarn.js"}
if [[ ! -x "$BROWSER_NODE" || ! -f "$BROWSER_YARN" ]]; then
  echo 'Install Browser tools: npm install --prefix .build/browser-tools --no-audit --no-fund --package-lock=false node@16.10.0 yarn@1.22.22' >&2
  exit 1
fi
[[ $("$BROWSER_NODE" --version) == v16.10.0 ]] || { echo 'Browser requires Node 16.10.0' >&2; exit 1; }
[[ $("$BROWSER_NODE" "$BROWSER_YARN" --version) == 1.22.22 ]] || { echo 'Browser requires Yarn 1.22.22' >&2; exit 1; }
export PATH="$(dirname "$BROWSER_NODE"):$ROOT/.build/browser-tools/node_modules/.bin:$PATH"
cd "$ROOT/browser"
exec "$BROWSER_NODE" "$BROWSER_YARN" "$@"
