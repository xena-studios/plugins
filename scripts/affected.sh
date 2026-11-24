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
#
# The rules:
# - Anything everything is built on (today's core, build-logic/, gradle/, the root build
#   files, gradle.properties, the wrapper, scripts/, this workflow) means everything.
# - A file in one of the rewrite's modules inside core/ (core/core-common and the others with
#   a build file of their own) means those modules, and every folder whose build names one
#   in project(":core:..."). Today's plugins don't, so a change there skips their tests and
#   boots.
# - A file in a plugin's folder means that folder's projects: the plugin, its API and
#   whatever else is in there (xmoney's two consumers). So does its generated docs
#   folder, docs/src/generated/<plugin>/, which its golden tests compare against.
#   The manifest schema there, and the core's sample plugin's folder (xsample), mean the
#   rewrite's modules, whose sample compares them.
# - Then every folder whose build uses one of those projects, and so on: xRewind's API
#   brings in the sandbox, whose consumer compiles against it. Those links are read from
#   the build files' project(":...") lines, so a new plugin needs no edit here. The
#   conventions' own links (every plugin on :core and its own API) need none: the core
#   means everything, and a plugin's API is in its folder.
# - Markdown, the rest of the docs and the other workflows mean nothing.
# - Anything else, such as a new top-level folder, means everything: unsure is never
#   skipped.
#
# The boot tests need every plugin a booted plugin uses (the sandbox's consumer needs
# xRewind), so those are built too, with assemble: jars, without their tests.
set -euo pipefail

cd "$(dirname "$0")/.."

# The folders holding plugins (and the sandbox): top-level ones with a Gradle build,
# other than the two everything is built on.
units=()
for file in */build.gradle.kts; do
  unit=${file%%/*}
  [[ $unit == core || $unit == build-logic ]] || units+=("$unit")
done
declare -A known=()
for unit in "${units[@]}"; do known[$unit]=1; done

# The build files in a folder that belong to the root build: not under build/ or
# node_modules/, and not in a separate build of its own (a folder below with its own
# settings.gradle.kts, like the spikes in sandbox/m0).
buildfiles() {
  local separate
  separate=$(find "$1" -mindepth 2 -name settings.gradle.kts -not -path '*/build/*' -not -path '*/node_modules/*' -exec dirname {} \; | sed 's|$|/|')
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
declare -A changed=()
if [[ $all == false ]]; then
  while IFS= read -r file; do
    [[ -n $file ]] || continue
    top=${file%%/*}
    case $file in
      *.md) ;;
      # One of the rewrite's modules, if that folder has a build file; today's core otherwise.
      core/*/*)
        module=${file#core/}
        module=${module%%/*}
        if [[ -f core/$module/build.gradle.kts ]]; then rewriteChanged=true; else all=true; fi ;;
      core/* | build-logic/* | gradle/* | scripts/* | gradle.properties | gradlew | gradlew.bat \
        | .github/workflows/plugins.yml) all=true ;;
      # The rewrite's reference manifests that belong to no plugin yet: the schema, and the core's
      # sample plugin's, which its checkXenaManifest compares.
      docs/src/generated/manifest.schema.json | docs/src/generated/xsample/*) rewriteChanged=true ;;
      docs/src/generated/*)
        plugin=${file#docs/src/generated/}
        plugin=${plugin%%/*}
        if [[ -n ${known[$plugin]:-} ]]; then changed[$plugin]=1; else all=true; fi ;;
      docs/* | .github/* | LICENSE | renovate.json | .gitignore) ;;
      # A plugin's folder. Anything else, the root *.gradle.kts files among it, is everything.
      *)
        if [[ $file == */* && -n ${known[$top]:-} ]]; then changed[$top]=1; else all=true; fi ;;
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
      compile+=("$project:compileJava")
    done
    if [[ $unit == xmoney ]]; then
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
