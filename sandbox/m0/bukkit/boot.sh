#!/usr/bin/env bash
# Boots one local test server with the given plugins, runs console commands, stops it.
#
#   SERVERS=/somewhere/outside/the/repo ./boot.sh <run-name> <server.jar> <java-version|jdk-home> \
#     [--jvm "-Dflag"]... [--run "command"]... [--bot <mc-version>] [--keep] plugin.jar...
#
# A fresh folder per run in $SERVERS/run/<run-name> (--keep reuses it, to see a second start
# with libraries already downloaded). Accepts the EULA for that local server. --bot starts a
# mineflayer client (bot/, after `npm install` there) before the commands, for benchmarks that need a player.
# Prints the lines that matter and leaves the whole log in the run folder.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
name=$1 server=$2 java=$3
shift 3
jvm=() runs=() plugins=() bot="" keep=0
while (($#)); do
  case $1 in
    --jvm) jvm+=("$2"); shift 2 ;;
    --run) runs+=("$2"); shift 2 ;;
    --bot) bot=$2; shift 2 ;;
    --keep) keep=1; shift ;;
    *) plugins+=("$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"); shift ;;
  esac
done

: "${SERVERS:?set SERVERS to a folder outside the repo}"
dir="$SERVERS/run/$name"
((keep)) || rm -rf "$dir"
mkdir -p "$dir/plugins"
rm -f "$dir"/plugins/*.jar
for p in ${plugins[@]+"${plugins[@]}"}; do cp "$p" "$dir/plugins/"; done
port=$((25600 + RANDOM % 300))
echo "eula=true" >"$dir/eula.txt"
cat >"$dir/server.properties" <<EOF
online-mode=false
server-port=$port
level-type=FLAT
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
motd=m0
difficulty=peaceful
EOF

# A JDK home folder, or a version for macOS's java_home (8 comes from $JAVA8_HOME).
if [[ -d $java ]]; then
  home=$java
elif [[ $java == 8 ]]; then
  home=${JAVA8_HOME:?set JAVA8_HOME to a JDK 8}
else
  home=$(/usr/libexec/java_home -v "$java")
fi
log="$dir/console.log"
fifo="$dir/stdin"
rm -f "$fifo" && mkfifo "$fifo"
cd "$dir"
start=$(date +%s)
"$home/bin/java" -Xms256M -Xmx1G ${jvm[@]+"${jvm[@]}"} -jar "$server" nogui <"$fifo" >"$log" 2>&1 &
pid=$!
exec 3>"$fifo"

wait_for() { # text timeout-seconds
  for ((i = 0; i < $2 * 4; i++)); do
    grep -qF -- "$1" "$log" && return 0
    kill -0 $pid 2>/dev/null || { echo "server exited early"; tail -40 "$log"; return 1; }
    sleep 0.25
  done
  echo "timed out waiting for: $1"; return 1
}

ok=1
wait_for "Done (" 300 || ok=0
end=$(date +%s)
if ((ok)); then
  botpid=""
  if [[ -n $bot ]]; then
    node "$here/bot/bot.js" 127.0.0.1 $port "$bot" >"$dir/bot.log" 2>&1 &
    botpid=$!
    wait_for "M0Bot joined" 60 || wait_for "M0Bot[" 5 || true
    sleep 3
  fi
  for r in ${runs[@]+"${runs[@]}"}; do
    echo "$r" >&3
    sleep "${M0_WAIT:-3}"
  done
  [[ -n $botpid ]] && { kill $botpid 2>/dev/null || true; sleep 1; }
fi
echo "stop" >&3
for ((i = 0; i < 120; i++)); do kill -0 $pid 2>/dev/null || break; sleep 0.5; done
kill -0 $pid 2>/dev/null && { echo "server didn't stop, killing"; kill -9 $pid; }
exec 3>&-

echo "== $name: $(basename "$server") on Java $("$home/bin/java" -version 2>&1 | head -1 | cut -d'"' -f2), wall to Done: $((end - start)) s"
grep -E "Done \(|M0 |Hello|HelloCloud|xbench|hellocloud|getPlugin|Kotlin|stdlib|text:|ERROR|SEVERE|WARN.*(Hello|M0|Unsafe|kotlin)|Exception|Error:|Unsafe|libraries|Loaded library" "$log" \
  | grep -vE "Unknown command|Type \"help\"" | cut -c1-400 || true
if [[ -f $dir/bot.log ]]; then echo "-- bot:"; tail -3 "$dir/bot.log"; fi
