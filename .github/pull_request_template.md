## What this changes

<!-- What changes for server owners, and why. Link the issue it fixes: "Fixes #123". -->

## Checklist

- [ ] `./gradlew build` passes
- [ ] Tests cover it, and a new command or feature has a line in `boot-checks.txt`
- [ ] Docs pages are updated, plus `docs/src/generated/` if a default config changed (`./gradlew test -PupdateGolden`)
- [ ] A line in the plugin's `CHANGELOG.md` under `## Unreleased`
- [ ] A new decision is a file in `decisions/` ([how](https://github.com/xena-studios/plugins/blob/main/CONTRIBUTING.md#decisions)); DECISIONS.md is left to the release
- [ ] It keeps [the rules](https://github.com/xena-studios/plugins/blob/main/CONTRIBUTING.md#what-a-change-has-to-keep-true): no repeating tasks, listeners only while on, settings apply on reload
- [ ] Nothing copied from another plugin: code a second plugin needs goes in the core first, in its own PR (#48)
