# Contributing

Thanks for helping with xUtilities, zChat, xLimbo, xRewind, xPlots, xTeleport, xVote, xBans, xMoney, xBackup and xStats. Everyone follows the [code of conduct](https://github.com/xena-studios/.github/blob/main/CODE_OF_CONDUCT.md). Security problems go to [SECURITY.md](SECURITY.md), never a public issue.

## Issues

[Open an issue](https://github.com/xena-studios/plugins/issues/new/choose) and pick a form; it asks for what we need. For questions, see [SUPPORT.md](SUPPORT.md).

Spotted a mistake in the docs? Every page at [plugins.xenastudios.co](https://plugins.xenastudios.co) has an **Edit page** link.

## Building

Needs JDK 21 or newer, plus JDK 25 to boot-test on 26.x servers. The docs need Node 24 (see [docs/README.md](docs/README.md)).

```sh
./gradlew build                   # compile, test, check each jar's size budget
./gradlew :<plugin>:runServer     # a Paper server with the plugin, in <plugin>/run/
./gradlew test -PupdateGolden     # rewrite docs/src/generated/ after changing a default config
./gradlew compileJava -Pxena.newestPaper=<paper-api version>   # build against the newest Paper
./gradlew :<plugin>:api:apiDump   # after a deliberate API change: rewrite <plugin>/api/<plugin>-api.api
scripts/boot-test.sh --project paper --version 1.21.11 \
  --run "sandbox selftest" --expect "SELFTEST PASS" sandbox/build/libs/sandbox-0.0.0.jar
```

On Paper, `boot-test.sh` then boots a second, empty server, waits for Paper to pause it, and runs every Xena plugin's `/<plugin> reload`. A paused server doesn't run the global region scheduler, so global work goes through the core's `Tasks`, which Paper keeps running.

## How it fits together

| Folder | What it is |
|---|---|
| `core/` | Code every plugin shares, shaded into each jar |
| `build-logic/` | The `xena.plugin` Gradle convention every plugin applies |
| `sandbox/` | A plugin that's never released, used to test the core on real servers in CI |
| `xutilities/`, `zchat/`, `xlimbo/`, `xrewind/`, `xplots/`, `xteleport/`, `xvote/`, `xbans/`, `xmoney/`, `xbackup/`, `xstats/` | The plugins, each with its own README |
| `scripts/` | `affected.sh`, `boot-test.sh`, `changelog.sh`, `new-minecraft.sh` |
| `docs/` | plugins.xenastudios.co |

A plugin extends `XenaPlugin`. It declares its config as records (`ConfigSpec`), and in `setup` says which commands and modules that config turns on. The core does the rest: registering on startup and on every reload, carrying old configs forward, the theme, the update checker and bStats.

[DECISIONS.md](DECISIONS.md) explains why things are built this way. Read the parts your change touches.

## What a change has to keep true

These keep the plugins fast and dependable on a busy server. A change that breaks one won't be merged.

- **No repeating tasks**, unless a feature can't work without one, and then only while it's on.
- **Listeners only while their feature is on.** A feature that's off costs nothing.
- **Do the work once.** Parse messages, compile patterns and read config when the config loads, not on every event.
- **Every setting applies on reload.** No "restart to apply".
- **Old configs keep working.** Renamed or moved settings are carried forward by the config migration. When a default message changes, put its old text in the spec as `formerDefault`.
- **Paper and Folia both work**, unless the plugin says otherwise (xLimbo is Paper only). Schedule through the core's `Tasks` or Paper's region and entity schedulers. Global work goes through `Tasks.global` or `Tasks.nextTick`, never straight to Paper's global region scheduler: on Paper that scheduler stops while an empty server is paused, and `Tasks` uses Bukkit's scheduler there instead.
- **Nothing copied between plugins.** Code a second plugin needs moves to the core, in its own pull request first; `./gradlew build` fails when the same code is in two modules ([#48](https://github.com/xena-studios/plugins/issues/48)).
- **The jar stays within its budget** (`jarBudgetKb` in the plugin's `build.gradle.kts`). Going over usually means a library got shaded in by mistake.

## Tests

| Kind | What it covers |
|---|---|
| Unit tests (JUnit, MockBukkit) | Config migration and command logic. Keep them next to the code they test. |
| Boot checks | Each plugin's `boot-checks.txt`: console commands CI runs on real Paper and Folia servers (1.21.11 and the newest), and what each must log. Add a line for each new command or feature. |
| Default config | Compared with `docs/src/generated/<plugin>/config.yml`. After changing a default or a comment, run `./gradlew test -PupdateGolden` and commit the result. |

## Pull requests

1. Branch from `main` (or fork).
2. Make the change, with its tests and the docs pages that describe it.
3. Add a line to the plugin's `CHANGELOG.md` under `## Unreleased`. Write it for server owners: what changed for them, not how.
4. Run `./gradlew build` and open the PR.

CI builds, tests, boot-tests on Paper and Folia, compiles against the newest Paper, and runs CodeQL. `main` requires `plugins-ok` and `docs-ok`. Each workflow skips what a change doesn't touch, so a docs-only PR doesn't wait for boot tests. On a PR, the plugins workflow builds, tests and boots only the plugins it changes and any that use them (`sandbox` uses xRewind's API, for example); a change to the core, `build-logic/`, `gradle/`, the root build files, `scripts/` or the workflow itself checks everything, as every push to `main` does. To see what a PR would run: `git diff --name-only main | scripts/affected.sh`. PRs and issues get labelled by plugin automatically.

Commits follow [Conventional Commits](https://www.conventionalcommits.org) with the plugin as scope, or `core` for shared code: `fix(zchat): keep the reply target through a reload`, `feat(xlimbo): ...`, `docs: ...`, `build: ...`.

Contributions are licensed under [GPL-3.0-only](LICENSE).

## Adding a plugin

Plan it in a [New plugin issue](https://github.com/xena-studios/plugins/issues/new?template=new_plugin.yml), then, with [xena-studios/brand](https://github.com/xena-studios/brand) checked out beside this repo (and its fonts, see its `docs/building.md`):

```sh
node scripts/new-plugin.ts xshop Shop Economy    # <id> <Name> <category>
```

It warns if another project already has the id as its Modrinth slug: the update checker reads that slug's versions, so reserve it as a draft in the xena-studios organization. Then it:

1. Adds the plugin to the brand repo (its `pnpm new-plugin`): a placeholder glyph, an accent that stays clear of every other Xena accent, and its entry in the brand catalog, where a plugin's name, words and colours live.
2. Writes the module from [`scripts/new-plugin/template/`](scripts/new-plugin/template): the plugin class on `XenaPlugin`, its config with the theme in its accent, the `api/` subproject, a test that keeps the docs' config page in step, `boot-checks.txt`, `CHANGELOG.md`, `README.md`, `modrinth.md`, and the docs pages (Overview, Commands, Configuration, Developer API).
3. Copies the brand's art and catalog into the docs (`pnpm brand:sync`) and rebuilds every list of the plugins (`pnpm catalog`): the README, the docs' sidebar, home page, footer and accents, the labels and the bug form.
4. Writes the API's signatures and the docs' default config, and builds the plugin. It boots green as it is.

What's left is marked TODO, and the script lists the files. Gradle finds the new folder on its own. In the brand repo, draw the glyph, write the headline, summary and blurb, rebuild, and open a pull request there; then `pnpm brand:sync` and `pnpm catalog` in `docs/` again. Add the plugin to [`docs/src/data/related.json`](docs/src/data/related.json), with the plugins it goes with and why, and add it to theirs: that's each Modrinth page's "More from Xena Studios". Until then its page lists every released plugin. Change the templates when the standard for every plugin changes, so the next one starts from it.

The convention shades in the core, generates `paper-plugin.yml`, sets up Modrinth, and starts the jar budget at 150 KiB (`xena { jarBudgetKb = ... }`).

## Releasing

For maintainers, from an up-to-date `main` with a clean working tree:

1. **Open the release PR:** `node scripts/release.ts <plugin>[@<version>]...`, such as `node scripts/release.ts xbans xvote@1.1.0`. For each plugin it renames `## Unreleased` in its `CHANGELOG.md` to `## <version>`, bumps `version` in its `build.gradle.kts` and the version its `boot-checks.txt` expects from `/<plugin> info`, and updates its docs: the "Current version" line, the API's Gradle and Maven snippets, and the note that the API reaches Maven Central with the release. Then it regenerates `docs/src/data/plugins.json`, commits on `release/<plugins>`, pushes, and opens one PR with each plugin's changelog section.
   - Without `@<version>`, the version comes from the Unreleased section: only `### Fixed` (or `### Security`) is a patch, any other heading a minor. A major release, or a section that removes or breaks something, needs `@<version>`.
   - A build version that's never been tagged, like a new plugin's 1.0.0, is released as it is. If its changelog already has a `## 1.0.0` section, that's the release notes, and the changelog is left alone.
   - It refuses, changing nothing, when a plugin has nothing under `## Unreleased`, its version is already tagged or isn't newer than its last tag, or the working tree has changes.
   - `--dry-run` prints every edit and changes nothing; `--no-push` commits on the release branch but doesn't push or open the PR.
2. **Merge it,** once its checks pass, and wait for the Plugins workflow to pass on `main`'s merge commit.
3. **Tag it:** `node scripts/release.ts --tag <pr-number>`. It reads each plugin's version and changelog at the merge commit, refuses unless the Plugins workflow passed on exactly that commit (Maven Central can't take a release back), and pushes an annotated `<plugin>-v<version>` tag for each, one at a time. `--dry-run` shows the tags without making them. Given plugin ids instead of a number, it tags `main`'s latest commit.
4. Each tag runs the release workflow. It checks the tag is on `main` and matches the version and changelog, then publishes the API (if there is one) to Maven Central and the plugin to Modrinth and GitHub, with that changelog section as the notes, and redeploys the docs. Its secrets are in the `release` environment, which only `main` and release tags can reach.

Each plugin's Modrinth page is its `modrinth.md`. [`modrinth.yml`](.github/workflows/modrinth.yml) publishes it whenever it changes on `main`, and every release publishes it again, so **edit the file, never the page on Modrinth**. Text between `<!-- modrinth_exclude.start -->` and `<!-- modrinth_exclude.end -->` is left out. Dry run: `MODRINTH_TOKEN=x ./gradlew :<plugin>:modrinthSyncBody -Pxena.modrinthDryRun=true --info`.

## Updates

- **Dependencies:** [Renovate](https://docs.renovatebot.com) opens PRs on Monday mornings ([`renovate.json`](renovate.json), on top of the [org settings](https://github.com/xena-studios/.github/blob/main/renovate-config.json)). Minor and patch updates come in one PR that merges itself once CI passes; majors wait for review. The minimum Paper in `gradle/libs.versions.toml` is never bumped automatically, because raising it drops servers.
- **New Minecraft versions:** [`minecraft.yml`](.github/workflows/minecraft.yml) runs on Mondays, or by hand (**Actions → New Minecraft → Run workflow**). When Paper has a stable build of a newer version, it opens a PR that adds it to `xena.gameVersions` and moves the boot tests and newest-API compile onto it. Merge it if the checks pass; Modrinth lists the new version from each plugin's next release.
