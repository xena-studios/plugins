## What this changes

<!-- What changes for server owners, and why. Link the issue it fixes, if any: "Fixes #123". -->

## Checklist

- [ ] `./gradlew build` passes
- [ ] Tests cover the change, and a new command or feature has a line in the plugin's `boot-checks.txt`
- [ ] The docs pages that describe it are updated, and `docs/src/generated/` too if a default config changed (`./gradlew test -PupdateGolden`)
- [ ] A line in the plugin's `CHANGELOG.md` under `## Unreleased`
- [ ] Still no repeating tasks, listeners only while their feature is on, and every setting applies on reload ([CONTRIBUTING.md](https://github.com/xena-studios/plugins/blob/main/CONTRIBUTING.md#what-a-change-has-to-keep-true))
