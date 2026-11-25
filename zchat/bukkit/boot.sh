#!/usr/bin/env bash
# Boots zChat on one Bukkit-family server with its boot checks (boot-checks.txt): every
# command from the console, a reload, debug, and a clean stop. For CI's boot matrix and
# by hand, after ./gradlew :zchat:bukkit:assemble. It boots the jar R8 shrank (build/r8/),
# as a release ships; ZCHAT_JAR=<jar> boots another.
#
#   zchat/bukkit/boot.sh paper|folia [version] [--java <JDK home>]
#   zchat/bukkit/boot.sh <server.jar> --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh). Further arguments go to
# scripts/boot-test.sh, such as more jars and --checks for the family test.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${ZCHAT_JAR:-$here/build/r8/zChat.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :zchat:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,12p' "$0" >&2; exit 2; }

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

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports
# on disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off, so a boot sends nothing.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" \
  --checks "$here/boot-checks.txt" "$@" "$jar"
