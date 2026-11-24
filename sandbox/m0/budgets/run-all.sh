#!/usr/bin/env bash
# The whole baseline behind RESULTS.md, one server at a time on an otherwise idle machine:
# no plugins and all 11 together, each with 10 bots; each plugin alone; both repeated once;
# all but xLimbo with 10 bots.
#
#   SERVERS=... PAPER=... TPL=... ASPROF=... ./run-all.sh <folder with the 11 plugin jars>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
jars=$1
"$here/measure.sh" base --bots 10
"$here/measure.sh" all --bots 10 "$jars"/*.jar
for j in "$jars"/*.jar; do
  n=$(basename "$j" .jar); n=${n%%-*}
  "$here/measure.sh" "$n" "$j"
done
"$here/measure.sh" base-2
"$here/measure.sh" all-2 --bots 10 "$jars"/*.jar
# xLimbo sends every joining player to its own world, so in "all" the bots never build or chat in
# the main world; this run leaves it out so the other ten see the bots' blocks and chat.
"$here/measure.sh" all-nolimbo --bots 10 $(ls "$jars"/*.jar | grep -v xlimbo)
