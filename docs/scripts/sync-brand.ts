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

if (!fs.existsSync(path.join(BRAND, "tokens/plugins.json"))) {
  console.error(`No brand checkout at ${BRAND}. Clone xena-studios/brand there or set BRAND_DIR.`);
  process.exit(1);
}

// [from (in brand), to (relative to docs/)]
const files = [
  ["logo/xena-studios.svg", "src/assets/xena-studios-dark.svg"],
  ["logo/xena-studios-black.svg", "src/assets/xena-studios-light.svg"],
  ["avatar/favicon.svg", "public/favicon.svg"],
  ["plugins/horizon.svg", "src/assets/horizon.svg"],
  ["social/docs-og.png", "public/og.png"],
  ["plugins/xutilities/icon.svg", "src/assets/plugins/xutilities.svg"],
  ["plugins/zchat/icon.svg", "src/assets/plugins/zchat.svg"],
  ["plugins/xlimbo/icon.svg", "src/assets/plugins/xlimbo.svg"],
  // The READMEs' banners, for the repo, the docs and each plugin.
  ["github/plugins-banner.png", "../.github/assets/banner.png"],
  ["github/plugin-docs-banner.png", "../.github/assets/docs-banner.png"],
  ["plugins/xutilities/banner.png", "../.github/assets/xutilities-banner.png"],
  ["plugins/zchat/banner.png", "../.github/assets/zchat-banner.png"],
  ["plugins/xlimbo/banner.png", "../.github/assets/xlimbo-banner.png"],
];

for (const [from, to] of files) {
  const dest = path.join(ROOT, to);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(path.join(BRAND, from), dest);
}
console.log(`synced ${files.length} files from ${BRAND}`);
