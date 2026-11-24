#!/usr/bin/env bash
# Boots xSample on one Bukkit-family server with its boot checks (boot-checks.txt): the
# platform's own checks at startup, console commands, and a clean stop. For CI's boot matrix
# and by hand, after ./gradlew :core:core-testing:sample:bukkit:assemble. It boots the jar R8
# shrank (build/r8/), as a release ships; XSAMPLE_JAR=<jar> boots another, such as Shadow's
# unshrunk build/libs/XSample.jar.
#
#   core/core-testing/sample/bukkit/boot.sh paper|folia [version] [--java <JDK home>]
#   core/core-testing/sample/bukkit/boot.sh <server.jar> --java <JDK home>
#
# Paper and Folia are downloaded (the newest build of the version, 1.21.11 unless given);
# Spigot and CraftBukkit come from BuildTools, so their jar is passed, with the Java that
# server accepts: 8 for 1.8.8, 11 for 1.13.2, 16 for 1.16.5. Any further arguments go to
# scripts/boot-test.sh, such as --logged "through the JDK's trusted lookup".
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../../.." && pwd)
jar=${XSAMPLE_JAR:-$here/build/r8/XSample.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :core:core-testing:sample:bukkit:assemble first" >&2; exit 2; }
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

# Old Spigot waits 20 seconds over its age without this; newer servers ignore it. bStats starts
# (xSample's and xTwin's, under a made-up id) with plugins/bStats/config.yml turning it off.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true \
  --jvm -Dxsample.bstats=1 --jvm -Dxtwin.bstats=1 --seed "$here/boot-seed" \
  --checks "$here/boot-checks.txt" "$@" "$jar"
