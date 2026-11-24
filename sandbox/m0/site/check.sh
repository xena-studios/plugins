#!/usr/bin/env bash
# Fails when docs/src/generated holds a stale copy of what the plugin's code generates:
# writes a fresh manifest, config.yml and schema (build/xena/), then compares them with the
# committed ones. CI runs the same thing as part of ./gradlew check.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew --quiet --console=plain checkXenaManifest
