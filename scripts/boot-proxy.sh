#!/usr/bin/env bash
# Boots a proxy, Velocity, BungeeCord or Waterfall, with the given plugin jars, alone or in front
# of a backend server, runs console commands on the proxy, can join a real client through it,
# stops both, and fails if a plugin logged an error or warning, a step's text never appeared, or
# a server didn't start and stop cleanly. For the Boot workflow's proxy cells and by hand.
#
#   scripts/boot-proxy.sh --proxy velocity|bungeecord|waterfall [--version 4.3.1] [--java <JDK home>] \
#     [--backend paper|<server.jar>] [--backend-version 1.21.11] [--backend-java <JDK home>] \
#     [--backend-plugin backend.jar]... [--grant Joiner=node,node]... [--jvm -Dflag]... \
#     [--checks file]... [--startup-budget <ms>] [--record-startup <folder>] plugin.jar...
#
# The proxy comes from scripts/server-jar.sh (its newest release unless --version says), or
# --proxy-jar names one on disk. Steps are scripts/boot-test.sh's, one per line in a checks file
# or as --run, --expect, --logged, --allow and --client, run on the proxy's console; the client
# (scripts/budgets/bots/client.js) joins the proxy's port. Two more, for the backend:
# "backend: <text>" waits for text the backend logged at any point, and "backend allow: <text>"
# lets the backend log that error or warning. A step that starts with "velocity ", "bungeecord "
# or "waterfall " runs only on that proxy.
#
# With --backend, the proxy forwards players to it as "lobby": Paper behind Velocity through
# Velocity's modern forwarding (one forwarding secret, written to both, which the Xena plugins'
# proxy channels sign with too, so nothing else is set), anything behind BungeeCord or Waterfall
# through BungeeCord's forwarding (spigot.yml's bungeecord: true), with one plugins/Xena/proxy.secret
# written to both, as an owner copies it. Both run offline, so the client needs no account.
#
# --grant gives a player permissions on the proxy, as a permission plugin would: on BungeeCord a
# group in its config.yml; on Velocity, which has none of its own, -Dxena.test.grants, which
# xSample's sample (never a real plugin) answers through Velocity's permission API.
#
# --startup-budget boots the proxy alone three more times once the run passed, and fails if the
# median of the plugins' own startup lines (-Dxena.startupTimes, the shim's "Startup times:") is
# over that many milliseconds, summed over the plugins. With --record-startup, the first of those
# boots also logs its class loading, and each plugin's startup class list (the classes it loaded
# before the proxy was done, scripts/budgets/startup_classes.py) goes to
# <folder>/startup-classes-<jar name>.txt, to copy beside the plugin's build file as
# startup-classes.txt. It needs --startup-budget.
#
# Servers, Mojang's jar and libraries are cached in .cache/servers/.
set -euo pipefail

proxy=""
version=""
proxy_jar=""
java_home=""
backend=""
backend_version=1.21.11
backend_java=""
startup_budget=""
record_startup=""
jvm=()
grants=()
steps=()
jars=()
backend_jars=()
while (($#)); do
  case "$1" in
    --proxy) proxy=$2; shift 2 ;;
    --version) version=$2; shift 2 ;;
    --proxy-jar) proxy_jar=$(cd "$(dirname "$2")" && pwd)/$(basename "$2"); shift 2 ;;
    --java) java_home=$2; shift 2 ;;
    --backend) backend=$2; shift 2 ;;
    --backend-version) backend_version=$2; shift 2 ;;
    --backend-java) backend_java=$2; shift 2 ;;
    --backend-plugin) backend_jars+=("$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"); shift 2 ;;
    --grant) grants+=("$2"); shift 2 ;;
    --jvm) jvm+=("$2"); shift 2 ;;
    --startup-budget) startup_budget=$2; shift 2 ;;
    --record-startup) mkdir -p "$2"; record_startup=$(cd "$2" && pwd); shift 2 ;;
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
case $proxy in
  velocity | bungeecord | waterfall) ;;
  *) echo "--proxy is velocity, bungeecord or waterfall" >&2; exit 2 ;;
