#!/usr/bin/env bash
# Boots xVanish on one Bukkit-family server with its boot checks (boot-checks.txt): every command
# that runs from the console, the import from SuperVanish's data.yml (seeded from import-seed/),
# a reload, debug, and a clean stop. For CI's boot matrix and by hand, after
# ./gradlew :xvanish:bukkit:assemble. It boots the jar R8 shrank (build/r8/), as a release ships;
# XVANISH_JAR=<jar> boots another.
#
#   xvanish/bukkit/boot.sh paper|folia [version] [--family] [--java <JDK home>]
#   xvanish/bukkit/boot.sh <server.jar> [--family] --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh). --family boots zChat 4.0 and
# xTeleport 2.0 beside it (their build/r8/ jars), for the family test with real clients
# (client-checks.txt): a vanished player unseen in the tab list, in zChat's mentions and by
# xTeleport's /tpa. Further arguments go to scripts/boot-test.sh, such as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XVANISH_JAR:-$here/build/r8/xVanish.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xvanish:bukkit:assemble first" >&2; exit 2; }
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
if (($#)) && [[ $1 == --family ]]; then
  shift
  for other in zchat/bukkit/build/r8/zChat.jar xteleport/bukkit/build/r8/xTeleport.jar; do
    [[ -f $root/$other ]] || { echo "no $root/$other: run ./gradlew :${other%%/*}:bukkit:assemble first" >&2; exit 2; }
    extra+=("$root/$other")
  done
fi

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports
# on disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" --seed "$here/import-seed" \
  --checks "$here/boot-checks.txt" --checks "$here/import-checks.txt" "$@" "$jar" "${extra[@]}"
