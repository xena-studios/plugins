#!/usr/bin/env bash
# Boots a real server with the given plugin jars, runs console commands, stops
# it, and fails if a plugin logged an error or warning, a command's expected
# output never appeared, or the server didn't start and stop cleanly.
#
#   scripts/boot-test.sh [--project paper|folia] [--version 1.21.11] \
#     [--checks file]... [--run "console command"]... [--expect "log text"]... \
#     [--logged "log text"]... plugin.jar...
#   scripts/boot-test.sh --server spigot-1.8.8.jar [--java <JDK home>] [--jvm -Dflag]... ...
#
# Steps run in order. Each --expect waits for text logged after the --run (or client:)
# before it. A checks file holds the same steps, one per line: "run: <command>",
# "expect: <text>", or "logged: <text>" for text logged at any point, such as
# during startup; blank lines and lines starting with # are skipped. A step
# that starts with "paper " or "folia " runs only on that server.
#
# "client: [args]" (or --client) joins a real client: scripts/budgets/bots/client.js, a
# mineflayer bot, with the server's port and these arguments, and the step fails unless it ends
# with CLIENT PASS. Node.js runs it; its packages are installed into .cache/budget-bots/ on first
# use, as the budget check's bots are. The bot is an operator only if the server's ops.json says
# so (--seed scripts/boot-seeds/client).
#
# "allow: <text>" (or --allow) is for the rare run that must log an error or
# warning, such as Paper refusing a second plugin that provides the same name:
# error and warning lines containing the text don't fail the run, and the text
# must appear, so the run also checks the error happened. Only that run gets it.
#
# On Paper, a second boot waits for the empty server to pause, as Paper pauses
# a server nobody is on, then runs every Xena plugin's "/<plugin> reload" and
# expects it to finish (#67, #69). Folia never pauses.
#
# Server jars, Mojang's jar and libraries are cached in .cache/servers/.
#
# --server boots a server jar you already have instead of downloading Paper's, such as
# Spigot or CraftBukkit from BuildTools, which can't be downloaded (the core's loader is
# boot-tested on Spigot 1.8.8 and 1.16.5 this way); its steps run unless they're marked
# "paper " or "folia ", and there's no paused boot. --java picks the JDK (a JDK home: Spigot
# 1.8.8 on Java 8, 1.16.5 on 16), and --jvm passes a flag to it.
#
# --seed copies a folder's contents into the server's folder before each boot, for files a
# server wants before its first start: Leaf's loader writes config/leaf-loader.yml and stops
# on a first start, so its boot is seeded with scripts/boot-seeds/leaf.
#
# --property key=value adds a line to the server.properties each boot writes, such as
# use-native-transport=false for Spigot 1.8.8 on Java 16 and later on Linux, whose Netty can't
# read a buffer's address there, so a client's connection is reset.
set -euo pipefail

