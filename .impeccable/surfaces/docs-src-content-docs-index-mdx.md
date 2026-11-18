---
version: 1
slug: "docs-src-content-docs-index-mdx"
primary_target: "docs/src/content/docs/index.mdx"
related_targets: ["docs/src/components/Downloads.astro","docs/src/components/PageTitle.astro"]
---

---
version: 1
slug: "docs-src-content-docs-index-mdx"
primary_target: "docs/src/content/docs/index.mdx"
related_targets: ["docs/src/components/Downloads.astro","docs/src/components/PageTitle.astro"]
---

# Plugin site: home, plugin overviews, downloads, reading pages

Scope: the whole plugin site. Home and each plugin's overview are Persuade; download pages are Operate; commands, configuration, guides and changelogs are Read. Audience: server owners first, plugin developers second. Action: download from Modrinth, then set up. Constraint: keep the brand's glyphs, accents, XENA STUDIOS endorsement and horizon; avoid anything that still looks like docs.

## Direction contract

THESIS: The family is a chest inventory: every plugin is an item in a slot, and its tooltip is the pitch, the stats and the Download. It refuses the docs index of equal cards and a sidebar tree.

OWN-WORLD: Deepslate GUI. Panels raised with a 2px pixel bevel (light top-left, dark bottom-right), square corners, inset slots holding the 16px glyphs scaled pixel-crisp, and the item tooltip (near-black violet ground, violet-to-indigo 2px border) with the name in the plugin's accent, lore in grey and stats as attribute lines. Space Grotesk for names (brand), JetBrains Mono for stats. The accent appears only on a name, the selected slot and its Download.

STORY: An owner sees all eleven at once, moves through the slots by hover or keyboard, reads each tooltip, downloads the one they need, and the docs keep the same panels, slots and tooltips as the frame.

FIRST VIEWPORT: Left: the title, a one-line pitch and the creative-inventory search field. Right: the chest inventory, one row per category labelled at its start, with xUtilities selected; its tooltip docked beside it, Download as the primary action, Docs second.

FORM: Inventory and Item Tooltip, rank 1 on my list (my pick), seed key aaf00f47.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Signature interaction: the tooltip follows the pointer over slots and docks beside the inventory on keyboard focus; the chosen slot is framed in the plugin's accent (the white frame marks keyboard focus).
