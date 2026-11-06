# Contributing

Thanks for helping with xUtilities, zChat and xLimbo. Everyone follows the [code of conduct](CODE_OF_CONDUCT.md). Security problems go to [SECURITY.md](SECURITY.md), never a public issue.

## Issues

[Open an issue](https://github.com/xena-studios/plugins/issues/new/choose) and pick a form. For a bug, include the version from `/<plugin> info`, your server software and version, and the console error. For questions, email [contact@xenastudios.co](mailto:contact@xenastudios.co).

Spotted a mistake in the docs? Every page at [docs.xenastudios.co](https://docs.xenastudios.co) has an **Edit page** link.

## Building

Needs JDK 21 or newer, plus JDK 25 to boot-test on 26.x servers. The docs need Node 24 (see [docs/README.md](docs/README.md)).

```sh
./gradlew build                   # compile, test, check each jar's size budget
./gradlew :<plugin>:runServer     # a Paper server with the plugin, in <plugin>/run/
./gradlew test -PupdateGolden     # rewrite docs/src/generated/ after changing a default config
./gradlew compileJava -Pxena.newestPaper=<paper-api version>   # build against the newest Paper
scripts/boot-test.sh --project paper --version 1.21.11 \
  --run "sandbox selftest" --expect "SELFTEST PASS" sandbox/build/libs/*.jar
```

## How it fits together

| Folder | What it is |
|---|---|
| `core/` | Code every plugin shares, shaded into each jar |
| `build-logic/` | The `xena.plugin` Gradle convention every plugin applies |
| `sandbox/` | A plugin that's never released, used to test the core on real servers in CI |
| `xutilities/`, `zchat/`, `xlimbo/` | The plugins, each with its own README |
| `scripts/` | `boot-test.sh`, `changelog.sh`, `new-minecraft.sh` |
| `docs/` | docs.xenastudios.co |

A plugin extends `XenaPlugin`. It declares its config as records (`ConfigSpec`), and in `setup` says which commands and modules that config turns on. The core does the rest: registering on startup and on every reload, carrying old configs forward, the theme, the update checker and bStats.

[DECISIONS.md](DECISIONS.md) explains why things are built this way. Read the parts your change touches.

## What a change has to keep true

These keep the plugins fast and dependable on a busy server. A change that breaks one won't be merged.

- **No repeating tasks**, unless a feature can't work without one, and then only while it's on.
- **Listeners only while their feature is on.** A feature that's off costs nothing.
- **Do the work once.** Parse messages, compile patterns and read config when the config loads, not on every event.
- **Every setting applies on reload.** No "restart to apply".
- **Old configs keep working.** Renamed or moved settings are carried forward by the config migration. When a default message changes, put its old text in the spec as `formerDefault`.
- **Paper and Folia both work**, unless the plugin says otherwise (xLimbo is Paper only). Schedule through the core's `Tasks` or Paper's region and entity schedulers, never the Bukkit scheduler.
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

CI builds, tests, boot-tests on Paper and Folia, compiles against the newest Paper, and runs CodeQL. `main` requires `plugins-ok` and `docs-ok`. Each workflow skips what a change doesn't touch, so a docs-only PR doesn't wait for boot tests. PRs and issues get labelled by plugin automatically.

Commits follow [Conventional Commits](https://www.conventionalcommits.org) with the plugin as scope, or `core` for shared code: `fix(zchat): keep the reply target through a reload`, `feat(xlimbo): ...`, `docs: ...`, `build: ...`.

Contributions are licensed under [GPL-3.0-only](LICENSE).

## Adding a plugin

Copy xUtilities. A new plugin needs:

- A folder with a `build.gradle.kts` that applies `id("xena.plugin")`, sets `version`, and names its main class in `paperPluginYaml`. Add it to `settings.gradle.kts`.
- `README.md`, `CHANGELOG.md`, `modrinth.md` (its Modrinth page) and `boot-checks.txt` (CI finds it on its own).
- A banner, added to `docs/scripts/sync-brand.ts`, and a folder in the docs.

The convention shades in the core, generates `paper-plugin.yml`, sets up Modrinth, and starts the jar budget at 150 KiB (`xena { jarBudgetKb = ... }`).

## Releasing

For maintainers:

1. Bump `version` in the plugin's `build.gradle.kts`, and rename `## Unreleased` in its `CHANGELOG.md` to `## <version>`.
2. Merge, then push a tag like `xutilities-v2.1.0`.
3. The release workflow checks the tag and changelog match, then publishes to Modrinth and GitHub with that changelog section as the notes. It uses the `MODRINTH_TOKEN` secret in the `release` environment, which only `main` and release tags can reach.

Each plugin's Modrinth page is its `modrinth.md`. [`modrinth.yml`](.github/workflows/modrinth.yml) publishes it whenever it changes on `main`, and every release publishes it again, so **edit the file, never the page on Modrinth**. Text between `<!-- modrinth_exclude.start -->` and `<!-- modrinth_exclude.end -->` is left out. Dry run: `MODRINTH_TOKEN=x ./gradlew :<plugin>:modrinthSyncBody -Pxena.modrinthDryRun=true --info`.

## Updates

- **Dependencies:** [Renovate](https://docs.renovatebot.com) opens PRs on Monday mornings ([`renovate.json`](renovate.json), on top of the [org settings](https://github.com/xena-studios/.github/blob/main/renovate-config.json)). Minor and patch updates come in one PR that merges itself once CI passes; majors wait for review. The minimum Paper in `gradle/libs.versions.toml` is never bumped automatically, because raising it drops servers.
- **New Minecraft versions:** [`minecraft.yml`](.github/workflows/minecraft.yml) runs on Mondays, or by hand (**Actions → New Minecraft → Run workflow**). When Paper has a stable build of a newer version, it opens a PR that adds it to `xena.gameVersions` and moves the boot tests and newest-API compile onto it. Merge it if the checks pass; Modrinth lists the new version from each plugin's next release.
