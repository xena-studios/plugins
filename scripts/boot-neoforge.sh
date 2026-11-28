#!/usr/bin/env bash
# Boots a real NeoForge dedicated server with the given mod jars, runs console commands, stops
# it, and fails if the log has an error or a warning from our mods, a command's expected output
# never appeared, or the server didn't start and stop cleanly. The NeoForge side of
# scripts/boot-fabric.sh, with the same steps.
#
#   scripts/boot-neoforge.sh --version 1.21.1 [--java <JDK home>] [--checks file]... \
#     [--run "console command"]... [--expect "log text"]... [--logged "log text"]... \
#     [--client "client.js arguments"]... [--seed folder]... mod.jar...
#
# Steps work as in boot-fabric.sh: each --expect waits for text logged after the --run (or
# client:) before it, --logged for text logged at any point; a checks file holds "run: ",
# "expect: ", "logged: " and "client: " lines. A client step joins real clients (mineflayer,
# scripts/budgets/bots/client.js) with --fabric, whose scenarios are the vanilla-server ones
# NeoForge shares; mineflayer joins up to 26.1, so run client steps on the 1.21 lines.
#
# --seed copies a folder's contents into the server's folder before it starts: ops.json, or a
# mod's config/<mod>/config.yml.
#
# The server is NeoForge's own installer's, at the version platform-neoforge builds against for
# that Minecraft line (core/platform-neoforge/stonecutter.properties.toml), checked against its
# Maven's SHA-1, and gets Kotlin for Forge at that line's version, from Modrinth. The JDK is the one Minecraft
# needs (21 for 1.21.x, 25 for 26.x): --java, or `java` on the PATH.
#
# Installers, servers, Mojang's jar and the libraries are cached in .cache/neoforge/.
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
    --client) steps+=("client: $2"); shift 2 ;;
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
[[ -n $version ]] || { echo "--version is needed: the Minecraft version, such as 1.21.11" >&2; exit 2; }
((${#jars[@]})) || { echo "no mod jars given" >&2; exit 2; }

root=$(cd "$(dirname "$0")/.." && pwd)
cache="$root/.cache/neoforge"
java=${java_home:+$java_home/bin/}java
base="$root/build/boot/neoforge-$version"
mkdir -p "$cache"

# ---- what platform-neoforge builds against for this version ----
# A key's value for this version: its own block's, or the shared one above every block.
properties="$root/core/platform-neoforge/stonecutter.properties.toml"
property() {
  local value
  value=$(awk -v section="[\"$version\"]" -v key="$1" '
    /^\[/ { block = $0; next }
    $1 == key && (block == "" || block == section) { value = $3; gsub(/"/, "", value) }
    END { print value }' "$properties")
  [[ -n $value ]] || { echo "$1 isn't set for $version in $properties" >&2; exit 2; }
  printf '%s' "$value"
}
grep -qF "[\"$version\"]" "$properties" || { echo "platform-neoforge doesn't build for $version (no [\"$version\"] in $properties)" >&2; exit 2; }
neoforge=$(property deps.neoforge)
kff=$(property deps.kff)

# Downloads $1 to $2 once, checked against the SHA-1 its Maven publishes beside it.
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

# Kotlin for Forge as owners install it: its mod, from Modrinth (its Maven has the parts apart),
# checked against the SHA-1 Modrinth lists for it.
mods=("$cache/kotlinforforge-$kff-all.jar")
if [[ ! -f ${mods[0]} ]]; then
  file=$(curl -fsSL "https://api.modrinth.com/v2/project/kotlin-for-forge/version" |
    jq -r --arg v "$kff" '[.[] | select(.version_number == $v and (.loaders | index("neoforge")))][0].files[0] | "\(.url) \(.hashes.sha1)"')
  [[ $file != "null null" && -n $file ]] || { echo "Kotlin for Forge $kff isn't on Modrinth for NeoForge" >&2; exit 1; }
  echo "Downloading kotlinforforge-$kff-all.jar"
  curl -fsSL -o "${mods[0]}.part" "${file% *}"
  echo "${file#* }  ${mods[0]}.part" | shasum -a 1 -c - >/dev/null || { rm -f "${mods[0]}.part"; echo "Kotlin for Forge $kff doesn't match Modrinth's checksum" >&2; exit 1; }
  mv "${mods[0]}.part" "${mods[0]}"
fi

# Our mods' names, to spot their warnings: platform-neoforge starts each line with the mod's.
names=()
for jar in "${jars[@]}"; do
  descriptor=$(unzip -p "$jar" META-INF/neoforge.mods.toml 2>/dev/null || true)
  [[ -n $descriptor ]] || { echo "$(basename "$jar") isn't a NeoForge mod: it has no META-INF/neoforge.mods.toml" >&2; exit 2; }
  names+=("$(sed -n 's/^displayName *= *"\([^"]*\)".*/\1/p' <<<"$descriptor" | head -1)")
done
ours=$(IFS='|'; echo "${names[*]}")

# ---- the server: NeoForge's installer, run once per version ----
server_dir="$cache/server-$neoforge"
if [[ ! -f $server_dir/libraries/net/neoforged/neoforge/$neoforge/unix_args.txt ]]; then
  mkdir -p "$server_dir"
  installer="$cache/neoforge-$neoforge-installer.jar"
  fetch_checked "https://maven.neoforged.net/releases/net/neoforged/neoforge/$neoforge/neoforge-$neoforge-installer.jar" "$installer"
  echo "Installing NeoForge $neoforge"
  (cd "$server_dir" && "$java" -jar "$installer" --installServer . >install.log 2>&1) || { tail -20 "$server_dir/install.log" >&2; echo "NeoForge's installer failed" >&2; exit 1; }
fi

fail() {
  echo "FAIL: $*" >&2
  echo "---- last lines of $log ----" >&2
  tail -60 "$log" >&2 || true
  kill "$pid" 2>/dev/null || true
  exit 1
}

# The bots' packages, installed once, as boot-test.sh and scripts/budgets/check.sh install them.
bots="$root/.cache/budget-bots"
bots_ready() {
  local here="$root/scripts/budgets/bots"
  command -v node >/dev/null || fail "a client: step needs Node.js"
  mkdir -p "$bots"
  cp "$here/package.json" "$here/package-lock.json" "$bots/"
  if ! cmp -s "$here/package-lock.json" "$bots/node_modules/.package-lock.json.installed" 2>/dev/null; then
    (cd "$bots" && npm ci --no-audit --no-fund --loglevel=error) || fail "npm ci for the client failed"
    cp "$here/package-lock.json" "$bots/node_modules/.package-lock.json.installed"
  fi
  cp "$here/client.js" "$bots/"
}

# Joins the client with the arguments $1, and fails unless it passes.
client() {
  local out="$base/client.log" args="$1 --fabric"
  bots_ready
  # Word splitting is the point: $args is the step's arguments.
  # shellcheck disable=SC2086
  if ! node "$bots/client.js" "$port" $args >"$out" 2>&1; then
    cat "$out" >&2
    fail "the client didn't pass"
  fi
  cat "$out"
}

# Waits up to $2 seconds for the fixed string $1 to appear in the log after line $3. The grep
# reads to the end, not -q: with pipefail, a grep that stops at an early match fails the pipe
# once tail, still writing, gets SIGPIPE.
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
# The installed server's libraries (Minecraft's jar among them), shared between runs.
ln -s "$server_dir/libraries" "$base/libraries"
cp "${mods[@]}" "${jars[@]}" "$base/mods/"
for seed in "${seeds[@]+"${seeds[@]}"}"; do cp -R "$seed/." "$base/"; done
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
(cd "$base" && exec "$java" -Xms512M -Xmx1G -Dxena.debug.paste=false "@libraries/net/neoforged/neoforge/$neoforge/unix_args.txt" nogui <stdin >"$log" 2>&1) &
pid=$!
exec 3>"$base/stdin"

wait_for "Done (" 300
echo "Started NeoForge $neoforge with ${names[*]}"
since=0
for step in "${steps[@]+"${steps[@]}"}"; do
  case "$step" in
    "run: "*)
      since=$(wc -l <"$log")
      echo "${step#run: }" >&3 ;;
    "expect: "*) wait_for "${step#expect: }" 60 "$since" ;;
    "logged: "*) wait_for "${step#logged: }" 60 0 ;;
    "client:"*)
      since=$(wc -l <"$log")
      client "${step#client:}" ;;
    *) fail "a step must start with \"run: \", \"expect: \", \"logged: \" or \"client:\": $step" ;;
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
# "[12:00:00] [Server thread/ERROR] [minecraft/DedicatedServer]: ...": NeoForge's layout, with
# the logger after the level; our lines are "[...] [xSample/]: [xSample] ...".
level='^\[[0-9:]+\] \[[^]]*/'
errors=$(grep -E "${level}(ERROR|FATAL)\]" "$log" || true)
[[ -z $errors ]] || fail "the log has errors:"$'\n'"$errors"
warnings=$(grep -E "${level}WARN\] \[[^]]*\]: \[($ours)\]" "$log" || true)
[[ -z $warnings ]] || fail "our mods logged warnings:"$'\n'"$warnings"
grep -E "${level}INFO\] \[[^]]*\]: \[($ours)\]" "$log" || true
echo "PASS: NeoForge $version"
