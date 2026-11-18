#!/usr/bin/env bash
# Finds Minecraft versions Paper has released as stable since the newest one in
# xena.gameVersions, and moves the repo onto them: appends them to
# xena.gameVersions, and points the boot tests and the newest-API compile at the
# newest (xena.ciNewestMinecraft and xena.ciNewestPaperApi, which
# .github/workflows/plugins.yml reads). Everything is in gradle.properties: the
# workflow's token can't push changes to workflow files. Prints the new versions,
# or nothing if there are none. Needs curl and jq.
set -euo pipefail
cd "$(dirname "$0")/.."

props=gradle.properties
fill=https://fill.papermc.io/v3/projects/paper

listed=$(sed -n 's/^xena\.gameVersions=//p' "$props")
newest=${listed##*,}

# Releases only (no -pre, -rc), newer than what's listed, oldest first.
candidates=$({ echo "$newest"; curl -fsS "$fill" | jq -r '.versions[][]' | grep -E '^[0-9]+(\.[0-9]+)+$'; } |
  sort -uV | sed "1,/^${newest//./\\.}\$/d")

# A version counts once Paper has a stable build of it.
added=()
for v in $candidates; do
  channel=$(curl -fsS "$fill/versions/$v/builds/latest" | jq -r '.channel')
  [[ $channel == STABLE ]] && added+=("$v")
done
((${#added[@]})) || exit 0
top=${added[${#added[@]}-1]}

# The newest stable paper-api build for that version, for the newest-API compile.
api=$(curl -fsS https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml |
  grep -oE "<version>${top//./\\.}\.build\.[0-9]+-stable</version>" | sed -E 's/<\/?version>//g' | sort -V | tail -1)
[[ -n $api ]] || { echo "no stable paper-api build of $top yet" >&2; exit 0; }

sed -i.bak "s/^xena\.gameVersions=.*/&,$(IFS=,; echo "${added[*]}")/" "$props"
sed -i.bak "s/^xena\.ciNewestMinecraft=.*/xena.ciNewestMinecraft=$top/" "$props"
sed -i.bak "s/^xena\.ciNewestPaperApi=.*/xena.ciNewestPaperApi=$api/" "$props"
rm -f "$props.bak"

printf '%s\n' "${added[@]}"
