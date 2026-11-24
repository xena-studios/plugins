#!/usr/bin/env bash
# Boots a real Fabric (or Quilt) dedicated server with the given mod jars, runs console
# commands, stops it, and fails if the log has an error or a warning from our mods, a
# command's expected output never appeared, or the server didn't start and stop cleanly.
# The Fabric side of scripts/boot-test.sh, which boots Paper, Folia and Spigot.
#
#   scripts/boot-fabric.sh --version 1.21.11 [--loader fabric|quilt] [--java <JDK home>] \
#     [--checks file]... [--run "console command"]... [--expect "log text"]... \
#     [--logged "log text"]... mod.jar...
#
# Steps work as in boot-test.sh: each --expect waits for text logged after the --run before
# it, --logged for text logged at any point; a checks file holds "run: ", "expect: " and
# "logged: " lines.
#
# The server gets Fabric API and fabric-language-kotlin at the versions platform-fabric builds
# against for that Minecraft line (core/platform-fabric/stonecutter.properties.toml), checked
# against Fabric's Maven checksums. Quilt runs the same Fabric mods through its own loader and
# installer, the newest it has for the version. The JDK is the one Minecraft needs (17 for
# 1.20.x, 21 for 1.21.x, 25 for 26.x): --java, or `java` on the PATH.
#
# Launchers, mods, Mojang's jar and libraries are cached in .cache/fabric/.
set -euo pipefail

