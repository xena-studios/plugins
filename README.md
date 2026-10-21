# Xena Studios plugins

Small, fast plugins for Paper servers, by [Xena Studios](https://xenastudios.co). Each one does one job, costs next to nothing to run, and lets you switch off anything you don't use.

| | Plugin | | |
|---|---|---|---|
| <img src="docs/src/assets/plugins/xutilities.svg" width="40" alt=""> | **xUtilities** | Every command you need. None you don't. | [Docs](https://docs.xenastudios.co/xutilities/) · [Modrinth](https://modrinth.com/plugin/xutilities) |
| <img src="docs/src/assets/plugins/zchat.svg" width="40" alt=""> | **zChat** | Format your chat. Keep it clean. | [Docs](https://docs.xenastudios.co/zchat/) · [Modrinth](https://modrinth.com/plugin/zchat) |
| <img src="docs/src/assets/plugins/xlimbo.svg" width="40" alt=""> | **xLimbo** | Never drop a player again. | [Docs](https://docs.xenastudios.co/xlimbo/) · [Modrinth](https://modrinth.com/plugin/xlimbo) |

## What's here

```
docs/    docs.xenastudios.co: the docs for every plugin (Starlight)
```

The plugins' source moves into this repo, one folder per plugin plus shared code, as each one is rebuilt. Until then, the released jars are on [Modrinth](https://modrinth.com/organization/xena-studios), and the docs describe those releases: xUtilities 1.0.1, zChat 2.0.0 and xLimbo 1.0.1.

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
