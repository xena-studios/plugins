# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

- **Server owners first:** people running Minecraft servers on Paper, Purpur or Folia, from small community servers to networks, who are choosing plugins (often replacing EssentialsX, LiteBans, CoreProtect, NuVotifier/VotingPlugin, Vault economies or zip-based backups) and then setting them up.
- **Plugin developers second:** people building on the plugins' developer APIs (Maven Central, `co.xenastudios.<plugin>:<plugin>-api`).

## Product Purpose

The site (`docs/`, deployed as a static Cloudflare Worker; at plugins.xenastudios.co) is the home of Xena Studios' plugin family. A visit should end in a download from Modrinth, then answer setup questions quickly: commands, permissions, config, switching from another plugin. Success is an owner who picks a plugin, downloads it, and gets it running without asking for help.

## Positioning

A family of small plugins that each do one job, built on one shared core: every setting applies on reload (apart from some plugins' database settings), configs carry forward on update, nothing runs in the background unless a feature needs it, databases are kept off the game threads, and every jar has a size budget the build enforces. Free and open source (GPL-3.0-only), every feature, no premium tier. Where a plugin replaces a popular one, it speaks its commands and imports its data; the other plugin is named only as compatibility, never in a headline.

## Operating Context

- Owners arrive from Modrinth, search or a link, compare against the plugin they use today, download from Modrinth (downloads count there) or, while Modrinth is still reviewing the plugin, from its GitHub releases, and come back for setup and reference.
- Each plugin has its own pages: overview, download (versions read at build time from Modrinth's API, or from the plugin's GitHub releases while Modrinth is still reviewing it), commands, configuration and changelog, plus switching guides and a developer API page where it has them.
- Everything listing the plugins is generated from the brand repo's catalog (`docs/src/data/plugins.json` via `docs/scripts/catalog.ts`), so the site must keep working as plugins are added.

## Capabilities and Constraints

- Fumadocs on TanStack Start, every page prerendered to static files, strict CSP (each page's own inline script moves to a file and the shared ones are hashed, by `docs/scripts/postbuild.ts`; fonts are self-hosted files). No server code, and a page-weight budget the build enforces.
- Dark only today.
- Eleven plugins, all released: xUtilities, zChat, xLimbo, xRewind, xPlots, xTeleport, xVote, xBans, xMoney, xBackup, xStats. Categories: Players, Moderation, Economy, Server.
- A plugin whose Modrinth project is still in Modrinth's review has no public Modrinth page (it's a 404 to everyone else), nor does an unreleased one: never link one. The plugins in review are listed in `docs/src/data/modrinth.json`, which `docs/scripts/catalog.ts` reads; until Modrinth approves one, its downloads come from its GitHub releases.

## Brand Commitments

Confirmed binding (owner, 2026-11-18): keep the plugin family brand from xena-studios/brand: each plugin's 16×16 pixel glyph icon and its own accent colour, the XENA STUDIOS endorsement, and the planet horizon. The site's layout and feel may change completely. Plugin names keep their lowercase prefix (xBans, zChat).

## Evidence on Hand

- Real numbers from the build: each plugin's jar budget (`jarBudgetKb`, largest 530 KB), which run on Folia (9 of 11), Modrinth version history and download sizes (the GitHub releases' for a plugin still in Modrinth's review).
- Brand assets: icons, banners and social cards in xena-studios/brand (`plugins/<id>/`); `pnpm brand:sync` copies the icons to `docs/src/assets/plugins/`; the READMEs link the banners from the brand's v1 release.
- No testimonials, customer logos, benchmarks beyond the docs' own measured pages (for example xBackup's performance page), or download counts are curated for the site: don't invent any.

## Product Principles

1. Every claim is true and checkable against the code or the docs.
2. Download first, then setup: the path from "what is this" to a running plugin is short.
3. The family is the selling point: plugins share a look, a config style and a core, and work together.
4. Lightweight is the promise, so the site must be fast too.
5. A new plugin appears everywhere on the site without hand edits.
