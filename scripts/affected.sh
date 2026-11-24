#!/usr/bin/env bash
# Works out what the Plugins workflow builds, tests and boots for a pull request, from
# the files it changes, read one per line from stdin. With --all, everything.
#
#   scripts/affected.sh [--all] < changed-files
#
# Prints key=value lines for $GITHUB_OUTPUT:
#   units    the folders whose plugins are tested, space-separated (for the log)
#   tasks    Gradle tasks for the build job; empty when nothing needs building
#   compile  Gradle tasks for the newest-API compile; empty to skip it
#   checks   boot-checks files the main boot runs; empty to skip it
#   xmoney   true when xMoney's own boots run
#   boot     the rewrite's platforms the Boot workflow boots xSample and the rewritten plugins
#            (xUtilities) on, space-separated (bukkit, fabric); empty to skip them
#
# The rules:
# - Anything everything is built on (today's core, build-logic/, gradle/, the root build
#   files, gradle.properties, the wrapper, scripts/, this workflow) means everything, except
#   the parts only the rewrite uses (rewriteOnly below): its conventions and their classes in
#   build-logic/, its budgets and pins in gradle/, its boot and budget scripts. Those mean the
#   rewrite's modules and the boots in the Boot workflow they feed, never today's plugins'
#   builds and boots. scripts/affected-test.sh fails if today's conventions reach one of them.
# - A file in one of the rewrite's modules inside core/ (core/core-common and the others with
#   a build file of their own) means those modules, and every folder whose build names one
#   in project(":core:..."). Today's plugins don't, so a change there skips their tests and
#   boots.
# - A file in a plugin's folder means that folder's projects: the plugin, its API and
#   whatever else is in there (xmoney's two consumers, or a rewritten plugin's api, common
#   and platform folders; those skip the newest-API compile and the Paper boot, which are
#   for today's Java plugins). So does its generated docs
#   folder, docs/src/generated/<plugin>/, which its golden tests compare against.
#   The manifest schema there, and the core's sample plugin's folder (xsample), mean the
#   rewrite's modules, whose sample compares them.
# - Then every folder whose build uses one of those projects, and so on: xRewind's API
#   brings in the sandbox, whose consumer compiles against it. Those links are read from
#   the build files' project(":...") lines, so a new plugin needs no edit here. The
#   conventions' own links (every plugin on :core and its own API) need none: the core
#   means everything, and a plugin's API is in its folder.
# - The rewrite's boot matrix (the Boot workflow) boots a platform when its adapter, the sample
#   plugins' code for it, a rewritten plugin's code for it (<plugin>/bukkit/, <plugin>/fabric/;
#   its common/ and api/ mean both), or anything under them changed: core/platform-fabric/ means Fabric;
#   core/platform-bukkit/, the loader and the samples' bukkit/ folders mean Bukkit; any other
#   module in core/ means both, as does everything, and the Boot workflow itself.
# - Markdown (DECISIONS.md and decisions/ among it), scripts/decisions.ts, the rest of the docs
#   and the other workflows mean nothing.
# - Anything else, such as a new top-level folder, means everything: unsure is never
#   skipped.
#
# The boot tests need every plugin a booted plugin uses (the sandbox's consumer needs
# xRewind), so those are built too, with assemble: jars, without their tests.
set -euo pipefail

cd "$(dirname "$0")/.."

# What only the rewrite uses, by path, and what a change to it runs: `rewrite` the rewrite's
# modules (and whatever uses them), `bukkit` and `fabric` those boots in the Boot workflow,
# nothing for none. Anything not listed in build-logic/, gradle/ or scripts/ means everything,
# so a new file there is safe until it's added here.
declare -A rewriteOnly=(
  # The rewrite's conventions, and the classes only they use. Today's plugins use xena.plugin,
  # xena.api and xena.java, and what those reach (scripts/affected-test.sh works it out).
  [build-logic/src/main/kotlin/xena.fabric-mod.gradle.kts]="rewrite fabric"
  [build-logic/src/main/kotlin/xena.fetched-libraries.gradle.kts]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/xena.java8.gradle.kts]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/xena.kmp-api.gradle.kts]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/xena.kmp.gradle.kts]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/xena.kotlin-jvm.gradle.kts]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/xena.manifest.gradle.kts]="rewrite"
  [build-logic/src/main/kotlin/xena.r8.gradle.kts]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/xena.release.gradle.kts]="rewrite"
  [build-logic/src/main/kotlin/XenaFabricModExtension.kt]="rewrite fabric"
  [build-logic/src/main/kotlin/XenaR8Extension.kt]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/XenaReferenceExtension.kt]="rewrite"
  [build-logic/src/main/kotlin/XenaReleaseExtension.kt]="rewrite"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/BenchmarkBudget.kt]="rewrite"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/GeneratedReference.kt]="rewrite"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/JarBudget.kt]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/KotlinConventions.kt]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/LibraryPins.kt]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/PinnedLibraries.kt]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/R8Shrink.kt]="rewrite bukkit fabric"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/ReleaseAssemble.kt]="rewrite"
  [build-logic/src/main/kotlin/co/xenastudios/gradle/Translations.kt]="rewrite"
  # The rewrite's jar budgets (xena.r8), the pins of the libraries its jars download
  # (xena.fetched-libraries), and the runtime budgets the Boot workflow measures.
  [gradle/jar-budgets.txt]="rewrite bukkit fabric"
  [gradle/library-pins.txt]="rewrite bukkit fabric"
  [gradle/perf-budgets.txt]="bukkit"
  # The Boot workflow's scripts: the budgets and the real client, the servers' seeds, Fabric's
  # boots. scripts/boot-test.sh boots both, so it isn't here.
  [scripts/boot-fabric.sh]="fabric"
  # The scaffolder for the rewrite's layout, which the Scaffold workflow runs end to end.
  [scripts/new-rewrite-plugin.ts]=""
  # The decisions' index, which the Docs workflow checks.
  [scripts/decisions.ts]=""
)
rewriteOnlyDirs=(scripts/budgets/ scripts/boot-seeds/)

