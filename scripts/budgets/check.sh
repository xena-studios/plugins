#!/usr/bin/env bash
# Measures Bukkit-family plugins against the rewrite's runtime budgets (gradle/perf-budgets.txt,
# from ROADMAP.md's "Fast, robust, light") on a real Paper server, the way M0 measured today's
# plugins (sandbox/m0/budgets), and fails when one is over:
#
#   startup  each plugin's time on the server thread (libraries:, onLoad, onEnable), from the
#            log's millisecond timestamps (log4j2.xml), median of the timed boots
#   idle     server-thread ms a tick and other threads' CPU ms a second in stacks through the
#            plugin's package, from async-profiler's CPU samples, with no players
#   load     the same with N mineflayer bots (bots/load.js) walking, building, chatting and
#            running the plugins' commands
#   heap     heap used after two full GCs over the same server without the plugins, metaspace
#            in the plugins' class loaders, and our instances' growth across the load
#
#   scripts/budgets/check.sh [--version 1.21.11] [--boots 3] [--bots 10] [--idle 60]
#     [--load 60] [--commands "xsample,coins,pay {other} 1"] [--java <JDK home>]
#     [--out <folder>] [--report-only <budget>,...] [--record-startup] plugin.jar...
#
# Run it after building the jars (./gradlew :core:core-testing:sample:bukkit:assemble for
# xSample's shrunk jar in build/r8/). --bots 0 skips the load. The report is <out>/report.md
# (and the job summary on GitHub Actions), the numbers <out>/results.json; <out> is
# build/budgets/ unless given. --report-only names budgets from perf-budgets.txt
# (startup-first-ms,startup-ms) that are reported when over, as a warning, but don't fail.
# Paper, async-profiler (pinned by SHA-256 below) and the bots'
# packages (npm ci) are fetched on first use and cached in .cache/. --record-startup also boots
# once more after the first boot, logging its class loading, and writes each plugin's startup class list to
# <out>/startup-classes-<plugin>.txt (startup_classes.py), to copy to the plugin's project as
# startup-classes.txt.
#
# Exit codes: 0 every budget met; 1 over a budget (the report says which); 2 a usage error;
# 3 the measurement itself failed (a download, the server not starting, the bots not joining),
# which says nothing about the plugins.
#
# On Linux, async-profiler needs perf events or falls back to a coarser timer: run
# `sudo sysctl kernel.perf_event_paranoid=1` first (CI too).
set -euo pipefail

version=1.21.11
boots=3
bots=10
idle=60
load=60
settle=30
commands=""
java_home=""
out=""
report_only=""
record_startup=false
jars=()
while (($#)); do
  case "$1" in
    --version) version=$2; shift 2 ;;
    --boots) boots=$2; shift 2 ;;
    --bots) bots=$2; shift 2 ;;
    --idle) idle=$2; shift 2 ;;
    --load) load=$2; shift 2 ;;
    --settle) settle=$2; shift 2 ;;
    --commands) commands=$2; shift 2 ;;
    --java) java_home=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --report-only) report_only=$2; shift 2 ;;
    --record-startup) record_startup=true; shift ;;
    -h | --help) sed -n '2,32p' "$0"; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) [[ -f $1 ]] || { echo "no plugin jar at $1" >&2; exit 2; }
       jars+=("$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"); shift ;;
  esac
