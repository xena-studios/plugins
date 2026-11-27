#!/usr/bin/env bash
# Boots xBans on one Bukkit-family server with its boot checks (boot-checks.txt): every command
# that runs from the console, a ban's life from 1.x's checks, imports' dry runs, a reload, debug,
# then imports from LiteBans' H2 and AdvancedBan's HSQLDB files (import-checks.txt, seeded from
# import-seed/), and a clean stop. For CI's boot matrix and by hand, after ./gradlew :xbans:bukkit:assemble. It
# boots the jar R8 shrank (build/r8/), as a release ships; XBANS_JAR=<jar> boots another.
#
#   xbans/bukkit/boot.sh paper|folia [version] [--zchat] [--java <JDK home>]
#   xbans/bukkit/boot.sh <server.jar> [--zchat] --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh). --zchat boots zChat 4.0 beside it
# (zchat/bukkit/build/r8/zChat.jar), for the family test: zChat asks xBans who's muted
# (zchat-checks.txt, and client-checks.txt with a real client). Further arguments go to
# scripts/boot-test.sh, such as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XBANS_JAR:-$here/build/r8/xBans.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xbans:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,14p' "$0" >&2; exit 2; }

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
if (($#)) && [[ $1 == --zchat ]]; then
  shift
  zchat=$root/zchat/bukkit/build/r8/zChat.jar
  [[ -f $zchat ]] || { echo "no $zchat: run ./gradlew :zchat:bukkit:assemble first" >&2; exit 2; }
  extra+=("$zchat")
  set -- --checks "$here/zchat-checks.txt" "$@"
fi

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports
# on disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off, so a boot sends nothing.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" --seed "$here/import-seed" \
  --checks "$here/boot-checks.txt" --checks "$here/import-checks.txt" "$@" "$jar" "${extra[@]}"
