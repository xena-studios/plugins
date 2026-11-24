---
title: Importers
description: "Bring your data into xSample from the plugin you used before."
pagefind: false
sidebar:
  order: 8
---

Each plugin xSample imports from gets a section: what comes across, what doesn't, and the command. Imports run with `/xsample import <plugin>`, which asks to confirm before it changes anything.

## From another plugin

1. Stop the server and back it up.
2. Start it with both plugins installed.
3. Run `/xsample import other`, then type it again to confirm.
