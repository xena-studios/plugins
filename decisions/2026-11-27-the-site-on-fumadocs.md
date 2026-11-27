# The site on Fumadocs and TanStack Start, with a page-weight budget (2026-11-27)

plugins.xenastudios.co moves from Astro Starlight to [Fumadocs](https://fumadocs.dev) (fumadocs-core and fumadocs-ui 16.19.7, fumadocs-mdx 15.6.4) on [TanStack Start](https://tanstack.com/start) 1.169.7, every page prerendered to static files and served by the same Cloudflare Worker with no server code. The owner's call: Starlight was holding the site back, Fumadocs is a better backbone, and the site should look clearly better in the same brand. Part of #120 and #162.

## Why TanStack Start

Fumadocs supports Next.js, React Router, Waku and TanStack Start. The owner chose TanStack Start: they run it in another project and maintain it, so it's the better long-term fit. It prerenders every route (`prerender` with `crawlLinks`, plus an explicit list of every page so the hidden `/<id>/next/` and old-major pages are built even though no link reaches them), and its static server functions (`@tanstack/start-static-server-functions`) write each page's data to a JSON file for client-side navigation, so nothing needs a server. Nothing about a static docs site needed another framework.

## What stays the same

- **Every address.** All 179 pages of the Starlight build (101 in its sitemap, the hidden `/<id>/next/` pages, the xsample redirect, `404.html`) are served by the new build, compared page by page; the sitemap is the old one plus the new pages (`/download/`, `/api/`, `/api/<plugin>/`, `/start/languages/`, `/start/versions/`), and every hidden page is still `noindex` and out of the sitemap and search.
- **The pages, as written.** `src/content/docs/**` is unchanged and so is how pages are written, because three plugins' pages were being written against Starlight while this moved. `src/mdx/starlight.ts` reads Starlight's Markdown: `:::note`/`:::tip`/`:::caution`/`:::danger` with titles, `<Code>` (turned into a highlighted code block), `@astrojs/starlight/components` (resolved to React ports with the same props), `.astro` imports (resolved to the `.tsx` beside them), `?raw` imports, and stray `:word` directives put back as text, as Starlight does. Frontmatter keeps Starlight's fields and meaning (`sidebar.order`/`label`, `pagefind`, `editUrl`, `template: splash`). xVanish's pages, written for Starlight, were built on the new site unchanged.
- **Every check.** The build still fails on a manifest that doesn't match `manifest.schema.json`, a broken internal link (now also a broken `#fragment`, which found nine on the download pages: versions from before this repo linking changelog headings that don't exist, now linking the changelog), a jar for a platform the manifest doesn't release on, and an out-of-date catalog. `routeData.ts` keeps Starlight's sidebar rules (one plugin at a time, one major at a time, `sidebar.order` then file name).
- **The strict CSP.** TanStack writes each page's route data as an inline script, different on every page, so hashing them all would mean a hash per page in one header. `scripts/postbuild.ts` instead moves each page's own inline script to a content-addressed file in `/assets/inline/` (loaded in the same place, so in the same order) and hashes the two every page shares; `script-src` is `'self'`, those hashes and `'wasm-unsafe-eval'` for search, and the build fails on any inline script the policy wouldn't allow.
- **Search is still Pagefind**, behind Fumadocs' search dialog. Fumadocs' own static index (Orama's successor) was 634 KB gzipped for this site, downloaded whole on first search; Pagefind's chunks load only what a query needs.

## The look

The plugin family's design system, as before: deepslate, the lavender accent and each plugin's own, Space Grotesk with JetBrains Mono for code and labels, the game GUI (beveled panels, inset slots, item tooltips), dark only (a light theme was tried and dropped by the owner). New:

- **The home, download and not-found pages are laid out as the other Xena sites** (xenastudios.co, brand.xenastudios.co): the `//` kicker over the title, hairline rows rather than card grids, the horizon at the top and the bottom, and the studio's footer, in the plugin design system. The home page's horizon is interactive: every plugin stands on its own stretch of the rim as a CSS 3D block, the chosen one lifts and turns while its stretch lights up and its item tooltip shows beside the copy; it moves on every few seconds, stops while pointed at or used, and doesn't move under `prefers-reduced-motion`. The old chest inventory came off the home page with this.
- **One header on every page, as the other Xena sites':** the XENA STUDIOS logo with "Plugins" as its label (as brand.xenastudios.co's "| Brand"), then Plugins, Download, Guides, Developers, GitHub and search; a menu on phones, and a sidebar button on docs pages. The brand puts XENA on a product site's footer only; the owner wanted the logo at the top too, and the footer keeps it.
- **A download page** (`/download/`): every plugin's latest jar with its date, Minecraft versions and servers, and every earlier version, read at build from Modrinth or GitHub as each plugin's own download page is.
- **Docs:** headings that link to themselves, a contents column, previous and next as GUI panels, reference tables in the GUI's material, and the overview of a rewritten plugin's newest major described from its manifest (version, platforms, Java), not the catalog (#162).

Checked with Impeccable's detector (`npx impeccable detect`) on every kind of page: what it flagged (two kickers above headings, a dark accent at 4.1:1, 10px labels, long lines) was fixed, but for the `//` kicker over each page's title, which is the brand's own.

## The page-weight budget

What a first visit to a page downloads, gzipped: the HTML, its CSS, every script it runs or preloads, the Latin fonts and its images. Search's index loads only when search opens and isn't counted.

| | JavaScript | In all |
| --- | --- | --- |
| **Budget (every page)** | **215 KB** | **340 KB** |
| Home | 195.7 KB | 304 KB |
| A docs page (`/start/installing/`) | 195.7 KB | 287 KB |
| A reference page (`/xutilities/next/commands/`) | 195.5 KB | 301 KB |
| Heaviest (`/xbans/next/configuration/`) | 195.5 KB | 313 KB |
| The Starlight site, for comparison | 4 to 6 KB | 32 to 41 KB |

`scripts/postbuild.ts` measures every page and fails the build over either limit. The JavaScript is the floor of a React app that hydrates: react-dom (about 60 KB), TanStack's router and serializer (about 35 KB), Fumadocs' layout and its Radix parts (about 45 KB), the page's own compiled content and the per-page route data. That's about 190 KB more than Starlight shipped, the cost of the framework the owner chose; what the budget buys is that it can't creep. Search, its engine and the page tree's data stay out of the first load, and every data-heavy component gets only its own slice of a manifest or release list as the page compiles (`src/lib/component-data.ts`), so a reference page doesn't carry a 130 KB manifest.

Lighthouse (mobile, simulated slow 4G and a 4x slower CPU, against `pnpm build` served locally): performance 99 on the home page, 99 on a docs page, 100 on a plugin overview, 99 on a reference page, 98 on a config page, 99 on the download page; accessibility, best practices and SEO 100 (69 SEO on a hidden page, which is `noindex` on purpose); total blocking time 0 ms and layout shift 0 everywhere. The floor is 95; the two Latin fonts are preloaded because without them a description re-wrapping when Space Grotesk arrived cost 0.23 layout shift.

## Not done, and what's next

- **Dokka at `/api/<plugin>/`.** The Gradle build doesn't produce it yet, so each `/api/<plugin>/` page says where the API is documented now (the guide, and javadoc.io once released). The plan, from M0's spike (`sandbox/m0/site/RESULTS.md`): add Dokka 2.3 to `xena.kmp-api` in build-logic; the Gradle CI job uploads each rewritten plugin's latest `api/build/dokka/html` as an artifact; the docs job copies it over `dist/client/api/<plugin>/` before `postbuild.ts`, whose CSP step then hashes Dokka's few inline scripts; only the latest API per plugin, for Cloudflare's per-version file count.
- **The manifest schema for editors** is served at `https://plugins.xenastudios.co/schemas/manifest.schema.json`, and the releases files' at `/schemas/releases.schema.json`.
