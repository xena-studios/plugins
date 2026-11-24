#!/usr/bin/env bash
# One measured configuration: a Paper server with the given plugins, booted twice in a fresh folder.
# The first boot creates the world and configs (and Paper remaps the jars); boots 2..$STARTS only
# time the startup; the last boot is measured:
#
#   1. startup: every log carries milliseconds since JVM start (log4j2.xml), for each plugin's
#      load and enable lines and for "Done";
#   2. heap: 30 s after Done, two full GCs, then GC.heap_info, VM.classloader_stats and the
#      class histogram of our packages;
#   3. idle: async-profiler (ASPROF, its asprof binary) samples every 1 ms of CPU time, per thread,
#      for $IDLE s, then /mspt;
#   4. load (--bots N): N mineflayer bots (bots/load.js), 20 s to settle, the same profile for
#      $LOAD s, then /mspt.
#
#   SERVERS=/outside/the/repo PAPER=/path/paper.jar TPL=/path/to/cache-libraries-versions ASPROF=... \
#     ./measure.sh <name> [--bots N] plugin.jar...
#
# TPL holds a previous Paper run's cache/, libraries/ and versions/, so no download is timed.
# Everything lands in $SERVERS/<name>; analyze.py reads it.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
name=$1; shift
bots=0 plugins=()
while (($#)); do
  case $1 in
    --bots) bots=$2; shift 2 ;;
    *) plugins+=("$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"); shift ;;
  esac
done
: "${SERVERS:?}" "${PAPER:?}" "${TPL:?}" "${ASPROF:?}"
IDLE=${IDLE:-90} LOAD=${LOAD:-120} MC=${MC:-1.21.11}
home=$(/usr/libexec/java_home -v "${JAVA:-21}")
dir="$SERVERS/$name"
rm -rf "$dir"; mkdir -p "$dir/plugins" "$dir/out"
cp -Rc "$TPL"/. "$dir/" 2>/dev/null || cp -R "$TPL"/. "$dir/"
for p in ${plugins[@]+"${plugins[@]}"}; do cp "$p" "$dir/plugins/"; done
port=$((25600 + RANDOM % 300))
echo "eula=true" >"$dir/eula.txt"
cat >"$dir/server.properties" <<PROPS
online-mode=false
server-port=$port
level-type=FLAT
level-seed=m0
generate-structures=false
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=50
motd=m0-budgets
difficulty=peaceful
PROPS
cd "$dir"

pid=""
boot() { # log-file
  rm -f stdin && mkfifo stdin
  "$home/bin/java" -Xms1G -Xmx1G -Dlog4j.configurationFile="$here/log4j2.xml" \
    -Dterminal.jline=false -Dterminal.ansi=false -jar "$PAPER" nogui <stdin >"$1" 2>&1 &
  pid=$!
  exec 3>stdin
  log=$1
  wait_for "Done (" 300
}
wait_for() { # text timeout-seconds
  for ((i = 0; i < $2 * 10; i++)); do
    grep -qF -- "$1" "$log" && return 0
    kill -0 $pid 2>/dev/null || { echo "server exited early"; tail -30 "$log"; exit 1; }
    sleep 0.1
  done
  echo "timed out waiting for: $1"; return 1
}
say() { echo "$1" >&3; }
profile() { # name seconds: CPU samples every 1 ms of CPU time, per thread, as collapsed stacks
  "$ASPROF" -e cpu -i 1ms -t -o collapsed -d "$2" -f "$dir/out/$1.txt" $pid >/dev/null
}
stop() {
  say stop
  for ((i = 0; i < 240; i++)); do kill -0 $pid 2>/dev/null || break; sleep 0.25; done
  kill -0 $pid 2>/dev/null && { echo "server didn't stop, killing"; kill -9 $pid; }
  exec 3>&-
}
trap '[[ -n $pid ]] && kill -9 $pid 2>/dev/null; [[ -n ${bp:-} ]] && kill $bp 2>/dev/null; true' EXIT

# The machine's load, before and after: a busy machine skews every number here.
uptime >out/uptime.txt
echo "== $name: first boot"
boot out/boot1.log; stop
for ((b = 2; b <= ${STARTS:-3}; b++)); do
  echo "== $name: timed boot $b"
  boot out/boot$b.log; sleep 2; stop
done
echo "== $name: measured boot"
boot out/measured.log
sleep 30
jcmd $pid GC.run >/dev/null; sleep 2; jcmd $pid GC.run >/dev/null; sleep 1
jcmd $pid GC.heap_info >out/heap.txt
jcmd $pid VM.classloader_stats >out/loaders.txt
jcmd $pid GC.class_histogram >out/histogram.txt
profile idle "$IDLE"
say "mspt"; sleep 1; echo "-- idle end" >>out/marks.txt
if ((bots)); then
  node "$here/bots/load.js" $port "$MC" "$bots" >out/bots.log 2>&1 &
  bp=$!
  sleep $((bots / 2 + 6))
  say "op @a"; say "minecraft:gamemode creative @a"
  sleep 20
  say "minecraft:gamemode creative @a"
  profile load "$LOAD"
  say "mspt"; say "list"; sleep 1
  kill $bp; wait $bp 2>/dev/null || true; bp=""
fi
stop; pid=""
uptime >>out/uptime.txt
echo "== $name: done"
