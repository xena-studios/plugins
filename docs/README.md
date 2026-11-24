![Xena Studios plugin docs. The plugins' icons and the title "Plugin docs" over a stepped horizon in their accents, with "Setup, commands and config for every Xena Studios plugin."](https://cdn.jsdelivr.net/gh/xena-studios/brand@1/github/plugin-docs-banner.png)

**Setup, commands and config for every Xena Studios plugin.**

The source of **[plugins.xenastudios.co](https://plugins.xenastudios.co)**: [Fumadocs](https://fumadocs.dev) on [TanStack Start](https://tanstack.com/start), every page prerendered to static files and served by Cloudflare, with no server code. The docs describe the current releases, so a plugin change and its docs go in the same pull request. Why this stack, and the page-weight budget it's held to: [decisions/2026-11-27-the-site-on-fumadocs.md](../decisions/2026-11-27-the-site-on-fumadocs.md).

## Develop

Needs Node 24 and pnpm.

```sh
pnpm install
pnpm dev          # http://localhost:4321, with hot reload
pnpm check        # type-check the pages' collection, components and scripts
pnpm build        # check, prerender every page to dist/client, then postbuild (below)
pnpm preview      # serve dist/client as Cloudflare does: _headers, _redirects, 404.html
pnpm brand:sync   # copy the brand assets in from a checkout of xena-studios/brand
pnpm catalog      # rebuild every list of the plugins from the brand catalog
```

`pnpm catalog:check` changes nothing, and fails if a list is out of date. The config and scripts are TypeScript, checked by `tsc`; the scripts run on Node directly, which strips their types.

`pnpm build` fails on: a type error; a manifest that doesn't match `manifest.schema.json`, or a releases file that doesn't match its schema; a broken internal link or `#fragment` on any built page; an inline script the CSP wouldn't allow; and a page over the page-weight budget. `scripts/postbuild.ts` runs those checks after Vite and also writes the redirects, the sitemap, the search index and the CSP's script hashes.

## Layout

```
source.config.ts     the pages' collection: their frontmatter (Starlight's fields) and Markdown (remark plugins below)
vite.config.ts       TanStack Start: prerender every page, the hidden ones too
src/
  content/docs/      the pages, in Markdown: start/ for what the plugins share, one folder per plugin
  generated/         each plugin's default config.yml, and each rewritten plugin's manifest.json and its schema, written by Gradle (don't edit)
  data/              the brand catalog, the plugin data pnpm catalog reads and writes, versions.json and releases/
  routes/            the pages' routes: $.tsx serves every page in content/docs; api/ the API reference; 404.tsx
  components/        the home page (Showcase, PluginList, Reasons, GetStarted, HelpBand), the download page, Starlight's components (starlight.tsx)
    reference/       a rewritten plugin's reference, from its manifest (<Reference plugin show />)
    plugin/          a rewritten plugin's install tabs and download picker
    layout/          the header, footers, title rows, plugin switcher and version switch
  mdx/starlight.ts   reads Starlight's Markdown: asides, <Code>, its components' imports, and each data component's data
  lib/               on the server: the pages (source.ts), what each page shows around its content (page.ts), the data components' data
  plugins.ts         every plugin with pages, which major a page describes, and where an unreleased rewrite's pages are served
  links.ts           points the links on an unreleased rewrite's pages where they're served
  redirects.ts       redirects for an old major's pages when a rewrite ships
  routeData.ts       each page's sidebar, previous and next, accent and whether it's a reading page
  styles/            the brand's tokens (tokens.css), the game GUI (gui.css), the docs (theme.css, components.css) and the home page (home.css)
  assets/            logos, plugin icons and the horizon, synced from the brand repo
public/
  accents/           each plugin's accent colour, inlined on its own pages (pnpm catalog)
  _headers           security headers; the build fills in the CSP's script hashes
  og.png             the link preview
  robots.txt         lets every crawler in, and points to the sitemap
  .well-known/security.txt   where to report security problems (RFC 9116)
scripts/
  catalog.ts         pnpm catalog
  sync-brand.ts      pnpm brand:sync
  postbuild.ts       runs after vite build
  serve.ts           pnpm preview
```

## Writing pages

- Each plugin's **Configuration** page shows its default config from `src/generated/<plugin>/config.yml`. That file comes from the plugin's tests, so it can't drift from the code. After changing a default or a comment in a plugin, run `./gradlew test -PupdateGolden` from the repo root and commit the result.
- Each plugin's **Changelog** and **Download** pages, and the version on its overview, are written by `pnpm catalog`: the changelog from `<plugin>/CHANGELOG.md`, the version from its `build.gradle.kts`. Edit those, not the pages.
- Pages are for server owners. Lead with what to do, show the exact config or command, and say what happens when it's wrong.
- Link to other pages with root-relative paths (`/zchat/formats/`). The build fails on a broken internal link, or a link to a heading that isn't there.
- **Pages are written as for Starlight**, and stay that way: its frontmatter (`title`, `description`, `sidebar.order`, `sidebar.label`, `pagefind`, `editUrl`, `template`), its asides (`:::note`, `:::tip`, `:::caution`, `:::danger`, each with an optional `[title]`), code blocks with `title="..."`, `{1,3}` and `"word"` marks, and its components: `import { Code, LinkCard, CardGrid, Tabs, TabItem, Badge, Aside, Steps } from "@astrojs/starlight/components"` resolves to `src/components/starlight.tsx`, and `import X from ".../components/<path>/X.astro"` to the React component `X.tsx` beside it (`src/mdx/starlight.ts`). A new component goes in as `X.tsx`, imported the same way.
- **Two site pages aren't Markdown:** `/download/` (every plugin's downloads, `<AllDownloads />` in `download.mdx`) and `/api/<plugin>/` (the API reference, `src/routes/api/`).

## The rewrite's pages

A rewritten plugin's docs are built from its code. Its Gradle build writes `src/generated/<plugin>/manifest.json` (commands, permissions, config, messages, placeholders, events and its platform table); the build checks every one against `src/generated/manifest.schema.json` (`src/lib/data.ts`), so a manifest the site can't read fails the build. Editors can point at the schema's stable address, `https://plugins.xenastudios.co/schemas/manifest.schema.json` (and a releases file at `/schemas/releases.schema.json`). `src/content/docs/xsample/` is the template, on the core's sample plugin: hidden (no navigation, search, sitemap or indexing; `src/data/versions.json`) but built, so every component is checked.

- **Pages:** overview, install, download, commands, permissions, configuration, messages, placeholders, integrations, importers, FAQ, developer API, changelog. A reference section is one line, `<Reference plugin="xsample" show="commands" />` (its rows are worked out as the page compiles, so a page carries only its own slice of the manifest) (also `platforms`, `permissions`, `config`, `messages`, `placeholders`, `events`), under a short hand-written intro. A section with nothing in it says so instead of showing an empty table.
- **Install:** `<InstallTabs plugin="..." />`, a tab per platform the manifest releases on.
- **Download:** `<DownloadPicker plugin="..." />`, from the manifest's platforms and `src/data/releases/<plugin>.json` (each jar's platforms and Minecraft versions, and the stores that carry it; its shape is in `src/lib/data.ts`). Before the page is interactive it's the full list; then two menus pick the one jar. A jar for a platform the manifest doesn't release on fails the build.
- **Languages and support:** `/start/languages/` is built from each rewritten plugin's `lang/*.yml` against its manifest (translated, machine-drafted or reviewed); `/start/versions/` is the support policy.
- **API reference:** `/api/<plugin>/` says where each API is documented until Dokka's pages for each rewritten plugin's latest API replace it (the plan is in the decision).
- **Versions:** when a plugin's rewrite is merged, move its current pages into `src/content/docs/<plugin>/v<old major>/`, write the new major's in `src/content/docs/<plugin>/`, and add it to `src/data/versions.json` as unreleased (below). Once it ships, `/<plugin>/` is the new major; the old one gets a sidebar of its own, a version switch under every title and a note that it's the old major, and each old page the new major doesn't have redirects from its old address (`src/redirects.ts`), so no link breaks.

