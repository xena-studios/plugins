---
title: Setting up
description: Open xVote's receiver to server lists, give them its key or token, set your sites, and test a vote.
sidebar:
  order: 2
---

## The receiver

Server lists send votes to xVote's receiver, on port **8192** by default. It isn't your Minecraft port, and it has to be reachable from the internet.

1. Start the server once. xVote makes its keys in `plugins/xVote/rsa/` and a token in `plugins/xVote/tokens.yml`, and the console says `Listening for votes on 0.0.0.0:8192`. If NuVotifier was installed, its keys, tokens and port are taken over instead.
2. Open the port in your firewall, or ask your host for it (see [One port](#your-host-gives-you-one-port) below).
3. On each list's page for your server, fill in its Votifier settings:
   - **Address:** your server's address, without the Minecraft port.
   - **Port:** `8192`, or whatever `receiver.port` is.
   - **Public key** (lists that use Votifier v1): `/xvote key` shows it, or open `plugins/xVote/rsa/public.key`.
   - **Token** (lists that use Votifier v2, sometimes called NuVotifier): `/xvote token` shows it.
4. Most lists have a **Send test vote** button. Press it, then check the console for `Vote for <name> from <list>`. `/xvote status` shows the last few votes and the service name each list sends.

`receiver.port`, `receiver.host` and the limits apply on `/xvote reload`.

### Your host gives you one port

Many shared hosts give a server only its Minecraft port. The receiver needs a second one, and a plugin can't share the Minecraft port. Look for one of these in your host's panel:

- **Additional ports, or allocations** (Pterodactyl and Pelican panels: the *Network* tab). Add one and set `receiver.port` to it.
- **Port forwarding**, on a host that runs the server behind a router: forward the port you choose to the server.
- If neither is there, ask your host's support for "a second port for Votifier". It's a common request, and most hosts add one for free.

Lists then use the new port, not 8192.

### Keys and tokens

`rsa/public.key` and `rsa/private.key` are the RSA key pair for **v1**, the original Votifier protocol, which most lists still use. `tokens.yml` holds the tokens for **v2**: one `default` for every list, and any list that wants its own by its service name (`/xvote token PlanetMinecraft.com` makes one). Both are kept exactly as NuVotifier keeps them, so they copy across either way.

Keep `private.key` and the tokens secret: anyone with them can send votes as a list. `tokens.yml` is kept out of `config.yml` so you can share your config when asking for help.

### Limits

The receiver runs on its own threads and never touches the game. It turns away an address making more than `per-ip-per-minute` connections, closes a connection that hasn't sent its vote within `timeout-seconds`, and handles at most `max-connections` at once. A list's wrong token or key is logged once, not on every vote, until the next reload.

## Sites

`sites` lists the sites players vote on, shown by `/vote`:

```yaml
sites:
  planetminecraft:
    name: "Planet Minecraft"
    service: "PlanetMinecraft.com"
    link: "https://www.planetminecraft.com/server/your-server/vote/"
    cooldown-hours: 24
    rewards: []
```

- `service` is the name the list sends with its votes. Most lists say it on their Votifier page; otherwise press their test button and read it from `/xvote status`.
- `link` is your server's vote page on that list.
- `cooldown-hours` is how often the list lets players vote. A vote sooner than that, less five minutes for slow clocks, is a repeat and is dropped.
- `rewards` are given for this site's votes only, on top of the [triggers](/xvote/configuration/#rewards-and-triggers).

Votes from a list that isn't in `sites` still count and earn `every-vote` rewards; they just aren't shown in `/vote`.

## Test it

`/vote test <player> [site]` sends a signed vote to the receiver through its own port, as a list would, so it tests the whole path: the port, the token, the ledger and the rewards. Leave out the site for one with no cooldown. It counts like a real vote.

## Names

Lists send the name the player typed. xVote matches it to a player who is online, has voted before, or has joined the server. A name that has never joined waits; when someone with that name first joins, the votes are theirs and they're paid. `counting.unknown-name-days` is how long they wait.

Bedrock players through Floodgate have a prefix (`.` by default) that they often leave off when voting. A name nobody has is tried with `counting.bedrock-prefix`, and spaces in gamertags become underscores, as Floodgate makes them.

## Networks

For a network (Velocity or BungeeCord with several backends), no proxy plugin is needed:

1. Put every backend's xVote on the same MySQL or MariaDB database: `storage.type: mysql`, the same `mysql` settings and `table-prefix`.
2. Turn on `network.enabled` on every backend.
3. Run the receiver on one backend (turn `receiver.enabled` off on the others), and point every list at that backend's address and receiver port.

A vote for a player online on another backend reaches it within `network.check-every` seconds and is paid there. A vote for an offline player is paid by whichever backend they join first. Each vote is taken by one server in the database before it's paid, so it's never paid twice, even when two servers try at once. The vote party starts on every backend at the same time.

`storage` and `network` apply on restart.
