// Copies the docs' brand assets from a checkout of xena-studios/brand, the
// single source of truth for logos, icons, the horizon and colours. The copies are
// committed, so builds never fetch the brand repo.
//
//   pnpm brand:sync                    # expects ../../brand (next to this repo)
//   BRAND_DIR=/path/to/brand pnpm brand:sync
import fs from "node:fs";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..");
const BRAND = path.resolve(process.env.BRAND_DIR ?? path.join(ROOT, "../../brand"));

if (!fs.existsSync(path.join(BRAND, "tokens/plugins-catalog.json"))) {
  console.error(`No brand checkout with the plugin catalog at ${BRAND}. Clone xena-studios/brand there (or pull it), or set BRAND_DIR.`);
  process.exit(1);
}

// [from (in brand), to (relative to docs/)]
// The header carries the XENA STUDIOS logo and the footer the XENA mark: the owner's call for
// this site (decisions/2026-11-27-the-site-on-fumadocs.md), over the brand's footer-only rule.
const files: [string, string][] = [
  ["logo/xena.svg", "src/assets/xena.svg"],
  ["logo/xena-studios.svg", "src/assets/xena-studios.svg"],
  ["avatar/favicon.svg", "public/favicon.svg"],
  ["plugins/horizon.svg", "src/assets/horizon.svg"],
  // Every plugin's name, words and colours: scripts/catalog.ts builds the lists from it.
  ["tokens/plugins-catalog.json", "src/data/catalog.json"],
  ["social/docs-og.png", "public/og.png"],
];

// Each plugin's icon, for every plugin in the brand catalog. The READMEs' banners aren't
// copied: they link the brand's v1 release through jsDelivr, as its guide says.
const catalog: { plugins: { id: string }[] } = JSON.parse(fs.readFileSync(path.join(BRAND, "tokens/plugins-catalog.json"), "utf8"));
for (const { id } of catalog.plugins) {
  files.push([`plugins/${id}/icon.svg`, `src/assets/plugins/${id}.svg`]);
}

for (const [from, to] of files) {
  const dest = path.join(ROOT, to);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(path.join(BRAND, from), dest);
}

// The family's colour tokens, which theme.css and gui.css build on, with a note on where they're from.
const tokens = fs.readFileSync(path.join(BRAND, "tokens/plugins.css"), "utf8");
fs.writeFileSync(
  path.join(ROOT, "src/styles/tokens.css"),
  "/* The plugin family's colour tokens, copied from xena-studios/brand (tokens/plugins.css) by\n" +
    "   pnpm brand:sync: don't edit by hand. theme.css and gui.css build on them. */\n" +
    tokens,
);
console.log(`synced ${files.length + 1} files from ${BRAND}; run pnpm catalog to rebuild the lists`);
