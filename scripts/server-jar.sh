#!/usr/bin/env bash
# Finds, downloads or builds the server jars the boot matrix runs that scripts/boot-test.sh
# doesn't download itself (it does Paper and Folia): Spigot through BuildTools, and the forks
# booted with a Bukkit or Paper plugin's jar. For the Boot workflow and by hand.
#
#   scripts/server-jar.sh --resolve <server> [version]   what would be fetched, as key=value lines
#   scripts/server-jar.sh <server> [version]             fetches it, and prints the same plus jar=
#
# <server> is spigot, pandaspigot, leaf, leaves, shreddedpaper or canvas. Without a version,
# Spigot is the newest Minecraft CI boots (xena.ciNewestMinecraft in gradle.properties), and a
# fork its own newest. The lines, for $GITHUB_OUTPUT:
#   version  the Minecraft version
#   build    what identifies the jar, for a cache key: the fork's build, or for Spigot its
#            build on hub.spigotmc.org and BuildTools' Jenkins build, so the cache refreshes
#            when either moves
#   java     the newest Java that server accepts, which the boot runs on
#   jar      (fetching only) the jar, in .cache/servers/jars/
#
# Every fork's jar is checked against the SHA-256 its source publishes (its API, or GitHub's for
# Leaf). Two sources publish none: Spigot's Jenkins records no fingerprint for BuildTools.jar
# (only for the Maven module's unshaded jar), so BuildTools comes over HTTPS from the build
# the cache key names and its SHA-256 is logged; and Canvas, whose API is down and whose
# Jenkins records none either, so the workflow lets its boot fail. BuildTools then checks what
# it downloads itself. It runs on $BUILDTOOLS_JAVA (a JDK home) or the java on the PATH: Java 8
# for 1.8.8 to 1.16.5, 25 for the newest.
set -euo pipefail