# The folders holding plugins (and the sandbox): top-level ones with a Gradle build,
# other than the two everything is built on, and plugins in the rewrite's layout
# (<plugin>/api, common and a folder per platform, with no build at the top).
units=()
declare -A known=() kmp=()
for file in */build.gradle.kts */common/build.gradle.kts; do
  [[ -f $file ]] || continue
  unit=${file%%/*}
  [[ $unit == core || $unit == build-logic || -n ${known[$unit]:-} ]] && continue
  units+=("$unit")
  known[$unit]=1
  [[ $file == */common/build.gradle.kts && ! -f $unit/build.gradle.kts ]] && kmp[$unit]=1
done

# The build files in a folder that belong to the root build: not under build/ or
# node_modules/, and not in a separate build of its own (a folder below with its own
# settings.gradle.kts, like the spikes in sandbox/m0).
# A rewritten plugin's fabric/ is a branch of core/platform-fabric's own build, not this one's.
buildfiles() {
  local separate
  separate=$(find "$1" -mindepth 2 -name settings.gradle.kts -not -path '*/build/*' -not -path '*/node_modules/*' -exec dirname {} \; | sed 's|$|/|')
  [[ -z ${kmp[$1]:-} || ! -d $1/fabric ]] || separate+=$'\n'"$1/fabric/"
  separate=$(sed '/^$/d' <<<"$separate")
  find "$1" -name build.gradle.kts -not -path '*/build/*' -not -path '*/node_modules/*' |
    { if [[ -n $separate ]]; then grep -vF "$separate" || true; else cat; fi; }
}

# The Gradle projects in a folder: it and each folder under it with a build file.
projects() {
  buildfiles "$1" | sed -e 's|/build.gradle.kts$||' -e 's|/|:|g' -e 's|^|:|' | sort
}

# uses[folder]: the other folders its builds name in project(":...").
# rewriteUsers[folder]: set if its builds use one of the rewrite's modules in core/.
declare -A uses=() rewriteUsers=()
for unit in "${units[@]}"; do
  uses[$unit]=$(buildfiles "$unit" | xargs grep -ohE 'project\(":[^"]+"\)' |
    sed -E 's/project\(":([^:"]+).*/\1/' | sort -u | grep -vxE "core|$unit" | tr '\n' ' ' || true)
  if buildfiles "$unit" | xargs grep -qE 'project\(":core:' 2>/dev/null; then rewriteUsers[$unit]=1; fi
done

# The rewrite's modules: each folder in core/ with its own build file (core/core-common, ...).
rewrite=$(projects core | grep -vx ':core' || true)

