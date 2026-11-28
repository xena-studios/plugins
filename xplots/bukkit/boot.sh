#!/usr/bin/env bash
# Boots xPlots on one Bukkit-family server with its boot checks (boot-checks.txt): the database
# read off the server thread, a field made from a typed selection with its signs, settings, a
# plot's info, a reload, debug, and the field deleted again. For CI's boot matrix and by hand,
# after ./gradlew :xplots:bukkit:assemble.
# It boots the jar R8 shrank (build/r8/), as a release ships; XPLOTS_JAR=<jar> boots another.
#
#   xplots/bukkit/boot.sh paper|folia [version] [--worldguard] [--family] [--java <JDK home>]
#   xplots/bukkit/boot.sh <server.jar> [--worldguard] [--family] --java <JDK home>
#
# As core/core-testing/sample/bukkit/boot.sh: Paper and Folia are downloaded, Spigot and
# CraftBukkit come from BuildTools (scripts/server-jar.sh).
# --worldguard boots WorldGuard and WorldEdit beside it, the release for that Minecraft fetched
# once from Modrinth and checked against its sha256 (1.13: 7.0.0 and 7.0.0; 1.21.x: 7.0.15 and
# 7.4.6; 26.x: 7.0.21 and 7.4.6), and checks the field's region (worldguard-checks.txt). Folia
# has no WorldGuard, so it's refused there. On Spigot, WorldEdit's warning that it works better
# on Paper is allowed.
# --family boots xMoney, xTeleport, xRewind and xVanish 2.0 beside it (their build/r8/ jars;
# below 1.15.2 with Vault 1.7.3, as xMoney needs there), and checks xPlots finds each through
# its API (family-checks.txt): rent through xMoney's ledger, a rollback through xRewind's.
# Further arguments go to scripts/boot-test.sh, such as more jars and --checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${XPLOTS_JAR:-$here/build/r8/xPlots.jar}
[[ -f $jar ]] || { echo "no $jar: run ./gradlew :xplots:bukkit:assemble first" >&2; exit 2; }
(($#)) || { sed -n '2,20p' "$0" >&2; exit 2; }

server=() version= project=
case "$1" in
  paper | folia)
    project=$1
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

below() { [[ -n $version && $(printf '%s\n%s\n' "$version" "$2" | sort -V | head -1) == "$version" && $version != "$2" ]]; }

extra=() checks=()
while (($#)); do
  case "$1" in
    --worldguard)
      shift
      [[ $project != folia ]] || { echo "WorldGuard doesn't run on Folia" >&2; exit 2; }
      [[ -n $version ]] || { echo "--worldguard needs the server's version" >&2; exit 2; }
      case "$version" in
        1.13*)
          extra+=("$(fetch https://cdn.modrinth.com/data/1u6JkXh5/versions/dcfDNKXg/worldedit-bukkit-7.0.0.jar \
            287b819212fe8c1497bb321070fec17ff58efc82c354514a454117a5fe4c131a worldedit-bukkit-7.0.0.jar)")
          extra+=("$(fetch https://cdn.modrinth.com/data/DKY9btbd/versions/Co8Wzc6T/worldguard-bukkit-7.0.0.jar \
            a7c960fd914f64389cc6aa01082a2f60373689ea9dd1ff42a98be77eb5baa72b worldguard-bukkit-7.0.0.jar)") ;;
        1.21*)
          extra+=("$(fetch https://cdn.modrinth.com/data/1u6JkXh5/versions/YFnqiUlL/worldedit-bukkit-7.4.6.jar \
            e0ce67d870ad1fef8a40dd9d7926360caf3bb44722438545d6b20b55f82a0c91 worldedit-bukkit-7.4.6.jar)")
          extra+=("$(fetch https://cdn.modrinth.com/data/DKY9btbd/versions/WaElxvDz/worldguard-bukkit-7.0.15.jar \
            82109263128cb2232fd0e987f8f8bfa145f6a071c5fa92cf3d221922af863c74 worldguard-bukkit-7.0.15.jar)") ;;
        26.*)
          extra+=("$(fetch https://cdn.modrinth.com/data/1u6JkXh5/versions/YFnqiUlL/worldedit-bukkit-7.4.6.jar \
            e0ce67d870ad1fef8a40dd9d7926360caf3bb44722438545d6b20b55f82a0c91 worldedit-bukkit-7.4.6.jar)")
          extra+=("$(fetch https://cdn.modrinth.com/data/DKY9btbd/versions/fiHLM6oY/worldguard-bukkit-7.0.21.jar \
            070223c535629f3f5d15f002388c1e3ae615a5f4ba051e7ccfb5d716540547a9 worldguard-bukkit-7.0.21.jar)") ;;
        *) echo "no pinned WorldGuard for $version" >&2; exit 2 ;;
      esac
      checks+=(--checks "$here/worldguard-checks.txt")
      # On Spigot, WorldEdit warns at startup that it works better on Paper, a dozen lines of it.
      [[ -n $project ]] || checks+=(--allow "[WorldEdit]") ;;
    --family)
      shift
      for p in xmoney:xMoney xteleport:xTeleport xrewind:xRewind xvanish:xVanish; do
        f=$root/${p%%:*}/bukkit/build/r8/${p##*:}.jar
        [[ -f $f ]] || { echo "no $f: run ./gradlew :${p%%:*}:bukkit:assemble first" >&2; exit 2; }
        extra+=("$f")
      done
      # Older than 1.15.2 the server can't let xMoney stand in for Vault: Vault.jar beside it, as owners run it.
      if below "$version" 1.15.2; then
        extra+=("$(fetch https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar \
          a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d Vault-1.7.3.jar)")
      fi
      checks+=(--checks "$here/family-checks.txt") ;;
    *) break ;;
  esac
done

# Old Spigot waits 20 seconds over its age without the first; the second keeps debug reports
# on disk rather than uploading them from CI. bStats starts with xSample's seeded
# plugins/bStats/config.yml turning it off, so a boot sends nothing.
exec "$root/scripts/boot-test.sh" "${server[@]}" --jvm -DIReallyKnowWhatIAmDoingISwear=true --jvm -Dxena.debug.paste=false \
  --seed "$root/core/core-testing/sample/bukkit/boot-seed" \
  --checks "$here/boot-checks.txt" "${checks[@]}" "$@" "$jar" "${extra[@]}"
