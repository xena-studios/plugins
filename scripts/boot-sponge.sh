#!/usr/bin/env bash
# Boots a real SpongeVanilla server with the given plugin jars, runs console commands, stops it,
# and fails if the log has an error or a warning from our plugins, a command's expected output
# never appeared, or the server didn't start and stop cleanly. The Sponge side of
# scripts/boot-test.sh (Paper, Folia, Spigot) and scripts/boot-fabric.sh.
#
#   scripts/boot-sponge.sh --version 1.16.5-8.2.0 [--java <JDK home>] [--checks file]... \
#     [--run "console command"]... [--expect "log text"]... [--logged "log text"]... \
#     [--seed folder]... plugin.jar...
#
# --version is SpongeVanilla's, `<Minecraft>-<API>` as Sponge publishes it (1.16.5-8.2.0,
# 26.3-21.0.0-RC2827): the jar comes from Sponge's repository, checked against the SHA-1 it
# publishes beside it, and is cached in .cache/sponge/ with the Minecraft jar and the libraries
# it downloads on its first start. The JDK is the one that Minecraft needs (8 for 1.16.5, 21 for
# 1.21.x, 25 for 26.x): --java, or `java` on the PATH.
#
# Steps work as in boot-fabric.sh: each --expect waits for text logged after the --run before
# it, --logged for text logged at any point; a checks file holds "run: ", "expect: " and
# "logged: " lines. --seed copies a folder's contents into the server's folder before it starts,
# such as config/<plugin>/config.yml.
set -euo pipefail

