// Redirects for versioned docs, so no URL breaks when a plugin's rewrite ships. Its old pages
// move from /<id>/<page>/ to /<id>/v<old>/<page>/ (src/data/versions.json); a page the new
// major also has keeps its address and shows the new major, and every other one redirects to
// where it moved. Read by astro.config.ts, which hands them to Astro.
import fs from "node:fs";
import path from "node:path";
import type { DocsPlugin } from "./plugins.ts";

const DOCS = path.resolve(import.meta.dirname, "content/docs");
const PAGE = /\.(md|mdx|mdoc)$/;

/** Each page's address within [dir], relative to it: "" for index, "setup", "setup/networks". */
function pages(dir: string): string[] {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { recursive: true, encoding: "utf8" })
    .filter((f) => PAGE.test(f))
    .map((f) => f.replace(PAGE, "").split(path.sep).join("/").replace(/(^|\/)index$/, "").toLowerCase());
}

export function versionRedirects(plugins: DocsPlugin[]): Record<string, string> {
  const redirects: Record<string, string> = {};
  for (const plugin of plugins) {
    if (!plugin.older.length) continue;
    const root = path.join(DOCS, plugin.id);
    // The newest major's pages: everything in the plugin's folder but the v<old>/ folders.
    const newest = new Set(pages(root).filter((p) => !/^v\d+(\/|$)/.test(p)));
    // Newest old major first, so a page several old majors have goes to the latest of them.
    for (const major of plugin.older) {
      for (const page of pages(path.join(root, `v${major}`))) {
        const from = page ? `/${plugin.id}/${page}/` : `/${plugin.id}/`;
        if (!page || newest.has(page) || from in redirects) continue;
        redirects[from] = `/${plugin.id}/v${major}/${page}/`;
      }
    }
  }
  return redirects;
}
