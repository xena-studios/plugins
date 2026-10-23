# Contributing

Thanks for helping with xUtilities, zChat and xLimbo. This covers reporting problems, building the plugins, and getting a change merged.

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md). Security problems go to [SECURITY.md](SECURITY.md), never to a public issue.

## Reporting a bug or asking for a feature

Open an [issue](https://github.com/xena-studios/plugins/issues/new/choose) and pick a form. For a bug, the version from `/<plugin> info`, your server software and version, and the console error matter most. Questions that aren't bugs are best sent to [contact@xenastudios.co](mailto:contact@xenastudios.co).

Found a mistake in the docs? Every page at [docs.xenastudios.co](https://docs.xenastudios.co) has an **Edit page** link.

## Building

Needs a JDK 21 or newer, plus JDK 25 to boot-test on 26.x servers. The docs need Node 24.

```sh
./gradlew build                   # compile, test, and check each jar against its size budget
./gradlew :<plugin>:runServer     # a Paper server with the plugin, in <plugin>/run/
./gradlew test -PupdateGolden     # rewrite docs/src/generated/ after changing a default config
./gradlew compileJava -Pxena.newestPaper=<paper-api version>   # against the newest Paper, failing on removals
scripts/boot-test.sh --project paper --version 1.21.11 \
  --run "sandbox selftest" --expect "SELFTEST PASS" sandbox/build/libs/*.jar
```

The jars land in `<plugin>/build/libs/`. The docs have their own [README](docs/README.md).

## How the repo fits together

```
build-logic/   the xena.plugin Gradle convention every plugin applies
core/          code every plugin shares, shaded into each jar
sandbox/       a plugin that's never released: boots the core on real servers in CI
xutilities/    xUtilities, with its own README
zchat/         zChat
xlimbo/        xLimbo
scripts/       boot-test.sh, changelog.sh, new-minecraft.sh
docs/          docs.xenastudios.co
```

A plugin extends `XenaPlugin` from the core. It declares its config as records (`ConfigSpec`) and, in `setup`, the commands and modules that config wants; the core registers them on startup and again on every reload, carries old configs forward, and handles the theme, the update checker and bStats. [DECISIONS.md](DECISIONS.md) records why things are built the way they are; read the parts your change touches.

## What a change has to keep true

These are what make the plugins fast and dependable on a busy server. A change that breaks one won't be merged, however useful it is.

- **No repeating tasks** unless a feature can't work without one, and then only while that feature is on.
- **Listeners only while their feature is on.** A feature that's switched off costs nothing.
- **Do the work once.** Parse messages, compile patterns and look up config when the config loads, not on every event.
- **Every setting applies on reload.** No "restart to apply".
- **Old configs keep working.** A renamed or moved setting is carried forward by the plugin's config migration. When a default message changes, its old text goes in the spec as `formerDefault`, so owners who never changed it get the new one.
- **Paper and Folia** both work, unless the plugin says otherwise (xLimbo doesn't run on Folia). Run work through the core's `Tasks` or Paper's entity and region schedulers, never the Bukkit scheduler.
- **The jar stays within its size budget** (`jarBudgetKb` in the plugin's `build.gradle.kts`). The budgets leave room for normal growth; going over means something big came in, such as a library shaded in by mistake.

## Tests

- **Unit tests** (JUnit, MockBukkit) cover config migration and command logic. Put them next to the code they test.
- **Boot checks.** Each plugin's `boot-checks.txt` lists console commands CI runs on real Paper and Folia servers, at 1.21.11 and the newest release, with the output each must log. A new command or feature should add a line there.
- **The default config** is compared with `docs/src/generated/<plugin>/config.yml`, which the docs show. Changing a default or a config comment means running `./gradlew test -PupdateGolden` and committing the file.

## Pull requests

1. Fork the repo, or make a branch if you have access, from `main`.
2. Make the change, with its tests, and update the docs pages that describe it.
3. Add a line for it to the plugin's `CHANGELOG.md` under `## Unreleased` (add the heading if it isn't there). Write it for server owners: what changed for them, not how.
4. Run `./gradlew build` and open the PR. The template asks for the rest.

CI builds, tests, boot-tests on Paper and Folia, and compiles against the newest Paper; CodeQL scans the change for security problems. `main` only takes changes through pull requests, and its two required checks, `plugins-ok` and `docs-ok`, have to pass. Each workflow skips what a change doesn't touch, so a docs-only PR doesn't wait for the boot tests. PRs and issues are labelled by plugin on their own.

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org), with the plugin as the scope: `fix(zchat): keep the reply target through a reload`, `feat(xlimbo): ...`, `docs: ...`, `build: ...`. Use `core` for the shared code.

By contributing, you agree your contribution is licensed under the repo's licence, [GPL-3.0](LICENSE).

## Adding a plugin

A new plugin is a folder with a `build.gradle.kts` that applies `id("xena.plugin")`, sets `version`, and names its main class in `paperPluginYaml`; add it to `settings.gradle.kts`. The convention shades in the core, generates `paper-plugin.yml`, sets a 150 KiB jar budget (`xena { jarBudgetKb = ... }`) and wires up Modrinth. Give it a `README.md`, a `CHANGELOG.md`, a `modrinth.md` (its Modrinth page) and a `boot-checks.txt` (CI picks that up on its own), a banner (add it to `docs/scripts/sync-brand.mjs`), and a folder in the docs. xUtilities is the example to copy.

## Releasing

For maintainers. Bump the plugin's `version` in its `build.gradle.kts`, rename `## Unreleased` in its `CHANGELOG.md` to `## <version>`, merge, then push a tag such as `xutilities-v2.1.0`. The release workflow checks the two match, then publishes to Modrinth and GitHub with that changelog section as the notes. Publishing to Modrinth needs the repo's `MODRINTH_TOKEN` secret.

Each plugin's Modrinth page is its `modrinth.md`. When one changes on `main`, [`modrinth.yml`](.github/workflows/modrinth.yml) publishes it, and every release publishes it again; edit the file, never the page on Modrinth, or the next sync overwrites the edit. Anything between `<!-- modrinth_exclude.start -->` and `<!-- modrinth_exclude.end -->` is left out. To check what would be sent without sending it: `MODRINTH_TOKEN=x ./gradlew :<plugin>:modrinthSyncBody -Pxena.modrinthDryRun=true --info`.

## Dependency updates

[Renovate](https://docs.renovatebot.com) opens PRs on Monday mornings ([`renovate.json`](renovate.json), on top of the org's shared settings in [`xena-studios/.github`](https://github.com/xena-studios/.github/blob/main/renovate-config.json)). Every minor and patch update comes in one PR that merges itself once CI passes; majors wait for a review. The minimum Paper in `gradle/libs.versions.toml` is never touched: raising it drops servers, so it's changed by hand.

New Minecraft versions come from [`minecraft.yml`](.github/workflows/minecraft.yml), which runs on Mondays or by hand from the Actions tab (**New Minecraft** → **Run workflow**). When Paper has a stable build of a newer version, it opens a PR that adds it to `xena.gameVersions` and moves the boot tests and the newest-API compile onto it. Merge it if the checks pass; Modrinth lists the plugins for it from their next release.
