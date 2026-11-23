---
name: Xena Studios Plugins
description: The plugin family as a deepslate game GUI, a chest inventory whose item tooltips sell, specify and hand over each plugin.
colors:
  family-lavender: "#C9C2E0"
  family-lavender-low: "#2A2735"
  family-lavender-high: "#E6E1F5"
  deepslate-ground: "#121116"
  sidebar-ground: "#0F0E13"
  panel: "#1B1A21"
  panel-highlight: "#46434F"
  panel-shade: "#08070B"
  slot: "#0E0D12"
  slot-highlight: "#2E2C36"
  slot-shade: "#050407"
  edge: "#050407"
  tooltip-ground: "#100A18"
  tooltip-border-top: "#6E3CF0"
  tooltip-border-bottom: "#2C1478"
  tooltip-outline: "#050208"
  code-ground: "#0D0C10"
  rule: "#2A2832"
  text: "#F2F0F5"
  text-soft: "#E2E0E8"
  text-quiet: "#C4C1CD"
  muted: "#9C99A8"
  lore: "#A9A6B4"
  hint: "#8A8796"
  gray-deep: "#5E5B69"
  attribute: "#8FA8FF"
  on-accent: "#0B0A0E"
typography:
  display:
    fontFamily: "Space Grotesk Variable, system-ui, sans-serif"
    fontSize: "clamp(2.4rem, 1.4rem + 3.4vw, 4.75rem)"
    fontWeight: 700
    lineHeight: 0.98
    letterSpacing: "-0.035em"
  headline:
    fontFamily: "Space Grotesk Variable, system-ui, sans-serif"
    fontSize: "clamp(2.25rem, 1.5rem + 2.6vw, 3.75rem)"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.035em"
  title:
    fontFamily: "Space Grotesk Variable, system-ui, sans-serif"
    fontSize: "clamp(1.75rem, 1.3rem + 1.9vw, 2.75rem)"
    fontWeight: 700
    lineHeight: 1.12
    letterSpacing: "-0.02em"
  item-name:
    fontFamily: "Space Grotesk Variable, system-ui, sans-serif"
    fontSize: "1.375rem"
    fontWeight: 700
    letterSpacing: "-0.01em"
  lead:
    fontFamily: "Space Grotesk Variable, system-ui, sans-serif"
    fontSize: "clamp(1.0625rem, 1rem + 0.35vw, 1.25rem)"
    fontWeight: 400
    lineHeight: 1.6
  body:
    fontFamily: "Space Grotesk Variable, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
  lore:
    fontFamily: "Space Grotesk Variable, system-ui, sans-serif"
    fontSize: "0.9375rem"
    fontWeight: 400
    lineHeight: 1.55
  button:
    fontFamily: "Space Grotesk Variable, system-ui, sans-serif"
    fontSize: "0.9375rem"
    fontWeight: 700
    lineHeight: 1.2
  stat:
    fontFamily: "JetBrains Mono Variable, ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "0.8125rem"
    fontWeight: 400
    lineHeight: 1.7
    fontFeature: "\"tnum\""
  label:
    fontFamily: "JetBrains Mono Variable, ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    letterSpacing: "0.14em"
rounded:
  none: "0px"
spacing:
  px: "2px"
  slot-gap: "0.25rem"
  action-gap: "0.6rem"
  row-gap: "0.7rem"
  stack: "1.1rem"
  panel: "clamp(1.25rem, 3vw, 2rem)"
  section: "clamp(2rem, 4vw, 4.5rem)"