done
((${#jars[@]})) || { sed -n '2,32p' "$0" >&2; exit 2; }
((boots >= 2)) || { echo "--boots is at least 2: the first boot only creates the world" >&2; exit 2; }

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out=${out:-$root/build/budgets}
mkdir -p "$out"
out=$(cd "$out" && pwd)
cache="$root/.cache"
java=${java_home:+$java_home/bin/}java
jcmd=${java_home:+$java_home/bin/}jcmd
command -v "$java" >/dev/null || { echo "no java: put a JDK 21 on the PATH or pass --java" >&2; exit 2; }
command -v "$jcmd" >/dev/null || { echo "no jcmd beside $java: --java needs a JDK, not a JRE" >&2; exit 2; }

# Fails the measurement, not the plugins: exit 3.
broken() {
  echo "MEASUREMENT FAILED: $*" >&2
  if [[ -n ${log:-} && -f ${log:-} ]]; then
    echo "---- last lines of $log ----" >&2
    tail -40 "$log" >&2 || true
  fi
  exit 3
}

# ---- Paper, verified against its checksum ----
mkdir -p "$cache/servers"
build=$(curl -fsS "https://fill.papermc.io/v3/projects/paper/versions/$version/builds/latest") ||
  broken "couldn't ask PaperMC for Paper $version's latest build"
url=$(grep -o '"url":"[^"]*"' <<<"$build" | head -1 | cut -d'"' -f4)
sha=$(grep -o '"sha256":"[^"]*"' <<<"$build" | head -1 | cut -d'"' -f4)
paper="$cache/servers/${url##*/}"
if [[ ! -f $paper ]]; then
  echo "Downloading ${url##*/}"
  curl -fsSL -o "$paper.part" "$url" || broken "couldn't download $url"
  mv "$paper.part" "$paper"
fi
echo "$sha  $paper" | shasum -a 256 -c - >/dev/null || broken "$paper doesn't match PaperMC's SHA-256"

# ---- async-profiler, pinned ----
ap_version=4.6
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) ap_file=async-profiler-$ap_version-linux-x64.tar.gz
    ap_sha=1e79ed54d8952889a2e74f993d22ee925e3322460d4f37a2ed95bfb88585da36 ;;
  Linux-aarch64 | Linux-arm64) ap_file=async-profiler-$ap_version-linux-arm64.tar.gz
    ap_sha=e9e26d2aae0d2935a0a676342c2e4612076ef15f04788c82f965d408a6659fbb ;;
  Darwin-*) ap_file=async-profiler-$ap_version-macos.zip
    ap_sha=646540c3c852d7fa7e43bbc4f090a193b7bfaf62d0954b42e98eae09edf36113 ;;
  *) echo "no async-profiler for $(uname -s) $(uname -m)" >&2; exit 2 ;;
esac
ap_dir="$cache/async-profiler/${ap_file%.tar.gz}"
ap_dir=${ap_dir%.zip}
asprof="$ap_dir/bin/asprof"
if [[ ! -x $asprof ]]; then
  mkdir -p "$cache/async-profiler"
  archive="$cache/async-profiler/$ap_file"
  echo "Downloading $ap_file"
  curl -fsSL -o "$archive" "https://github.com/async-profiler/async-profiler/releases/download/v$ap_version/$ap_file" ||
    broken "couldn't download $ap_file"
  echo "$ap_sha  $archive" | shasum -a 256 -c - >/dev/null || { rm -f "$archive"; broken "$ap_file doesn't match its pinned SHA-256"; }
  case $ap_file in
    *.zip) (cd "$cache/async-profiler" && unzip -qo "$ap_file") ;;
    *) tar -xzf "$archive" -C "$cache/async-profiler" ;;
  esac
  rm -f "$archive"
  [[ -x $asprof ]] || broken "$ap_file has no bin/asprof"
fi

# ---- the bots ----
if ((bots)); then
  command -v node >/dev/null || { echo "--bots needs Node.js (or --bots 0)" >&2; exit 2; }
  mkdir -p "$cache/budget-bots"
  cp "$here/bots/package.json" "$here/bots/package-lock.json" "$cache/budget-bots/"
  if ! cmp -s "$here/bots/package-lock.json" "$cache/budget-bots/node_modules/.package-lock.json.installed" 2>/dev/null; then
    (cd "$cache/budget-bots" && npm ci --no-audit --no-fund --loglevel=error) || broken "npm ci for the bots failed"
    cp "$here/bots/package-lock.json" "$cache/budget-bots/node_modules/.package-lock.json.installed"
  fi
  cp "$here/bots/load.js" "$cache/budget-bots/"