### Unreleased rewrites

The rewrites are merged one by one but released together (ROADMAP.md, Release), so a rewrite's pages sit on `main` for a while before its release. Meanwhile the site shows the last release as it did before: its line in `src/data/versions.json` has `"unreleased"`, with the last release's version and jar budget, and then

- `/<plugin>/` serves the old major's pages (the files in `v<old>/`), at the addresses they had, with the plugin's catalog entry (version, jar budget) as it was;
- the new major's pages (the files in `<plugin>/`) are built at `/<plugin>/next/`, with every link checked, but hidden: out of the sidebar, search and the sitemap, `noindex`, and skipped by every other page's previous and next. Its download page says it isn't released yet;
- `pnpm catalog` writes the new major's changelog page from `<plugin>/CHANGELOG.md`, and the old major's from the same file without the new major's sections, so a fix release of the old major still shows.

Write every page as it will be after the release: the new major's links as `/<plugin>/commands/`, the old major's as `/<plugin>/v<old>/commands/`. `src/plugins.ts` serves each file where it belongs (`servedId`) and `src/links.ts` rewrites the links on the plugin's pages to match, before the links validator checks them, so a broken link fails the build either way. Pages outside the plugin's folder (the start pages) link to `/<plugin>/...` and get the current release either way.

**A new plugin's first major** (xVanish 1.0) has no old major to show meanwhile: its line is `{ "id": "xvanish", "name": "xVanish", "newest": 1, "older": [], "unreleased": {} }`, with no last release's facts. Nothing is served at `/<plugin>/` until it ships, and its pages, in `src/content/docs/<plugin>/`, are built at `/<plugin>/next/`, hidden, as a rewrite's are. It needs a `name` until its brand catalog entry is there, at its release.

