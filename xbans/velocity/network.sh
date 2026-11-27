#!/usr/bin/env bash
# xBans' network test, behind Velocity or BungeeCord (scripts/boot-proxy.sh --two-backends): the
# proxy and two servers, lobby and game, on one MariaDB database with network mode on, as a network
# runs it. Real clients: Joiner on lobby, Friend on game. A mute made on lobby stops Friend's chat
# on game; a ban made on lobby takes Friend off the network and the proxy turns them away at its
# door (scripts/budgets/bots/client.js, xbans-network); network-checks.txt has the steps. After
# ./gradlew :xbans:bukkit:assemble :xbans:velocity:assemble (or :xbans:bungee:assemble).
#
#   xbans/velocity/network.sh velocity|bungeecord --java <JDK home> --backend paper|<server.jar> \
#     [--backend-version 1.21.11] --backend-java <JDK home> [more scripts/boot-proxy.sh arguments]
#
# The database is XR_MYSQL's (host:port:database:user:password), as CI's MariaDB service sets it;
# without it, a MariaDB 11 container for the run (Docker). Each run's tables have a prefix of their
# own, so runs sharing a database never meet. The proxy's own punishment commands are off in this
# test, so Joiner's /ban and /mute reach lobby.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
(($#)) || { sed -n '2,16p' "$0" >&2; exit 2; }
proxy=$1
shift
case $proxy in
  velocity) side=velocity jar=$root/xbans/velocity/build/r8/xBans-Velocity.jar ;;
  bungeecord | waterfall) side=bungee jar=$root/xbans/bungee/build/r8/xBans-Bungee.jar ;;
  *) echo "the first argument is velocity, bungeecord or waterfall, not $proxy" >&2; exit 2 ;;
esac
backend=$root/xbans/bukkit/build/r8/xBans.jar
for need in "$jar" "$backend"; do [[ -f $need ]] || { echo "no $need: assemble it first" >&2; exit 2; }; done

container=""
cleanup() { [[ -z $container ]] || docker rm -f "$container" >/dev/null 2>&1 || true; }
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
prefix="xbn$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 6 || true)_"

seeds=$(mktemp -d)
trap 'cleanup; rm -rf "$seeds"' EXIT
storage() {
  cat <<YAML
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
folder=$([[ $side == velocity ]] && echo xbans || echo xBans)
mkdir -p "$seeds/backend/plugins/xBans" "$seeds/proxy/plugins/$folder"
storage >"$seeds/backend/plugins/xBans/config.yml"
cp "$root/scripts/boot-seeds/client/ops.json" "$seeds/backend/"
{
  storage
  echo "commands:"
  for command in ban tempban mute tempmute unban unmute kick warn; do printf '  %s:\n    enabled: false\n' "$command"; done
} >"$seeds/proxy/plugins/$folder/config.yml"

"$root/scripts/boot-proxy.sh" --proxy "$proxy" --two-backends --backend-seed "$seeds/backend" --proxy-seed "$seeds/proxy" \
  --backend-plugin "$backend" --jvm -Dxena.debug.paste=false --checks "$root/xbans/$side/network-checks.txt" "$@" "$jar"
