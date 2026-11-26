#!/usr/bin/env bash
# Boots xMoney on one Bukkit-family server with its boot checks (boot-checks.txt), beside the two
# never-released plugins that use it as Vault (xmoney/vault-consumer, and on Paper and Folia
# xmoney/hook-consumer, a Paper plugin on today's core's Vault hook): every command from the
# console, Vault 1 and VaultUnlocked 2 called the ways real plugins call them, the importers, a
# reload, debug, and a clean stop. For CI's boot matrix and by hand, after
# ./gradlew :xmoney:bukkit:assemble :xmoney:vault-consumer:assemble :xmoney:hook-consumer:assemble.
# It boots the jar R8 shrank (build/r8/), as a release ships; XMONEY_JAR=<jar> boots another.
#
#   xmoney/bukkit/boot.sh paper|folia [version] [--java <JDK home>]
#   xmoney/bukkit/boot.sh <server.jar> --java <JDK home>
#
# Below 1.15.2 Bukkit can't let xMoney stand in for Vault, so a real Vault.jar (1.7.3, checked
# against its sha256) boots beside it there, as owners run it. --vaultunlocked adds a real
# VaultUnlocked jar instead, which Paper refuses as a duplicate (boot-checks-vaultunlocked.txt);
# --zchat boots zChat 4.0 beside it (zchat/bukkit/build/r8/zChat.jar), its format seeded to show
# <balance> (zchat-seed/, zchat-checks.txt), the family test of the two.
# Further arguments go to scripts/boot-test.sh, such as more jars and --checks for the family test.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XMONEY_JAR:-$here/build/r8/xMoney.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xmoney:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,18p' "$0" >&2; exit 2; }

server=() paper=false version=
case "$1" in
  paper | folia)
    server=(--project "$1")
    paper=true
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

extra=() checks=("$here/boot-checks.txt")
if (($#)) && [[ $1 == --zchat ]]; then
  shift
  zchat=$root/zchat/bukkit/build/r8/zChat.jar
  [[ -f $zchat ]] || { echo "no $zchat: run ./gradlew :zchat:bukkit:assemble first" >&2; exit 2; }
  extra+=("$zchat")
  checks+=("$here/zchat-checks.txt")
  set -- --seed "$here/zchat-seed" "$@"
fi
if (($#)) && [[ $1 == --vaultunlocked ]]; then
  shift
  extra+=("$(fetch https://github.com/TheNewEconomy/VaultUnlocked/releases/download/2.20.1/VaultUnlocked-2.20.1.jar \
    4ec21910767a68b2defacb121cffee9840ded8b01abcbff03779fb9ab637296a VaultUnlocked-2.20.1.jar)")
  checks=("$here/boot-checks-vaultunlocked.txt")
fi

# Older than 1.15.2: no provides:, so Vault.jar beside xMoney, as on owners' servers.
older() { [[ -n $version ]] && [[ $(printf '%s\n1.15.2\n' "$version" | sort -V | head -1) == "$version" && $version != 1.15.2 ]]; }
if older; then
  extra+=("$(fetch https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar \
    a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d Vault-1.7.3.jar)")
fi

consumers=("$root/xmoney/vault-consumer/build/libs/vault-consumer.jar")
# The core's Vault hook (today's xPlots and xTeleport) is a Paper plugin: Paper and Folia only.
[[ $paper == false ]] || consumers+=("$root/xmoney/hook-consumer/build/libs/hook-consumer.jar")
for consumer in "${consumers[@]}"; do [[ -f $consumer ]] || { echo "no $consumer: assemble it first" >&2; exit 2; }; done
args=()
for file in "${checks[@]}"; do args+=(--checks "$file"); done
[[ $paper == false || ${checks[0]} == *vaultunlocked* ]] || args+=(--checks "$here/boot-checks-paper.txt")

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports on
# disk rather than uploading them from CI. bStats starts with xSample's seeded config turning it off.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" \
  "${args[@]}" "$@" "$jar" "${consumers[@]}" "${extra[@]}"
