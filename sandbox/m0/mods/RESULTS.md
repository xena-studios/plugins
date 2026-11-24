# M0 spike: Fabric, Stonecutter, Sponge and the stores

This covers part of #121. It ran on 2026-11-23 on macOS arm64 with Temurin 17, 21 and 25. Nothing here is wired into the root build.

```
sandbox/m0/mods/          Stonecutter build: one Kotlin source, three Fabric jars
  settings.gradle.kts     Stonecutter 0.10, loom-back-compat 0.4.3, versions 1.20.1 / 1.21.11 / 26.3
  stonecutter.properties.toml   the dependency versions for each Minecraft version
  build.gradle.kts        shared by every version
  src/main/kotlin/.../XenaSpike.kt
  sponge/                 its own build (../gradlew -p sponge): a Sponge API 17 plugin
```

To build it: `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew collect` writes the jars to `build/libs/`. For Sponge, run `./gradlew -p sponge build`.

## 1. A server-side Kotlin Fabric mod with Cloud 2 and Adventure

**Answer: yes, on all three versions.** The mod registers `/xspike hello [name]` through **cloud-fabric**, the Cloud 2 integration. The reply is MiniMessage (a gradient, bold text and placeholders) sent through **adventure-platform-fabric**. The Kotlin stdlib comes from **fabric-language-kotlin** (entrypoint `"adapter": "kotlin"` on a Kotlin `object`). The mod adds no registry entries, and vanilla clients joined.

| | 1.20.1 | 1.21.11 | 26.3 (newest stable) |
|---|---|---|---|
| Server JVM | Java 17 | Java 21 | Java 25 |
| Loom variant | fabric-loom-remap, Mojang mappings | fabric-loom-remap, Mojang mappings | fabric-loom (26.1+ is unobfuscated) |
| Fabric API | 0.92.12+1.20.1 | 0.141.6+1.21.11 | 0.167.0+26.3 |
| adventure-platform-fabric (bundled Adventure) | 5.9.0 (Adventure 4.14) | 6.8.0 (4.25) | 7.2.0 (**Adventure 5.2**) |
| cloud-fabric | 2.0.0-beta.1 (built for 1.20.4, see below) | 2.0.0-beta.15 | 2.1.0 |
| Boot | `XSPIKE LOADED on Minecraft 1.20.1 (Kotlin 2.4.21)`, Done 3.8 s | Done 0.9 s | Done 0.7 s |
| Console `xspike hello Console` | `Hello, Console! (Kotlin + Cloud + Adventure on 1.20.1)` | same | same |
| Vanilla client joins and runs the command | mineflayer 4.39 (vanilla protocol): spawned, got a gradient JSON message | mineflayer: same | **real vanilla 26.3 client** (HeadlessMC 3.0.0-RC9, offline, no render): `Offline joined the game`, stayed connected, `execute as Offline run xspike hello` ran for it |
| **Mod jar** | **1,431,347 B** | **1,678,826 B** | **1,631,244 B** |

What makes up each jar:
- Our own code is about 6 KB: one 5.7 KB class plus `fabric.mod.json`.
- The rest is jar-in-jar: adventure-platform-fabric (800 KB to 1.0 MB, with Adventure and MiniMessage inside) and cloud-fabric (670 to 690 KB, with cloud-core, cloud-brigadier and geantyref inside).
- Server owners install Fabric API (1.8 to 2.4 MB) and fabric-language-kotlin (8.1 MB, shared by every Kotlin mod) as usual.

Findings that matter for the roadmap:
- **Both libraries are pinned to one Minecraft version per release.** Their own `fabric.mod.json` files show it:
  - adventure-platform-fabric 6.8.0: `"minecraft": ">1.21.10 <1.22"`; 7.0.0: `~26.2-`; 7.2.0: `~26.3-`.
  - cloud-fabric: 2.0.0 requires `>=26.2`; 2.1.0 requires `>=26.3`; beta.15 requires `>=1.21.11`.
  - So every Minecraft version (in practice every minor) needs its own jar with its own pair of libraries. One jar cannot cover a range.
