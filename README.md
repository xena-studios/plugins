# Xena Studios plugins

Small, fast plugins for Paper servers, by [Xena Studios](https://xenastudios.co). Each one does one job, costs next to nothing to run, and lets you switch off anything you don't use.

| | Plugin | | |
|---|---|---|---|
| <img src="docs/src/assets/plugins/xutilities.svg" width="40" alt=""> | **xUtilities** | Every command you need. None you don't. | [Docs](https://docs.xenastudios.co/xutilities/) · [Modrinth](https://modrinth.com/plugin/xutilities) |
| <img src="docs/src/assets/plugins/zchat.svg" width="40" alt=""> | **zChat** | Format your chat. Keep it clean. | [Docs](https://docs.xenastudios.co/zchat/) · [Modrinth](https://modrinth.com/plugin/zchat) |
| <img src="docs/src/assets/plugins/xlimbo.svg" width="40" alt=""> | **xLimbo** | Never drop a player again. | [Docs](https://docs.xenastudios.co/xlimbo/) · [Modrinth](https://modrinth.com/plugin/xlimbo) |

## What's here

```
build-logic/   the xena.plugin Gradle convention every plugin applies
core/          code every plugin shares, shaded into each jar
sandbox/       a plugin that's never released: boots the core on real servers in CI
xutilities/    xUtilities 2
zchat/         zChat 3
xlimbo/        xLimbo 2
scripts/       boot-test.sh (runs jars on a real Paper or Folia server), changelog.sh
docs/          docs.xenastudios.co: the docs for every plugin (Starlight)
```

The released jars are on [Modrinth](https://modrinth.com/organization/xena-studios), and the docs describe the current releases: xUtilities 2.0.0, zChat 3.0.0 and xLimbo 2.0.0. Why the plugins are built the way they are: [DECISIONS.md](DECISIONS.md).

## Plugins

Needs a JDK 21 or newer. Every plugin supports Paper 1.21.11 and newer (Java 21 bytecode), and Folia unless it says otherwise.

```sh
./gradlew build                  # compile, test, and check each jar against its size budget
./gradlew :<plugin>:runServer    # a Paper server with the plugin, in <plugin>/run/
scripts/boot-test.sh --project paper --version 1.21.11 \
  --run "sandbox selftest" --expect "SELFTEST PASS" sandbox/build/libs/*.jar
```

A new plugin is a folder with a `build.gradle.kts` that applies `id("xena.plugin")`, sets `version`, and names its main class in `paperPluginYaml`; add it to `settings.gradle.kts`. The convention shades in the core, generates `paper-plugin.yml`, sets a 100 KiB jar budget (`xena { jarBudgetKb = ... }`) and wires up Modrinth. The plugin class extends `XenaPlugin`: it declares its config as records (`ConfigSpec`) and, in `setup`, the commands and modules that config wants; the core does the rest, on startup and on every reload. xUtilities is the example to copy.

Each plugin also has:

- a `boot-checks.txt`: console commands CI runs on real servers, and the output each must log;
- a test that its default config matches `docs/src/generated/<plugin>/config.yml`, which the docs show. After changing a default or a comment, run `./gradlew test -PupdateGolden` and commit the file.

`./gradlew compileJava -Pxena.newestPaper=<paper-api version>` compiles against a newer Paper than the minimum and fails on anything it marks for removal; CI does this for the newest release.

CI builds every push and boots every plugin on Paper and Folia at 1.21.11 and the newest release. To release, bump the plugin's `version`, add a `## <version>` section to its `CHANGELOG.md`, and push a tag such as `xutilities-v2.0.0`: the release workflow publishes to Modrinth (needs a `MODRINTH_TOKEN` secret) and GitHub.

## Docs

Needs Node 24.

```sh
cd docs
npm install
npm run dev     # http://localhost:4321
npm run build   # static site in dist/, with every internal link checked
```

Pages are Markdown in `docs/src/content/docs/`, one folder per plugin and `start/` for what they share. The docs wear the plugin family's brand, as Raptor's docs wear Raptor's: deepslate, Space Grotesk, dark only, and each plugin's pages in its own accent (`docs/public/accents/`) with its icon beside the title. `npm run brand:sync` copies the logo, favicon, stepped horizon, link preview and plugin icons in from [xena-studios/brand](https://github.com/xena-studios/brand).

Pushes to `main` that touch `docs/` deploy to Cloudflare once the repo has a `CLOUDFLARE_API_TOKEN` secret and a `CLOUDFLARE_ACCOUNT_ID` variable; pull requests only build.

## Reporting bugs

Open an [issue](https://github.com/xena-studios/plugins/issues) with the plugin and version (`/<plugin> info`), your server software and version, and any console errors.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