components:
  button-primary:
    backgroundColor: "{colors.family-lavender}"
    textColor: "{colors.on-accent}"
    typography: "{typography.button}"
    rounded: "{rounded.none}"
    padding: "0.55rem 1.15rem"
    height: "2.75rem"
  button-secondary:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.text}"
    typography: "{typography.button}"
    rounded: "{rounded.none}"
    padding: "0.55rem 1.15rem"
    height: "2.75rem"
  panel:
    backgroundColor: "{colors.panel}"
    rounded: "{rounded.none}"
  slot:
    backgroundColor: "{colors.slot}"
    rounded: "{rounded.none}"
    size: "clamp(2.6rem, 2rem + 1.6vw, 3.6rem)"
  slot-overview:
    backgroundColor: "{colors.slot}"
    rounded: "{rounded.none}"
    size: "clamp(6rem, 4rem + 6vw, 9.5rem)"
  tooltip:
    backgroundColor: "{colors.tooltip-ground}"
    textColor: "{colors.lore}"
    rounded: "{rounded.none}"
    padding: "1.1rem 1.2rem 1.2rem"
  search-field:
    backgroundColor: "{colors.slot}"
    textColor: "{colors.text}"
    rounded: "{rounded.none}"
    padding: "0.7rem 0.85rem"
  nav-link:
    textColor: "{colors.lore}"
    padding: "0.45rem 0.8rem"
  nav-link-current:
    backgroundColor: "{colors.slot}"
    textColor: "{colors.text}"
    padding: "0.45rem 0.8rem"
  sidebar-link:
    textColor: "{colors.muted}"
    rounded: "{rounded.none}"
  sidebar-link-current:
    backgroundColor: "{colors.slot}"
    textColor: "{colors.text}"
    rounded: "{rounded.none}"
  inline-code:
    backgroundColor: "{colors.slot}"
    rounded: "{rounded.none}"
    padding: "0.1em 0.4em"
  table-header:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.lore}"
    typography: "{typography.label}"
---

# Design System: Xena Studios Plugins

## Overview

**Creative North Star: "Inventory and Item Tooltip"**

The family is a chest inventory in a deepslate game GUI. Every plugin is an item in a slot; pointing at it shows its tooltip, and the tooltip is the pitch, the stats and the Download. The same three materials frame every page: raised panels with a 2px pixel bevel, slots that are the same bevel inverted, and the near-black violet item tooltip with its violet-to-indigo border. The header, navigation, page titles, panels, code and tables are built from them.

Inside that frame, reading stays calm. Commands, configuration, guides and changelogs keep a plain column of Space Grotesk on deepslate, so the GUI is the frame, not the reading surface. Colour is held back: the ground is a tight family of violet-tinted near-blacks, and the only saturated colour on a page is the accent of the plugin it belongs to (or the family's pale lavender elsewhere), on three things only.

Everything that lists the plugins (slots, rows, the horizon's rim, accents) is generated from the brand catalog, so a new plugin takes its place in the GUI without a hand edit. The site is dark only.

**Key Characteristics:**
- Square corners and a 2px pixel bevel: light top-left, dark bottom-right on panels, inverted on slots.
- The item tooltip as the selling and handover surface: name in the item's accent, lore in grey, stats as blue attribute lines.
- One accent per page, from the catalog, on a name, the chosen slot and the primary action.
- A calm reading column inside the GUI frame.
- Pixel-crisp 16px glyphs scaled in slots; a stepped pixel horizon whose rim is shared by every plugin.

## Colors

A deepslate family of violet-tinted near-blacks, one cool attribute blue, the tooltip's violet border, and one accent per page supplied by the brand catalog.

### Primary
- **Family Lavender** (`family-lavender`): the accent on every page that does not belong to a plugin (home, Get started, Developers). Its low and high steps (`family-lavender-low`, `family-lavender-high`) are Starlight's accent tints.
- **The plugin's accent** (no fixed value): on a plugin's pages, `public/accents/<id>.css` replaces the lavender with that plugin's colour. On the home inventory each slot and tooltip carries its own item's accent as `--item`. Values are the catalog's `accentText` (`src/data/plugins.json`, written by `scripts/catalog.ts` from xena-studios/brand). Where the brand colour is too dark for text on deepslate, the catalog carries a lighter text value (xBans uses a lighter red than its brand red); the horizon keeps the brand value.

### Secondary
- **Attribute Blue** (`attribute`): the tooltip's attribute lines. Used for stats (version, platforms, jar budget, Developer API), feature titles in lore lists, aside titles, and the download facts line. Beyond those titles, it marks measured facts and nothing else.

### Tertiary
- **Tooltip Violet** (`tooltip-border-top` to `tooltip-border-bottom`): the item tooltip's 2px vertical gradient border, over `tooltip-ground` with a `tooltip-outline` ring outside it. Used on tooltips, asides and overview lore lists, nowhere else.

### Neutral
- **Deepslate Ground** (`deepslate-ground`): the page background, and the bottom of the horizon's body.
- **Sidebar Ground** (`sidebar-ground`): the sidebar, a panel's inset ground.
- **Panel** (`panel`) with **Panel Highlight** (`panel-highlight`) and **Panel Shade** (`panel-shade`): the raised GUI surface and its bevel. The header bar, chest, buttons, pager and table header row.
- **Slot** (`slot`) with **Slot Highlight** (`slot-highlight`) and **Slot Shade** (`slot-shade`): the inset surface and its inverted bevel. Item slots, the search field, inline code, current navigation items.
- **Edge** (`edge`): the 2px dark border around raised panels and buttons, and under the header.
- **Code Ground** (`code-ground`): code blocks and terminal frames, with `rule` as their hairline.
- **Rule** (`rule`): hairlines between table rows and list rows.
- **Text** (`text`): body copy, headings, links at rest, and the keyboard-focus frame.
- **Lore** (`lore`): the tooltip's grey: lore text, the lead paragraph, header navigation at rest, table headers.
- **Muted** (`muted`) and **Hint** (`hint`): sidebar links at rest, mono labels, placeholders and row labels (hint reads at 5.1:1 on the ground).
- **On Accent** (`on-accent`): ink on an accent-filled button, near-black so every plugin's accent reads.

### Named Rules
**The Three Places Rule.** The accent appears only on a name (the plugin's or item's name), the chosen slot and the primary action. Links are text colour with a quiet underline and take the accent only on hover.

**The Catalog Owns the Accent Rule.** Accents come from the brand catalog, per plugin, and are never hand-typed. A surface needing a plugin's colour reads `--sl-color-accent` (the page's plugin) or the catalog's `accentText` (another plugin's item); a new plugin's colour arrives through `scripts/catalog.ts`, not through CSS.

