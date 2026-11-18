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
const files = [
  ["logo/xena-studios.svg", "src/assets/xena-studios-dark.svg"],
  ["logo/xena-studios-black.svg", "src/assets/xena-studios-light.svg"],
  ["logo/xena.svg", "src/assets/xena.svg"],
  ["avatar/favicon.svg", "public/favicon.svg"],
  ["plugins/horizon.svg", "src/assets/horizon.svg"],
  // Every plugin's name, words and colours: scripts/catalog.ts builds the lists from it.
  ["tokens/plugins-catalog.json", "src/data/catalog.json"],
  ["social/docs-og.png", "public/og.png"],
  // The READMEs' banners, for the repo, the docs and each plugin.
  ["github/plugins-banner.png", "../.github/assets/banner.png"],
  ["github/plugin-docs-banner.png", "../.github/assets/docs-banner.png"],
];

// Each plugin's icon (docs) and banner (its README), for every plugin in the brand catalog.
const catalog: { plugins: { id: string }[] } = JSON.parse(fs.readFileSync(path.join(BRAND, "tokens/plugins-catalog.json"), "utf8"));
for (const { id } of catalog.plugins) {
  files.push([`plugins/${id}/icon.svg`, `src/assets/plugins/${id}.svg`]);
  files.push([`plugins/${id}/banner.png`, `../.github/assets/${id}-banner.png`]);
}

for (const [from, to] of files) {
  const dest = path.join(ROOT, to);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(path.join(BRAND, from), dest);
}
console.log(`synced ${files.length} files from ${BRAND}; run pnpm catalog to rebuild the lists`);
