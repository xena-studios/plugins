#!/usr/bin/env bash
# xVote's network test, behind Velocity or BungeeCord (scripts/boot-proxy.sh --two-backends): the
# proxy and two servers, lobby and game, on one MariaDB database with network mode on, as a network
# runs it. The proxy's receiver takes every vote (the servers' are off); real clients send real
# Votifier v2 votes to it: one for Friend before Friend has ever joined, paid on lobby as Friend
# first joins; one for Friend once on game, paid on game; one for Joiner, paid on lobby
# (scripts/budgets/bots/client.js, xvote-network); network-checks.txt has the steps. After
# ./gradlew :xvote:bukkit:assemble :xvote:velocity:assemble (or :xvote:bungee:assemble).
#
#   xvote/velocity/network.sh velocity|bungeecord --java <JDK home> --backend paper|<server.jar> \
#     [--backend-version 1.21.11] --backend-java <JDK home> [more scripts/boot-proxy.sh arguments]
#
# The database is XR_MYSQL's (host:port:database:user:password), as CI's MariaDB service sets it;
# without it, a MariaDB 11 container for the run (Docker). Each run's tables have a prefix of their
# own, so runs sharing a database never meet.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
(($#)) || { sed -n '2,15p' "$0" >&2; exit 2; }
proxy=$1
shift
case $proxy in
  velocity) side=velocity jar=$root/xvote/velocity/build/r8/xVote-Velocity.jar ;;
  bungeecord | waterfall) side=bungee jar=$root/xvote/bungee/build/r8/xVote-Bungee.jar ;;
  *) echo "the first argument is velocity, bungeecord or waterfall, not $proxy" >&2; exit 2 ;;
esac
backend=$root/xvote/bukkit/build/r8/xVote.jar
for need in "$jar" "$backend"; do [[ -f $need ]] || { echo "no $need: assemble it first" >&2; exit 2; }; done

container=""
seeds=$(mktemp -d)
cleanup() {
  [[ -z $container ]] || docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$seeds"
}
trap cleanup EXIT
if [[ -z ${XR_MYSQL:-} ]]; then
  command -v docker >/dev/null || { echo "no XR_MYSQL and no docker: nowhere for the network's database" >&2; exit 2; }
  container=$(docker run -d -e MARIADB_ROOT_PASSWORD=ci -e MARIADB_DATABASE=xr -p 127.0.0.1::3306 mariadb:11)
  for ((i = 0; i < 120; i++)); do
    docker exec "$container" mariadb-admin -uroot -pci ping >/dev/null 2>&1 && break
    sleep 1
  done
  port=$(docker port "$container" 3306/tcp | head -1 | sed 's/.*://')
  # MariaDB answers ping before it takes logins over TCP; a moment more.
  sleep 3
  XR_MYSQL="127.0.0.1:$port:xr:root:ci"
fi
IFS=: read -r host port database user password <<<"$XR_MYSQL"
prefix="xvn$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 6 || true)_"

storage() {
  cat <<YAML
config-version: 2
storage:
  type: mysql
  table-prefix: "$prefix"
  mysql:
    host: "$host"
    port: $port
    database: "$database"
    username: "$user"
    password: "$password"
    properties: "useSSL=false&allowPublicKeyRetrieval=true"
network:
  enabled: true
  check-every: 1
YAML
}
# BungeeCord names the plugin's folder after its name, Velocity after its id.
folder=$([[ $side == velocity ]] && echo xvote || echo xVote)
mkdir -p "$seeds/backend/plugins/xVote" "$seeds/proxy/plugins/$folder"
# The servers' receivers are off: the proxy's takes the network's votes, and two servers on one
# machine would want the same port.
{ storage; printf 'receiver:\n  enabled: false\n'; } >"$seeds/backend/plugins/xVote/config.yml"
cp "$root/scripts/boot-seeds/client/ops.json" "$seeds/backend/"
storage >"$seeds/proxy/plugins/$folder/config.yml"
cp "$root/xvote/bukkit/client-seed/plugins/xVote/tokens.yml" "$seeds/proxy/plugins/$folder/"

# BungeeCord's /server is for players granted it; Velocity's for everyone.
grant=()
[[ $side == velocity ]] || grant=(--grant Friend=bungeecord.command.server)

"$root/scripts/boot-proxy.sh" --proxy "$proxy" "${grant[@]+"${grant[@]}"}" --two-backends --backend-seed "$seeds/backend" --proxy-seed "$seeds/proxy" \
  --backend-plugin "$backend" --jvm -Dxena.debug.paste=false --checks "$root/xvote/$side/network-checks.txt" "$@" "$jar"