**The White Frame Rule.** The chosen slot is framed in the plugin's accent (2px); a 2px frame in `text` marks keyboard focus. Selection and focus never share a colour.

## Typography

**Display Font:** Space Grotesk Variable (with system-ui, sans-serif)
**Body Font:** Space Grotesk Variable (with system-ui, sans-serif)
**Label/Mono Font:** JetBrains Mono Variable (with ui-monospace, SFMono-Regular, Menlo, monospace)

**Character:** Space Grotesk carries names and words in bold with tight negative tracking, like a GUI's item names; JetBrains Mono is the readout, used for stats and short labels, never for prose. Both are self-hosted.

### Hierarchy
- **Display** (700, `clamp(2.4rem, 1.4rem + 3.4vw, 4.75rem)`, 0.98): the home page's one heading, above the inventory.
- **Headline** (700, `clamp(2.25rem, 1.5rem + 2.6vw, 3.75rem)`, 1): a plugin overview's name, in the plugin's accent.
- **Title** (700, `clamp(1.75rem, 1.3rem + 1.9vw, 2.75rem)`, 1.12, -0.02em): every other page's h1; h2 to h4 in the reading column share the weight and tracking, balanced.
- **Item Name** (700, 1.375rem, -0.01em): the name at the top of a docked tooltip, in the item's accent; 1rem on the floating tooltip. The download page's version line is a larger cousin (`clamp(1.75rem, 1.4rem + 1.4vw, 2.5rem)`).
- **Lead** (400, `clamp(1.0625rem, 1rem + 0.35vw, 1.25rem)`, 1.6): the home pitch, in lore grey, held to 34ch.
- **Body** (400, 1rem, Starlight's line height): the reading column, at Starlight's default measure, with pretty wrapping.
- **Lore** (400, 0.9375rem, 1.55): tooltip lore, in `lore`. Overview lore lines use the same colour and line height at the body size.
- **Button** (700, 0.9375rem, 1.2): every GUI button.
- **Stat** (mono 400, 0.8125rem, 1.7, tabular figures): attribute lines in `attribute`.
- **Label** (mono 400, 0.75rem, 0.14em, uppercase): sidebar group names, the "Plugins" site label, aside titles. Table headers use 500 at 0.12em; inventory row labels use 0.6875rem at 0.12em in `hint`.

### Named Rules
**The Mono Is a Readout Rule.** JetBrains Mono sets measured facts (versions, sizes, platforms) and short structural labels. It never sets a sentence, a headline or decoration.

**The Bold Name Rule.** Names are Space Grotesk Bold with negative tracking (-0.01em to -0.035em, tighter as size grows). Nothing else on the page is set larger than the name it belongs to.

## Layout

The home page's first screen stacks the pitch over the workbench with a `clamp(1.75rem, 3vw, 2.5rem)` gap: the reading column (1080px at most) is too narrow for pitch, chest and tooltip in one row. The workbench holds the chest and its docked tooltip (`minmax(15rem, 20rem)`) side by side with a 1.5rem gap. The chest has one row per catalog category, each labelled at its start, nine slots wide with 0.25rem between slots; a category's plugins wrap onto as many rows as they need, and empty slots fill out its last row. Below 44rem the tooltip moves above the chest so the Download comes first, and rows are six slots sized to the screen, padded to six the same way.

The horizon runs edge to edge (100vw) under the inventory as the floor the chest rests on; the page clips horizontal overflow so it never adds a sideways scroll.

Plugin pages use Starlight's three-part frame: the GUI header bar, a sidebar showing only that plugin's pages under a plugin switcher, and the content. Home, overview and download pages drop the contents column, the previous/next pager and the edit/last-updated line; reading pages keep all three.

Spacing steps in the build: 2px is the pixel unit for every bevel, border and frame; 0.25rem between slots; 0.6rem between actions; 0.7rem between inventory rows; about 1.1rem of padding inside tooltips; `clamp(1.25rem, 3vw, 2rem)` inside large tooltip panels. Breakpoints in use: 40rem, 44rem, 50rem (header navigation moves to the menu), 72rem.

### Named Rules
**The Calm Column Rule.** Reading pages keep a calm column inside the GUI frame: plain text on deepslate at Starlight's measure. GUI material goes on the frame, code, tables, asides and section openers, never behind running prose.

**The Selling Pages Rule.** Home, overview and download pages have no contents column, pager or edit line. They sell or hand over the plugin; they are not reading pages.

## Elevation & Depth

Depth is pixel bevel, not light. A raised surface has a 2px inset highlight on its top and left (`panel-highlight`) and a 2px inset shade on its bottom and right (`panel-shade`), inside a 2px `edge` border. A slot inverts it (shade top-left, highlight bottom-right), so it reads as cut into the panel. Buttons carry a deeper 6px bottom shade so they stand up, and press flat. Starlight's soft shadows are turned off; the one soft shadow in the system is under the item tooltip, which floats over the GUI.

### Shadow Vocabulary
- **Raised bevel** (`box-shadow: inset 2px 2px 0 #46434F, inset -2px -2px 0 #08070B`): panels, the chest, pager links, the plugin switcher.
- **Inset bevel** (`box-shadow: inset 2px 2px 0 #050407, inset -2px -2px 0 #2E2C36`): slots, the search field, current header navigation.
- **Button bevel** (`box-shadow: inset 2px 2px 0 #46434F, inset -2px -6px 0 #08070B`): GUI buttons at rest. Primary mixes its bevel from the accent (55% with white, 55% with black).
- **Pressed** (`box-shadow: inset 2px 2px 0 #08070B, inset -2px -2px 0 #46434F`, `translateY(2px)`): a button while held.
- **Chosen slot ring** (`0 0 0 2px <accent>` added outside the inset bevel): the current sidebar page. On the inventory, a 2px outline in the item's accent.
- **Tooltip float** (`box-shadow: 0 18px 40px -12px rgb(0 0 0 / 0.65)`): item tooltips only.
- **Fine bevel** (`box-shadow: inset 1px 1px 0 #050407, inset -1px -1px 0 #2E2C36`): inline code, at half the pixel.
- **Section opener** (2px `panel-shade` top border with a 1px `panel-highlight` line under it): above each h2 in the reading column, and under the overview's product header.

### Named Rules
**The Two-Pixel Bevel Rule.** Every raised or inset surface uses the 2px bevel (`--gui-px`), light top-left and dark bottom-right, inverted for slots. Hard-edged inset shadows are this world's native material; blurred shadows are not.

**The Only Lift Rule.** The item tooltip is the only element with a soft drop shadow. Panels, slots and buttons stay in the GUI plane.

## Shapes

Every panel, slot, button, tooltip, aside, code block, navigation item and table is square (0px). The form language is the block and the pixel: 2px borders, stepped edges, and a horizon whose arc is drawn in 24-unit pixel blocks, each block 4 units tall, snapped to a 4-unit grid. Glyphs are 16px pixel art drawn with `image-rendering: pixelated` at 72 to 76% of their slot. The only curves are the planet's arc, approximated in steps, and the glyph images' slight corners, 8% of their size at every scale.

## Components

### Buttons
Raised GUI blocks that press in.
- **Shape:** square (0px), 2px `edge` border, button bevel, at least 2.75rem tall.
- **Primary:** filled with the page's accent (or the item's accent on the home tooltip), `on-accent` ink, bevel mixed from the accent. One per surface: Download, or the link to every version on GitHub when the download page has no version to show. On the download page, Download is the jar itself: from Modrinth, or from the plugin's GitHub release while it's still in Modrinth's review.
- **Secondary:** `panel` with `text`: How it works, Commands, Configuration, What's new, and Modrinth or GitHub (wherever the download page's versions came from).
- **Hover:** brightness 1.18 over 120ms. **Active:** presses 2px down with the bevel inverted (80ms). **Focus:** a 2px `text` outline, 2px offset. **Disabled:** desaturated and dimmed, not clickable.
- Starlight's link buttons wear the same material.

