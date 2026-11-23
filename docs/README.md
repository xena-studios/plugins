![Xena Studios plugin docs. The plugins' icons and the title "Plugin docs" over a stepped horizon in their accents, with "Setup, commands and config for every Xena Studios plugin."](../.github/assets/docs-banner.png)

**Setup, commands and config for every Xena Studios plugin.**

The source of **[plugins.xenastudios.co](https://plugins.xenastudios.co)**, a static [Starlight](https://starlight.astro.build) site served by Cloudflare. The docs describe the current releases, so a plugin change and its docs go in the same pull request.

## Develop

Needs Node 24 and pnpm.

```sh
pnpm install
pnpm dev          # http://localhost:4321, with hot reload
pnpm check        # type-check the pages, components and scripts
pnpm build        # check, then build the static site in dist/, with every internal link checked
pnpm brand:sync   # copy the brand assets in from a checkout of xena-studios/brand
pnpm catalog      # rebuild every list of the plugins from the brand catalog
```

`pnpm catalog:check` changes nothing, and fails if a list is out of date. The config and scripts are TypeScript, checked by `astro check`. The scripts run on Node directly, which strips their types.

## Layout

```
src/
  content/docs/      the pages, in Markdown: start/ for what the plugins share, one folder per plugin
  generated/         each plugin's default config.yml, written by the plugin's tests (don't edit)
  data/              the brand catalog, and the plugin data pnpm catalog reads and writes
  components/        the home inventory, download lists and horizon, and overrides of Starlight's header, sidebar, page title, footer and theme
  pages/404.astro    the not-found page, kept out of search engines
  routeData.ts       each page's sidebar and accent
  styles/            the plugin family's look (theme.css) and its game GUI (gui.css)
  assets/            logos, plugin icons and the horizon, synced from the brand repo
public/
  accents/           each plugin's accent colour, applied to its own pages (pnpm catalog)
  _headers           security headers; the build fills in the CSP's script hashes
  og.png             the link preview
  robots.txt         lets every crawler in, and points to the sitemap
  .well-known/security.txt   where to report security problems (RFC 9116)
scripts/
  catalog.ts         pnpm catalog
  sync-brand.ts      pnpm brand:sync
  headers.ts         runs after astro build
```

## Writing pages

- Each plugin's **Configuration** page shows its default config from `src/generated/<plugin>/config.yml`. That file comes from the plugin's tests, so it can't drift from the code. After changing a default or a comment in a plugin, run `./gradlew test -PupdateGolden` from the repo root and commit the result.
- Each plugin's **Changelog** and **Download** pages, and the version on its overview, are written by `pnpm catalog`: the changelog from `<plugin>/CHANGELOG.md`, the version from its `build.gradle.kts`. Edit those, not the pages.
- Pages are for server owners. Lead with what to do, show the exact config or command, and say what happens when it's wrong.
- Link to other pages with root-relative paths (`/zchat/formats/`). The build fails on a broken internal link.

## Look

The docs use the plugin family's brand: deepslate, Space Grotesk, dark only, and each plugin's pages in its own accent with its icon beside the title.

Logos, icons, the horizon, the favicon and the link preview come from [xena-studios/brand](https://github.com/xena-studios/brand). Don't redraw them here; run `pnpm brand:sync`. It expects the brand repo at `../../brand` (or set `BRAND_DIR`), and also refreshes the README banners in `.github/assets/` and the brand catalog in `src/data/catalog.json`, so run `pnpm catalog` after it.

## Deploying

Pushes to `main` that touch `docs/`, or a plugin's build files or changelog, build and deploy to Cloudflare ([`docs.yml`](../.github/workflows/docs.yml)). So do every release and a daily run, which keep the download pages current. Pull requests only build.

- The `CLOUDFLARE_API_TOKEN` secret lives in the `docs` environment, which only `main` can use, and only the deploy step gets it.
- **`public/.well-known/security.txt` expires on 2027-11-10.** Move the date a year out before then.
