**Every change, logged. Any of it, undone.**

xRewind is a block logger with rollback for Paper and Folia: who placed, broke or took what, where and when, and a way to undo it. **It's being built and isn't released yet;** the plan is [#20](https://github.com/xena-studios/plugins/issues/20).

- **Every feature free.** No early-access builds, nothing behind a Patreon.
- **New Minecraft versions the same week.** Paper's API only, no server internals, so an update rarely breaks it.
- **You won't feel it running.** Nothing touches the database on a game thread: changes are queued as a few numbers and written in batches.
- **Easy to switch to.** CoreProtect's command syntax, and its history imported.

Needs Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer. Uses SQLite out of the box, or MySQL/MariaDB.

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xrewind:build       # compile, test, and check the jar's size budget
./gradlew :xrewind:runServer   # a Paper server with xRewind, in xrewind/run/
```

```
src/main/java/co/xenastudios/xrewind/
  XRewind.java     the plugin: declares the config and, in setup, the features it turns on
  storage/         the database: its tables, and opening it off the server's threads
```
