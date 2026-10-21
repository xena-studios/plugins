// Copies the docs' brand assets from a checkout of xena-studios/brand, the
// single source of truth for logos, icons, the horizon and colours. The copies are
// committed, so builds never fetch the brand repo.
//
//   npm run brand:sync                 # expects ../../brand (next to this repo)
//   BRAND_DIR=/path/to/brand npm run brand:sync
import fs from "node:fs";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..");
const BRAND = path.resolve(process.env.BRAND_DIR ?? path.join(ROOT, "../../brand"));

if (!fs.existsSync(path.join(BRAND, "tokens/plugins.json"))) {
  console.error(`No brand checkout at ${BRAND}. Clone xena-studios/brand there or set BRAND_DIR.`);
  process.exit(1);
}

// [from (in brand), to (in docs)]
const files = [
  ["logo/xena-studios.svg", "src/assets/xena-studios-dark.svg"],
  ["logo/xena-studios-black.svg", "src/assets/xena-studios-light.svg"],
  ["avatar/favicon.svg", "public/favicon.svg"],
  ["scene/horizon.svg", "src/assets/horizon.svg"],
  ["plugins/xutilities/icon.svg", "src/assets/plugins/xutilities.svg"],
  ["plugins/zchat/icon.svg", "src/assets/plugins/zchat.svg"],
  ["plugins/xlimbo/icon.svg", "src/assets/plugins/xlimbo.svg"],
];

for (const [from, to] of files) {
  const dest = path.join(ROOT, to);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(path.join(BRAND, from), dest);
}
console.log(`synced ${files.length} files from ${BRAND}`);
