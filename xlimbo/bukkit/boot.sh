#!/usr/bin/env bash
# Boots xLimbo on Paper with its boot checks (boot-checks.txt): its world and floor, every command
# that runs from the console, a reload, debug, and a clean stop. For CI's boot matrix and by hand,
# after ./gradlew :xlimbo:bukkit:assemble. It boots the jar R8 shrank (build/r8/), as a release
# ships; XLIMBO_JAR=<jar> boots another.
#
#   xlimbo/bukkit/boot.sh paper [version] [--family] [--java <JDK home>]
#   xlimbo/bukkit/boot.sh <server.jar> [--family] --java <JDK home>
#
# Paper only, from 1.21.11: Paper is downloaded (scripts/boot-test.sh). --family boots xTeleport
# 2.0 and xVanish 1.0 beside it (their build/r8/ jars) with send-back.via: teleport seeded
# (family-seed/), for the family test with real clients (family-checks.txt). Further arguments go
# to scripts/boot-test.sh, such as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XLIMBO_JAR:-$here/build/r8/xLimbo.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xlimbo:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,13p' "$0" >&2; exit 2; }

server=()
case "$1" in
  paper)
    server=(--project paper)
    shift
    if (($#)) && [[ $1 != -* ]]; then
      server+=(--version "$1")
      shift
    fi ;;
  *.jar) server=(--server "$1"); shift ;;
  *) echo "the first argument is paper or a server jar, not $1: xLimbo is Paper only" >&2; exit 2 ;;
esac

extra=()
checks=(--checks "$here/boot-checks.txt")
if (($#)) && [[ $1 == --family ]]; then
  shift
  for other in xteleport/bukkit/build/r8/xTeleport.jar xvanish/bukkit/build/r8/xVanish.jar; do
    [[ -f $root/$other ]] || { echo "no $root/$other: run ./gradlew :${other%%/*}:bukkit:assemble first" >&2; exit 2; }
    extra+=("$root/$other")
  done
  checks=(--seed "$here/family-seed" --checks "$here/family-checks.txt")
fi

# The second flag keeps debug reports on disk rather than uploading them from CI. bStats starts
# with xSample's seeded plugins/bStats/config.yml turning it off.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" "${checks[@]}" "$@" "$jar" "${extra[@]}"