esac
((${#jars[@]})) || { echo "no plugin jars given" >&2; exit 2; }
[[ -z $record_startup || -n $startup_budget ]] || { echo "--record-startup needs --startup-budget, whose boots it records" >&2; exit 2; }

root=$(cd "$(dirname "$0")/.." && pwd)
cache="$root/.cache/servers"
java=${java_home:+$java_home/bin/}java
mkdir -p "$cache"

# ---- the jars ----
if [[ -z $proxy_jar ]]; then
  proxy_jar=$("$root/scripts/server-jar.sh" "$proxy" ${version:+"$version"} | sed -n 's/^jar=//p')
fi
version=$(basename "$proxy_jar" .jar)
backend_jar=""
backend_kind=""
if [[ -n $backend ]]; then
  if [[ $backend == paper ]]; then
    backend_jar=$("$root/scripts/server-jar.sh" paper "$backend_version" | sed -n 's/^jar=//p')
    backend_kind=paper
  else
    backend_jar=$(cd "$(dirname "$backend")" && pwd)/$(basename "$backend")
    [[ -f $backend_jar ]] || { echo "no server jar at $backend" >&2; exit 2; }
    backend_kind=spigot
  fi
  [[ $proxy != velocity || $backend_kind == paper ]] || { echo "behind Velocity the backend is Paper, which takes its modern forwarding" >&2; exit 2; }
fi
base="$root/build/boot/$version${backend_jar:+-behind-$(basename "$backend_jar" .jar)}"

# Our plugins' names, to spot their lines: Velocity's by id ([xsample]), BungeeCord's by name.
names=()
for jar in "${jars[@]}" ${backend_jars[@]+"${backend_jars[@]}"}; do
  descriptor=$({ unzip -p "$jar" velocity-plugin.json bungee.yml plugin.yml paper-plugin.yml 2>/dev/null || true; })
  [[ -n $descriptor ]] || { echo "$(basename "$jar") isn't a plugin: no velocity-plugin.json, bungee.yml or plugin.yml" >&2; exit 2; }
  while IFS= read -r name; do names+=("$name"); done < <(sed -nE 's/^name: *//p; s/.*"(id|name)": *"([^"]+)".*/\2/p' <<<"$descriptor" | sort -u)
done
read -r -a names <<<"$(printf '%s\n' "${names[@]}" | sort -fu | tr '\n' ' ')"
ours=$(IFS='|'; echo "${names[*]}")

proxy_pid="" backend_pid=""
fail() {
  echo "FAIL: $*" >&2
  for file in "${proxy_log:-}" "${backend_log:-}"; do
    [[ -n $file && -f $file ]] || continue
    echo "---- last lines of $file ----" >&2
    tail -60 "$file" >&2 || true
  done
  [[ -z $proxy_pid ]] || kill "$proxy_pid" 2>/dev/null || true
  [[ -z $backend_pid ]] || kill "$backend_pid" 2>/dev/null || true
  exit 1
}

# Waits up to $2 seconds for the fixed string $1 in log $4 after line $3, while $5 runs. The greps
# after sed read to the end, not -q: with pipefail, a grep that stops at an early match fails the
# pipe once sed, still writing, gets SIGPIPE.
wait_in() {
  local text=$1 seconds=$2 after=$3 file=$4 pid=$5
  for ((i = 0; i < seconds * 2; i++)); do
    tail -n "+$((after + 1))" "$file" 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' | grep -F -- "$text" >/dev/null && return 0
    kill -0 "$pid" 2>/dev/null || fail "$(basename "$(dirname "$file")") exited while waiting for \"$text\""
    sleep 0.5
  done
  fail "\"$text\" didn't appear in $(basename "$(dirname "$file")")'s log within ${seconds}s"
}

# The bots' packages, installed once, as scripts/boot-test.sh does.
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

# A port under Linux's ephemeral range, as boot-test.sh picks one.
port() { echo $((20000 + RANDOM % 12000)); }

secret=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 || true)

# Writes the backend's folder in $1 for the proxy's forwarding, listening on $2.
backend_files() {
  local dir=$1 port=$2
  mkdir -p "$dir/plugins"
  for shared in cache libraries versions; do
    mkdir -p "$cache/$(basename "$backend_jar" .jar)/$shared"
    ln -s "$cache/$(basename "$backend_jar" .jar)/$shared" "$dir/$shared"
  done
  [[ ${#backend_jars[@]} -eq 0 ]] || cp "${backend_jars[@]}" "$dir/plugins/"
  echo "eula=true" >"$dir/eula.txt"
  cat >"$dir/server.properties" <<EOF
online-mode=false
server-port=$port
server-ip=127.0.0.1
level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:stone","height"\:1}],"biome"\:"minecraft\:plains"}
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
EOF
  if [[ $proxy == velocity ]]; then
    mkdir -p "$dir/config"
    printf 'proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: %s\n' "$secret" >"$dir/config/paper-global.yml"
  else
    printf 'settings:\n  bungeecord: true\n' >"$dir/spigot.yml"
    mkdir -p "$dir/plugins/Xena"
    echo "$secret" >"$dir/plugins/Xena/proxy.secret"
  fi
}

# Writes the proxy's folder in $1, listening on $2 and forwarding to a backend on $3, if any.
proxy_files() {
  local dir=$1 port=$2 to=${3:-}
  mkdir -p "$dir/plugins"
  cp "${jars[@]}" "$dir/plugins/"
  if [[ $proxy == velocity ]]; then
    local mode=none
    [[ -z $to ]] || mode=modern
    # Only what differs from Velocity's defaults, which it fills in.
    cat >"$dir/velocity.toml" <<EOF
config-version = "2.7"
bind = "127.0.0.1:$port"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "$mode"
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:${to:-1}"
try = ["lobby"]

[forced-hosts]

[advanced]
log-player-connections = true

[query]
enabled = false
EOF
    echo "$secret" >"$dir/forwarding.secret"
  else
    local groups="" permissions="" grant name nodes node
    # YAML's empty mapping and list when nobody is granted anything.
    for grant in ${grants[@]+"${grants[@]}"}; do
      name=${grant%%=*} nodes=${grant#*=}
      groups+="  $name:"$'\n'"  - xena-test-$name"$'\n'
      permissions+="  xena-test-$name:"$'\n'
      for node in ${nodes//,/ }; do permissions+="  - '$node'"$'\n'; done
    done
    cat >"$dir/config.yml" <<EOF
online_mode: false
ip_forward: true
log_pings: false
listeners:
- host: 127.0.0.1:$port
  query_port: $port
  query_enabled: false
  motd: Xena boot test
  max_players: 10
  priorities:
  - lobby
  force_default_server: true
  forced_hosts: {}
servers:
  lobby:
    address: 127.0.0.1:${to:-1}
    restricted: false
    motd: lobby
groups:
${groups:-  nobody: []}
permissions:
${permissions:-  default: []}
EOF
    mkdir -p "$dir/plugins/Xena"
    echo "$secret" >"$dir/plugins/Xena/proxy.secret"
  fi
}

# The flags the proxy runs with: the caller's, the grants, the startup lines.
proxy_flags=(-Xmx512M -Dxena.startupTimes=true ${jvm[@]+"${jvm[@]}"})
if [[ $proxy == velocity && ${#grants[@]} -gt 0 ]]; then
  proxy_flags+=("-Dxena.test.grants=$(IFS=';'; echo "${grants[*]}")")
fi

# Starts the proxy in $1, with more JVM flags after it; sets proxy_pid and proxy_log, and the fd 3
# its console reads.
start_proxy() {
  local dir=$1
  shift
  proxy_log="$dir/console.log"
  mkfifo "$dir/stdin"
  (cd "$dir" && exec "$java" "${proxy_flags[@]}" "$@" -jar "$proxy_jar" <stdin >"$proxy_log" 2>&1) &
  proxy_pid=$!
  exec 3>"$dir/stdin"
  # Velocity says Done; BungeeCord and Waterfall that they're listening.
  for ((i = 0; i < 240; i++)); do
    sed 's/\x1b\[[0-9;]*m//g' "$proxy_log" 2>/dev/null | grep -E 'Done \(|Listening on /' >/dev/null && return 0
    kill -0 "$proxy_pid" 2>/dev/null || fail "the proxy exited while starting"
    sleep 0.5
  done
  fail "the proxy didn't start within 120s"
}

# Stops the proxy and waits for it.
stop_proxy() {
  echo "end" >&3
  for ((i = 0; i < 60; i++)); do
    kill -0 "$proxy_pid" 2>/dev/null || break
    sleep 0.5
  done
  kill -0 "$proxy_pid" 2>/dev/null && fail "the proxy didn't stop within 30s"
  wait "$proxy_pid" || fail "the proxy exited with status $?"
  exec 3>&-
  proxy_pid=""
}

# The error and warning lines of log $1 that the plugins named by $2 or the allowed texts in $3...
# don't excuse: every error, and our plugins' warnings.
judge() {
  local file=$1 ours=$2
  shift 2
  local lines text
  # "[12:00:00 ERROR]: ", "[12:00:00 ERROR] [xsample]: ", "12:00:00 [SEVERE] ", Spigot 1.13-1.16's "[12:00:00] [Server thread/ERROR]: ".
  lines=$(sed 's/\x1b\[[0-9;]*m//g' "$file" | grep -E '(ERROR|SEVERE)\]' || true)
  lines+=$'\n'$(sed 's/\x1b\[[0-9;]*m//g' "$file" | grep -E "(WARN|WARNING)\](:)? \[($ours)\]" || true)
  for text in "$@"; do
    lines=$(grep -vF -- "$text" <<<"$lines" || true)
  done
  sed '/^$/d' <<<"$lines"
}

# ---- the run ----
rm -rf "$base"
mkdir -p "$base"
proxy_port=$(port)
backend_port=""
backend_allowed=()
if [[ -n $backend_jar ]]; then
  backend_port=$(port)
  while [[ $backend_port == "$proxy_port" ]]; do backend_port=$(port); done
  backend_files "$base/backend" "$backend_port"
  backend_log="$base/backend/console.log"
  mkfifo "$base/backend/stdin"
  backend_cmd=("${backend_java:+$backend_java/bin/}java" -Xms512M -Xmx1G -DIReallyKnowWhatIAmDoingISwear=true -jar "$backend_jar")
  if [[ $backend_kind == paper ]]; then backend_cmd+=(--nogui); else backend_cmd+=(nogui); fi
  (cd "$base/backend" && exec "${backend_cmd[@]}" <stdin >"$backend_log" 2>&1) &
  backend_pid=$!
  exec 4>"$base/backend/stdin"
  wait_in "Done (" 300 0 "$backend_log" "$backend_pid"
  echo "Started the backend, $(basename "$backend_jar" .jar), on port $backend_port"
fi
proxy_files "$base/proxy" "$proxy_port" "$backend_port"
start_proxy "$base/proxy"
echo "Started $version on port $proxy_port with ${names[*]}"

since=0
allowed=()
for step in "${steps[@]+"${steps[@]}"}"; do
  case "$step" in
    velocity\ * | bungeecord\ * | waterfall\ *)
      [[ ${step%% *} == "$proxy" ]] || continue
      step=${step#* } ;;
  esac
  case "$step" in
    "run: "*)
      since=$(wc -l <"$proxy_log")
      echo "${step#run: }" >&3 ;;
    "expect: "*) wait_in "${step#expect: }" 60 "$since" "$proxy_log" "$proxy_pid" ;;
    "logged: "*) wait_in "${step#logged: }" 60 0 "$proxy_log" "$proxy_pid" ;;
    "allow: "*) allowed+=("${step#allow: }") ;;
    "backend: "*)
      [[ -n $backend_jar ]] || fail "a backend: step needs --backend"
      wait_in "${step#backend: }" 60 0 "$backend_log" "$backend_pid" ;;
    "backend allow: "*) backend_allowed+=("${step#backend allow: }") ;;
    "client:"*)
      since=$(wc -l <"$proxy_log")
      bots_ready
      out="$base/client.log"
      # Word splitting is the point: the step's arguments.
      # shellcheck disable=SC2086
      if ! node "$bots/client.js" "$proxy_port" ${step#client:} >"$out" 2>&1; then
        cat "$out" >&2
        fail "the client didn't pass"
      fi
      cat "$out" ;;
    *) fail "a step must start with \"run: \", \"expect: \", \"logged: \", \"allow: \", \"client:\", \"backend: \" or \"backend allow: \": $step" ;;
  esac
done

stop_proxy
if [[ -n $backend_jar ]]; then
  echo "stop" >&4
  for ((i = 0; i < 120; i++)); do
    kill -0 "$backend_pid" 2>/dev/null || break
    sleep 0.5
  done
  kill -0 "$backend_pid" 2>/dev/null && fail "the backend didn't stop within 60s"
  wait "$backend_pid" || fail "the backend exited with status $?"
  exec 4>&-
  backend_pid=""
fi

# ---- judge the logs ----
for text in "${allowed[@]+"${allowed[@]}"}"; do
  sed 's/\x1b\[[0-9;]*m//g' "$proxy_log" | grep -F -- "$text" >/dev/null || fail "an allowed error or warning never appeared: \"$text\""
done
problems=$(judge "$proxy_log" "$ours" ${allowed[@]+"${allowed[@]}"})
[[ -z $problems ]] || fail "the proxy's log has errors, or our plugins' warnings:"$'\n'"$problems"
if [[ -n $backend_jar ]]; then
  for text in "${backend_allowed[@]+"${backend_allowed[@]}"}"; do
    grep -qF -- "$text" "$backend_log" || fail "an allowed backend error or warning never appeared: \"$text\""
  done
  problems=$(judge "$backend_log" "$ours" ${backend_allowed[@]+"${backend_allowed[@]}"})
  [[ -z $problems ]] || fail "the backend's log has errors, or our plugins' warnings:"$'\n'"$problems"
fi
sed 's/\x1b\[[0-9;]*m//g' "$proxy_log" | grep -E "INFO\](:)? \[($ours)\]" || true

# ---- startup ----
# Each boot's startup: the sum over our plugins of the shim's line, "enable 105.1 ms" on Velocity,
# "onLoad 16.2 ms, onEnable 142.4 ms" on BungeeCord. The run above downloaded what it needed.
if [[ -n $startup_budget ]]; then
  times=()
  for boot in 1 2 3; do
    dir="$base/startup-$boot"
    proxy_files "$dir" "$(port)" ""
    # The first run's downloads: the family's library cache, and BungeeCord's for libraries:.
    cp -R "$base/proxy/plugins/Xena/." "$dir/plugins/Xena/" 2>/dev/null || true
    [[ ! -d $base/proxy/libraries ]] || cp -R "$base/proxy/libraries" "$dir/"
    if [[ -n $record_startup && $boot == 1 ]]; then
      start_proxy "$dir" "-Xlog:class+load=info:file=$dir/classload.log:uptimemillis"
      # The JVM's uptime as the proxy said it was done, give or take the half second start_proxy polls.
      done_ms=$(tail -1 "$dir/classload.log" | sed -nE 's/^\[([0-9]+)ms\].*/\1/p')
      [[ -n $done_ms ]] || fail "the class-loading log has no uptime"
    else
      start_proxy "$dir"
    fi
    sleep 2
    stop_proxy
    if [[ -n $record_startup && $boot == 1 ]]; then
      for jar in "${jars[@]}"; do
        python3 "$root/scripts/budgets/startup_classes.py" --log "$dir/classload.log" --done-ms "$done_ms" --jar "$jar" \
          --out "$record_startup/startup-classes-$(basename "$jar" .jar).txt" || fail "couldn't write $(basename "$jar")'s startup classes"
      done
    fi
    total=$(sed 's/\x1b\[[0-9;]*m//g' "$proxy_log" | grep -oE 'Startup times: .*' |
      grep -oE '[0-9]+\.[0-9]+ ms' | awk '{ sum += $1 } END { printf "%.1f", sum }')
    times+=("$total")
  done
  median=$(printf '%s\n' "${times[@]}" | sort -n | sed -n 2p)
  echo "Startup on $version: ${times[*]} ms, median $median ms (budget $startup_budget ms)"
  awk -v m="$median" -v b="$startup_budget" 'BEGIN { exit !(m <= b) }' || fail "startup is over its budget: $median ms against $startup_budget ms"
fi
echo "PASS: $version${backend_jar:+ in front of $(basename "$backend_jar" .jar)}"
