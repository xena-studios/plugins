#!/usr/bin/env bash
# Boots xTeleport on one Bukkit-family server with its boot checks (boot-checks.txt): every
# command that runs from the console, real /rtp searches in the overworld, the Nether and the
# End, the import's dry run, a reload, debug, and a clean stop. For CI's boot matrix and by hand,
# after ./gradlew :xteleport:bukkit:assemble. It boots the jar R8 shrank (build/r8/), as a
# release ships; XTELEPORT_JAR=<jar> boots another.
#
#   xteleport/bukkit/boot.sh paper|folia [version] [--xmoney] [--java <JDK home>]
#   xteleport/bukkit/boot.sh <server.jar> [--xmoney] --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh). --xmoney boots xMoney 2.0 beside it
# (xmoney/bukkit/build/r8/xMoney.jar), with /spawn costing 5 (xmoney-seed/), for the family test
# with a real client (client-checks.txt with --xmoney). Further arguments go to
# scripts/boot-test.sh, such as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XTELEPORT_JAR:-$here/build/r8/xTeleport.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xteleport:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,15p' "$0" >&2; exit 2; }

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
if (($#)) && [[ $1 == --xmoney ]]; then
  shift
  xmoney=$root/xmoney/bukkit/build/r8/xMoney.jar
  [[ -f $xmoney ]] || { echo "no $xmoney: run ./gradlew :xmoney:bukkit:assemble first" >&2; exit 2; }
  extra+=("$xmoney")
  set -- --seed "$here/xmoney-seed" "$@"
fi

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports
# on disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off, so a boot sends nothing.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" \
  --checks "$here/boot-checks.txt" "$@" "$jar" "${extra[@]}"
