# Decisions

<!-- Written by node scripts/decisions.ts from decisions/. Don't edit it by hand. -->

Why the plugins are built the way they are, oldest first. Each decision is a file in [decisions/](decisions/), named by its date. Release pull requests bring this list up to date, so the newest few may be in that folder alone until the next one; [CONTRIBUTING.md](CONTRIBUTING.md#decisions) says how to add one.

| Decision | Date |
|---|---|
| <a id="boot-tests-on-real-servers-2026-10-21"></a>[Boot tests on real servers](decisions/2026-10-21-boot-tests-on-real-servers.md) | 2026-10-21 |
| <a id="commands-are-registered-at-runtime-so-command-changes-apply-on-reload-2026-10-21"></a>[Commands are registered at runtime, so command changes apply on reload](decisions/2026-10-21-commands-are-registered-at-runtime.md) | 2026-10-21 |
| <a id="configs-are-generated-from-a-schema-in-code-2026-10-21"></a>[Configs are generated from a schema in code](decisions/2026-10-21-configs-are-generated-from-a-schema-in-code.md) | 2026-10-21 |
| <a id="jar-budgets-are-per-plugin-and-honest-2026-10-21"></a>[Jar budgets are per plugin, and honest](decisions/2026-10-21-jar-budgets-are-per-plugin.md) | 2026-10-21 |
| <a id="messages-share-a-theme-2026-10-21"></a>[Messages share a theme](decisions/2026-10-21-messages-share-a-theme.md) | 2026-10-21 |
| <a id="mockbukkit-for-command-logic-real-servers-for-the-rest-2026-10-21"></a>[MockBukkit for command logic, real servers for the rest](decisions/2026-10-21-mockbukkit-for-command-logic.md) | 2026-10-21 |
| <a id="one-minimum-version-paper-12111-java-21-2026-10-21"></a>[One minimum version: Paper 1.21.11, Java 21](decisions/2026-10-21-one-minimum-version.md) | 2026-10-21 |
| <a id="the-core-is-shaded-into-each-plugin-never-installed-on-its-own-2026-10-21"></a>[The core is shaded into each plugin, never installed on its own](decisions/2026-10-21-the-core-is-shaded-into-each-plugin.md) | 2026-10-21 |
| <a id="the-core-what-every-plugin-gets-2026-10-21"></a>[The core: what every plugin gets](decisions/2026-10-21-the-core.md) | 2026-10-21 |
| <a id="the-rewrite-ships-as-new-majors-one-plugin-at-a-time-2026-10-21"></a>[The rewrite ships as new majors, one plugin at a time](decisions/2026-10-21-the-rewrite-ships-as-new-majors.md) | 2026-10-21 |
| <a id="jar-budgets-are-a-tripwire-not-a-target-2026-10-22"></a>[Jar budgets are a tripwire, not a target](decisions/2026-10-22-jar-budgets-are-a-tripwire.md) | 2026-10-22 |
| <a id="renovate-for-dependencies-a-workflow-for-minecraft-2026-10-22"></a>[Renovate for dependencies, a workflow for Minecraft](decisions/2026-10-22-renovate-for-dependencies.md) | 2026-10-22 |
| <a id="xlimbo-is-paper-only-2026-10-22"></a>[xLimbo is Paper only](decisions/2026-10-22-xlimbo-is-paper-only.md) | 2026-10-22 |
| <a id="zchats-chat-path-2026-10-22"></a>[zChat's chat path](decisions/2026-10-22-zchats-chat-path.md) | 2026-10-22 |
| <a id="main-is-protected-and-checked-by-codeql-2026-10-23"></a>[Main is protected, and checked by CodeQL](decisions/2026-10-23-main-is-protected.md) | 2026-10-23 |
| <a id="a-config-that-cant-be-saved-is-still-used-2026-11-17"></a>[A config that can't be saved is still used](decisions/2026-11-17-a-config-that-cant-be-saved-is-still-used.md) | 2026-11-17 |
| <a id="a-shared-database-layer-with-no-drivers-shaded-in-2026-11-17"></a>[A shared database layer, with no drivers shaded in](decisions/2026-11-17-a-shared-database-layer.md) | 2026-11-17 |
| <a id="audit-follow-ups-split-words-vanish-reloads-2026-11-17"></a>[Audit follow-ups: split words, vanish, reloads](decisions/2026-11-17-audit-follow-ups.md) | 2026-11-17 |
| <a id="chat-pages-offline-players-warmups-and-vault-move-to-the-core-2026-11-17"></a>[Chat pages, offline players, warmups and Vault move to the core](decisions/2026-11-17-chat-pages.md) | 2026-11-17 |
| <a id="developer-apis-ship-inside-the-plugin-and-shared-code-lives-in-the-core-2026-11-17"></a>[Developer APIs ship inside the plugin, and shared code lives in the core](decisions/2026-11-17-developer-apis-ship-inside-the-plugin.md) | 2026-11-17 |
| <a id="in-game-edits-write-configyml-2026-11-17"></a>[In-game edits write config.yml](decisions/2026-11-17-in-game-edits-write-config-yml.md) | 2026-11-17 |
| <a id="network-mode-one-change-feed-in-the-database-2026-11-17"></a>[Network mode: one change feed in the database](decisions/2026-11-17-network-mode.md) | 2026-11-17 |
| <a id="releases-the-job-that-runs-gradle-cant-write-to-the-repo-2026-11-17"></a>[Releases: the job that runs Gradle can't write to the repo](decisions/2026-11-17-releases.md) | 2026-11-17 |
| <a id="xplots-plots-in-memory-found-by-arithmetic-2026-11-17"></a>[xPlots: plots in memory, found by arithmetic](decisions/2026-11-17-xplots.md) | 2026-11-17 |
| <a id="xrewinds-write-path-references-in-numbers-out-2026-11-17"></a>[xRewind's write path: references in, numbers out](decisions/2026-11-17-xrewinds-write-path.md) | 2026-11-17 |
| <a id="global-work-survives-an-empty-servers-pause-2026-11-18"></a>[Global work survives an empty server's pause](decisions/2026-11-18-global-work-survives-an-empty-servers-pause.md) | 2026-11-18 |
| <a id="xbackup-a-content-addressed-store-read-twice-restored-on-start-2026-11-18"></a>[xBackup: a content-addressed store, read twice, restored on start](decisions/2026-11-18-xbackup.md) | 2026-11-18 |
| <a id="xbans-whats-in-force-in-memory-history-append-only-2026-11-18"></a>[xBans: what's in force in memory, history append-only](decisions/2026-11-18-xbans.md) | 2026-11-18 |
| <a id="xmoney-the-economy-stands-in-for-vault-and-memory-is-the-truth-2026-11-18"></a>[xMoney: the economy stands in for Vault, and memory is the truth](decisions/2026-11-18-xmoney.md) | 2026-11-18 |
| <a id="xstats-counters-read-on-each-players-thread-leaderboards-in-memory-a-website-on-the-jdks-server-2026-11-18"></a>[xStats: counters read on each player's thread, leaderboards in memory, a website on the JDK's server](decisions/2026-11-18-xstats.md) | 2026-11-18 |
| <a id="xteleport-one-path-for-every-teleport-and-rtp-that-cant-lag-2026-11-18"></a>[xTeleport: one path for every teleport, and RTP that can't lag](decisions/2026-11-18-xteleport.md) | 2026-11-18 |
| <a id="xvote-a-receiver-of-our-own-and-votes-taken-before-theyre-paid-2026-11-18"></a>[xVote: a receiver of our own, and votes taken before they're paid](decisions/2026-11-18-xvote.md) | 2026-11-18 |
| <a id="more-stores-hangar-and-voxelshop-published-by-the-release-2026-11-23"></a>[More stores: Hangar and voxel.shop, published by the release](decisions/2026-11-23-more-stores.md) | 2026-11-23 |
| <a id="the-rewrite-every-platform-in-kotlin-one-plugin-at-a-time-2026-11-23"></a>[The rewrite: every platform, in Kotlin, one plugin at a time](decisions/2026-11-23-the-rewrite.md) | 2026-11-23 |
| <a id="a-real-client-in-the-boot-tests-one-mineflayer-bot-on-spigot-188-and-paper-12111-2026-11-24"></a>[A real client in the boot tests: one mineflayer bot on Spigot 1.8.8 and Paper 1.21.11](decisions/2026-11-24-a-real-client-in-the-boot-tests.md) | 2026-11-24 |
| <a id="budgets-the-yaml-parser-downloaded-r8-on-the-sample-jars-the-stdlib-loaded-off-the-server-thread-2026-11-24"></a>[Budgets: the YAML parser downloaded, R8 on the sample jars, the stdlib loaded off the server thread](decisions/2026-11-24-budgets.md) | 2026-11-24 |
| <a id="commands-are-data-that-runs-and-the-docs-read-the-same-data-2026-11-24"></a>[Commands are data that runs, and the docs read the same data](decisions/2026-11-24-commands-are-data-that-runs.md) | 2026-11-24 |
| <a id="decisions-one-file-each-and-rewrite-only-pull-requests-skip-todays-boots-2026-11-24"></a>[Decisions one file each, and rewrite-only pull requests skip today's boots](decisions/2026-11-24-decisions-one-file-each.md) | 2026-11-24 |
| <a id="fabric-jars-relocate-their-core-one-convention-relocation-then-r8-then-loom-2026-11-24"></a>[Fabric jars relocate their core: one convention, relocation then R8 then Loom](decisions/2026-11-24-fabric-jars-relocate-their-core.md) | 2026-11-24 |
| <a id="gradle-reads-maven-central-through-googles-mirror-2026-11-24"></a>[Gradle reads Maven Central through Google's mirror](decisions/2026-11-24-gradle-reads-maven-central-through-googles-mirror.md) | 2026-11-24 |
| <a id="m0-java-8-bytecode-each-servers-own-text-and-pumpkin-waits-on-upstream-2026-11-24"></a>[M0: Java 8 bytecode, each server's own text, and Pumpkin waits on upstream](decisions/2026-11-24-m0.md) | 2026-11-24 |
| <a id="one-release-workflow-every-jar-built-once-attested-then-each-store-alone-2026-11-24"></a>[One release workflow: every jar built once, attested, then each store alone](decisions/2026-11-24-one-release-workflow.md) | 2026-11-24 |
| <a id="platform-fabric-a-build-of-its-own-brigadier-without-cloud-and-fabric-apis-callbacks-only-2026-11-24"></a>[platform-fabric: a build of its own, Brigadier without Cloud, and Fabric API's callbacks only](decisions/2026-11-24-platform-fabric.md) | 2026-11-24 |
| <a id="security-ci-codeql-reads-the-kotlin-jazzer-fuzzes-the-parsers-2026-11-24"></a>[Security CI: CodeQL reads the Kotlin, Jazzer fuzzes the parsers](decisions/2026-11-24-security-ci.md) | 2026-11-24 |
| <a id="smaller-jars-r8s-marker-stripped-a-short-package-keep-rules-only-where-something-reads-them-2026-11-24"></a>[Smaller jars: R8's marker stripped, a short package, keep rules only where something reads them](decisions/2026-11-24-smaller-jars.md) | 2026-11-24 |
| <a id="startup-under-budget-paper-gets-the-libraries-from-our-loader-and-the-startup-classes-load-ahead-2026-11-24"></a>[Startup under budget: Paper gets the libraries from our loader, and the startup classes load ahead](decisions/2026-11-24-startup-under-budget.md) | 2026-11-24 |
| <a id="the-admin-subcommands-checks-before-a-paste-imports-page-by-page-migrations-checked-by-count-2026-11-24"></a>[The admin subcommands: checks before a paste, imports page by page, migrations checked by count](decisions/2026-11-24-the-admin-subcommands.md) | 2026-11-24 |
| <a id="the-boot-matrix-every-server-the-rewrite-ships-for-forks-from-their-own-downloads-and-a-second-plugin-for-the-family-2026-11-24"></a>[The boot matrix: every server the rewrite ships for, forks from their own downloads, and a second plugin for the family](decisions/2026-11-24-the-boot-matrix.md) | 2026-11-24 |
| <a id="the-build-the-configuration-cache-everywhere-r8-on-every-rewrite-jar-a-scaffolder-for-the-new-layout-2026-11-24"></a>[The build: the configuration cache everywhere, R8 on every rewrite jar, a scaffolder for the new layout](decisions/2026-11-24-the-build.md) | 2026-11-24 |
| <a id="the-core-on-bukkit-two-apis-to-compile-against-every-path-picked-by-class-checks-2026-11-24"></a>[The core on Bukkit: two APIs to compile against, every path picked by class checks](decisions/2026-11-24-the-core-on-bukkit.md) | 2026-11-24 |
| <a id="the-cores-library-loader-java-on-its-own-one-cache-pins-the-build-checks-2026-11-24"></a>[The core's library loader: Java on its own, one cache, pins the build checks](decisions/2026-11-24-the-cores-library-loader.md) | 2026-11-24 |
| <a id="the-family-one-folder-one-board-one-player-cache-2026-11-24"></a>[The family: one folder, one board, one player cache](decisions/2026-11-24-the-family.md) | 2026-11-24 |
| <a id="the-platform-layer-folias-model-everywhere-events-by-key-completablefuture-on-the-jvm-2026-11-24"></a>[The platform layer: Folia's model everywhere, events by key, CompletableFuture on the JVM](decisions/2026-11-24-the-platform-layer.md) | 2026-11-24 |
| <a id="the-rewrite-grows-inside-todays-repo-module-by-module-2026-11-24"></a>[The rewrite grows inside today's repo, module by module](decisions/2026-11-24-the-rewrite-grows-inside-todays-repo.md) | 2026-11-24 |
| <a id="the-rewrites-config-a-schema-in-kotlin-yaml-read-by-snakeyaml-engine-kmp-and-written-by-us-2026-11-24"></a>[The rewrite's config: a schema in Kotlin, YAML read by snakeyaml-engine-kmp and written by us](decisions/2026-11-24-the-rewrites-config.md) | 2026-11-24 |
| <a id="the-rewrites-database-one-writer-that-never-repeats-a-commit-statements-per-dialect-records-for-pumpkin-2026-11-24"></a>[The rewrite's database: one writer that never repeats a commit, statements per dialect, records for Pumpkin](decisions/2026-11-24-the-rewrites-database.md) | 2026-11-24 |
| <a id="the-sites-skeleton-pages-from-the-manifest-a-picker-from-data-old-majors-in-their-own-folder-2026-11-24"></a>[The site's skeleton: pages from the manifest, a picker from data, old majors in their own folder](decisions/2026-11-24-the-sites-skeleton.md) | 2026-11-24 |
| <a id="the-update-checker-and-bstats-in-the-rewrites-core-modrinths-version-types-as-channels-bstats-on-bukkit-only-2026-11-24"></a>[The update checker and bStats in the rewrite's core: Modrinth's version types as channels, bStats on Bukkit only](decisions/2026-11-24-the-update-checker-and-bstats-in-the-rewrites-core.md) | 2026-11-24 |
| <a id="translations-english-in-code-other-languages-as-files-drawn-once-per-language-2026-11-24"></a>[Translations: English in code, other languages as files, drawn once per language](decisions/2026-11-24-translations.md) | 2026-11-24 |