version=""
java_home=""
steps=()
seeds=()
jars=()
while (($#)); do
  case "$1" in
    --version) version=$2; shift 2 ;;
    --java) java_home=$2; shift 2 ;;
    --run) steps+=("run: $2"); shift 2 ;;
    --expect) steps+=("expect: $2"); shift 2 ;;
    --logged) steps+=("logged: $2"); shift 2 ;;
    --seed) seeds+=("$(cd "$2" && pwd)"); shift 2 ;;
    --checks)
      while IFS= read -r line || [[ -n $line ]]; do
        [[ -z ${line//[[:space:]]/} || $line == \#* ]] || steps+=("$line")
      done <"$2"
      shift 2 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) jars+=("$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"); shift ;;
  esac
done
[[ -n $version ]] || { echo "--version is needed: SpongeVanilla's, such as 1.16.5-8.2.0" >&2; exit 2; }
((${#jars[@]})) || { echo "no plugin jars given" >&2; exit 2; }

root=$(cd "$(dirname "$0")/.." && pwd)
cache="$root/.cache/sponge"
java=${java_home:+$java_home/bin/}java
base="$root/build/boot/sponge-$version"
minecraft=${version%%-*}
mkdir -p "$cache"

# ---- the server ----
server="$cache/spongevanilla-$version-universal.jar"
url="https://repo.spongepowered.org/repository/maven-releases/org/spongepowered/spongevanilla/$version/spongevanilla-$version-universal.jar"
if [[ ! -f $server ]]; then
  echo "Downloading SpongeVanilla $version"
  curl -fsSL --retry 3 -o "$server.part" "$url"
  mv "$server.part" "$server"
fi
sha1=$(curl -fsSL --retry 3 "$url.sha1")
echo "$sha1  $server" | shasum -a 1 -c - >/dev/null || { rm -f "$server"; echo "${server##*/} doesn't match its checksum" >&2; exit 1; }

# Our plugins' ids, to spot their warnings: Sponge's console shows each line's logger, the plugin's id.
ids=()
for jar in "${jars[@]}"; do
  descriptor=$(unzip -p "$jar" META-INF/sponge_plugins.json 2>/dev/null || true)
  [[ -n $descriptor ]] || { echo "$(basename "$jar") isn't a Sponge plugin: it has no META-INF/sponge_plugins.json" >&2; exit 2; }
  ids+=("$(sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' <<<"$descriptor" | grep -v '^spongeapi$' | head -1)")
done
ours=$(IFS='|'; echo "${ids[*]}")

fail() {
  echo "FAIL: $*" >&2
  echo "---- last lines of $log ----" >&2
  tail -60 "$log" >&2 || true
  kill "$pid" 2>/dev/null || true
  exit 1
}

# Waits up to $2 seconds for the fixed string $1 to appear in the log after line $3 (see
# boot-fabric.sh for why the grep reads to the end).
wait_for() {
  for ((i = 0; i < $2 * 2; i++)); do
    tail -n "+$((${3:-0} + 1))" "$log" 2>/dev/null | grep -F -- "$1" >/dev/null && return 0
    kill -0 "$pid" 2>/dev/null || fail "server exited while waiting for \"$1\""
    sleep 0.5
  done
  fail "\"$1\" didn't appear within $2s"
}

log="$base/console.log"
rm -rf "$base"
mkdir -p "$base/mods"
# What SpongeVanilla downloads on its first start (Minecraft and its libraries), shared between runs.
mkdir -p "$cache/libraries-$version"
ln -s "$cache/libraries-$version" "$base/libraries"
cp "${jars[@]}" "$base/mods/"
for seed in "${seeds[@]+"${seeds[@]}"}"; do cp -R "$seed/." "$base/"; done
echo "eula=true" >"$base/eula.txt"
# A port under Linux's ephemeral range, as in boot-test.sh.
port=$((20000 + RANDOM % 12000))
# A one-layer flat world. On 1.16 a flat world's settings in server.properties log "Not a
# registry ops" from vanilla's own parser, whatever they say, so 1.16 gets the default world.
if [[ $minecraft == 1.16* ]]; then
  world='level-type=default'
else
  world='level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:stone","height"\:1}],"biome"\:"minecraft\:plains"}'
fi
cat >"$base/server.properties" <<PROPS
online-mode=false
server-port=$port
$world
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
PROPS

mkfifo "$base/stdin"
# Debug reports stay in the server's folder: a boot test never uploads one. No colours in the log.
(cd "$base" && exec "$java" -Xms512M -Xmx1G -Dxena.debug.paste=false -Dterminal.ansi=false -Djline.terminal=dumb -jar "$server" nogui <stdin >"$log" 2>&1) &
pid=$!
exec 3>"$base/stdin"

# The first start downloads Minecraft and remaps it.
wait_for "Done (" 600
echo "Started SpongeVanilla $version with ${ids[*]}"
since=0
for step in "${steps[@]+"${steps[@]}"}"; do
  case "$step" in
    "run: "*)
      since=$(wc -l <"$log")
      echo "${step#run: }" >&3 ;;
    "expect: "*) wait_for "${step#expect: }" 60 "$since" ;;
    "logged: "*) wait_for "${step#logged: }" 60 0 ;;
    *) fail "a step must start with \"run: \", \"expect: \" or \"logged: \": $step" ;;
  esac
done

echo "stop" >&3
for ((i = 0; i < 120; i++)); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.5
done
kill -0 "$pid" 2>/dev/null && fail "server didn't stop within 60s"
wait "$pid" || fail "server exited with status $?"
exec 3>&-
grep -qF "Stopping" "$log" || fail "the server never said it was stopping"

# ---- judge the log ----
# "[12:00:00] [Server thread/ERROR] [xsample]: ...", Sponge's layout; colours taken out first.
clean=$(sed $'s/\x1b\\[[0-9;]*m//g' "$log")
level='^\[[0-9:]+\] \[[^]]*/'
errors=$(grep -E "${level}ERROR\]" <<<"$clean" || true)
[[ -z $errors ]] || fail "the log has errors:"$'\n'"$errors"
warnings=$(grep -E "${level}WARN\] \[($ours)\]" <<<"$clean" || true)
[[ -z $warnings ]] || fail "our plugins logged warnings:"$'\n'"$warnings"
grep -E "${level}INFO\] \[($ours)\]" <<<"$clean" || true
echo "PASS: SpongeVanilla $version"
