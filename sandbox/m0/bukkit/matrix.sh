#!/usr/bin/env bash
# The startup matrix behind RESULTS.md: for each server, a boot with no plugins (baseline),
# a first boot with Hello (stdlib fetched), and a second boot reusing the folder (cached).
#
#   SERVERS=... JAVA8_HOME=... JDK16=... ./matrix.sh [filter]
#
# Server jars: Spigot from BuildTools in $SERVERS/bt/out, the others in $SERVERS/jars.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${SERVERS:?}"
filter=${1:-}

hello17=$here/hello/build/libs/Hello.jar
hello16=$here/build/jvm16/Hello.jar

# name | server jar | java | extra jvm flag | hello jar
rows=(
  "spigot-1.8.8|bt/out/spigot-1.8.8.jar|17||$hello17"
  "spigot-1.8.8|bt/out/spigot-1.8.8.jar|21||$hello17"
  "paper-1.8.8|jars/paper-1.8.8-445.jar|17||$hello17"
  "pandaspigot-1.8.8|jars/pandaspigot-1.8.8.jar|17||$hello17"
  "pandaspigot-1.8.8|jars/pandaspigot-1.8.8.jar|21||$hello17"
  "spigot-1.12.2|bt/out/spigot-1.12.2.jar|17||$hello17"
  "spigot-1.13.2|bt/out/spigot-1.13.2.jar|17||$hello17"
  "spigot-1.14.4|bt/out/spigot-1.14.4.jar|17||$hello17"
  "spigot-1.15.2|bt/out/spigot-1.15.2.jar|17||$hello17"
  "spigot-1.16.5|bt/out/spigot-1.16.5.jar|17||$hello17"
  "spigot-1.16.5|bt/out/spigot-1.16.5.jar|${JDK16:-16}||$hello16"
  "paper-1.16.5|jars/paper-1.16.5-794.jar|17|-DPaper.IgnoreJavaVersion=true|$hello17"
  "spigot-1.21.11|bt/out/spigot-1.21.11.jar|21||$hello17"
  "paper-1.21.11|jars/paper-1.21.11-132.jar|21||$hello17"
  "folia-26.3|jars/folia-26.3-27.jar|25||$hello17"
)

for row in "${rows[@]}"; do
  IFS='|' read -r name jar java flag hello <<<"$row"
  [[ -n $filter && $name != *$filter* ]] && continue
  j=$java; [[ -d $java ]] && j=16
  run="m-$name-j$j"
  args=(); [[ -n $flag ]] && args=(--jvm "$flag")
  base=$("$here/boot.sh" "$run-base" "$SERVERS/$jar" "$java" ${args[@]+"${args[@]}"} 2>&1 | grep -o 'Done ([0-9.]*s)' | head -1)
  first=$("$here/boot.sh" "$run" "$SERVERS/$jar" "$java" ${args[@]+"${args[@]}"} --run hello "$hello" 2>&1)
  second=$("$here/boot.sh" "$run" "$SERVERS/$jar" "$java" ${args[@]+"${args[@]}"} --keep --run hello "$hello" 2>&1)
  pick() { grep -oE 'Done \([0-9.]*s\)|M0 startup: .*|needs Java.*|Unsupported Java.*|getPlugin.*|squares=.*|stdlib from [^|]*|Could not load.*' <<<"$1" | sort -u | tr '\n' ';'; }
  echo "$name java $j | baseline $base | first: $(pick "$first") | second: $(pick "$second")"
  rm -rf "$SERVERS/run/$run-base"
done
