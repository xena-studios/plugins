# Security policy

The plugins run inside other people's servers, so we take security reports seriously.

## Reporting a vulnerability

**Don't open a public issue for a security problem.**

- Use GitHub's [private vulnerability reporting](https://github.com/xena-studios/plugins/security/advisories/new) on this repository, or
- email **[security@xenastudios.co](mailto:security@xenastudios.co)**.

Include the plugin and its version (`/<plugin> info`), your server software and version, steps to reproduce, and the impact you believe it has.

## What to expect

- Acknowledgement within **72 hours**
- An initial assessment within **7 days**
- A fixed release as soon as one is ready, ahead of other work, with a GitHub security advisory
- Credit in the advisory and the changelog, if you'd like it

## Supported versions

Fixes go into the newest release of each plugin. Older majors (xUtilities 1.x, zChat 1.x and 2.x, xLimbo 1.x) don't get fixes; update to the newest from the plugin's download page, `https://plugins.xenastudios.co/<plugin>/download/`.

## Scope

In scope:

- Every plugin released from this repository, its developer API, and the shared core
- Ways for a player to get past a permission, a filter or a protection: running a command they shouldn't, injecting formatting or click actions into chat, breaking xLimbo's floor or leaving its world, changing an xPlots plot that isn't theirs, getting round an xBans ban or mute, or skipping an xTeleport warmup or cooldown
- Money that doesn't add up: money made, copied or lost in xMoney, an xTeleport cost or xPlots rent not paid, or an xVote vote paid twice
- xVote's vote receiver, which takes connections from the internet: forged votes, or text from a vote getting into a reward command
- xStats' website: showing what it shouldn't (a private player, a stat that isn't on the site, a file outside it), running a script in a visitor's browser, or its head proxy fetching anything but heads
- xBackup: its passwords, keys and tokens getting into a backup or a remote copy, a restore writing outside the server folder, or a certificate, host key or downloaded library it doesn't check as the docs say
- Anything that lets a player, or anyone reaching xVote's receiver or xStats' website, crash or lag a server out of proportion to what they did
- The update checker's connection to Modrinth
- The docs site, [plugins.xenastudios.co](https://plugins.xenastudios.co)

Out of scope:

- Problems in Paper, Folia, the proxy, or other plugins, unless one of ours makes them reachable
- What server owners or their staff can do with permissions they've been given
- Denial of service through volume alone, such as flooding a server with connections
- xLimbo's `/join` being spoofed through a proxy that isn't set up to forward securely ([Securing the proxy](https://plugins.xenastudios.co/xlimbo/network-setup/#securing-the-proxy) covers this)
- Slow requests tying up xStats' website on the open internet with no reverse proxy or Cloudflare in front, which its docs ask for ([Limits](https://plugins.xenastudios.co/xstats/website/#limits) covers this)

## Safe harbour

We won't pursue legal action against good-faith research that follows this policy, tests only on servers you own or have permission to test, and avoids harm to other people's servers, players and data.
