## What this changes

<!-- What changes for server owners, and why. Link the issue it fixes: "Fixes #123". -->

## Checklist

- [ ] `./gradlew build` passes
- [ ] Tests cover it, and a new command or feature has a line in `boot-checks.txt`
- [ ] Docs pages are updated, plus `docs/src/generated/` if a default config changed (`./gradlew test -PupdateGolden`)
- [ ] A line in the plugin's `CHANGELOG.md` under `## Unreleased`
- [ ] It keeps [the rules](https://github.com/xena-studios/plugins/blob/main/CONTRIBUTING.md#what-a-change-has-to-keep-true): no repeating tasks, listeners only while on, settings apply on reload
