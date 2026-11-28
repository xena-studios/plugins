#!/usr/bin/env bash
# Boots xBackup on one Bukkit-family server with its boot checks (boot-checks.txt): backups with
# the world pause, a full check, a staged restore and a clean-up, a reload, debug, and a clean
# stop. For CI's boot matrix and by hand, after ./gradlew :xbackup:bukkit:assemble. It boots the
# jar R8 shrank (build/r8/), as a release ships; XBACKUP_JAR=<jar> boots another.
#
#   xbackup/bukkit/boot.sh paper|folia [version] [--family] [--java <JDK home>]
#   xbackup/bukkit/boot.sh <server.jar> [--family] --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh). --family boots xMoney 2.0 and xBans
# 2.0 beside it (their build/r8 jars) with family-checks.txt: their SQLite databases are copied
# by their own writers in each backup, and the copies restore. Further arguments go to
# scripts/boot-test.sh, such as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XBACKUP_JAR:-$here/build/r8/xBackup.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xbackup:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,13p' "$0" >&2; exit 2; }

server=()
case "$1" in
  paper | folia)
    server=(--project "$1")
    shift
    if (($#)) && [[ $1 != -* ]]; then
      server+=(--version "$1")
      shift
    fi ;;
  *.jar) server=(--server "$1"); shift ;;
  *) echo "the first argument is paper, folia or a server jar, not $1" >&2; exit 2 ;;
esac

extra=()
if (($#)) && [[ $1 == --family ]]; then
  shift
  for plugin in xmoney/bukkit/build/r8/xMoney.jar xbans/bukkit/build/r8/xBans.jar; do
    [[ -f $root/$plugin ]] || { echo "no $root/$plugin: run ./gradlew :xmoney:bukkit:assemble :xbans:bukkit:assemble first" >&2; exit 2; }
    extra+=("$root/$plugin")
  done
  extra+=(--checks "$here/family-checks.txt")
fi

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports on
# disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off, so a boot sends nothing.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" \
  --checks "$here/boot-checks.txt" "$@" "$jar" "${extra[@]}"
