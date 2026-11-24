# M0 spike: reference pages generated from code (results)

Part of #121 ("Site pages generated from a plugin's code"). Everything below was run on 2026-11-23
(macOS arm64, Temurin 25, Gradle 9.8.2, Kotlin 2.4.21, kotlinx.serialization 1.13.0, Node 24.21,
pnpm 12.16.1, Astro 7.4.12, Starlight 0.42.6, zod 4.6.7, Dokka 2.3.0). Nothing here is wired into
the root build, and the docs part is a hidden route on this branch only.

## Summary

1. **Yes.** A plugin declares its commands, permissions, config, messages, placeholders and events
   once, as data in the core's DSL; the same objects run (a dispatcher test drives the declared
   tree) and a Gradle task turns them, plus the platform table from the build file, into one
   versioned `manifest.json` with a JSON Schema generated from the same Kotlin classes.
2. **Yes:** the docs read the committed manifest (`docs/src/generated/<plugin>/manifest.json`, the
   way `config.yml` goldens work today) through an Astro content collection validated against that
   schema with `z.fromJSONSchema`, and render it with seven small Starlight components in the site's
   existing table style. `pnpm build` passes (0 errors from `astro check`, all links valid).
3. **Stale output fails.** `./gradlew check` (or `./check.sh`) regenerates and diffs against the
   committed copy, naming file, line, committed and fresh values. A manifest the site can't read
   (wrong enum, misspelled key, another `formatVersion`) fails `pnpm build`. A translation using a
   tag its message doesn't fill in fails the Gradle task.
4. **Dokka fits the same deploy:** Dokka 2.3.0's HTML copied to `dist/api/<plugin>/` after
   `astro build` is served as-is, and the existing `scripts/headers.ts` picks up its six inline
   scripts for the CSP with no change. Watch the file count (325 files for a tiny module).
5. **Versioned docs: separate routes per plugin, not starlight-versions** (it versions the whole site
   at once, and overrides `PageTitle` and `ThemeSelect`, which this site already overrides).

## The design

```
<plugin>/common  (KMP)        xenaPlugin("xbans", ...) { permission(...); command(...) { executes {...} }
                               config(XBConfig); messages(XBMessages); placeholder(...); event(...) }
<plugin>/build.gradle.kts     xena { platform("paper", "Paper", "1.8.8", "latest", java = 8, ...) }
        │
        ▼  ./gradlew xenaManifest           (JavaExec on the plugin's JVM classes; no server)
<plugin>/build/xena/<id>/manifest.json      commands, permissions, config, messages + language
                   /<id>/config.yml         coverage, placeholders, events, platforms
                   /manifest.schema.json    JSON Schema 2020-12, from the serializer's descriptors
        │
        ├─ ./gradlew checkXenaManifest      (part of check) diff vs docs/src/generated → fail
        └─ ./gradlew updateXenaManifest     copy into docs/src/generated, commit
                                │
                                ▼
docs/src/content.config.ts      collection "references" = glob(*/manifest.json), schema =
                                z.fromJSONSchema(manifest.schema.json)  → build fails on bad shape
docs/src/components/…           <Platforms> <Commands> <Permissions> <Config> <Messages>
                                <Placeholders> <Events>, used from each plugin's MDX pages
```

- **One declaration, two consumers.** The DSL objects aren't doc comments: `Command` carries its
  handler and argument parsers, `ConfigSpec.Key` is what the plugin reads (`XDemoConfig.startingCoins`),
  `Permission` is what the dispatcher checks. The manifest is a pure function of them, so the docs
  can only describe what runs. `XDemoTest` drives the dispatcher with the declared tree (aliases,
  player-only, permissions, argument ranges) and checks the manifest says the same.
- **Built once, in the core.** `xenaPlugin` adds `/<id> reload` and `/<id> debug`, each with its own
  permission under `<id>.admin`, and the `<id>.admin` parent with both as children (ROADMAP.md,
  "Admin commands"); they appear in every plugin's manifest marked `builtIn`. Placeholders get their
  PlaceholderAPI, MiniPlaceholders and Text Placeholder API spellings from the core, not the plugin.
- **Checks at declaration time.** A permission a command uses must be declared; a required argument
  can't follow an optional one; a choice's default must be one of its choices; a message's declared
  placeholders must appear in its English text and it may use no other tags but styles.
- **Translations.** The writer reads `lang/*.yml` (every language but English), fails on unknown keys
  and on tags the message doesn't fill in, and records per-language coverage and missing keys: the
  roadmap's "how complete each language is" page is the `languages` array.
- **Platforms come from the build**, not code: `xena { platform(...) }` passes them to the writer.
  In M1 these come from one table in build-logic (ROADMAP.md, "Platforms per plugin") that also
  drives the release's Modrinth loaders and game versions, so docs and stores can't disagree.
- **Versioned format.** `formatVersion` is `const: 1` in the schema, so a site that hasn't been
  taught format 2 fails its build instead of rendering half a page. The manifest is deterministic
  (declaration order, no timestamps, trailing newline), so diffs in PRs read like the change.