**The switch, in the release's pull request, is one line:** delete `"unreleased": {...}` from the plugin's line in `src/data/versions.json`, then run `pnpm catalog`, which brings the build's version and jar budget into the catalog. The rest of that pull request is the release's own: `src/data/releases/<plugin>.json` for the download picker (the build fails without it once the plugin is released), `(unreleased)` and its note off the changelog's heading, and the start pages' requirements.

## Look

The docs use the plugin family's brand: its tokens (`tokens/plugins.css`, copied to `src/styles/tokens.css`), deepslate, Space Grotesk with JetBrains Mono for code and labels, dark only, its game GUI (beveled panels, inset slots, item tooltips; `gui.css`), and each plugin's pages in its own accent with its icon beside the title. The home, download and not-found pages are laid out as the other Xena sites are (xenastudios.co's DESIGN.md: the `//` kicker, hairline rows, the horizon at the top and the bottom) in the same design system, and the home page's horizon is interactive, every plugin standing on its stretch of the rim. Every page has the same header, laid out as the other Xena sites': the XENA STUDIOS logo, then Plugins, Download, Guides, Developers, GitHub and search. Search is Pagefind's index behind Fumadocs' dialog, loaded the first time it opens.

Logos, icons, the horizon, the favicon and the link preview come from [xena-studios/brand](https://github.com/xena-studios/brand). Don't redraw them here; run `pnpm brand:sync`. It expects the brand repo at `../../brand` (or set `BRAND_DIR`), and also refreshes the brand catalog in `src/data/catalog.json`, so run `pnpm catalog` after it. The READMEs' banners aren't copied: they link the brand's v1 release through jsDelivr.

## Deploying

Pushes to `main` that touch `docs/`, or a plugin's build files or changelog, build and deploy to Cloudflare ([`docs.yml`](../.github/workflows/docs.yml)). So do every release and a daily run, which keep the download pages current. Pull requests only build.

- The build output is `dist/client` (`wrangler.jsonc`); `dist/server` is only what prerendered it, and is never deployed.
- The `CLOUDFLARE_API_TOKEN` secret lives in the `docs` environment, which only `main` can use, and only the deploy step gets it.
- **`public/.well-known/security.txt` expires on 2027-11-10.** Move the date a year out before then.
