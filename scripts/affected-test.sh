#!/usr/bin/env bash
# Checks scripts/affected.sh: that what it calls the rewrite's alone in build-logic/ is out of
# reach of today's plugins, and what it runs for each kind of change. The Plugins workflow
# runs it before affected.sh; it needs bash 4 or newer, as affected.sh does.
#
#   scripts/affected-test.sh [--table]
#
# --table prints what each case runs, as a Markdown table, instead of only checking it.
set -euo pipefail

cd "$(dirname "$0")/.."
table=false
[[ ${1:-} == --table ]] && table=true
failures=0
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }

# 1. What today's plugins reach in build-logic/. They start from the conventions their build
# files apply: every build file in the root build but the rewrite's modules in core/ and the
# plugins in the rewrite's layout (a common/ folder and no build at the top), and the root's.
# From each file reached, a convention it applies ("xena.<name>") and a file declaring a
# class, object, interface, function, property or type alias whose name it uses are reached
# too. Over-reaching is safe: it only fails this test, and the fix is to take the file off
# affected.sh's list.
logic=build-logic/src/main/kotlin
declare -A declares=()
while IFS= read -r file; do
  names=$(grep -ohE '^((public|internal|private|abstract|open|data|sealed|enum|inline|value|annotation|const) )*(class|object|interface|fun interface|fun|val|var|typealias) +(<[^>]*> +)?([A-Za-z0-9_]+(<[^>]*>)?\.)?[A-Za-z0-9_]+' "$file" |
    grep -oE '[A-Za-z0-9_]+$' | sort -u | tr '\n' ' ' || true)
  declares[$file]=$names
done < <(find "$logic" -name '*.kt' | sort)

