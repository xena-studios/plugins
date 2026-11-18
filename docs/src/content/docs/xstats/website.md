---
title: The website
description: Turn on xStats' stats website, put it behind your own domain safely, brand it, or host it anywhere.
sidebar:
  order: 5
---

xStats can serve a stats website itself: player search, a page for every player with their head, stats, ranks and the last 30 days, leaderboards for each period, and server totals, in your server's name, logo and colours. It's plain HTML, CSS and JavaScript, about 21 KiB, shipped inside the jar.

It runs on Java's own web server, so there's nothing else to install, and it can't change anything: it only answers `GET` requests, has no admin pages and nothing to log in to.

## Turn it on

It's off until you turn it on, so a port is never opened without you knowing.

```yaml
web:
  enabled: true
  port: 8804
  public-url: "https://stats.example.com"
```

Then `/xstats reload`. The console says where it's listening. `public-url` is the address players open: `/stats` links to it, and it's left out when blank.

`/xstats web test` checks the site works on your server's Java before you open anything to the internet: it starts a test copy on a free port on this machine, fetches each page, checks what comes back (including that nothing outside the site can be fetched), and stops it.

## Safe setups

Pick the one that fits your host.

**Behind your own domain, with a reverse proxy.** The usual setup on a VPS or dedicated server. Make xStats listen on this machine only, and let Caddy or nginx serve HTTPS:

```yaml
web:
  enabled: true
  bind: "127.0.0.1"
  port: 8804
  trusted-proxies: ["127.0.0.1"]
  client-ip-header: "X-Forwarded-For"
```

Caddy:

```
stats.example.com {
    reverse_proxy 127.0.0.1:8804
}
```

nginx:

```nginx
server {
    server_name stats.example.com;
    location / {
        proxy_pass http://127.0.0.1:8804;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

`trusted-proxies` makes the rate limit count each visitor by the address the proxy passes on, instead of counting every visitor as the proxy. Only list your own proxy's address: a header from anyone else is ignored. xStats reads only the header in `client-ip-header`, `X-Forwarded-For` by default, and takes the nearest address in it that isn't one of your proxies, so a visitor can't pick their own address by sending one: nginx and Caddy pass on whatever headers a visitor sends, and add the real address last. An IPv6 visitor is counted by their /64, which one household or server usually has whole. A port the proxy adds (`1.2.3.4:5678`, `[2001:db8::1]:443`) is ignored.

**Through a Cloudflare Tunnel.** For hosts with one port and no domain set up, or to keep your server's IP address private. Run `cloudflared` on the same machine, pointed at `http://127.0.0.1:8804`, with `bind: "127.0.0.1"` and `trusted-proxies: ["127.0.0.1"]`. Cloudflare adds the visitor's address to `X-Forwarded-For`, so the default works. Since only Cloudflare reaches `cloudflared`, `client-ip-header: "CF-Connecting-IP"` is safe here too.

**Behind Cloudflare's proxy, without a tunnel.** List [Cloudflare's address ranges](https://www.cloudflare.com/ips/) in `trusted-proxies` (such as `"173.245.48.0/20"`), and set `client-ip-header: "CF-Connecting-IP"`. Only choose `CF-Connecting-IP` when every request reaching xStats through a trusted proxy came through Cloudflare: anyone can send that header, and only Cloudflare replaces it.

**On a shared host (Pterodactyl and similar).** Ask your host for an extra port (an "allocation") and set `port` to it, keeping `bind: "0.0.0.0"`. The site is then at `http://<your server's address>:<port>/`. For your own domain and HTTPS, put Cloudflare in front, or use [Host it anywhere](#host-it-anywhere) with this server as the API.

## Limits

- `requests-per-minute` (120): requests one visitor may make each minute. Opening a page is a few. More get `429 Too Many Requests` until the minute is up. Heads, when they're proxied, have a limit of their own, five times this, since a leaderboard shows up to a hundred. If more than 10,000 addresses visit in one minute (a flood of made-up ones), addresses not seen yet that minute are let through without a count until the next minute, so a flood can't lock out real visitors. The limit on requests at once still protects the server.
- `max-concurrent` (32): requests handled at once. More wait up to two seconds, then get `503`; when far more than that wait, new connections are closed.
- Pages everyone sees (the server's page, every leaderboard) are made once after each save and sent from memory, compressed, with cache headers, so a busy site costs about as much as a quiet one. Leaderboards and search are checked with the server on every visit (a quick `304` when nothing changed), so a player who runs `/stats private` isn't kept in a browser or CDN; a player's own page may be kept for 30 seconds. A player's page is made when it's asked for, from memory, with their 30-day history read from the database and kept for a minute.

The web server stops cleanly on `/xstats reload` (when a web setting changed), when the plugin is disabled, and when the server stops. If it couldn't start (the port was taken), the next `/xstats reload` tries again.

**On the open internet, put a reverse proxy or Cloudflare in front.** xStats' server is Java's own, which gives a slow request 15 seconds and a slow reply 60, but can't tell a visitor trickling in a byte at a time from a slow connection; Caddy, nginx and Cloudflare can. Those two times are Java settings (`sun.net.httpserver.maxReqTime` and `maxRspTime`) shared by every plugin's web server on the same Java, so xStats sets them only if nothing else has, and if another plugin started a web server first, its settings apply.

## Branding

```yaml
web:
  branding:
    name: "My Server"
    tagline: "Survival since 2019"
    logo: "logo.png"
    accent: "#F77FA5"
    background: "#0F1419"
    surface: "#1A222B"
    text: "#E6EDF3"
```

`logo` is a link (`https://...`) or a PNG, JPEG, SVG or WebP file you put in `plugins/xStats/web/`. Colours are written `#RRGGBB`. Everything here, and every player's name, is shown as plain text: nothing in them can add HTML or scripts to the page.

## Heads

Players' heads come from `web.avatars.url`, with `{uuid}`, `{name}` and `{size}` filled in: [mc-heads.net](https://mc-heads.net) by default, or `https://crafatar.com/avatars/{uuid}?size={size}&overlay`. Each visitor's browser loads them from that service.

With `web.avatars.proxy: true`, xStats fetches heads itself and keeps each for a day (up to 8 MiB of them), so visitors' browsers never contact a third party. Only heads of players xStats knows are fetched, four at a time (up to 64 others wait up to five seconds for a turn, without holding up pages), each at most 64 KiB, following redirects only on the same host, so the proxy can't be used to fetch anything else.

## Which stats show

Only stats with `website: true` are on the site and in its API. Players who ran `/stats private` aren't on it at all: no page, not in search, not on its leaderboards, from the moment they run it. Places on the site are counted among the players it shows, so nobody's place gives away that someone private is above them.

Player pages are linked by uuid, so Bedrock players (through Floodgate, with names such as `.Steve`) have pages too. Searching by name works for both.

## Host it anywhere

The same site works as static files on GitHub Pages, Cloudflare Pages, Netlify or your own web host, reading this server's API.

1. Turn the web server on (`web.site: false` serves only the API, if you like), and set `public-url` to the address the API is reachable at, such as `https://api.example.com`.
2. Run `/xstats web export`. The site's files go to `plugins/xStats/site/`, with a `config.json` that points at `public-url`.
3. Upload that folder. To point it elsewhere later, edit `config.json`: `{"api": "https://api.example.com"}`.

For the browser to be allowed to read the API from another site, that site must be in `web.cors-origins`. The default `"*"` allows any site, which is safe here: the API is read-only and uses no cookies. List your site's address instead to allow only it.

Build your own instead? The [web API](/xstats/web-api/) is documented and versioned, and so are the [database tables](/xstats/database/).