### Slots
- **Style:** `slot` with the inset bevel; a 16px glyph centred, pixelated.
- **Hover:** a 16% white wash inside the bevel, as the game lightens a slot.
- **Chosen:** a 2px outline in the item's accent. **Keyboard focus:** a 2px `text` outline, so focus and choice stay distinct.
- **Filtered by search:** 18% opacity, greyscale, out of the tab order.
- **Empty:** the same slot with nothing in it, filling the row to nine.
- **Sizes:** the inventory slot; a large overview slot holding the plugin's glyph beside its name; a 3.25rem slot beside other plugin page titles; 2rem in the plugin switcher.

### Item Tooltip (signature)
The selling and handover surface.
- **Material:** `tooltip-ground`, a 2px `tooltip-border-top` to `tooltip-border-bottom` gradient border, a 2px `tooltip-outline` ring outside it, and the tooltip float shadow.
- **Content order:** name (in the item's accent), tagline in `text` 600, lore in `lore`, attribute lines in mono `attribute`, then actions (primary Download, secondary How it works). Unreleased items replace Download with a coming-soon line linking GitHub releases.
- **Behaviour:** pointing at a slot shows a small floating tooltip (name and tagline) at the pointer, offset 18px right and 30px up, on fine pointers only. Click or focus chooses the slot and docks its full tooltip beside the chest; a second click opens the plugin's page. Without JavaScript each slot is a link and the first released plugin's tooltip is docked.
- **Other uses:** the home page's reasons panel, the download page's latest-version panel, asides (title as an attribute label), and overview lore lists.

### Overview lore
A plugin overview opens with at most five lore lines in tooltip material: the first list on the page, flowing in two columns (`columns: 2 19rem`) on wide screens, each line in `lore` with its bold lead-in as an attribute line. Above it, the product header: the big slot, the name in the accent, the tagline, mono stats, and Download then Commands and Configuration.

### Horizon
The family's planet horizon, drawn at build time from the catalog. The rim is stepped in pixel blocks, and each plugin owns one equal stretch of it, in catalog order and in its brand accent. A soft glow blends those accents along the rim, and the body below runs from `panel` to `deepslate-ground` and fades out. It is decoration, hidden from assistive technology, and a new plugin joins the rim on its own.

### Inputs / Fields
- **Search:** an inset slot holding a borderless input; placeholder in `hint`; caret in the accent. It filters slots as the creative inventory's search does, choosing the first match.
- **Focus:** a 2px outline around the whole slot.

### Navigation
- **Header:** one solid `panel` bar with a 2px `edge` under it. XENA STUDIOS, then "Plugins" as a mono label; site links in `lore` 600 that turn `text` on a slot ground on hover. The current section is an inset slot. Below 50rem the links and source icon move into Starlight's menu.
- **Sidebar:** links in `muted`, `text` on hover. The current page is the chosen slot: inset ground, inset bevel, a 2px accent ring, weight 600. Group names are mono labels. Outbound links get a small arrow.
- **Plugin switcher:** on a plugin's pages, a raised panel above the sidebar with the plugin's glyph in a mini slot, its name in its accent and a mono "Switch" hint; it opens (a `<details>`) to every plugin, each name in its own accent when current.
- **Pager:** previous and next as raised panels, brightened on hover.

### Code, tables and asides
- **Code blocks:** `code-ground`, square, a `rule` hairline, no shadow, JetBrains Mono for code and Space Grotesk for frame titles.
- **Inline code:** sits in a small slot with the fine bevel.
- **Tables:** `rule` hairlines; the header row is a GUI bar (panel with a 2px bevel top and bottom) in mono uppercase `lore`; body cells use tabular figures; a command or setting in the first column never breaks mid-name.
- **Asides:** tooltip ground with a violet border (`tooltip-border-top` on top, `tooltip-border-bottom` elsewhere), title as a mono label in `attribute`.

### Motion
Short and functional, on one easing, `cubic-bezier(0.16, 1, 0.3, 1)`: buttons brighten in 120ms and press in 80ms; links change colour and underline in 160ms. The one exception is the outbound arrow, which keeps xenastudios.co's nudge (1px up and right in 180ms, on that site's curve). The floating tooltip follows the pointer directly, with no animation. Under reduced motion every transition collapses to 1ms.