project=paper
version=1.21.11
server_jar=""
java_home=""
jvm=()
seeds=()
properties=()
steps=()
jars=()
while (($#)); do
  case "$1" in
    --project) project=$2; shift 2 ;;
    --version) version=$2; shift 2 ;;
    --server) server_jar=$(cd "$(dirname "$2")" && pwd)/$(basename "$2"); project=local; shift 2 ;;
    --java) java_home=$2; shift 2 ;;
    --jvm) jvm+=("$2"); shift 2 ;;
    --seed) seeds+=("$(cd "$2" && pwd)"); shift 2 ;;
    --property) properties+=("$2"); shift 2 ;;
    --run) steps+=("run: $2"); shift 2 ;;
    --expect) steps+=("expect: $2"); shift 2 ;;
    --logged) steps+=("logged: $2"); shift 2 ;;
    --allow) steps+=("allow: $2"); shift 2 ;;
    --client) steps+=("client: $2"); shift 2 ;;
    --checks)
      while IFS= read -r line || [[ -n $line ]]; do
        [[ -z ${line//[[:space:]]/} || $line == \#* ]] || steps+=("$line")
      done <"$2"
      shift 2 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) jars+=("$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"); shift ;;
  esac
done
((${#jars[@]})) || { echo "no plugin jars given" >&2; exit 2; }

root=$(cd "$(dirname "$0")/.." && pwd)
cache="$root/.cache/servers"
java=${java_home:+$java_home/bin/}java
# Paper's flag; servers before 1.9 only take vanilla's argument.
nogui=--nogui
[[ -z $server_jar ]] || nogui=nogui
mkdir -p "$cache"

if [[ -n $server_jar ]]; then
  [[ -f $server_jar ]] || { echo "no server jar at $server_jar" >&2; exit 2; }
  version=$(basename "$server_jar" .jar)
fi
base="$root/build/boot/$project-$version"

# ---- the server jar, verified against Paper's checksum ----
if [[ -z $server_jar ]]; then
  build=$(curl -fsS "https://fill.papermc.io/v3/projects/$project/versions/$version/builds/latest")
  url=$(grep -o '"url":"[^"]*"' <<<"$build" | head -1 | cut -d'"' -f4)
  sha=$(grep -o '"sha256":"[^"]*"' <<<"$build" | head -1 | cut -d'"' -f4)
  server_jar="$cache/${url##*/}"
  if [[ ! -f $server_jar ]]; then
    echo "Downloading ${url##*/}"
    curl -fsSL -o "$server_jar.part" "$url"
    mv "$server_jar.part" "$server_jar"
  fi
  echo "$sha  $server_jar" | shasum -a 256 -c - >/dev/null
fi

# ---- the plugins ----
# Our plugins' names, to spot their warnings in the log. Folia gets only the
# plugins that declare they run on it. The ones built on the core (their jar holds
# its relocated XenaPlugin) have "/<lowercase name> reload".
names=()
installed=()
reloadable=()
for jar in "${jars[@]}"; do
  # unzip complains about whichever descriptor the jar doesn't have.
  descriptor=$({ unzip -p "$jar" paper-plugin.yml plugin.yml 2>/dev/null || true; })
  [[ -n $descriptor ]] || { echo "$(basename "$jar") isn't a plugin: it has no paper-plugin.yml or plugin.yml" >&2; exit 2; }
  if [[ $project == folia ]] && ! grep -q '^folia-supported: true' <<<"$descriptor"; then
    echo "Skipping $(basename "$jar"): not for Folia"
    continue
  fi
  name=$(sed -n 's/^name: *//p' <<<"$descriptor" | head -1)
  names+=("$name")
  installed+=("$jar")
  # Not grep -q, which would stop unzip early and fail the pipe.
  if unzip -Z1 "$jar" | grep '/core/XenaPlugin\.class$' >/dev/null; then
    reloadable+=("$name")
  fi
done
((${#names[@]})) || { echo "no plugins to test on $project"; exit 0; }
ours=$(IFS='|'; echo "${names[*]}")

fail() {
  echo "FAIL: $*" >&2
  echo "---- last lines of $log ----" >&2
  tail -60 "$log" >&2 || true
  kill "$pid" 2>/dev/null || true
  exit 1
}

# The bots' packages, installed once, as scripts/budgets/check.sh installs them.
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
  local out="$dir/client.log"
  bots_ready
  # Word splitting is the point: $1 is the step's arguments.
  # shellcheck disable=SC2086
  if ! node "$bots/client.js" "$port" $1 >"$out" 2>&1; then
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

# Boots a fresh server in $1 with pause-when-empty-seconds $2 (vanilla's 60 when
# empty), runs the steps in "run", stops it and judges the log.
boot() {
  dir=$1
  log="$dir/console.log"
  rm -rf "$dir"
  mkdir -p "$dir/plugins"
  # Sharing the slow-to-fetch files between runs.
  for shared in cache libraries versions; do
    mkdir -p "$cache/$project-$version/$shared"
    ln -s "$cache/$project-$version/$shared" "$dir/$shared"
  done
  cp "${installed[@]}" "$dir/plugins/"
  for seed in "${seeds[@]+"${seeds[@]}"}"; do cp -R "$seed/." "$dir/"; done
  echo "eula=true" >"$dir/eula.txt"
  # A port under Linux's ephemeral range (32768 and up), which outgoing connections such
  # as downloads draw from, so the server never finds its port already taken.
  port=$((20000 + RANDOM % 12000))
  cat >"$dir/server.properties" <<EOF
online-mode=false
server-port=$port
level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:stone","height"\:1}],"biome"\:"minecraft\:plains"}
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
${2:+pause-when-empty-seconds=$2}
EOF
  for property in "${properties[@]+"${properties[@]}"}"; do echo "$property" >>"$dir/server.properties"; done

  mkfifo "$dir/stdin"
  (cd "$dir" && exec "$java" -Xms512M -Xmx1G ${jvm[@]+"${jvm[@]}"} -jar "$server_jar" "$nogui" <stdin >"$log" 2>&1) &
  pid=$!
  exec 3>"$dir/stdin"

  wait_for "Done (" 300
  echo "Started $project $version with ${names[*]}"
  local since=0 step
  allowed=()
  for step in "${run[@]+"${run[@]}"}"; do
    case "$step" in
      paper\ * | folia\ *)
        [[ ${step%% *} == "$project" ]] || continue
        step=${step#* } ;;
    esac
    case "$step" in
      "run: "*)
        since=$(wc -l <"$log")
        echo "${step#run: }" >&3 ;;
      "expect: "*) wait_for "${step#expect: }" 60 "$since" ;;
      "logged: "*) wait_for "${step#logged: }" 60 0 ;;
      "allow: "*) allowed+=("${step#allow: }") ;;
      "client:"*)
        since=$(wc -l <"$log")
        client "${step#client:}" ;;
      *) fail "a step must start with \"run: \", \"expect: \", \"logged: \", \"client:\" or \"allow: \": $step" ;;
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

  # ---- judge the log ----
  local text
  for text in "${allowed[@]+"${allowed[@]}"}"; do
    grep -qF -- "$text" "$log" || fail "an allowed error or warning never appeared: \"$text\""
  done
  # "[12:00:00 ERROR]: ", or "[12:00:00] [Server thread/ERROR]: " on Spigot 1.13 to 1.16.
  level='^\[[0-9:]+(\] \[[^]]*/| )'
  errors=$(unexpected "$(grep -E "${level}ERROR\]" "$log" || true)")
  [[ -z $errors ]] || fail "the log has errors:"$'\n'"$errors"
  warnings=$(unexpected "$(grep -E "${level}WARN\]: \[($ours)\]" "$log" || true)")
  [[ -z $warnings ]] || fail "our plugins logged warnings:"$'\n'"$warnings"
  grep -E "${level}INFO\]: \[($ours)\]" "$log" || true
}

# Drops lines an "allow:" step expects; each of those must have been logged.
unexpected() {
  local lines=$1 text
  for text in "${allowed[@]+"${allowed[@]}"}"; do
    lines=$(grep -vF -- "$text" <<<"$lines" || true)
  done
  printf '%s' "$lines"
}

run=("${steps[@]+"${steps[@]}"}")
boot "$base"

# ---- paused ----
# An empty Paper server pauses after pause-when-empty-seconds, and its global region
# scheduler stops while it's paused, so work queued there waits for a player to join.
# A reload typed into an empty server's console once did nothing until then (#67).
if [[ $project == paper ]] && ((${#reloadable[@]})); then
  run=()
  # The same errors are allowed, since the same plugins boot.
  for step in "${steps[@]+"${steps[@]}"}"; do
    [[ ${step#paper } == "allow: "* ]] && run+=("$step")
  done
  run+=("logged: Server empty for 1 seconds, pausing")
  for name in "${reloadable[@]}"; do
    run+=("run: $(tr '[:upper:]' '[:lower:]' <<<"$name") reload" "expect: Reloaded $name in")
  done
  boot "$base-paused" 1
  echo "Reloads work on a paused server: ${reloadable[*]}"
fi
echo "PASS: $project $version"
