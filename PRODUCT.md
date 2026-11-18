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

A family of small plugins that each do one job, built on one shared core: every setting applies on reload, configs carry forward on update, nothing runs in the background unless a feature needs it, databases are kept off the game threads, and every jar has a size budget the build enforces. Free and open source (GPL-3.0-only), every feature, no premium tier. Where a plugin replaces a popular one, it speaks its commands and imports its data; the other plugin is named only as compatibility, never in a headline.

## Operating Context

- Owners arrive from Modrinth, search or a link, compare against the plugin they use today, download from Modrinth (downloads count there), and come back for setup and reference.
- Each plugin has its own pages: overview, download (versions read from Modrinth's API at build time), commands, configuration, switching guides, developer API, changelog.
- Everything listing the plugins is generated from the brand repo's catalog (`docs/src/data/plugins.json` via `docs/scripts/catalog.ts`), so the site must keep working as plugins are added.

## Capabilities and Constraints

- Starlight on Astro, static output, strict CSP (inline scripts are hashed by `docs/scripts/headers.ts`; fonts are self-hosted files). No server code.
- Dark only today.
- Eleven plugins: xUtilities, zChat, xLimbo, xRewind (released); xPlots, xTeleport, xVote, xBans, xMoney, xBackup, xStats (built, releasing). Categories: Players, Moderation, Economy, Server.
- Unreleased plugins have no public Modrinth page: never link one.

## Brand Commitments

Confirmed binding (owner, 2026-11-18): keep the plugin family brand from xena-studios/brand: each plugin's 16×16 pixel glyph icon and its own accent colour, the XENA STUDIOS endorsement, and the planet horizon. The site's layout and feel may change completely. Plugin names keep their lowercase prefix (xBans, zChat).

## Evidence on Hand

- Real numbers from the build: each plugin's jar budget (`jarBudgetKb`, largest 500 KB), which run on Folia (9 of 11), Modrinth version history and download sizes.
- Brand assets: icons, banners and social cards in xena-studios/brand (`plugins/<id>/`), synced to `docs/src/assets/plugins/`.
- No testimonials, customer logos, benchmarks beyond the docs' own measured pages (for example xBackup's performance page), or download counts are curated for the site: don't invent any.

## Product Principles

1. Every claim is true and checkable against the code or the docs.
2. Download first, then setup: the path from "what is this" to a running plugin is short.
3. The family is the selling point: plugins share a look, a config style and a core, and work together.
4. Lightweight is the promise, so the site must be fast too.
5. A new plugin appears everywhere on the site without hand edits.