- **Schema from code.** `jsonSchema()` walks `Manifest.serializer().descriptor`: classes become
  `$defs`, never-null properties are required (the writer encodes defaults, omits nulls),
  `additionalProperties: false`, and each `@Doc` (a `@SerialInfo` annotation) becomes a
  description. No kotlin-reflect. The one reflective call is at build time: loading the `object` the
  build names (`Class.forName(...).getField("INSTANCE")`).

## What was run

From `sandbox/m0/site/`:

```
$ ./gradlew test xenaManifest
Wrote xdemo: 6 commands, 7 permissions, 6 messages, 2 placeholders, 2 events, 8 platforms
BUILD SUCCESSFUL
$ ./gradlew check            # before anything was committed to docs/
> The docs' generated reference is out of date:
    manifest.schema.json: not committed
    xdemo/config.yml: not committed
    xdemo/manifest.json: not committed
  If the change is intended, run ./gradlew updateXenaManifest and commit the result.
$ ./gradlew updateXenaManifest && ./gradlew check
Generated reference up to date (…/docs/src/generated)
```

A changed default (`starting-coins` 100 → 150 in `XDemo.kt`), then `./check.sh`:

```
Execution failed for task ':xdemo:checkXenaManifest' (registered by plugin 'xena.manifest').
> The docs' generated reference is out of date:
    xdemo/config.yml: line 2
      committed: starting-coins: 100
      fresh:     starting-coins: 150
    xdemo/manifest.json: line 273
      committed:       "default": 100,
      fresh:           "default": 150,
  If the change is intended, run ./gradlew updateXenaManifest and commit the result.
```

A German line using a tag its message doesn't fill in (`pay.cooldown: "<error>Warte <zeit>."`):

```
java.lang.IllegalStateException: lang/de.yml: pay.cooldown uses <zeit>, which pay.cooldown doesn't fill in
```

From `docs/` (`corepack pnpm install --frozen-lockfile && corepack pnpm build`): `astro check`
0 errors, 0 warnings, 31 files; 103 pages built including `/spike/manifest/xdemo/`; all internal
links valid; `_headers: 3 inline script hashes`. The `i18n` collection warning in the log is there
on `main` too. Hand-broken manifests, each rebuilt with `astro build`:

```
"status": "shipped"         platforms.0.status: Invalid option: expected one of "release"|"planned"
"permision": "xdemo.coins"  commands.0: Unrecognized key: "permision"
"formatVersion": 2          formatVersion: Invalid input: expected 1
```

The page was looked at in `astro preview` at 1280 px: platform table with Planned badges, commands
with their arguments, ranges, aliases and permission, permissions with defaults and children, the
config table with sections and Restart badges plus the default `config.yml`, messages with language
coverage meters, placeholders in each plugin's spelling, and events. A first layout with the
permission in a third column scrolled sideways (the theme keeps the first column's code unbroken);
the permission now sits under the usage.

## Dokka at /api/<plugin>/ (quick check)

`./gradlew :core:dokkaGenerateHtml` (Dokka 2.3.0, `moduleName = "xdemo-api"`) on this spike's
`core` module (the DSL and manifest classes): **325 files, 4.3 MB** (270 HTML, 31 SVG, 10 fonts, 7 JS, 5 CSS).
Fonts and scripts are local; the only external URL is a link to Dokka's GitHub. Pages link with
relative paths (`var pathToRoot = "../../"`), so they work under any prefix.

Copied to `dist/api/xdemo/` after `astro build` and before `node scripts/headers.ts`:
`_headers: 9 inline script hashes` (Starlight's 3 plus Dokka's 6: the `no-js` swap, its dark-mode
check and one `pathToRoot` per depth), `wrangler deploy --dry-run` reads the assets fine, and
`/api/xdemo/` serves. Its `navigation.html` and search data load with `fetch`, which `connect-src
'self'` allows. Pagefind leaves it out, since Starlight marks its own pages with
`data-pagefind-body`.

For M1:
- The Gradle CI job uploads each plugin's `api/build/dokka/html` as an artifact; the docs job (which
  has no JDK) downloads it, and a `docs/scripts/api.ts` copies it to `dist/api/<plugin>/` between
  `astro build` and `headers.ts`. Generated, never committed.
- Count files. Cloudflare Workers static assets allow 20,000 files per version on the free plan
  (100,000 on paid). Today's site is 285 files. Fourteen real APIs at several hundred to a couple of
  thousand files each can approach 20,000, so keep only the latest API per plugin under `/api/`,
  and link older ones on javadoc.io (already published from Maven Central).
- `/api/*` gets its own `Cache-Control` in `_headers`, and the Developers page (`ApiList.astro`)
  links `/api/<plugin>/` instead of javadoc.io once a plugin's rewrite ships.

## Versioned docs (quick check)

`starlight-versions` 0.10.2 (2026-10-30, peer `@astrojs/starlight >=0.39.0`, so it installs here)
read from its package, not built:
- It versions the **whole site**: adding a version copies every page under `src/content/docs/` into
  `src/content/docs/<slug>/` and writes `src/content/versions/<slug>.json`. Our plugins move to the
  rewrite one at a time (M2 to M15), so "the whole site at 2.x" isn't a real version: xUtilities
  would be on 3.0 while zChat is still on today's 3.x.