starts=(build.gradle.kts)
while IFS= read -r file; do
  unit=${file%%/*}
  case $file in
    core/*/*) continue ;;
    sandbox/m0/*) continue ;;
  esac
  [[ $unit != "$file" && -f $unit/common/build.gradle.kts && ! -f $unit/build.gradle.kts ]] && continue
  starts+=("$file")
done < <(git ls-files '*build.gradle.kts' | grep -v '^build-logic/')

declare -A reached=()
queue=()
reach() { [[ -n ${reached[$1]:-} ]] || { reached[$1]=1; queue+=("$1"); }; }
for file in "${starts[@]}"; do
  for id in $(grep -ohE '"xena\.[a-z0-9-]+"' "$file" | tr -d '"' | sort -u); do
    [[ -f $logic/$id.gradle.kts ]] && reach "$logic/$id.gradle.kts"
  done
done
while ((${#queue[@]})); do
  file=${queue[0]}
  queue=("${queue[@]:1}")
  for id in $(grep -ohE '"xena\.[a-z0-9-]+"' "$file" | tr -d '"' | sort -u); do
    [[ -f $logic/$id.gradle.kts ]] && reach "$logic/$id.gradle.kts"
  done
  for other in "${!declares[@]}"; do
    [[ -n ${reached[$other]:-} || $other == "$file" ]] && continue
    for name in ${declares[$other]}; do
      if grep -qw -- "$name" "$file"; then reach "$other"; break; fi
    done
  done
done

# affected.sh's list of the rewrite's files.
mapfile -t only < <(sed -nE 's/^ *\[([^]]+)\]=.*/\1/p' scripts/affected.sh)
((${#only[@]} > 10)) || fail "couldn't read rewriteOnly from scripts/affected.sh"
for file in "${only[@]}"; do
  [[ -e $file ]] || fail "affected.sh lists $file, which doesn't exist"
  [[ -z ${reached[$file]:-} ]] || fail "affected.sh calls $file the rewrite's alone, but today's plugins reach it"
done

# 2. What each kind of change runs. Each case is the changed files, then what affected.sh
# must print, as "key=value" lines; "everything" is what --all prints.
everything=$(scripts/affected.sh --all)
rewriteTasks=$(git ls-files 'core/*/build.gradle.kts' | while read -r f; do
  dir=${f%/build.gradle.kts}
  # Not in a separate build of its own (core/platform-fabric).
  d=$dir; separate=false
  while [[ $d == core/* ]]; do [[ -f $d/settings.gradle.kts ]] && separate=true; d=${d%/*}; done
  [[ $separate == true ]] || echo ":${dir//\//:}:build"
done | sort | tr '\n' ' ' | sed 's/ $//')
# The rewritten plugins (<plugin>/common, no build at the top) use the rewrite's modules, so a
# change to those builds them too, after the root's own check; their fabric/ is platform-fabric's.
pluginTasks=$(for f in */common/build.gradle.kts; do
  unit=${f%%/*}
  [[ -f $unit/build.gradle.kts ]] && continue
  for b in "$unit"/*/build.gradle.kts; do
    module=${b%/build.gradle.kts}
    [[ ${module##*/} == fabric ]] || echo ":${module//\//:}:build"
  done | sort
done | tr '\n' ' ' | sed 's/ $//')
[[ -z $pluginTasks ]] || rewriteTasks=":build $pluginTasks $rewriteTasks"

rows=()
check() {
  local name=$1 files=$2 want=$3 got
  got=$(scripts/affected.sh <<< "$files")
  if [[ $want == everything ]]; then
    [[ $got == "$everything" ]] || fail "$name: wanted everything, got:"$'\n'"$got"
  else
    while IFS= read -r line; do
      [[ -n $line ]] || continue
      grep -qxF -- "$line" <<< "$got" || fail "$name: wanted '$line', got:"$'\n'"$got"
    done <<< "$want"
  fi
  local tasks checks boot
  tasks=$(sed -n 's/^tasks=//p' <<< "$got")
  checks=$(sed -n 's/^checks=//p' <<< "$got")
  boot=$(sed -n 's/^boot=//p' <<< "$got")
  local old="no"
  [[ -n $checks ]] && old="yes"
  [[ $tasks == "$rewriteTasks" ]] && tasks="the rewrite's modules"
  [[ $tasks == build ]] && tasks="everything"
  # shellcheck disable=SC2016 # Markdown's backticks
  rows+=("| $name | \`$(tr '\n' ' ' <<< "$files" | sed 's/ $//; s/ /`, `/g')\` | ${tasks:-nothing} | $old | ${boot:-none} |")
}
nothing=$'tasks=\nchecks=\nboot='
rewrite="tasks=$rewriteTasks"$'\nchecks='

check "Rewrite core only" core/core-common/src/commonMain/kotlin/co/xenastudios/core/common/text/Component.kt \
  "$rewrite"$'\nboot=bukkit fabric proxy'
check "Rewrite's Bukkit platform" core/platform-bukkit/src/main/kotlin/co/xenastudios/core/bukkit/BukkitText.kt \
  "$rewrite"$'\nboot=bukkit'
check "Rewrite's Fabric platform (its own build)" core/platform-fabric/src/main/kotlin/co/xenastudios/core/fabric/FabricCommands.kt \
  "$rewrite"$'\nboot=fabric'
check "Rewrite's Velocity platform" core/platform-velocity/src/main/kotlin/co/xenastudios/core/velocity/VelocityCommands.kt \
  "$rewrite"$'\nboot=proxy'
check "Rewrite's BungeeCord platform" core/platform-bungee/src/main/kotlin/co/xenastudios/core/bungee/BungeeChannel.kt \
  "$rewrite"$'\nboot=proxy'
check "xSample's Velocity plugin" core/core-testing/sample/velocity/src/main/kotlin/co/xenastudios/xsample/velocity/XSampleVelocity.kt \
  "$rewrite"$'\nboot=proxy'
check "The loader's shims" core/core-loader/src/velocity/java/co/xenastudios/core/loader/velocity/VelocityBootstrap.java \
  "$rewrite"$'\nboot=bukkit proxy'
check "Proxy boot script" scripts/boot-proxy.sh $'tasks=\nchecks=\nboot=proxy'
check "The real client" scripts/budgets/bots/client.js $'tasks=\nchecks=\nboot=bukkit proxy'
check "A rewritten plugin's common code" xutilities/common/src/commonMain/kotlin/co/xenastudios/xutilities/Commands.kt \
  $'tasks=:build :xutilities:api:build :xutilities:bukkit:build :xutilities:common:build\nchecks=\nboot=bukkit fabric'
check "A rewritten plugin's Bukkit adapter" xutilities/bukkit/src/main/kotlin/co/xenastudios/xutilities/bukkit/BukkitXServer.kt \
  $'tasks=:build :xutilities:api:build :xutilities:bukkit:build :xutilities:common:build\nchecks=\nboot=bukkit'
check "A rewritten plugin's Fabric mod (platform-fabric's build)" xutilities/fabric/src/main/kotlin/co/xenastudios/xutilities/fabric/XUtilitiesMod.kt \
  $'checks=\nboot=fabric'
check "zChat's common code" zchat/common/src/commonMain/kotlin/co/xenastudios/zchat/ChatModule.kt \
  $'tasks=:build :zchat:api:build :zchat:bukkit:build :zchat:bungee:build :zchat:common:build :zchat:velocity:build\nchecks=\nboot=bukkit fabric proxy'
check "zChat's Bukkit adapter" zchat/bukkit/src/main/kotlin/co/xenastudios/zchat/bukkit/ZChatBukkit.kt \
  $'tasks=:build :zchat:api:build :zchat:bukkit:build :zchat:bungee:build :zchat:common:build :zchat:velocity:build\nchecks=\nboot=bukkit'
check "zChat's Fabric mod (platform-fabric's build)" zchat/fabric/src/main/kotlin/co/xenastudios/zchat/fabric/ZChatMod.kt \
  $'checks=\nboot=fabric'
check "zChat's Velocity relay" zchat/velocity/src/main/kotlin/co/xenastudios/zchat/velocity/ZChatVelocity.kt \
  $'tasks=:build :zchat:api:build :zchat:bukkit:build :zchat:bungee:build :zchat:common:build :zchat:velocity:build\nchecks=\nboot=proxy'
check "zChat's real-client checks" zchat/bukkit/client-checks.txt \
  $'tasks=:build :zchat:api:build :zchat:bukkit:build :zchat:bungee:build :zchat:common:build :zchat:velocity:build\nchecks=\nboot=bukkit'
check "xMoney's common code" xmoney/common/src/commonMain/kotlin/co/xenastudios/xmoney/ledger/Ledger.kt \
  $'tasks=:build :xmoney:api:build :xmoney:bukkit:build :xmoney:common:build :xmoney:hook-consumer:build :xmoney:vault-consumer:build\nchecks=\nboot=bukkit fabric'
check "xMoney's Bukkit adapter" xmoney/bukkit/src/main/kotlin/co/xenastudios/xmoney/bukkit/vault/LegacyEconomy.kt \
  $'tasks=:build :xmoney:api:build :xmoney:bukkit:build :xmoney:common:build :xmoney:hook-consumer:build :xmoney:vault-consumer:build\nchecks=\nboot=bukkit'
check "xMoney's Fabric mod (platform-fabric's build)" xmoney/fabric/src/main/kotlin/co/xenastudios/xmoney/fabric/CommonEconomyProvider.kt \
  $'checks=\nboot=fabric'
check "xMoney's Vault test plugin" xmoney/vault-consumer/src/main/java/co/xenastudios/xmoney/vaultconsumer/VaultConsumer.java \
  $'tasks=:build :xmoney:api:build :xmoney:bukkit:build :xmoney:common:build :xmoney:hook-consumer:build :xmoney:vault-consumer:build\nchecks=\nboot=bukkit fabric'
check "xTeleport's common code" xteleport/common/src/commonMain/kotlin/co/xenastudios/xteleport/rtp/Search.kt \
  $'tasks=:build :xteleport:api:build :xteleport:bukkit:build :xteleport:common:build\nchecks=\nboot=bukkit fabric'
check "xTeleport's API" xteleport/api/src/commonMain/kotlin/co/xenastudios/xteleport/api/XTeleportApi.kt \
  $'tasks=:build :xteleport:api:build :xteleport:bukkit:build :xteleport:common:build\nchecks=\nboot=bukkit fabric'
check "xTeleport's Bukkit adapter" xteleport/bukkit/src/main/kotlin/co/xenastudios/xteleport/bukkit/BukkitTeleportServer.kt \
  $'tasks=:build :xteleport:api:build :xteleport:bukkit:build :xteleport:common:build\nchecks=\nboot=bukkit'
check "xTeleport's real-client checks" xteleport/bukkit/client-checks.txt \
  $'tasks=:build :xteleport:api:build :xteleport:bukkit:build :xteleport:common:build\nchecks=\nboot=bukkit'
check "xTeleport's Fabric mod (platform-fabric's build)" xteleport/fabric/src/main/kotlin/co/xenastudios/xteleport/fabric/FabricTeleportServer.kt \
  $'checks=\nboot=fabric'
check "xBans' common code" xbans/common/src/commonMain/kotlin/co/xenastudios/xbans/Punisher.kt \
  $'tasks=:build :xbans:api:build :xbans:bukkit:build :xbans:bungee:build :xbans:common:build :xbans:velocity:build\nchecks=\nboot=bukkit fabric proxy'
check "xBans' API" xbans/api/src/commonMain/kotlin/co/xenastudios/xbans/api/XBansApi.kt \
  $'tasks=:build :xbans:api:build :xbans:bukkit:build :xbans:bungee:build :xbans:common:build :xbans:velocity:build\nchecks=\nboot=bukkit fabric proxy'
check "xBans' Bukkit adapter" xbans/bukkit/src/main/kotlin/co/xenastudios/xbans/bukkit/Guards.kt \
  $'tasks=:build :xbans:api:build :xbans:bukkit:build :xbans:bungee:build :xbans:common:build :xbans:velocity:build\nchecks=\nboot=bukkit'
check "xBans' real-client checks" xbans/bukkit/client-checks.txt \
  $'tasks=:build :xbans:api:build :xbans:bukkit:build :xbans:bungee:build :xbans:common:build :xbans:velocity:build\nchecks=\nboot=bukkit'
check "xBans' Fabric mod (platform-fabric's build)" xbans/fabric/src/main/kotlin/co/xenastudios/xbans/fabric/XBansMod.kt \
  $'tasks=:build :xbans:api:build :xbans:bukkit:build :xbans:bungee:build :xbans:common:build :xbans:velocity:build\nchecks=\nboot=fabric'
check "xBans' Velocity door" xbans/velocity/src/main/kotlin/co/xenastudios/xbans/velocity/XBansVelocity.kt \
  $'tasks=:build :xbans:api:build :xbans:bukkit:build :xbans:bungee:build :xbans:common:build :xbans:velocity:build\nchecks=\nboot=proxy'
check "xBans' network test" xbans/bungee/network-checks.txt \
  $'tasks=:build :xbans:api:build :xbans:bukkit:build :xbans:bungee:build :xbans:common:build :xbans:velocity:build\nchecks=\nboot=proxy'
check "xVanish's common code" xvanish/common/src/commonMain/kotlin/co/xenastudios/xvanish/XVanishPlugin.kt \
  $'tasks=:build :xvanish:api:build :xvanish:bukkit:build :xvanish:common:build\nchecks=\nboot=bukkit fabric proxy'
check "xVanish's API" xvanish/api/src/commonMain/kotlin/co/xenastudios/xvanish/api/XVanishApi.kt \
  $'tasks=:build :xvanish:api:build :xvanish:bukkit:build :xvanish:common:build\nchecks=\nboot=bukkit fabric proxy'
check "xVanish's Bukkit adapter" xvanish/bukkit/src/main/kotlin/co/xenastudios/xvanish/bukkit/Listeners.kt \
  $'tasks=:build :xvanish:api:build :xvanish:bukkit:build :xvanish:common:build\nchecks=\nboot=bukkit proxy'
check "xVanish's real-client checks" xvanish/bukkit/client-checks.txt \
  $'tasks=:build :xvanish:api:build :xvanish:bukkit:build :xvanish:common:build\nchecks=\nboot=bukkit proxy'
# Its network test is behind the proxies, though it has no proxy side.
check "xVanish's network test" xvanish/bukkit/network-checks.txt \
  $'tasks=:build :xvanish:api:build :xvanish:bukkit:build :xvanish:common:build\nchecks=\nboot=bukkit proxy'
check "xVanish's Fabric mod (platform-fabric's build)" xvanish/fabric/src/main/kotlin/co/xenastudios/xvanish/fabric/FabricVanishServer.kt \
  $'tasks=:build :xvanish:api:build :xvanish:bukkit:build :xvanish:common:build\nchecks=\nboot=fabric'
check "xMarket's common code" xmarket/common/src/commonMain/kotlin/co/xenastudios/xmarket/Trades.kt \
  $'tasks=:build :xmarket:api:build :xmarket:bukkit:build :xmarket:common:build\nchecks=\nboot=bukkit fabric'
check "xMarket's API" xmarket/api/src/commonMain/kotlin/co/xenastudios/xmarket/api/XMarketApi.kt \
  $'tasks=:build :xmarket:api:build :xmarket:bukkit:build :xmarket:common:build\nchecks=\nboot=bukkit fabric'
check "xMarket's price list sources" xmarket/prices/base.txt \
  $'tasks=:build :xmarket:api:build :xmarket:bukkit:build :xmarket:common:build\nchecks=\nboot=bukkit fabric'
check "xMarket's Bukkit adapter" xmarket/bukkit/src/main/kotlin/co/xenastudios/xmarket/bukkit/BukkitMarketServer.kt \
  $'tasks=:build :xmarket:api:build :xmarket:bukkit:build :xmarket:common:build\nchecks=\nboot=bukkit'
check "xMarket's real-client checks" xmarket/bukkit/xmoney-client-checks.txt \
  $'tasks=:build :xmarket:api:build :xmarket:bukkit:build :xmarket:common:build\nchecks=\nboot=bukkit'
check "xMarket's Fabric mod (platform-fabric's build)" xmarket/fabric/src/main/kotlin/co/xenastudios/xmarket/fabric/FabricMarketServer.kt \
  $'tasks=:build :xmarket:api:build :xmarket:bukkit:build :xmarket:common:build\nchecks=\nboot=fabric'
check "Today's core (core/src)" core/src/main/java/co/xenastudios/core/XenaPlugin.java everything
check "Today's plugin" xvote/src/main/java/co/xenastudios/xvote/XVote.java \
  $'units=xvote\ncompile=:xvote:compileJava :xvote:api:compileJava\nchecks=xvote/boot-checks.txt\nboot='
check "Rewrite's convention" build-logic/src/main/kotlin/xena.r8.gradle.kts "$rewrite"$'\nboot=bukkit fabric proxy'
check "Rewrite's build-logic class" build-logic/src/main/kotlin/co/xenastudios/gradle/KotlinConventions.kt "$rewrite"$'\nboot=bukkit fabric proxy'
check "Today's convention" build-logic/src/main/kotlin/xena.plugin.gradle.kts everything
check "build-logic's own build" build-logic/build.gradle.kts everything
check "Rewrite's jar budgets" gradle/jar-budgets.txt "$rewrite"$'\nboot=bukkit fabric proxy'
check "Rewrite's shrinking rules" build-logic/src/main/resources/xena/r8/common.pro "$rewrite"$'\nboot=bukkit fabric proxy'
check "Version catalog" gradle/libs.versions.toml everything
check "Runtime budgets script" scripts/budgets/check.sh $'tasks=\nchecks=\nboot=bukkit'
check "Paper and Folia boot script" scripts/boot-test.sh everything
check "Docs only" $'docs/src/content/docs/index.mdx\nREADME.md' "$nothing"
check "A decision" $'decisions/2026-11-24-decisions-one-file-each.md\nDECISIONS.md\nscripts/decisions.ts' "$nothing"
check "Plugins workflow" .github/workflows/plugins.yml everything
check "Boot workflow" .github/workflows/boot.yml $'tasks=\nchecks=\nboot=bukkit fabric proxy'
check "Another workflow" .github/workflows/docs.yml "$nothing"
check "A new top-level file" Makefile everything

if [[ $table == true ]]; then
  echo "| Change | Files | Builds and tests | Boots today's plugins | Boots the rewrite on |"
  echo "|---|---|---|---|---|"
  declare -A shown=()
  for row in "${rows[@]}"; do
    [[ -n ${shown[$row]:-} ]] || echo "$row"
    shown[$row]=1
  done
fi

if ((failures)); then
  echo "$failures failed" >&2
  exit 1
fi
echo "scripts/affected.sh: ${#reached[@]} build-logic files today's plugins reach, none on its rewrite-only list; ${#rows[@]} cases pass" >&2
