#!/usr/bin/env bash
# Boots a Pumpkin server with the two spike plugins and prints their log lines.
#   scripts/run-pumpkin.sh <path to the pumpkin binary> <run dir>
# Build Pumpkin first, outside this repo: `cargo build --release` in a Pumpkin checkout.
set -euo pipefail
spike=$(cd "$(dirname "$0")/.." && pwd)
pumpkin=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
run=$(mkdir -p "$2/plugins" && cd "$2" && pwd)

(cd "$spike" && ./gradlew -q :pumpkin-hello:assemblePluginRelease :pumpkin-peer:assemblePluginRelease)
cp "$spike"/pumpkin-hello/build/pumpkin-hello.wasm "$spike"/pumpkin-peer/build/pumpkin-peer.wasm "$run/plugins/"

# Pumpkin asks on the console before loading a plugin that requests permissions, and refuses
# when there's no TTY. It remembers the answer per plugin hash in permission_cache.json.
python3 - "$run/plugins" <<'EOF'
import hashlib, json, pathlib, sys
plugins = pathlib.Path(sys.argv[1])
cache_file = plugins / "permission_cache.json"
cache = json.loads(cache_file.read_text()) if cache_file.exists() else {"entries": {}}
digest = hashlib.sha256((plugins / "pumpkin-hello.wasm").read_bytes()).hexdigest()
cache["entries"][digest] = {"permissions_requested": ["fs.write.data"], "approved": True}
cache_file.write_text(json.dumps(cache, indent=2))
EOF

cd "$run"
"$pumpkin" > pumpkin.log 2>&1 &
pid=$!
for _ in $(seq 1 120); do grep -q 'Started server' pumpkin.log && break; sleep 0.5; done
sleep 1
kill -INT "$pid"; wait "$pid" || true
sed 's/\x1b\[[0-9;]*m//g' pumpkin.log | grep -E 'm0-|xena-m0|plugin|Started server|ERROR'
