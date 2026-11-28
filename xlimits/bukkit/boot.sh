#!/usr/bin/env bash
# Boots xLimits on one Bukkit-family server with its boot checks (boot-checks.txt): every command
# that runs from the console, the imports from FarmControl's and Insights' files and a reload,
# debug, and a clean stop, with the small caps of boot-seed/'s config.yml; on Paper and Spigot,
# summoning past a cap too (spawn-checks.txt). For CI's boot matrix and by hand, after
# ./gradlew :xlimits:bukkit:assemble. It boots the jar R8 shrank (build/r8/), as a release ships;
# XLIMITS_JAR=<jar> boots another.
#
#   xlimits/bukkit/boot.sh paper|folia [version] [--java <JDK home>]
#   xlimits/bukkit/boot.sh <server.jar> --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh). XLIMITS_SPAWNS=false leaves out
# spawn-checks.txt, for a server on Folia's regions by another name (ShreddedPaper, Canvas),
# whose console can't summon either. Further arguments go to
# scripts/boot-test.sh, such as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XLIMITS_JAR:-$here/build/r8/xLimits.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xlimits:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,15p' "$0" >&2; exit 2; }

server=()
spawns=(--checks "$here/spawn-checks.txt")
[[ ${XLIMITS_SPAWNS:-true} == false ]] && spawns=()
case "$1" in
  paper | folia)
    server=(--project "$1")
    [[ $1 == folia ]] && spawns=()
    shift
    if (($#)) && [[ $1 != -* ]]; then
      server+=(--version "$1")
      shift
    fi ;;
  *.jar) server=(--server "$1"); shift ;;
  *) echo "the first argument is paper, folia or a server jar, not $1" >&2; exit 2 ;;
esac

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports
# on disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" --seed "$here/boot-seed" \
  --checks "$here/boot-checks.txt" "${spawns[@]}" "$@" "$jar"