resolve_only=false
if [[ ${1:-} == --resolve ]]; then resolve_only=true; shift; fi
(($#)) || { sed -n '2,25p' "$0" >&2; exit 2; }
server=$1
version=${2:-}

root=$(cd "$(dirname "$0")/.." && pwd)
jars="$root/.cache/servers/jars"
mkdir -p "$jars"

# GitHub's API allows 60 calls an hour without a token; Actions gives one.
github=()
[[ -z ${GH_TOKEN:-${GITHUB_TOKEN:-}} ]] || github=(-H "Authorization: Bearer ${GH_TOKEN:-${GITHUB_TOKEN:-}}")

# The newest Java a Minecraft version's server accepts, as Spigot's launcher and Paper's
# check it: 8 below 1.13, 11 to 1.15, 16 for 1.16, 17 to 1.20.4, 21 to 1.21, 25 from 26.1.
java_for() {
  local v=$1 minor
  if [[ $v != 1.* ]]; then echo 25; return; fi
  minor=$(cut -d. -f2 <<<"$v")
  if ((minor < 13)); then echo 8
  elif ((minor < 16)); then echo 11
  elif ((minor == 16)); then echo 16
  elif ((minor < 20)) || [[ $v == 1.20 || $v =~ ^1\.20\.[1-4]$ ]]; then echo 17
  else echo 21
  fi
}

# Downloads $1 to $2 unless it's there, then checks it with `shasum -a $3` against $4, if any.
download() {
  local url=$1 file=$2 algorithm=$3 sum=$4
  if [[ ! -f $file ]]; then
    echo "Downloading ${file##*/}" >&2
    curl -fsSL -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  if [[ -z $sum ]]; then
    echo "${file##*/}: its source publishes no checksum to check it against" >&2
    return
  fi
  [[ $(shasum -a "$algorithm" "$file" | cut -d' ' -f1) == "$sum" ]] || { rm -f "$file"; echo "${file##*/} doesn't match its checksum" >&2; exit 1; }
}

# Newest first among version strings (1.21.11 before 1.21.4, 26.3 before 1.21.11).
newest() { sort -V | tail -1; }

url="" sum="" algorithm=256 file=""
case $server in
  spigot)
    version=${version:-$(sed -n 's/^xena\.ciNewestMinecraft=//p' "$root/gradle.properties")}
    spigot=$(curl -fsS "https://hub.spigotmc.org/versions/$version.json" | jq -r .name)
    tools=$(curl -fsS https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/buildNumber)
    build="$spigot-bt$tools"
    java=$(java_for "$version")
    file="$jars/spigot-$version-$build.jar" ;;
  pandaspigot)
    # PandaSpigot is 1.8.8 only; it runs on Java 8 to 21 (M0 booted it on 17 and 21).
    version=${version:-1.8.8}
    api="https://downloads.hpfxd.com/v2/projects/pandaspigot/versions/$version"
    found=$(curl -fsS "$api/builds" | jq -c '.builds | max_by(.build)')
    build=$(jq -r .build <<<"$found")
    sum=$(jq -r .downloads.paperclip.sha256 <<<"$found")
    url="$api/builds/$build/downloads/paperclip"
    java=21
    file="$jars/pandaspigot-$version-$build.jar" ;;
  leaves)
    api=https://api.leavesmc.org/v2/projects/leaves
    version=${version:-$(curl -fsS "$api" | jq -r '.versions[]' | newest)}
    found=$(curl -fsS "$api/versions/$version/builds/latest")
    build=$(jq -r .build <<<"$found")
    sum=$(jq -r .downloads.application.sha256 <<<"$found")
    url="$api/versions/$version/builds/$build/downloads/application"
    java=$(java_for "$version")
    file="$jars/leaves-$version-$build.jar" ;;
  shreddedpaper)
    api=https://api.multipaper.io/v2/projects/shreddedpaper
    version=${version:-$(curl -fsS "$api" | jq -r '.versions[]' | newest)}
    build=$(curl -fsS "$api/versions/$version" | jq -r '.builds | max')
    found=$(curl -fsS "$api/versions/$version/builds/$build")
    name=$(jq -r .downloads.application.name <<<"$found")
    sum=$(jq -r .downloads.application.sha256 <<<"$found")
    url="$api/versions/$version/builds/$build/downloads/$name"
    java=$(java_for "$version")
    file="$jars/shreddedpaper-$version-$build.jar" ;;
  leaf)
    # Leaf publishes on GitHub: a release per Minecraft version (ver-26.3), its jar replaced by
    # each build, with GitHub's SHA-256 of the asset.
    releases=$(curl -fsS ${github[@]+"${github[@]}"} "https://api.github.com/repos/Winds-Studio/Leaf/releases?per_page=30")
    version=${version:-$(jq -r '.[] | select(.prerelease | not) | .tag_name | select(startswith("ver-")) | ltrimstr("ver-")' <<<"$releases" | newest)}
    asset=$(jq -c --arg tag "ver-$version" '.[] | select(.tag_name == $tag) | .assets[] | select(.name | endswith(".jar"))' <<<"$releases" | head -1)
    [[ -n $asset ]] || { echo "Leaf has no release for $version" >&2; exit 1; }
    name=$(jq -r .name <<<"$asset")
    build=${name%.jar}
    build=${build##*-}
    sum=$(jq -r '.digest // "" | ltrimstr("sha256:")' <<<"$asset")
    url=$(jq -r .browser_download_url <<<"$asset")
    java=$(java_for "$version")
    file="$jars/leaf-$version-$build.jar" ;;
  canvas)
    # Canvas's own API (api.canvasmc.io) is down, so its Jenkins' last good build, which only
    # follows the newest Minecraft; the jar says which, once downloaded.
    jenkins=https://jenkins.canvasmc.io/job/Canvas
    found=$(curl -fsSg "$jenkins/lastSuccessfulBuild/api/json?tree=number,artifacts[fileName,relativePath]")
    build=$(jq -r .number <<<"$found")
    url="$jenkins/$build/artifact/$(jq -r '.artifacts[] | select(.fileName | endswith(".jar")) | .relativePath' <<<"$found" | head -1)"
    sum=""
    version=${version:-newest}
    java=25
    file="$jars/canvas-$build.jar" ;;
  *) echo "unknown server $server: spigot, pandaspigot, leaf, leaves, shreddedpaper or canvas" >&2; exit 2 ;;
esac

echo "version=$version"
echo "build=$build"
echo "java=$java"
[[ $resolve_only == false ]] || exit 0

if [[ $server == spigot ]]; then
  if [[ ! -f $file ]]; then
    # BuildTools from the Jenkins build named in the key.
    work="$root/.cache/buildtools"
    mkdir -p "$work"
    download "https://hub.spigotmc.org/jenkins/job/BuildTools/$tools/artifact/target/BuildTools.jar" "$work/BuildTools-$tools.jar" 256 ""
    echo "BuildTools $tools: SHA-256 $(shasum -a 256 "$work/BuildTools-$tools.jar" | cut -d' ' -f1)" >&2
    buildtools_java=${BUILDTOOLS_JAVA:+$BUILDTOOLS_JAVA/bin/}java
    echo "Building Spigot $version with BuildTools $tools on $("$buildtools_java" -version 2>&1 | head -1)" >&2
    (cd "$work" && "$buildtools_java" -jar "BuildTools-$tools.jar" --rev "$version" --compile spigot --output-dir "$work/out" >&2)
    mv "$work/out/spigot-$version.jar" "$file"
  fi
else
  download "$url" "$file" "$algorithm" "$sum"
fi
echo "jar=$file"