fi

# ---- the plugins ----
names=()
for jar in "${jars[@]}"; do
  name=$({ unzip -p "$jar" plugin.yml 2>/dev/null || true; } | sed -n 's/^name: *//p' | head -1 | tr -d "'\"")
  [[ -n $name ]] || { echo "$(basename "$jar") has no plugin.yml with a name" >&2; exit 2; }
  names+=("$name")
done

pid=""
bots_pid=""
trap '[[ -n $bots_pid ]] && kill "$bots_pid" 2>/dev/null; [[ -n $pid ]] && kill -9 "$pid" 2>/dev/null; true' EXIT

# A fresh server folder $1 sharing Paper's slow-to-fetch files, with the given plugins.
prepare() {
  local dir=$1; shift
  rm -rf "$dir"
  mkdir -p "$dir/plugins"
  for shared in cache libraries versions; do
    mkdir -p "$cache/servers/paper-$version/$shared"
    ln -s "$cache/servers/paper-$version/$shared" "$dir/$shared"
  done
  (($#)) && cp "$@" "$dir/plugins/"
  # bStats starts as on any server, but sends nothing from a measurement (a released plugin's
  # jar has its real service id).
  mkdir -p "$dir/plugins/bStats"
  cp "$root/core/core-testing/sample/bukkit/boot-seed/plugins/bStats/config.yml" "$dir/plugins/bStats/"
  echo "eula=true" >"$dir/eula.txt"
  port=$((20000 + RANDOM % 12000))
  # M0's world: flat, small view, nobody pausing it (an empty server pauses after a minute,
  # and a paused server doesn't tick, which would hide a plugin's idle cost).
  cat >"$dir/server.properties" <<EOF
online-mode=false
server-port=$port
level-type=minecraft\:flat
level-seed=budgets
generate-structures=false
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=50
difficulty=peaceful
pause-when-empty-seconds=-1
EOF
}

# Boots the server in $dir, logging to $1, and waits for it to be up. Further arguments go to the JVM.
boot() {
  log=$1
  shift
  rm -f "$dir/stdin"
  mkfifo "$dir/stdin"
  (cd "$dir" && exec "$java" -Xms1G -Xmx1G -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -Dxena.startupTimes=true "$@" \
    -Dlog4j.configurationFile="$here/log4j2.xml" -Dterminal.jline=false -Dterminal.ansi=false \
    -jar "$paper" --nogui <stdin >"$log" 2>&1) &
  pid=$!
  exec 3>"$dir/stdin"
  wait_for "Done (" 300
}

wait_for() { # text seconds
  local i
  for ((i = 0; i < $2 * 10; i++)); do
    grep -qF -- "$1" "$log" && return 0
    kill -0 "$pid" 2>/dev/null || broken "the server exited while waiting for \"$1\""
    sleep 0.1
  done
  broken "\"$1\" didn't appear within $2 s"
}

say() { echo "$1" >&3; }

stop() {
  say stop
  local i
  for ((i = 0; i < 240; i++)); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.25
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid"
    broken "the server didn't stop within 60 s"
  fi
  wait "$pid" 2>/dev/null || true
  pid=""
  exec 3>&-
}

# Two full GCs, then the heap ($1.heap), class loaders ($1.loaders) and our classes ($1.histogram).
# The heap is read three times, two GCs before each ($1.heap.1..3; $1.heap is the last): what a
# full GC leaves still moves by a few MB with the server's own work between reads (chunk and
# entity caches, its async threads), and the budget is the difference of two such readings, so
# analyze.py takes the lowest of each side's three.
snapshot() {
  for k in 1 2 3; do
    "$jcmd" "$pid" GC.run >/dev/null && sleep 2 && "$jcmd" "$pid" GC.run >/dev/null && sleep 1
    "$jcmd" "$pid" GC.heap_info >"$1.heap.$k"
  done
  cp "$1.heap.3" "$1.heap"
  "$jcmd" "$pid" VM.classloader_stats >"$1.loaders"
  "$jcmd" "$pid" GC.class_histogram >"$1.histogram"
}

# CPU samples every 200 µs of CPU time, per thread, as collapsed stacks, for $2 seconds.
profile() {
  "$asprof" -e cpu -i 200us -t -o collapsed -d "$2" -f "$out/$1.collapsed" "$pid" >"$out/$1.asprof.log" 2>&1 ||
    broken "async-profiler failed: $(tail -3 "$out/$1.asprof.log")"
}

uptime >"$out/uptime.txt"

# ---- the server alone, for the heap the plugins add ----
echo "== Paper $version without plugins"
dir="$out/base"
prepare "$dir"
boot "$out/base-boot1.log"; stop
boot "$out/base-measured.log"; sleep "$settle"; snapshot "$out/base"; stop

# ---- with the plugins ----
dir="$out/server"
prepare "$dir" "${jars[@]}"
echo "== Paper $version with ${names[*]}: first boot (world, configs, libraries)"
boot "$out/boot1.log"; stop
# The list is recorded on a restart, as every boot after the first is: the first writes the
# config and lang files and fetches the libraries, which a restart doesn't load classes for. The
# jars boot without their own lists, so a class an older list names but nothing loads any more
# isn't loaded ahead and recorded again.
if $record_startup; then
  echo "== recording boot: class loading"
  for jar in "${jars[@]}"; do
    python3 - "$jar" "$dir/plugins/$(basename "$jar")" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as src, zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        if item.filename != "META-INF/xena/startup-classes.txt":
            dst.writestr(item, src.read(item))
PY
  done
  boot "$out/record.log" "-Xlog:class+load=info:file=$out/classload.log:uptimemillis"; sleep 2; stop
  cp "${jars[@]}" "$dir/plugins/"
  for i in "${!jars[@]}"; do
    python3 "$here/startup_classes.py" --log "$out/classload.log" --boot "$out/record.log" --jar "${jars[$i]}" \
      --out "$out/startup-classes-${names[$i]}.txt"
  done
fi
for ((b = 2; b < boots + 1; b++)); do
  echo "== timed boot $b of $((boots + 1))"
  boot "$out/boot$b.log"; sleep 2; stop
done
echo "== measured boot: heap, idle profile$( ((bots)) && echo ", $bots bots")"
boot "$out/boot$((boots + 1)).log"
sleep "$settle"
snapshot "$out/before"
echo "== idle for $idle s"
profile idle "$idle"
if ((bots)); then
  echo "== $bots bots for $load s"
  node "$cache/budget-bots/load.js" "$port" "$version" "$bots" "$commands" >"$out/bots.log" 2>&1 &
  bots_pid=$!
  # Joined, then made operators in creative so they can build and run every command.
  for ((i = 0; i < 120; i++)); do
    grep -qF "Bot$((bots - 1)) joined the game" "$log" && break
    kill -0 "$bots_pid" 2>/dev/null || broken "the bots stopped: $(tail -5 "$out/bots.log")"
    sleep 0.5
  done
  grep -qF "Bot$((bots - 1)) joined the game" "$log" || broken "the bots didn't join within a minute: $(tail -5 "$out/bots.log")"
  for ((i = 0; i < bots; i++)); do say "op Bot$i"; done
  say "minecraft:gamemode creative @a"
  sleep 20
  say "minecraft:gamemode creative @a"
  profile load "$load"
  kill "$bots_pid" 2>/dev/null || true
  wait "$bots_pid" 2>/dev/null || true
  bots_pid=""
  sleep 10
  snapshot "$out/after"
fi
stop
uptime >>"$out/uptime.txt"

python3 "$here/analyze.py" --out "$out" --budgets "$root/gradle/perf-budgets.txt" --boots "$boots" \
  --idle "$idle" --load "$load" --bots "$bots" --report-only "$report_only" ${jars[@]+"${jars[@]}"}