- **No Cloud 2 release targets 1.20.1.**
  - Cloud 2 for modded Minecraft started at 2.0.0-beta.1 (January 2024, built for 1.20.4). It declares `minecraft >1.18.2` and needs a `me.lucko:fabric-permissions-api:0.2-SNAPSHOT` that no repository has any more (the build excludes it because the jar carries it inside).
  - It loaded and ran a literal plus a string argument on 1.20.1. Arguments specific to Minecraft (entities, items, positions) are **untested** on 1.20.1.
- **Adventure differs across these versions:** API 4.14 on 1.20.1 and **Adventure 5** on 26.3. Our usage (MiniMessage, `Placeholder`, `Audience.sendMessage`) compiled unchanged against both. The 5.9 → 6.0 package move (`platform.fabric.FabricServerAudiences` → `platform.modcommon.MinecraftServerAudiences`) is the only conditional in the source.
- **Fabric's bytecode follows Minecraft, not a single "Java 21":** 17 for 1.20.1, 21 for 1.20.5 to 1.21.x, and 25 for 26.1+. Minecraft 26.1+ requires Java 25.

**Recommendation:**
- Keep fabric-language-kotlin, as planned.
- Keep Cloud 2 on Fabric for 1.21.x and 26.x. For 1.20.1 (and Forge 1.20.1), either accept cloud-fabric beta.1 after testing the argument types we use, or put cloud-core and cloud-brigadier behind our own small `CommandManager`. That is the approach the Sponge spike below proves.
- For text, consider having `platform-fabric` draw our markup straight to vanilla `Component` (a few lines per version through Stonecutter) instead of bundling adventure-platform-fabric:
  - It saves about 0.8 to 1 MB per jar.
  - It removes one library that is pinned to a single Minecraft version.
  - It avoids the Adventure 4 / 5 split.
  - Keep Adventure where the platform ships it (Paper, Sponge, Velocity).
- Change "Java 21 for Fabric and NeoForge" in ROADMAP.md to "the Minecraft version's Java: 17 / 21 / 25".

## 2. Stonecutter: one source, three versions

**Answer: low pain.** The setup is Stonecutter 0.10, `dev.kikugie.loom-back-compat` 0.4.3 and Loom 1.19.4, on Gradle 9.9.1.
- loom-back-compat picks `fabric-loom-remap` below 26.1 and `fabric-loom` on 26.1+. It also lets `modImplementation`/`include` work on both, and applies Mojang mappings on the obfuscated versions.
- So the same Mojang names (`CommandSourceStack`, `textName`, `server`) compile on 1.20.1, 1.21.11 and 26.3.
- The Kotlin source needed **one** conditional, the Adventure import and call (`//? if modcommon {`), plus a `/*$ minecraft*/` swap.
- Everything that differs is in `stonecutter.properties.toml`: the Fabric API, Adventure and Cloud versions, and the `minecraft` range for `fabric.mod.json`.
- `Set active project to 1.20.1` rewrote the comments in the Kotlin source correctly, and `Reset active project` put them back.

Timings (M-series Mac, warm Gradle daemon):

| Build | Time |
|---|---|
| Cold first build: three Minecraft versions downloaded and remapped, all dependencies | 151 s (plus 85 s for an earlier failed attempt, on the missing snapshot) |
| `collect` with nothing changed | about 2 s |
| `collect` after touching the source | about 2 s |
| `clean collect --no-build-cache --rerun-tasks`: three compileKotlin, three jar, two remapJar | 3.3 s |

Each version configures Loom at configuration time; the Minecraft setup is cached in `~/.gradle/caches/fabric-loom`.

Caveats:
- The pain is not Stonecutter. It is the per-version dependency matrix (point 1) and checking each library's own `minecraft` range by hand.
- A real `platform-fabric` with event hooks across 1.20.1 → 26.x will need more conditionals than this one-command mod. Name changes such as `ResourceLocation` → `Identifier` in 1.21.11 are handled by Stonecutter's `replacements`, as its template shows.

**Recommendation:**
- Adopt Stonecutter with loom-back-compat for `platform-fabric`, with Mojang mappings everywhere.
- Pick versions by library line: one node per adventure-platform/cloud-fabric release line we support.
- In CI, cache `~/.gradle/caches/fabric-loom`, because the cold setup dominates.

## 3. Sponge API 8+ (SpongeVanilla)

