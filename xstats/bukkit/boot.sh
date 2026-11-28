#!/usr/bin/env bash
# Boots xStats on one Bukkit-family server with its boot checks (boot-checks.txt): every command
# that runs from the console, the website's self-test and export, the import, a reload, debug, and
# a clean stop; and a board in the world where the server has text displays (board-checks.txt,
# 1.19.4 on). For CI's boot matrix and by hand, after ./gradlew :xstats:bukkit:assemble. It boots
# the jar R8 shrank (build/r8/), as a release ships; XSTATS_JAR=<jar> boots another.
#
#   xstats/bukkit/boot.sh paper|folia [version] [--xmoney] [--java <JDK home>]
#   xstats/bukkit/boot.sh <server.jar> [--xmoney] --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh). XSTATS_BOARDS=false leaves out the
# board, for a server older than 1.19.4 (Spigot 1.13.2, the floor). --xmoney boots xMoney 2.0
# beside it (xmoney/bukkit/build/r8/xMoney.jar), for the family test: xStats finds xMoney's API by
# name and keeps its stats (xmoney-checks.txt). Further arguments go to scripts/boot-test.sh, such
# as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XSTATS_JAR:-$here/build/r8/xStats.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xstats:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,16p' "$0" >&2; exit 2; }

server=()
boards=(--checks "$here/board-checks.txt")
[[ ${XSTATS_BOARDS:-true} == false ]] && boards=()
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
  set -- --checks "$here/xmoney-checks.txt" "$@"
fi

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports on
# disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" \
  --checks "$here/boot-checks.txt" "${boards[@]}" "$@" "$jar" ${extra[@]+"${extra[@]}"}