loader=fabric
version=""
java_home=""
steps=()
jars=()
while (($#)); do
  case "$1" in
    --loader) loader=$2; shift 2 ;;
    --version) version=$2; shift 2 ;;
    --java) java_home=$2; shift 2 ;;
    --run) steps+=("run: $2"); shift 2 ;;
    --expect) steps+=("expect: $2"); shift 2 ;;
    --logged) steps+=("logged: $2"); shift 2 ;;
    --checks)
      while IFS= read -r line || [[ -n $line ]]; do
        [[ -z ${line//[[:space:]]/} || $line == \#* ]] || steps+=("$line")
      done <"$2"
      shift 2 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) jars+=("$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"); shift ;;
  esac
done
[[ -n $version ]] || { echo "--version is needed: the Minecraft version, such as 1.21.11" >&2; exit 2; }
[[ $loader == fabric || $loader == quilt ]] || { echo "--loader is fabric or quilt, not $loader" >&2; exit 2; }
((${#jars[@]})) || { echo "no mod jars given" >&2; exit 2; }

root=$(cd "$(dirname "$0")/.." && pwd)
cache="$root/.cache/fabric"
java=${java_home:+$java_home/bin/}java
base="$root/build/boot/$loader-$version"
mkdir -p "$cache"

# ---- what platform-fabric builds against for this version ----
# A key's value for this version: its own block's, or the shared one above every block.
properties="$root/core/platform-fabric/stonecutter.properties.toml"
property() {
  local value
  value=$(awk -v section="[\"$version\"]" -v key="$1" '
    /^\[/ { block = $0; next }
    $1 == key && (block == "" || block == section) { value = $3; gsub(/"/, "", value) }
    END { print value }' "$properties")
  [[ -n $value ]] || { echo "$1 isn't set for $version in $properties" >&2; exit 2; }
  printf '%s' "$value"
}
grep -qF "[\"$version\"]" "$properties" || { echo "platform-fabric doesn't build for $version (no [\"$version\"] in $properties)" >&2; exit 2; }
fabric_api=$(property deps.fabric_api)
flk=$(property deps.flk)
fabric_loader=$(property deps.fabric_loader)

# Downloads $1 to $2 once, checked against the SHA-1 Fabric's Maven publishes beside it.
fetch_checked() {
  local url=$1 file=$2
  if [[ ! -f $file ]]; then
    echo "Downloading ${file##*/}"
    curl -fsSL -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  local sha1
  sha1=$(curl -fsSL "$url.sha1")
  echo "$sha1  $file" | shasum -a 1 -c - >/dev/null || { rm -f "$file"; echo "${file##*/} doesn't match its checksum" >&2; exit 1; }
}

maven=https://maven.fabricmc.net/net/fabricmc
mods=("$cache/fabric-api-$fabric_api.jar" "$cache/fabric-language-kotlin-$flk.jar")
fetch_checked "$maven/fabric-api/fabric-api/${fabric_api//+/%2B}/fabric-api-${fabric_api//+/%2B}.jar" "${mods[0]}"
fetch_checked "$maven/fabric-language-kotlin/${flk//+/%2B}/fabric-language-kotlin-${flk//+/%2B}.jar" "${mods[1]}"

# Our mods' names, to spot their warnings: platform-fabric starts each line with the mod's.
names=()
for jar in "${jars[@]}"; do
  descriptor=$(unzip -p "$jar" fabric.mod.json 2>/dev/null || true)
  [[ -n $descriptor ]] || { echo "$(basename "$jar") isn't a Fabric mod: it has no fabric.mod.json" >&2; exit 2; }
  names+=("$(sed -n 's/.*"name": *"\([^"]*\)".*/\1/p' <<<"$descriptor" | head -1)")
done
ours=$(IFS='|'; echo "${names[*]}")

# ---- the server: a launcher that downloads Minecraft and the loader's libraries on first run ----
server_dir="$cache/$loader-$version"
mkdir -p "$server_dir"
if [[ $loader == fabric ]]; then
  installer=1.1.2
  launcher="$server_dir/fabric-server-$fabric_loader-$installer.jar"
  [[ -f $launcher ]] || curl -fsSL -o "$launcher" "https://meta.fabricmc.net/v2/versions/loader/$version/$fabric_loader/$installer/server/jar"
else
  launcher="$server_dir/quilt-server-launch.jar"
  if [[ ! -f $launcher ]]; then
    installer=$(curl -fsSL https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/maven-metadata.xml | sed -n 's:.*<release>\(.*\)</release>.*:\1:p')
    curl -fsSL -o "$cache/quilt-installer.jar" "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/$installer/quilt-installer-$installer.jar"
    "$java" -jar "$cache/quilt-installer.jar" install server "$version" --download-server --install-dir="$server_dir" >/dev/null
  fi
fi

fail() {
  echo "FAIL: $*" >&2
  echo "---- last lines of $log ----" >&2
  tail -60 "$log" >&2 || true
  kill "$pid" 2>/dev/null || true
  exit 1
}

# Waits up to $2 seconds for the fixed string $1 to appear in the log after line $3.
wait_for() {
  for ((i = 0; i < $2 * 2; i++)); do
    tail -n "+$((${3:-0} + 1))" "$log" 2>/dev/null | grep -qF -- "$1" && return 0
    kill -0 "$pid" 2>/dev/null || fail "server exited while waiting for \"$1\""
    sleep 0.5
  done
  fail "\"$1\" didn't appear within $2s"
}

log="$base/console.log"
rm -rf "$base"
mkdir -p "$base/mods"
# The launcher's downloads, shared between runs.
for shared in libraries versions .fabric .quilt; do
  mkdir -p "$server_dir/$shared"
  ln -s "$server_dir/$shared" "$base/$shared"
done
[[ ! -f $server_dir/server.jar ]] || ln -s "$server_dir/server.jar" "$base/server.jar"
echo "serverJar=$server_dir/server.jar" >"$base/fabric-server-launcher.properties"
cp "${mods[@]}" "${jars[@]}" "$base/mods/"
echo "eula=true" >"$base/eula.txt"
# A port under Linux's ephemeral range, as in boot-test.sh.
port=$((20000 + RANDOM % 12000))
cat >"$base/server.properties" <<EOF
online-mode=false
server-port=$port
level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:stone","height"\:1}],"biome"\:"minecraft\:plains"}
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
EOF

mkfifo "$base/stdin"
# Debug reports stay in the server's folder: a boot test never uploads one.
(cd "$base" && exec "$java" -Xms512M -Xmx1G -Dxena.debug.paste=false -jar "$launcher" nogui <stdin >"$log" 2>&1) &
pid=$!
exec 3>"$base/stdin"

wait_for "Done (" 300
echo "Started $loader $version with ${names[*]}"
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
grep -qF "Stopping server" "$log" || fail "the server never said it was stopping"

# ---- judge the log ----
# "[12:00:00] [Server thread/ERROR]: ...", vanilla's layout, which Fabric and Quilt keep.
level='^\[[0-9:]+\] \[[^]]*/'
errors=$(grep -E "${level}ERROR\]" "$log" || true)
[[ -z $errors ]] || fail "the log has errors:"$'\n'"$errors"
warnings=$(grep -E "${level}WARN\]: \[($ours)\]" "$log" || true)
[[ -z $warnings ]] || fail "our mods logged warnings:"$'\n'"$warnings"
grep -E "${level}INFO\]: \[($ours)\]" "$log" || true
echo "PASS: $loader $version"