**Answer: it boots and works, but Cloud 2 has no Sponge 8+ module.**
- Incendo publishes only `cloud-sponge7` (API 7). The API 8 port (Incendo/cloud-minecraft PR #21) was closed unmerged in January 2024.
- The spike puts `cloud-core` 2.0.0 behind a 30-line `CommandManager` that registers one Sponge `Command.Raw` per root literal. It handles execution and suggestions.
- Sponge's native Adventure works directly: SpongeAPI 17 ships Adventure 4.26.1 with MiniMessage, and `CommandCause.audience().sendMessage(...)` needs no platform adapter.
- Plugin and server: Kotlin 2.4.21 plugin, SpongeGradle 2.5.1, SpongeVanilla **1.21.10-17.0.0** (the recommended build) on Java 21.
- Log evidence:
  - `Loaded plugin(s): [..., xenaspike]`
  - `XSPIKE LOADED on Sponge API 17.1.0-SNAPSHOT (Kotlin 2.4.21)`
  - Console: `Hello, Console! (Kotlin + Cloud + Adventure on Sponge 1.21.10)`
  - mineflayer joined as a vanilla client and received the gradient message.
- Jar: **2,012,737 B**. Sponge has no library loader and no Kotlin language provider, so the stdlib is shaded, relocated and shrunk by Shadow `minimize()`: about 1.25 MB of Kotlin and 0.49 MB of Cloud plus geantyref (compressed).

**Recommendation:**
- Sponge stays feasible.
- Budget a small `platform-sponge` Cloud bridge of our own. With the raw-command approach, players see only plain-string suggestions, not native argument types, until a proper Brigadier bridge exists.
- Shrink the stdlib with R8/ProGuard rather than Shadow `minimize()`, which kept 675 Kotlin classes.

## 4. CurseForge upload API from one release workflow

This part comes from the documentation only. No account or token was used. The only network calls were unauthenticated GETs to see which hosts exist.

**Answer: yes. It is one API and one account token, used against different hosts depending on the project class.**

Source: https://support.curseforge.com/en/support/solutions/articles/9000197321-curseforge-api, the "legacy" API, which is still the current one.

**Endpoints:**
- `GET /api/game/versions`, `GET /api/game/version-types` (undocumented, but tools use it), `GET /api/game/dependencies`.
- `POST /api/projects/{id}/upload-file`, multipart:
  - `file`
  - `metadata` JSON:
    - `changelog`, `changelogType` (text, html or markdown), `displayName`, `releaseType` (alpha, beta or release)
    - `gameVersions: [ids]`, or the newer `gameVersionNames: ["Server", "26.1.2", ...]`
    - `relations.projects[{slug, type}]`, `isMarkedForManualRelease`
- Auth: the `X-Api-Token` header.

**Game versions:** loaders, Java versions and environments are all "game versions" of different types.
- A mod file lists the Minecraft version ids, the loader (Fabric, NeoForge or Forge), the environment (Server) and optionally the Java version.
- CurseForgeGradle's README says CurseForge requires environment tags on all Minecraft mods from 2026-07-15.

Hosts, checked without a token:

| Files | Host | Version ids |
|---|---|---|
| Fabric / NeoForge / Forge mods | `https://minecraft.curseforge.com/api` (401 without a token, so it is live) | Minecraft versions + modloader + environment (+ java) |
| Bukkit plugins (dev.bukkit.org, "Bukkit Plugins" class) | `https://dev.bukkit.org/api` (401, live) with the same token | type 1 "Bukkit" version ids (e.g. 14417 = 1.21.11, per the public CI scripts of BrendonButler/Shops); no Java/loader types |
| Hytale | `https://legacy.curseforge.com/api` (401, live; it detects game and project from the project id). `hytale.curseforge.com/api` returns a redirect, not an API | Two tools hard-code 14284. **Not verified** in any official doc |

The Bukkit host and Hytale's id come from tool source and public CI scripts (HytalePublisher, MCReleaser, mc-publish, SetHomesTwo), not from CurseForge's docs.

Consequences:
- Each plugin needs **separate CurseForge projects** for each class: a Bukkit Plugins project on dev.bukkit, a Mods project for the Fabric/NeoForge/Forge jars, and a Hytale project.
- Each project has its own id, stored next to `xena.voxelId`/`xena.hangarId`.

Tools:

| Tool | Latest | Mods | Bukkit (dev.bukkit) | Hytale | Notes |
|---|---|---|---|---|---|
| modmuss50 `me.modmuss50.mod-publish-plugin` | 2.2.1 | yes, plus Modrinth/GitHub/Discord in one DSL | no: it filters version types to `minecraft*` and requires a client/server flag | no | `apiEndpoint` can be overridden, but version lookup still fails for Bukkit |
| Darkhax `net.darkhax.curseforgegradle` | 1.3.33 | yes | probably (sends `gameVersionNames`, default `legacy.curseforge.com`, "supports all games") | probably | untested |
| Kir-Antipov `mc-publish` (GitHub Action) | v3.3.2 | yes | tries Bukkit-type ids but posts to minecraft.curseforge.com | no | an Action, not Gradle; host not configurable |
| Modrinth `com.modrinth.minotaur` | 2.10.0 (already used here) | Modrinth only | Modrinth only (paper/bukkit/spigot loaders) | n/a | keep it for Modrinth |

**Recommendation:**
- Do not adopt a third-party CurseForge plugin. Add a `CurseForgePublish` task to build-logic, next to `VoxelPublish` and `Stores`, which already talk plain HTTP and check for an existing version so a rerun is safe. It should:
  - pick the host from the file's class (dev.bukkit / minecraft / legacy for Hytale);
  - resolve ids through `/api/game/versions` and `/api/game/version-types`, or send `gameVersionNames`;
  - upload with `upload-file`.
- That is about 150 lines, and it covers Bukkit, every loader and Hytale the same way.
- Keep Minotaur for Modrinth.
- Before M1, verify with a real token: a dry upload to a test project on each of the three hosts, and the Hytale game-version id.
- If we'd rather not maintain the task, CurseForgeGradle is the only off-the-shelf candidate that might cover all three, but it is unproven here.

## 5. SpigotMC and BuiltByBit

From the documentation only.

**SpigotMC has no upload API.**
- Its only API, XenforoResourceManagerAPI (https://github.com/SpigotMC/XenforoResourceManagerAPI), has 8 read-only actions: listResources, getResource, getResourcesByAuthor, listResourceCategories, getResourceUpdate, getResourceUpdates, getAuthor, findAuthor. It states "All requests must currently be sent via GET", with no authentication.
- Spiget is a third-party read-only mirror.
- There is no official statement saying "there will never be one". People who automate Spigot uploads drive a browser (Playwright), which we shouldn't do.
- The roadmap's plan stands: the listing downloads from GitHub, and the release writes the update post to paste.

**BuiltByBit: posting updates needs Ultimate.**
- The official OpenAPI spec (https://github.com/BuiltByBit/api-openapi-spec, updated 2026-10-28) says "All operations not tagged 'free' require an active Ultimate subscription or invite-only permissions", and **no operation is tagged free**.
- The update endpoint is `POST /v2/resources/creator/update`:
  - required: `resource_id`, `version_name`, `file{name, data base64}`
  - optional: an update post in BBCode
  - the body is capped at 100 MB, so a raw file is limited to about 67 MB.
- The rest of v2 covers resources, versions, updates, licenses, purchases, reviews, add-ons, coupons and analytics.
- Rate limits are deliberately unpublished; the official wrapper honours 429 `Retry-After`.
- The wiki pages (builtbybit.com/wiki/ultimate-api, api-v2) returned 403 to the fetcher.
- A free account gets no API that could publish. The roadmap line stands as written.

## Exact commands used

```
# Fabric jars, then three servers (Fabric launcher for loader 0.20.1, installer 1.1.2, with Fabric API,
# fabric-language-kotlin 1.14.2+kotlin.2.4.21 and the mod), started outside the repo:
./gradlew collect
java -jar server.jar nogui      # Java 17 / 21 / 25 for 1.20.1 / 1.21.11 / 26.3
> xspike hello Console
node join.js <port> <version>   # mineflayer 4.39.0, offline: join, /xspike hello Zach, wait for the reply
headlessmc launch 26.3 -lwjgl --offline --server=127.0.0.1:<port>   # real vanilla 26.3 client
# Sponge:
./gradlew -p sponge build
java -jar spongevanilla-1.21.10-17.0.0-universal.jar nogui
```

Every server and client was stopped afterwards.
