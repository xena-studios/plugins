# Security policy

The plugins run inside other people's servers, so we take security reports seriously.

## Reporting a vulnerability

**Don't open a public issue for a security problem.**

- Use GitHub's [private vulnerability reporting](https://github.com/xena-studios/plugins/security/advisories/new) on this repository, or
- email **[contact@xenastudios.co](mailto:contact@xenastudios.co)** with "Security" in the subject.

Include the plugin and its version (`/<plugin> info`), your server software and version, steps to reproduce, and the impact you believe it has.

## What to expect

- Acknowledgement within **72 hours**
- An initial assessment within **7 days**
- A fixed release as soon as one is ready, ahead of other work, with a GitHub security advisory
- Credit in the advisory and the changelog, if you'd like it

## Supported versions

Fixes go into the newest release of each plugin. Older majors (xUtilities 1.x, zChat 1.x and 2.x, xLimbo 1.x) don't get fixes; update to the newest from [Modrinth](https://modrinth.com/organization/xena-studios).

## Scope

In scope:

- xUtilities, zChat and xLimbo, and the shared core in this repository
- Ways for a player to get past a permission, a filter or a protection: running a command they shouldn't, injecting formatting or click actions into chat, breaking xLimbo's floor or leaving its world
- Anything that lets a player crash or lag a server out of proportion to what they did
- The update checker's connection to Modrinth
- The docs site, [docs.xenastudios.co](https://docs.xenastudios.co)

Out of scope:

- Problems in Paper, Folia, the proxy, or other plugins, unless one of ours makes them reachable
- What server owners or their staff can do with permissions they've been given
- Denial of service through volume alone, such as flooding a server with connections
- xLimbo's `/join` being spoofed through a proxy that isn't set up to forward securely ([Securing the proxy](https://docs.xenastudios.co/xlimbo/network-setup/#securing-the-proxy) covers this)

## Safe harbour

We won't pursue legal action against good-faith research that follows this policy, tests only on servers you own or have permission to test, and avoids harm to other people's servers, players and data.