all=false
[[ ${1:-} == --all ]] && all=true
rewriteChanged=false
bootBukkit=false bootFabric=false
declare -A changed=()
if [[ $all == false ]]; then
  while IFS= read -r file; do
    [[ -n $file ]] || continue
    top=${file%%/*}
    # What only the rewrite uses.
    only=
    if [[ -v rewriteOnly[$file] ]]; then
      only=" ${rewriteOnly[$file]} "
    else
      for dir in "${rewriteOnlyDirs[@]}"; do [[ $file == "$dir"* ]] && only=" bukkit "; done
    fi
    if [[ -n $only ]]; then
      [[ $only != *" rewrite "* ]] || rewriteChanged=true
      [[ $only != *" bukkit "* ]] || bootBukkit=true
      [[ $only != *" fabric "* ]] || bootFabric=true
      continue
    fi
    case $file in
      *.md) ;;
      # One of the rewrite's modules, if that folder has a build file; today's core otherwise.
      core/*/*)
        module=${file#core/}
        module=${module%%/*}
        if [[ -f core/$module/build.gradle.kts ]]; then rewriteChanged=true; else all=true; fi
        case $file in
          core/platform-fabric/*) bootFabric=true ;;
          core/platform-bukkit/* | core/core-loader/* | core/core-testing/*/bukkit/*) bootBukkit=true ;;
          *) bootBukkit=true bootFabric=true ;;
        esac ;;
      core/* | build-logic/* | gradle/* | scripts/* | gradle.properties | gradlew | gradlew.bat \
        | .github/workflows/plugins.yml) all=true ;;
      # The rewrite's reference manifests that belong to no plugin yet: the schema, and the core's
      # sample plugin's, which its checkXenaManifest compares.
      docs/src/generated/manifest.schema.json | docs/src/generated/xsample/*) rewriteChanged=true ;;
      docs/src/generated/*)
        plugin=${file#docs/src/generated/}
        plugin=${plugin%%/*}
        if [[ -n ${known[$plugin]:-} ]]; then changed[$plugin]=1; else all=true; fi ;;
      .github/workflows/boot.yml) bootBukkit=true bootFabric=true ;;
      docs/* | .github/* | LICENSE | renovate.json | .gitignore) ;;
      # A plugin's folder. Anything else, the root *.gradle.kts files among it, is everything.
      # A rewritten plugin boots in the Boot workflow: its bukkit/ on Bukkit, its fabric/ on
      # Fabric, the rest (common/, api/, its lang files) on both.
      *)
        if [[ $file == */* && -n ${known[$top]:-} ]]; then
          changed[$top]=1
          if [[ -n ${kmp[$top]:-} ]]; then
            case $file in
              */bukkit/*) bootBukkit=true ;;
              */fabric/*) bootFabric=true ;;
              *.md) ;;
              *) bootBukkit=true bootFabric=true ;;
            esac
          fi
        else
          all=true
        fi ;;
    esac
  done
fi

# Everything: what a push to main runs.
if [[ $all == true ]]; then
  checks=()
  for unit in "${units[@]}"; do
    [[ $unit != xmoney && -f $unit/boot-checks.txt ]] && checks+=("$unit/boot-checks.txt")
  done
  echo "units=${units[*]}"
  echo "tasks=build"
  echo "compile=compileJava"
  echo "checks=${checks[*]}"
  echo "xmoney=true"
  echo "boot=bukkit fabric"
  exit 0
fi

# Every folder using the rewrite's modules, when one of them changed.
if [[ $rewriteChanged == true ]]; then
  for unit in "${!rewriteUsers[@]}"; do changed[$unit]=1; done
fi

# The changed folders, and every folder using one, until none is left to add.
grew=true
while [[ $grew == true ]]; do
  grew=false
  for unit in "${units[@]}"; do
    [[ -n ${changed[$unit]:-} ]] && continue
    for used in ${uses[$unit]}; do
      if [[ -n ${changed[$used]:-} ]]; then changed[$unit]=1; grew=true; break; fi
    done
  done
done

# What those use in turn, for the boot tests: jars only.
declare -A needed=()
grew=true
while [[ $grew == true ]]; do
  grew=false
  for unit in "${units[@]}"; do
    [[ -n ${changed[$unit]:-} || -n ${needed[$unit]:-} ]] || continue
    for used in ${uses[$unit]}; do
      if [[ -z ${changed[$used]:-} && -z ${needed[$used]:-} ]]; then needed[$used]=1; grew=true; fi
    done
  done
done

tested=() tasks=() compile=() checks=() xmoney=false
for unit in "${units[@]}"; do
  if [[ -n ${changed[$unit]:-} ]]; then
    tested+=("$unit")
    for project in $(projects "$unit"); do
      tasks+=("$project:build")
      # The newest-API compile is for today's Java plugins on Paper's API.
      [[ -n ${kmp[$unit]:-} ]] || compile+=("$project:compileJava")
    done
    if [[ -n ${kmp[$unit]:-} ]]; then
      # The rewrite's plugins aren't in the Paper boot yet: their jars and checks are per platform.
      :
    elif [[ $unit == xmoney ]]; then
      xmoney=true
    elif [[ -f $unit/boot-checks.txt ]]; then
      checks+=("$unit/boot-checks.txt")
    fi
  elif [[ -n ${needed[$unit]:-} ]]; then
    for project in $(projects "$unit"); do tasks+=("$project:assemble"); done
  fi
done
# The root's build: the copy-paste check, which reads every plugin's Java however few
# changed. The rewrite's modules are Kotlin, which it doesn't read.
((${#tested[@]})) && tasks=(":build" "${tasks[@]}")
# The rewrite's modules: compiled for the JVM and wasmWasi, tested on both (Node for
# wasmWasi, which Kotlin's Gradle plugin fetches), and their ABI checked.
if [[ $rewriteChanged == true ]]; then
  for project in $rewrite; do tasks+=("$project:build"); done
fi

echo "units=${tested[*]}"
echo "tasks=${tasks[*]}"
echo "compile=${compile[*]}"
echo "checks=${checks[*]}"
echo "xmoney=$xmoney"
boot=()
[[ $bootBukkit == false ]] || boot+=(bukkit)
[[ $bootFabric == false ]] || boot+=(fabric)
echo "boot=${boot[*]}"