### Named Rules
**The Five Lore Lines Rule.** An overview's opening lore list has at most five lines. More features belong in the page below it, not in the tooltip.

**The Rim Belongs to the Catalog Rule.** The horizon is drawn from the catalog, each plugin owning a stretch of the rim. It is never a static image or a hand-picked set of colours.

## Do's and Don'ts

### Do:
- **Do** take every plugin colour from the brand catalog (`accentText` in `src/data/plugins.json`, or `--sl-color-accent` on that plugin's pages).
- **Do** keep the accent to a name, the chosen slot and the primary action; links rest in `text` and show the accent on hover.
- **Do** frame the chosen slot in the plugin's accent and mark keyboard focus with a 2px `text` frame.
- **Do** build frames from the three materials: raised panel, inset slot, item tooltip, all square with the 2px bevel.
- **Do** keep reading pages to a calm column inside the GUI frame.
- **Do** drop the contents column, pager and edit line on home, overview and download pages.
- **Do** open each overview with at most five lore lines in tooltip material.
- **Do** set stats in mono `attribute` with tabular figures, and only stats that the build, Modrinth or the plugin's GitHub releases can back.
- **Do** render glyphs pixelated at a whole-slot scale.

### Don't:
- **Don't** hand-type a plugin's hex value in CSS or a component.
- **Don't** put the accent on body links at rest, headings, borders or backgrounds beyond the three places.
- **Don't** round the corners of panels, slots, buttons, tooltips or code.
- **Don't** give panels, slots or buttons a blurred drop shadow; the tooltip is the only thing that floats.
- **Don't** put GUI panels or tooltip material behind running prose on reading pages.
- **Don't** draw the horizon from a fixed image or a hand-picked palette.
- **Don't** use JetBrains Mono for sentences or headlines.
