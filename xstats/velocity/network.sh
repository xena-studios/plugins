#!/usr/bin/env bash
# xStats' network test, behind Velocity (scripts/boot-proxy.sh --two-backends): the proxy and two
# servers, lobby and game, on one MariaDB database with network mode on, as a network runs it. A
# real client jumps on lobby, lobby saves, and the proxy's /networkleaderboard jumps has them on it
# (scripts/budgets/bots/client.js, xstats-network); network-checks.txt has the steps. After
# ./gradlew :xstats:bukkit:assemble :xstats:velocity:assemble.
#
#   xstats/velocity/network.sh --java <JDK home> --backend paper|<server.jar> \
#     [--backend-version 1.21.11] --backend-java <JDK home> [more scripts/boot-proxy.sh arguments]
#
# The database is XR_MYSQL's (host:port:database:user:password), as CI's MariaDB service sets it;
# without it, a MariaDB 11 container for the run (Docker). Each run's tables have a prefix of their
# own, so runs sharing a database never meet. Both backends get the same seed, so they share one
# server-name: the stat this test counts is lobby's alone, which is what it checks.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
(($#)) || { sed -n '2,14p' "$0" >&2; exit 2; }
jar=$root/xstats/velocity/build/r8/xStats-Velocity.jar
backend=$root/xstats/bukkit/build/r8/xStats.jar
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
prefix="xsn$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 6 || true)_"

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
mkdir -p "$seeds/backend/plugins/xStats" "$seeds/proxy/plugins/xstats"
# The servers' saves are by command here, not every five minutes, and the first start has no history to import.
{ storage; printf 'collecting:\n  import-on-first-start: false\n'; } >"$seeds/backend/plugins/xStats/config.yml"
cp "$root/scripts/boot-seeds/client/ops.json" "$seeds/backend/"
# The proxy reads the network's numbers within five seconds of a server's save.
{ storage; printf 'proxy:\n  refresh-seconds: 5\n'; } >"$seeds/proxy/plugins/xstats/config.yml"

"$root/scripts/boot-proxy.sh" --proxy velocity --two-backends --backend-seed "$seeds/backend" --proxy-seed "$seeds/proxy" \
  --backend-plugin "$backend" --jvm -Dxena.debug.paste=false --checks "$here/network-checks.txt" "$@" "$jar"
