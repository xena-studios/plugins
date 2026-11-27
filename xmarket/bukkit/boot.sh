#!/usr/bin/env bash
# Boots xMarket on one Bukkit-family server with its boot checks (boot-checks.txt): the price
# list written to shops/ and read back off the server thread, /worth, the audit (no loops, and
# what this version has that the list doesn't price), the stats, the import's dry run, a reload,
# debug, and a clean stop. For CI's boot matrix and by hand, after ./gradlew :xmarket:bukkit:assemble.
# It boots the jar R8 shrank (build/r8/), as a release ships; XMARKET_JAR=<jar> boots another.
#
#   xmarket/bukkit/boot.sh paper|folia [version] [--xmoney] [--java <JDK home>]
#   xmarket/bukkit/boot.sh <server.jar> [--xmoney] --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh). --xmoney boots xMoney 2.0 beside it
# (xmoney/bukkit/build/r8/xMoney.jar), for the family test with a real client
# (xmoney-client-checks.txt): a purchase through /shop's menus debits, /sell hand credits.
# Further arguments go to scripts/boot-test.sh, such as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XMARKET_JAR:-$here/build/r8/xMarket.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xmarket:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,16p' "$0" >&2; exit 2; }

server=() version=
case "$1" in
  paper | folia)
    server=(--project "$1")
    shift
    if (($#)) && [[ $1 != -* ]]; then
      version=$1
      server+=(--version "$1")
      shift
    fi ;;
  *.jar) server=(--server "$1"); version=$(basename "$1" .jar | sed -n 's/^[a-z]*-\([0-9.]*\).*/\1/p'); shift ;;
  *) echo "the first argument is paper, folia or a server jar, not $1" >&2; exit 2 ;;
esac

# A jar from the web, once, checked against its sha256: into .cache/plugins.
fetch() {
  local url=$1 sum=$2 name=$3 dir=$root/.cache/plugins
  mkdir -p "$dir"
  if [[ ! -f $dir/$name ]]; then curl -fsSL -o "$dir/$name.part" "$url" && mv "$dir/$name.part" "$dir/$name"; fi
  local got
  got=$( (command -v sha256sum >/dev/null && sha256sum "$dir/$name" || shasum -a 256 "$dir/$name") | cut -d' ' -f1)
  [[ $got == "$sum" ]] || { echo "$name's sha256 is $got, not $sum" >&2; rm -f "$dir/$name"; exit 2; }
  echo "$dir/$name"
}

extra=()
if (($#)) && [[ $1 == --xmoney ]]; then
  shift
  xmoney=$root/xmoney/bukkit/build/r8/xMoney.jar
  [[ -f $xmoney ]] || { echo "no $xmoney: run ./gradlew :xmoney:bukkit:assemble first" >&2; exit 2; }
  extra+=("$xmoney")
  # Older than 1.15.2 the server can't let xMoney stand in for Vault: Vault.jar beside it, as owners run it.
  if [[ -n $version ]] && [[ $(printf '%s\n1.15.2\n' "$version" | sort -V | head -1) == "$version" && $version != 1.15.2 ]]; then
    extra+=("$(fetch https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar \
      a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d Vault-1.7.3.jar)")
  fi
fi

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports
# on disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off, so a boot sends nothing.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" \
  --checks "$here/boot-checks.txt" "$@" "$jar" "${extra[@]}"
