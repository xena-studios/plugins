# Contributing

Thanks for helping with xUtilities, zChat, xLimbo, xRewind, xPlots, xTeleport, xVote, xBans, xMoney, xBackup and xStats. Everyone follows the [code of conduct](https://github.com/xena-studios/.github/blob/main/CODE_OF_CONDUCT.md). Security problems go to [SECURITY.md](SECURITY.md), never a public issue.

## Issues

[Open an issue](https://github.com/xena-studios/plugins/issues/new/choose) and pick a form; it asks for what we need. For questions, see [SUPPORT.md](SUPPORT.md).

Spotted a mistake in the docs? Most pages at [plugins.xenastudios.co](https://plugins.xenastudios.co) have an **Edit page** link.

## Building

Needs JDK 21 or newer, plus JDK 25 to build against the newest Paper or boot-test on 26.x servers. The docs need Node 24 (see [docs/README.md](docs/README.md)).

```sh
./gradlew build                   # compile, test, check each jar's size budget
./gradlew :<plugin>:runServer     # a Paper server with the plugin, in <plugin>/run/
./gradlew test -PupdateGolden     # rewrite docs/src/generated/ after changing a default config
./gradlew compileJava -Pxena.newestPaper=<paper-api version>   # build against the newest Paper
./gradlew :<plugin>:api:apiDump   # after a deliberate API change: rewrite <plugin>/api/<plugin>-api.api
./gradlew :core:core-api:updateKotlinAbi   # the same for a Kotlin API module of the rewrite
./gradlew build -PupdatePins      # after bumping a library downloaded at runtime: rewrite gradle/library-pins.txt
scripts/boot-test.sh --project paper --version 1.21.11 \
  --run "sandbox selftest" --expect "SELFTEST PASS" sandbox/build/libs/sandbox-0.0.0.jar
```

The rewrite's loader (`core/core-loader`) is boot-tested on servers that can't be downloaded, with a jar from [BuildTools](https://www.spigotmc.org/wiki/buildtools/) and the JDK that server needs:

```sh
./gradlew :core:core-loader:sandbox:assemble
scripts/boot-test.sh --server spigot-1.8.8.jar --java <JDK 8 home> --jvm -DIReallyKnowWhatIAmDoingISwear=true \
  --checks core/core-loader/sandbox/boot-checks.txt --logged "through URLClassLoader.addURL" \
  core/core-loader/sandbox/build/libs/LoaderSandbox.jar
```

`--logged` checks how the stdlib got there: `through URLClassLoader.addURL` on Java 8, `through the JDK's trusted lookup` on 9 and later (Spigot 1.8.8 on 17), `Loaded library` where the server reads `libraries:` (Spigot 1.16.5 on Java 16, Paper). `-DIReallyKnowWhatIAmDoingISwear` skips old Spigot's 20-second wait over its age.

On Paper, `boot-test.sh` then boots a second, empty server, waits for Paper to pause it, and runs every Xena plugin's `/<plugin> reload`. A paused server doesn't run the global region scheduler, so global work goes through the core's `Tasks`, which Paper keeps running.

The rewrite's Bukkit platform (`core/platform-bukkit`) is boot-tested with xSample built as a Bukkit plugin on it. Its checks run the platform's own (schedulers, files, the event bridge) at startup, then console commands; `boot.sh` passes them, and `runServer` or `runFolia` starts a server with it in `core/core-testing/sample/bukkit/run/`:

```sh
./gradlew :core:core-testing:sample:bukkit:assemble
core/core-testing/sample/bukkit/boot.sh folia 26.3 --java <JDK 25 home>
core/core-testing/sample/bukkit/boot.sh spigot-1.8.8.jar --java <JDK 8 home>
```

The Boot workflow runs these on every server the rewrite ships for, and on Fabric through `scripts/boot-fabric.sh`: per PR the touched platforms at their floor and newest, the whole matrix nightly. `scripts/server-jar.sh` gets the servers it boots with a jar: Spigot through BuildTools (`BUILDTOOLS_JAVA` is the JDK it runs on), and PandaSpigot, Leaf, Leaves, ShreddedPaper and Canvas from their own downloads. The family test boots xSample beside xTwin (`core/core-testing/twin`), a second plugin with its own relocated core, and checks they share the family's board, player cache and folder:

```sh
./gradlew :core:core-testing:twin:bukkit:assemble
core/core-testing/sample/bukkit/boot.sh "$(scripts/server-jar.sh spigot 1.8.8 | sed -n 's/^jar=//p')" --java <JDK 8 home> \
  --checks core/core-testing/twin/family-checks.txt core/core-testing/twin/bukkit/build/r8/XTwin.jar
```

`boot.sh` boots the jar R8 shrank (`build/r8/XSample.jar`), as a release ships; the YAML parser and the stdlib are downloaded (`libraries:`, or the loader below 1.16.5). The Fabric sandbox is built and booted the same way, from its own build: `./gradlew -p core/platform-fabric build collect`, then `scripts/boot-fabric.sh --version 26.3 --java <JDK 25 home> --checks core/platform-fabric/sandbox/boot-checks.txt core/platform-fabric/build/libs/xsample-fabric-1.0.0+26.3.jar`.

Real clients join the Fabric boots too, with the same `client:` step and the same scenarios (`scripts/budgets/bots/client.js`, mineflayer, so Node.js is needed): each plugin's `fabric/*client-checks.txt` beside the plugins its scenario needs, `--seed scripts/boot-seeds/client` so Joiner is an operator. Mineflayer joins up to 26.1, so they run on 1.20.1 and 1.21.11, not 26.3; on 1.20.1, which keeps no client language, Joiner reads English (decisions/2026-11-27-real-clients-on-fabric.md). xTeleport beside xMoney, for example:

```sh
libs=core/platform-fabric/build/libs
scripts/boot-fabric.sh --version 1.21.11 --java <JDK 21 home> --seed scripts/boot-seeds/client --seed xteleport/fabric/xmoney-seed \
  --checks xteleport/fabric/boot-checks.txt --checks xteleport/fabric/xmoney-client-checks.txt \
  "$libs"/xteleport-fabric-*+1.21.11.jar "$libs"/xmoney-fabric-*+1.21.11.jar
```

The proxies boot through `scripts/boot-proxy.sh`, alone or in front of a backend, with xSample's proxy side (`core/core-testing/sample/velocity`, `bungee`). Alone, its platform checks and console commands, and with `--startup-budget` its startup; in front of a backend (Paper behind Velocity through modern forwarding, Spigot behind BungeeCord), the pair test, a real client joining through the proxy while xSample's two sides talk over the proxy layer (decisions/2026-11-25-the-proxy-layer.md):

```sh
./gradlew :core:core-testing:sample:velocity:assemble :core:core-testing:sample:bungee:assemble :core:core-testing:sample:bukkit:assemble
scripts/boot-proxy.sh --proxy velocity --java <JDK 25 home> --checks core/core-testing/sample/velocity/boot-checks.txt \
  --startup-budget 150 core/core-testing/sample/velocity/build/r8/XSample-Velocity.jar
scripts/boot-proxy.sh --proxy velocity --java <JDK 25 home> --backend paper --backend-version 1.21.11 --backend-java <JDK 21 home> \
  --backend-plugin core/core-testing/sample/bukkit/build/r8/XSample.jar --grant 'Joiner=xsample.*,xsample.admin' \
  --checks core/core-testing/sample/velocity/pair-checks.txt core/core-testing/sample/velocity/build/r8/XSample-Velocity.jar
scripts/boot-proxy.sh --proxy bungeecord --java <JDK 21 home> --backend "$(scripts/server-jar.sh spigot 1.8.8 | sed -n 's/^jar=//p')" \
  --backend-java <JDK 8 home> --backend-plugin core/core-testing/sample/bukkit/build/r8/XSample.jar --grant 'Joiner=xsample.*,xsample.admin' \
  --checks core/core-testing/sample/bungee/pair-checks.txt core/core-testing/sample/bungee/build/r8/XSample-Bungee.jar
```

A rewritten plugin boots the same way. xUtilities, the first: `./gradlew :xutilities:bukkit:assemble`, then `xutilities/bukkit/boot.sh paper 26.3 --java <JDK 25 home>` (or a Spigot jar with `--java <JDK 8 home>`); its Fabric mod is a branch of platform-fabric's build, `./gradlew -p core/platform-fabric :xutilities:26.3:collect`, booted with `scripts/boot-fabric.sh --version 26.3 --checks xutilities/fabric/boot-checks.txt core/platform-fabric/build/libs/xutilities-fabric-*+26.3.jar`. The Boot workflow boots it in every cell, and beside xSample and xTwin in the family test.

### Performance budgets

The runtime budgets in ROADMAP.md ("Fast, robust, light") are checked two ways, neither part of `build`, since each takes minutes and wants a quiet machine:

```sh
# The core's hot paths under JMH (markup, messages per language, the event bus, commands,
# config reads), against core/core-benchmarks/budgets.txt: bytes allocated per call held
# close, times loosely. Fails the build when one is over.
./gradlew :core:core-benchmarks:checkBenchmarks
# A real Paper server with xSample's shrunk jar: startup (median of 3 boots), idle and
# 10-bot tick cost from async-profiler, heap and metaspace, against gradle/perf-budgets.txt.
./gradlew :core:core-testing:sample:bukkit:assemble
scripts/budgets/check.sh --java <JDK 21 home> --commands "xsample,coins,pay {other} 1" \
  core/core-testing/sample/bukkit/build/r8/XSample.jar
```

The Boot workflow runs both (its `budgets` and `benchmarks` jobs): startup is reported there with `--report-only startup-first-ms,startup-ms` until it's within its budget, and the 10-bot load runs nightly. `check.sh` exits 0 within every budget, 1 over one, 2 on a usage error and 3 when the measurement itself failed (a download, a server that didn't start, bots that didn't join). It needs Node.js for the bots (`--bots 0` skips them) and fetches Paper, async-profiler (pinned by SHA-256) and the bots' packages into `.cache/`. The report is `build/budgets/report.md`, and the job summary on GitHub Actions. On Linux, async-profiler wants `sysctl kernel.perf_event_paranoid=1`. Several plugin jars measure the family together; the first to enable gets the 150 ms first-plugin budget.

## How it fits together

| Folder | What it is |
|---|---|
| `core/` | Code every plugin shares, shaded into each jar; inside it, the rewrite's core (`core-common`, `core-api`, `core-jvm`, see [ROADMAP.md](ROADMAP.md), and `core-loader`, the Java shim and library loader) |
| `build-logic/` | The Gradle conventions: `xena.plugin` for every plugin, `xena.api` for its developer API; `xena.kmp`, `xena.kmp-api` and `xena.kotlin-jvm` for the rewrite's Kotlin modules, `xena.java8` for its Java one, `xena.fetched-libraries` for jars that download libraries at runtime, `xena.r8` for a rewrite jar shrunk with R8 against its budget in `gradle/jar-budgets.txt` |
| `sandbox/` | A plugin that's never released, used to test the core on real servers in CI |
| `xutilities/`, `zchat/`, `xlimbo/`, `xrewind/`, `xplots/`, `xteleport/`, `xvote/`, `xbans/`, `xmoney/`, `xbackup/`, `xstats/` | The plugins, each with its own README |
| `scripts/` | `affected.sh` and its test `affected-test.sh`, `decisions.ts` (writes DECISIONS.md), `boot-test.sh`, `boot-fabric.sh`, `boot-proxy.sh`, `budgets/check.sh` (the runtime budgets on a real server), `server-jar.sh` (Spigot from BuildTools, the forks' and the proxies' jars), `changelog.sh`, `new-minecraft.sh`, `new-plugin.ts` and its template, `new-rewrite-plugin.ts` (a plugin in the rewrite's layout), `release.ts` |
| `docs/` | plugins.xenastudios.co |

A plugin extends `XenaPlugin`. It declares its config as records (`ConfigSpec`), and in `setup` says which commands and modules that config turns on. The core does the rest: registering on startup and on every reload, carrying old configs forward, the theme, the update checker and bStats.

[DECISIONS.md](DECISIONS.md) explains why things are built this way, one file per decision in [`decisions/`](decisions/). Read the parts your change touches.

## What a change has to keep true

These keep the plugins fast and dependable on a busy server. A change that breaks one won't be merged.

- **No repeating tasks**, unless a feature can't work without one, and then only while it's on.
- **Listeners only while their feature is on.** A feature that's off costs nothing.
- **Do the work once.** Parse messages, compile patterns and read config when the config loads, not on every event.
- **Every setting applies on reload.** No "restart to apply". The one exception is where a plugin keeps its data (`storage`, and the `network` settings that share it), when the plugin must read that data before anyone joins; its Configuration page then says so.
- **Old configs keep working.** Renamed or moved settings are carried forward by the config migration. When a default message changes, put its old text in the spec as `formerDefault`.
- **Paper and Folia both work**, unless the plugin says otherwise (xLimbo and xPlots are Paper only). Schedule through the core's `Tasks` or Paper's region and entity schedulers. Global work goes through `Tasks.global` or `Tasks.nextTick`, never straight to Paper's global region scheduler: on Paper that scheduler stops while an empty server is paused, and `Tasks` uses Bukkit's scheduler there instead.
- **Nothing copied between plugins.** Code a second plugin needs moves to the core, in its own pull request first; `./gradlew build` fails when the same code is in two modules ([#48](https://github.com/xena-studios/plugins/issues/48)).
- **The jar stays within its budget** (`jarBudgetKb` in the plugin's `build.gradle.kts`). Going over usually means a library got shaded in by mistake.

## Tests

| Kind | What it covers |
|---|---|
| Unit tests (JUnit, MockBukkit) | Config migration and command logic. Keep them next to the code they test. |
| Boot checks | Each plugin's `boot-checks.txt`: console commands CI runs on real Paper and Folia servers (1.21.11 and the newest), and what each must log. Add a line for each new command or feature. |
| Default config | Compared with `docs/src/generated/<plugin>/config.yml`. After changing a default or a comment, run `./gradlew test -PupdateGolden` and commit the result. |
| MySQL | The database code against a real MySQL or MariaDB, when `XR_MYSQL=host:port:database:user:password` is set, as CI sets it. Skipped otherwise. |

## Pull requests

1. Branch from `main` (or fork).
2. Make the change, with its tests and the docs pages that describe it.
3. Add a line to the plugin's `CHANGELOG.md` under `## Unreleased`. Write it for server owners: what changed for them, not how.
4. Run `./gradlew build` and open the PR.

CI builds, tests, boot-tests on Paper and Folia, compiles against the newest Paper, and runs CodeQL. `main` requires `plugins-ok` and `docs-ok`, and `boot-ok` (the rewrite's boot matrix) once it's added. Each workflow skips what a change doesn't touch, so a docs-only PR doesn't wait for boot tests. On a PR, the plugins workflow builds, tests and boots only the plugins it changes and any that use them (`sandbox` uses xRewind's API, for example); a change to today's core (`core/src`), what today's plugins build with in `build-logic/`, `gradle/`, the root build files, the scripts their boots run or the workflow itself checks everything, as every push to `main` does. A change only to the rewrite (its modules in `core/`, its conventions in `build-logic/`, its budgets and pins in `gradle/`, its boot and budget scripts) builds and tests just the rewrite's modules (on the JVM and on `wasmWasi`) and whatever uses them, and boots only the rewrite (the Boot workflow), never today's plugins on Paper and Folia. To see what a PR would run: `git diff --name-only main | scripts/affected.sh`, with bash 4 or newer (macOS's own is 3.2); `scripts/affected-test.sh` checks those rules, case by case. PRs and issues get labelled by plugin automatically.

Commits follow [Conventional Commits](https://www.conventionalcommits.org) with the plugin as scope, or `core` for shared code: `fix(zchat): keep the reply target through a reload`, `feat(xlimbo): ...`, `docs: ...`, `build: ...`.

Contributions are licensed under [GPL-3.0-only](LICENSE).

## Decisions

A choice someone will later ask "why?" about (a library, a limit, a design that rules something out) gets a decision: one file in [`decisions/`](decisions/), named `<date>-<slug>.md` for the day it's settled and a few words of its title, such as `decisions/2026-11-24-smaller-jars.md`. Its first line is `# <title> (<date>)`, with the same date; then why, and what was measured. Link one from another as `decisions/<file>.md` (`../decisions/<file>.md` from a folder).

Don't edit `DECISIONS.md` in the same PR: it's the index, written by `node scripts/decisions.ts`, and leaving it alone is what keeps two PRs that each add a decision from conflicting ([why](decisions/2026-11-24-decisions-one-file-each.md)). The Docs workflow runs `node scripts/decisions.ts --check`, which fails on a misnamed file or a wrong row and notes the decisions not listed yet; release PRs (`scripts/release.ts`) list them. Changing a decision's title or file name changes its row, so then run `node scripts/decisions.ts` and commit the index too.

## Adding a plugin

Plan it in a [New plugin issue](https://github.com/xena-studios/plugins/issues/new?template=new_plugin.yml), then, with [xena-studios/brand](https://github.com/xena-studios/brand) checked out beside this repo (and its fonts, see its `docs/building.md`):

```sh
node scripts/new-plugin.ts xshop Shop Economy    # <id> <Name> <category>
```

It warns if another project already has the id as its Modrinth slug: the update checker reads that slug's versions, so reserve it as a draft in the xena-studios organization. Then it:

1. Adds the plugin to the brand repo (its `pnpm new-plugin`): a placeholder glyph, an accent that stays clear of every other Xena accent, and its entry in the brand catalog, where a plugin's name, words and colours live.
2. Writes the module from [`scripts/new-plugin/template/`](scripts/new-plugin/template): the plugin class on `XenaPlugin`, its config with the theme in its accent, the `api/` subproject, a test that keeps the docs' config page in step, `boot-checks.txt`, `CHANGELOG.md`, `README.md`, `modrinth.md`, and the docs pages (Overview, Commands, Configuration, Developer API).
3. Copies the brand's art and catalog into the docs (`pnpm brand:sync`) and rebuilds every list of the plugins (`pnpm catalog`): the README, the docs' sidebar, home page, footer and accents, the labels and the issue forms. That also writes the plugin's Changelog and Download pages.
4. Writes the API's signatures and the docs' default config, and builds the plugin (`--no-build` skips this). It boots green as it is.

What's left is marked TODO, and the script lists the files. Gradle finds the new folder on its own. In the brand repo, draw the glyph, write the headline, summary and blurb, rebuild, and open a pull request there; then `pnpm brand:sync` and `pnpm catalog` in `docs/` again. Add the plugin to [`docs/src/data/related.json`](docs/src/data/related.json), with the plugins it goes with and why, and add it to theirs: that's each Modrinth page's "More from Xena Studios". Until then its page lists every released plugin. Change the templates when the standard for every plugin changes, so the next one starts from it.

The convention shades in the core, generates `paper-plugin.yml`, sets up Modrinth, and starts the jar budget at 150 KiB (`xena { jarBudgetKb = ... }`).

**A plugin in the rewrite's layout** (ROADMAP.md, Architecture) starts from its own script, which writes the code only; the brand and docs steps above still apply:

```sh
node scripts/new-rewrite-plugin.ts xshop Shop    # <id> <Name> [--platforms] [--no-build]
```

It writes `xshop/api` (`xena.kmp-api`, its signatures committed in `xshop/api/api/`) and `xshop/common` (`xena.kmp`: a config, permissions and a `/xshop` command, with a test on the fake platform), writes the API's signatures and builds both on the JVM and wasmWasi. `--platforms` adds `xshop/bukkit` (the loader's Java shim, a Kotlin entry point, `plugin.yml`, R8 and a 150 KiB budget) and `xshop/fabric`, stubbed on `:core:platform-bukkit` and `:core:platform-fabric`; it refuses to build until those exist, since a project naming a missing one breaks every build. `settings.gradle.kts` includes the new folders on its own, and `scripts/affected.sh` builds only them when only they change. CI runs it end to end (`scaffold.yml`).

## Releasing

For maintainers, with Node 24 and the GitHub CLI (`gh`), from an up-to-date `main` with a clean working tree:

1. **Open the release PR:** `node scripts/release.ts <plugin>[@<version>]...`, such as `node scripts/release.ts xbans xvote@1.1.0`. For each plugin it renames `## Unreleased` in its `CHANGELOG.md` to `## <version>`, bumps `version` in its `build.gradle.kts` and the version its `boot-checks.txt` expects from `/<plugin> info`, and updates its docs: the "Current version" line, the API's Gradle and Maven snippets, and the note that the API reaches Maven Central with the release. Then it regenerates `docs/src/data/plugins.json` and DECISIONS.md (`scripts/decisions.ts`, listing the decisions added since the last release), commits on `release/<plugins>`, pushes, and opens one PR with each plugin's changelog section.
   - Without `@<version>`, the version comes from the Unreleased section: only `### Fixed` (or `### Security`) is a patch, any other heading a minor. A major release, or a section that removes or breaks something, needs `@<version>`.
   - A build version that's never been tagged, like a new plugin's 1.0.0, is released as it is. If its changelog already has a `## 1.0.0` section, that's the release notes, and the changelog is left alone.
   - It refuses, changing nothing, when a plugin has nothing under `## Unreleased`, its version is already tagged or isn't newer than its last tag, or the working tree has changes.
   - `--dry-run` prints every edit and changes nothing; `--no-push` commits on the release branch but doesn't push or open the PR.
2. **Merge it,** once its checks pass, and wait for the Plugins workflow to pass on `main`'s merge commit.
3. **Tag it:** `node scripts/release.ts --tag <pr-number>`. It reads each plugin's version and changelog at the merge commit, refuses unless the Plugins workflow passed on exactly that commit (Maven Central can't take a release back), and pushes an annotated `<plugin>-v<version>` tag for each, one at a time. `--dry-run` shows the tags without making them. Given plugin ids instead of a number, it tags `main`'s latest commit.
4. Each tag runs the release workflow. It checks the tag is on `main` and matches the version and changelog, then publishes the API (if there is one) to Maven Central and the plugin to Modrinth, Hangar, voxel.shop and GitHub, with that changelog section as the notes, and redeploys the docs. Each store skips a version it already has, so if one fails, rerun the workflow once it's fixed. Its secrets are in the `release` environment, which only `main` and release tags can reach.

**The rewrite's releases.** The steps above are today's plugins, released from `maintenance` by that branch's own `release.yml`. On `main`, [`release.yml`](.github/workflows/release.yml) is the rewrite's ([decisions/2026-11-27-the-release-set.md](decisions/2026-11-27-the-release-set.md)). Each plugin's project on `xena.release` lists every jar it ships (`xenaRelease`), and one run releases one plugin or the whole set to GitHub (with `SHA256SUMS` and build attestations), Modrinth, Hangar (Paper, Velocity, Waterfall), voxel.shop and CurseForge, and writes the SpigotMC and BuiltByBit update posts to paste. A store is skipped until the plugin's `xenaRelease` has its id (`hangarId`, `voxelId`, `curseForgeBukkitId`, `curseForgeModsId`, `curseForgeHytaleId`). Every jar is built and every store's request for every plugin checked against that store's schema and lists before anything is uploaded; then each store is its own job with only its own token, so rerun just the one that failed.

1. **Open the release pull request:** `node scripts/release.ts --together` (every plugin still `unreleased` in `docs/src/data/versions.json`, or name them). It takes `(unreleased)` and its note off each changelog section, deletes `"unreleased"` from each line (the site's switch), renames a new plugin's `modrinth.draft.md` to `modrinth.md`, builds the release set and adds it to `docs/src/data/releases/<plugin>.json` with each jar's size and SHA-256, and regenerates the catalog and DECISIONS.md. `--dry-run` prints it all and changes nothing; `--no-push` commits without pushing; `--site-data <dir>` takes the entries from a release folder (the Release workflow's `release` artifact) instead of building. The Release workflow's dry run on that pull request builds every jar again and fails if one differs from what the releases files record.
2. **Merge it** once its checks pass, and wait for the Plugins workflow on `main`'s merge commit.
3. **Release it:** `node scripts/release.ts --tag <pr-number>` checks each version and changelog at the merge commit and that the Plugins workflow passed there, then starts one Release run for every plugin (`--dry-run` shows the command). That run makes each `<plugin>-v<version>` tag with its GitHub release. A real run refuses a changelog section still marked `(unreleased)` or a site line still `unreleased`.

**Dry run:** Actions → Release → Run workflow with `tags: all` (every rewritten plugin at its build's version) or a list of tags, `dry-run` ticked; pull requests touching the release code run it too. Locally: `./gradlew -p core/platform-fabric collect`, then `./gradlew <project>:releaseAssemble <project>:releasePublish -Pxena.release.root=release -Pxena.release.dryRun=true` for each project, which leaves each store's checked request in `release/<plugin>/dry-run/`. CurseForge needs `CURSEFORGE_TOKEN` in the `release` environment. A rewritten plugin's store page is `releaseStorePage` (`-Pxena.release.dryRun=true` checks and prints it).

**A plugin's first release.** Before tagging it, take `released: false` off its entry in the brand repo's `scripts/plugin-brand.ts`, rebuild there (`pnpm build:plugins`) and merge that; then `pnpm brand:sync` and `pnpm catalog` in `docs/` put it on the family's banners and take "Coming soon" off the README and the site. Once the release has put its first version on Modrinth, fill in the project's disclosures on modrinth.com and submit it for review. Until Modrinth approves it, its page there is a 404 to everyone else, so its id stays in [`docs/src/data/modrinth.json`](docs/src/data/modrinth.json) (`new-plugin.ts` puts it there): nothing links its Modrinth page, its README row and the other plugins' Modrinth pages link its docs, and its download page lists its GitHub releases. `pnpm catalog:check`, which CI runs, fails on a link to it. When Modrinth approves it, take it off the list, point its README's Download link at Modrinth, and run `pnpm catalog`. xenastudios.co lists the plugins by hand (xena-studios/website, `src/components/sections/Plugins.astro`): add it there too, with a Modrinth link only once it's approved.

Each plugin's Modrinth and Hangar page is its `modrinth.md`. [`modrinth.yml`](.github/workflows/modrinth.yml) publishes it whenever it changes on `main`, and every release publishes it again, so **edit the file, never the page on Modrinth or Hangar**. voxel.shop's API can't edit a resource's page, so that one is kept by hand. Text between `<!-- modrinth_exclude.start -->` and `<!-- modrinth_exclude.end -->` is left out. Dry run: `MODRINTH_TOKEN=x ./gradlew :<plugin>:modrinthSyncBody -Pxena.modrinthDryRun=true --info`.

**Hangar and voxel.shop.** A plugin is published to each only once it has a project there. Make the project on [Hangar](https://hangar.papermc.io) under the Xena Studios organization, named as the plugin (`xPlots`), with its summary, icon and the Folia tag if it runs on Folia; and the free resource on [voxel.shop](https://voxel.shop), with its page copied from `modrinth.md`. Then add them to the plugin's `build.gradle.kts`: `xena { hangarId = "xPlots"; voxelId = 1234 }`, where 1234 is the number at the end of the resource's link. Until then, releases and page syncs skip that store. A new voxel.shop resource also takes no updates until voxel.shop's staff approve it, so until then releases skip it with a warning. Versions with a `-` go to Hangar's Snapshot channel and are marked beta on voxel.shop. The `release` environment holds `HANGAR_API_TOKEN` (an organization API key with `create_version` and `edit_page`) and `VOXEL_API_KEY` (with "Post Updates").

## Updates

- **Dependencies:** [Renovate](https://docs.renovatebot.com) opens PRs on Monday mornings ([`renovate.json`](renovate.json), on top of the [org settings](https://github.com/xena-studios/.github/blob/main/renovate-config.json)). Minor and patch updates come in one PR that merges itself once CI passes; majors wait for review. WorldGuard and WorldEdit stay on the newest builds for Java 21, and xBackup's downloaded libraries (pinned by SHA-256) get a PR of their own, merged by hand. So does the rewrite's Kotlin, whose stdlib is downloaded too: its PR fails until `./gradlew build -PupdatePins` has updated `gradle/library-pins.txt`. The minimum Paper in `gradle/libs.versions.toml` is never bumped automatically, because raising it drops servers.
- **New Minecraft versions:** [`minecraft.yml`](.github/workflows/minecraft.yml) runs on Mondays, or by hand (**Actions → New Minecraft → Run workflow**). When Paper has a stable build of a newer version, it opens a PR that adds it to `xena.gameVersions` and moves the boot tests and newest-API compile onto it. Merge it if the checks pass; Modrinth lists the new version from each plugin's next release.