- It overrides `ThemeSelect`, `PageTitle`, `Search` and `Banner`. This site already overrides
  `ThemeSelect` and `PageTitle`, so we'd have to render its `VersionSelect` and `VersionNotice`
  inside ours by hand.

So per plugin, plain routes:
- `/<plugin>/` is always the newest major. When a plugin's rewrite ships, its current pages move to
  `src/content/docs/<plugin>/v<old major>/` (for example `/xutilities/v2/`), frozen except for
  `maintenance` fixes, with a "you're reading 2.x" aside and a link to the same page in the new
  major. `routeData.ts` already scopes the sidebar per plugin, so it can hide `v2/` from the newest
  major's sidebar and show only `v2/` pages inside it.
- Generated data is per major: `docs/src/generated/<plugin>/manifest.json` from `main`, and
  today's `config.yml` goldens stay where they are for the old major until `maintenance` retires
  (M20), when the `v<old>/` folders are deleted.
- A small version switcher in `PageTitle.astro`, reading the catalog's majors, replaces the
  plugin's version dropdown.

## Files

| | |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew*` | Its own build (Gradle 9.8.2 wrapper, copied from the root). |
| `core/…/Plugin.kt` | `xenaPlugin { }`: permissions, commands, placeholders, events; adds the admin subcommands. |
| `core/…/Commands.kt` | Commands, typed arguments with parsers, and a stand-in dispatcher. |
| `core/…/Config.kt` | `ConfigSpec`: typed keys in file order, the default `config.yml`, the config tree for docs. |
| `core/…/Messages.kt` | `MessageSpec`, and the translation check (unknown keys, foreign tags, coverage). |
| `core/…/Manifest.kt` | The manifest classes, `@Doc`, `FORMAT_VERSION`. |
| `core/…/Schema.kt` | JSON Schema from the serializer's descriptors. |
| `core/…/Dump.kt` | The writer the Gradle task runs. |
| `buildSrc/…/xena.manifest.gradle.kts`, `XenaExtension.kt`, `CheckGenerated.kt` | `xena { }`, `xenaManifest`, `checkXenaManifest` (on `check`), `updateXenaManifest`. |
| `xdemo/` | The fake plugin: `XDemo.kt`, `lang/de.yml` and `lang/pt-BR.yml`, `XDemoTest.kt`. |
| `check.sh` | Fails when the committed reference is stale. |
| `docs/src/generated/manifest.schema.json`, `docs/src/generated/xdemo/{manifest.json,config.yml}` | The committed output. |
| `docs/src/content.config.ts` | The `references` collection, validated against the schema. |
| `docs/src/components/spike/*.astro`, `manifest.ts` | The seven reference components and the manifest's TypeScript types. |
| `docs/src/pages/spike/manifest/[plugin].astro` | The hidden page: `/spike/manifest/xdemo/` (noindex, out of search, not in the sidebar). |

## Recommendations for M1

1. **Put the DSL in `core-common`** (KMP), as above: commands, permissions, `ConfigSpec`,
   `MessageSpec`, placeholders and events as data with their runtime behaviour attached. The
   platform adapters register the same tree with Cloud (JVM) or Pumpkin's commands, and generate
   `plugin.yml` permissions, `fabric.mod.json` and `velocity-plugin.json` entries from it, so those
   can't drift either.
2. **The manifest writer runs on the JVM target only** (a `jvmMain` entry point and a
   build-logic convention plugin, `xena.reference`), since the declarations are common code. Keep
   kotlinx.serialization (already proven on `wasmWasi`) for the model and the schema walk.
3. **Fold the checks into the existing golden flow**: `-PupdateGolden` updates the manifests too,
   and `./gradlew check` fails when they're stale, so one command fixes every generated file. The
   Gradle CI job runs it on every PR. It needs no server and takes seconds.
4. **Events from the API, not by hand.** The spike lists events in the DSL; in M1 read them from the
   `api` module instead (an `@XenaEvent` marker plus a Dokka plugin or the ABI dump), so an event
   can't exist without being documented.
5. **Generate the TypeScript types from the schema** (`json-schema-to-typescript`, committed as
   `docs/src/generated/manifest.d.ts`) instead of the hand-written `manifest.ts`, and serve the
   schema at `plugins.xenastudios.co/schemas/manifest-v1.json` for editors.
6. **Pages, not a reference route.** Each plugin's `commands.mdx`, `permissions.mdx` and
   `configuration.mdx` import the components with a short hand-written intro above (what to do,
   what happens when it's wrong), as `configuration.mdx` already does with `config.yml`. The spike
   route and `src/components/spike/` are deleted then.
7. **One table for platforms** in build-logic, feeding the manifest, the release's store uploads and
   a `platforms` check against the brand catalog's words ("For Paper and Folia").
8. **Exclude generated-only routes from the sitemap** if any stay hidden. Starlight's sitemap lists
   `/spike/manifest/xdemo/` today even though it's `noindex`. That's harmless for a spike, wrong for
   a real page.
