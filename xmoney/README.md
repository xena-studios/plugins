![xMoney by Xena Studios. An emerald pixel coin icon and the xMoney name over a stepped horizon in emerald, with the headline "The economy and Vault, in one plugin."](../.github/assets/xmoney-banner.png)

**The economy and Vault, in one plugin.**

xMoney is an economy plugin for Paper and Folia with Vault built in: shops, jobs and vote plugins written for Vault or VaultUnlocked find it, and the money, with no Vault jar installed. ("xMoney" is a working name.)

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **One jar instead of two.** It carries Vault's and VaultUnlocked's APIs and answers both.
- **A ledger of every change:** who, how much, the balance after, and why. `/eco history` shows it.
- **You won't feel it running.** Every balance is in memory, so Vault calls never wait on the disk, from any thread. One background thread saves each change with its ledger line, in the same transaction, as it happens.
- **Whole-number amounts,** so nothing drifts, and NaN, negative or huge amounts from other plugins are refused.
- **Networks** on one MySQL or MariaDB database (experimental in 1.0): balances shared, and never spent twice.
- **Easy to switch to.** Imports from EssentialsX, CMI, or the economy registered with Vault now, with a dry run first.
- **Every change applies on reload**, except where balances are kept, which takes a restart.

**[Download on Modrinth](https://modrinth.com/plugin/xmoney)** · [Docs](https://plugins.xenastudios.co/xmoney/) · [Commands](https://plugins.xenastudios.co/xmoney/commands/) · [Switching](https://plugins.xenastudios.co/xmoney/switching/) · [Developer API](https://plugins.xenastudios.co/xmoney/developer-api/) · [Changelog](CHANGELOG.md)

Needs Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. Uses SQLite out of the box, or MySQL/MariaDB. Replaces Vault: don't install Vault or VaultUnlocked beside it. PlaceholderAPI is optional.

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xmoney:build       # compile, test, and check the jar's size budget
./gradlew :xmoney:runServer   # a Paper server with xMoney, in xmoney/run/
XR_MYSQL=127.0.0.1:3306:xr:root:pw ./gradlew :xmoney:test   # also two servers on a real MariaDB
```

```
api/                 the developer API (co.xenastudios.xmoney:xmoney-api), shipped in the jar unrelocated
src/main/java/co/xenastudios/xmoney/
  XMoney.java        the plugin: reads every balance at startup, registers Vault and the API, sets up commands
  Bank.java          what Vault, the API and the commands share: the ledger, the format, the limits
  money/             fixed-point amounts (longs of ten-thousandths), formatting and parsing
  ledger/            accounts in memory, the ledger, its one writer thread, the tables
  vault/             the Vault 1 and VaultUnlocked 2 economies, the Permission fallback, the leftover-jar warning
  command/           /balance, /pay, /baltop, /eco, /xmoney import and status
  imports/           EssentialsX, CMI and Vault balances, and import plans
  hook/              the API, its events, and PlaceholderAPI
  listen/            joins: the one listener
vault-consumer/      never released: a plugin.yml plugin calling Vault the ways shops do
hook-consumer/       never released: a Paper plugin charging through the core's VaultEconomy
boot-checks.txt                   console commands CI runs on real Paper and Folia servers
boot-checks-vaultunlocked.txt     the same beside a real VaultUnlocked jar, which Paper refuses
```

The shared core (reloading, config migration, the theme, the database, the change feed, offline players, chat pages) is in [`core/`](../core).

## Licence

[GPL-3.0-only](../LICENSE), like the rest of the repo. The jar also contains the classes of [VaultUnlockedAPI](https://github.com/TheNewEconomy/VaultUnlockedAPI) 2.20 (with [Vault's API](https://github.com/MilkBowl/VaultAPI)), unmodified, under the LGPL-3.0; the licence texts and a notice are in `src/main/resources/META-INF/licenses/` and in the jar.
