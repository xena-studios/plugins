// Finishes the static build in dist/client after `vite build` has prerendered every page, and
// fails it on anything wrong. In order:
//
//   1. Every reference manifest against manifest.schema.json, and every releases file, checked.
//   2. The old majors' redirects (src/redirects.ts): `_redirects` for Cloudflare, and a page at
//      each old address that sends a browser on, as the Starlight site had.
//   3. The schemas served for editors: /schemas/manifest.schema.json and releases.schema.json.
//   4. The sitemap (sitemap-index.xml, sitemap-0.xml), without hidden or redirecting pages.
//   5. Search: Pagefind's index of every page marked data-pagefind-body, in /pagefind/.
//   6. Every internal link, and its #fragment, checked against the built pages.
//   7. The CSP (_headers): each page's own inline script (TanStack's dehydrated route data, which
//      differs on every page) moves to a file in /assets/inline/, and the inline scripts every
//      page shares (the theme and scroll restoration) are hashed, so script-src stays 'self' and
//      those hashes, and nothing else inline can run.
//   8. The page-weight budget (decisions/2026-11-27-the-site-on-fumadocs.md), on every page.
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";
import * as pagefind from "pagefind";
import { z } from "zod";
import { checkAllManifests, checkAllReleases, releasesSchema } from "../src/lib/data.ts";
import { docsPlugins } from "../src/plugins.ts";
import { versionRedirects } from "../src/redirects.ts";

const DOCS = path.resolve(import.meta.dirname, "..");
const DIST = path.join(DOCS, "dist/client");
const SITE = "https://plugins.xenastudios.co";
const fail: string[] = [];
const log = (s: string) => console.log(`postbuild: ${s}`);

/** Every built page: its address and its file. */
function htmlFiles(): { url: string; file: string }[] {
  return fs
    .readdirSync(DIST, { recursive: true, encoding: "utf8" })
    .filter((f) => f.endsWith(".html"))
    .map((f) => {
      const rel = f.split(path.sep).join("/");
      const url = rel === "index.html" ? "/" : rel.endsWith("/index.html") ? `/${rel.slice(0, -"index.html".length)}` : `/${rel}`;
      return { url, file: path.join(DIST, f) };
    });
}
const read = (f: string) => fs.readFileSync(f, "utf8");
const gz = (b: Buffer | string) => gzipSync(b, { level: 9 }).length;

// --- 1. The data the pages are built from ---
const manifests = checkAllManifests();
checkAllReleases();
log(`${manifests.length} manifests match manifest.schema.json`);

// The crawl also renders /404/ (the route behind 404.html): the site has no such address.
fs.rmSync(path.join(DIST, "404"), { recursive: true, force: true });
// 404.html answers every address that isn't a page, so it can't start the app, whose router
// would find no page there: it stays plain HTML (the theme script stays, so it's still dark or
// light as chosen).
{
  const file = path.join(DIST, "404.html");
  if (!fs.existsSync(file)) fail.push("dist/client/404.html is missing");
  else {
    const html = read(file)
      .replace(/<link\b[^>]*rel="modulepreload"[^>]*>/g, "")
      .replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, (s) => (/next-themes|localStorage\.getItem|\(e, i, s, u, m, a, l, h\)/.test(s) ? s : ""));
    fs.writeFileSync(file, html);
  }
}

// --- 2. Redirects ---
const redirects = versionRedirects(docsPlugins);
const redirectLines: string[] = [];
for (const [from, to] of Object.entries(redirects)) {
  redirectLines.push(`${from} ${to} 301`);
  const file = path.join(DIST, from, "index.html");
  if (fs.existsSync(file)) fail.push(`redirect ${from} → ${to}: a page is already there`);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(
    file,
    `<!doctype html><title>Redirecting to: ${to}</title><meta http-equiv="refresh" content="0;url=${to}"><meta name="robots" content="noindex"><link rel="canonical" href="${SITE}${to}"><body><a href="${to}">Redirecting from <code>${from}</code> to <code>${to}</code></a></body>`,
  );
}
fs.writeFileSync(path.join(DIST, "_redirects"), redirectLines.length ? `${redirectLines.join("\n")}\n` : "");
const redirected = new Set(Object.keys(redirects));
log(`${redirectLines.length} redirects`);

// --- 3. Schemas for editors, at stable addresses ---
fs.mkdirSync(path.join(DIST, "schemas"), { recursive: true });
fs.copyFileSync(path.join(DOCS, "src/generated/manifest.schema.json"), path.join(DIST, "schemas/manifest.schema.json"));
fs.writeFileSync(
  path.join(DIST, "schemas/releases.schema.json"),
  JSON.stringify({ $id: `${SITE}/schemas/releases.schema.json`, title: "A plugin's releases (docs/src/data/releases/<plugin>.json)", ...z.toJSONSchema(releasesSchema) }, null, 2) + "\n",
);

// --- 4. Sitemap ---
const pages = htmlFiles();
const indexable = pages.filter(({ url, file }) => {
  if (url === "/404.html" || redirected.has(url)) return false;
  return !/<meta name="robots" content="noindex"/.test(read(file));
});
const urls = indexable.map((p) => `${SITE}${p.url}`).sort();
fs.writeFileSync(
  path.join(DIST, "sitemap-0.xml"),
  `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${urls.map((u) => `<url><loc>${u}</loc></url>`).join("")}</urlset>`,
);
fs.writeFileSync(
  path.join(DIST, "sitemap-index.xml"),
  `<?xml version="1.0" encoding="UTF-8"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><sitemap><loc>${SITE}/sitemap-0.xml</loc></sitemap></sitemapindex>`,
);
log(`sitemap: ${urls.length} pages`);

// --- 5. Search ---
{
  // Buttons (a heading's "copy link", a code block's "copy") aren't content.
  const { index, errors } = await pagefind.createIndex({ excludeSelectors: ["button", "[data-pagefind-ignore]"] });
  if (!index || errors.length) throw new Error(`pagefind: ${errors.join(", ")}`);
  const added = await index.addDirectory({ path: DIST });
  if (added.errors.length) throw new Error(`pagefind: ${added.errors.join(", ")}`);
  await index.writeFiles({ outputPath: path.join(DIST, "pagefind") });
  await pagefind.close();
  log(`search: ${added.page_count} pages indexed`);
}

// --- 6. Links ---
const ids = new Map<string, Set<string>>();
const idsOf = (file: string) => {
  if (!ids.has(file)) ids.set(file, new Set([...read(file).matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]!)));
  return ids.get(file)!;
};
const decode = (s: string) => s.replace(/&amp;/g, "&").replace(/&#x27;|&#39;/g, "'").replace(/&quot;/g, '"');
/** The built file an internal address serves, or undefined. */
function target(pathname: string): string | undefined {
  const p = decodeURIComponent(pathname);
  const dir = path.join(DIST, p, "index.html");
  if (fs.existsSync(dir)) return dir;
  const file = path.join(DIST, p);
  if (fs.existsSync(file) && fs.statSync(file).isFile()) return file;
  return undefined;
}
let checked = 0;
for (const { url, file } of pages) {
  if (redirected.has(url)) continue;
  for (const [, raw] of read(file).matchAll(/<a\b[^>]*?\shref="([^"]*)"/g)) {
    let href = decode(raw!);
    if (href.startsWith(SITE)) href = href.slice(SITE.length) || "/";
    if (href.startsWith("#")) href = `${url}${href}`;
    if (!href.startsWith("/") || href.startsWith("//")) continue;
    checked++;
    const u = new URL(href, SITE);
    const to = target(u.pathname);
    const where = `${url}: ${href}`;
    if (!to) {
      if (!redirected.has(u.pathname)) fail.push(`broken link ${where}`);
      continue;
    }
    const hash = decodeURIComponent(u.hash.slice(1));
    if (hash && to.endsWith(".html") && !idsOf(to).has(hash) && hash !== "_top") fail.push(`broken fragment ${where}`);
  }
}
log(`links: ${checked} internal links checked`);

// --- 7. The CSP ---
const SCRIPT = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
const counts = new Map<string, number>();
const sha = (body: string) => createHash("sha256").update(body).digest("base64");
for (const { file } of pages) for (const [, attrs, body] of read(file).matchAll(SCRIPT)) if (body && !/\ssrc=/.test(attrs!)) counts.set(sha(body), (counts.get(sha(body)) ?? 0) + 1);
// An inline script on at least half the pages is the site's own and stays inline, hashed;
// anything rarer is a page's own data and moves to a file.
const shared = new Set([...counts].filter(([, n]) => n >= pages.length / 2).map(([h]) => h));
fs.mkdirSync(path.join(DIST, "assets/inline"), { recursive: true });
let moved = 0;
for (const { file } of pages) {
  const html = read(file);
  const out = html.replace(SCRIPT, (whole, attrs: string, body: string) => {
    if (!body || /\ssrc=/.test(attrs) || shared.has(sha(body))) return whole;
    const name = `${createHash("sha256").update(body).digest("hex").slice(0, 20)}.js`;
    fs.writeFileSync(path.join(DIST, "assets/inline", name), body);
    moved++;
    // A module stays a module; a classic script stays in place and in order.
    return `<script${attrs} src="/assets/inline/${name}"></script>`;
  });
  if (out !== html) fs.writeFileSync(file, out);
}
const headersFile = path.join(DIST, "_headers");
const headers = read(headersFile);
const placeholder = " __INLINE_SCRIPTS__";
if (!headers.includes(placeholder)) throw new Error("_headers has no inline-script placeholder");
fs.writeFileSync(headersFile, headers.replace(placeholder, [...shared].sort().map((h) => ` 'sha256-${h}'`).join("")));
log(`CSP: ${shared.size} inline script hashes; ${moved} page scripts moved to /assets/inline/`);
for (const { file, url } of htmlFiles()) {
  for (const [, attrs, body] of read(file).matchAll(SCRIPT)) {
    if (body && !/\ssrc=/.test(attrs!) && !shared.has(sha(body))) fail.push(`${url}: an inline script the CSP doesn't allow`);
  }
}

// --- 8. Page weight ---
// What a first visit to a page downloads before it's interactive, gzipped: the HTML, its
// stylesheets, every script it runs or preloads, the fonts its text uses (the Latin subsets)
// and its images. Search's index loads only when search opens, so it isn't counted.
const BUDGET = { jsKb: 215, totalKb: 340 };
const fonts = fs
  .readdirSync(path.join(DIST, "assets"))
  .filter((f) => /^(space-grotesk|jetbrains-mono)-latin-wght-normal.*\.woff2$/.test(f))
  .reduce((n, f) => n + fs.statSync(path.join(DIST, "assets", f)).size, 0);
const sizeCache = new Map<string, number>();
const sizeOf = (url: string) => {
  if (!sizeCache.has(url)) {
    const f = target(url.split("?")[0]!);
    sizeCache.set(url, f ? (/\.(woff2|png)$/.test(f) ? fs.statSync(f).size : gz(fs.readFileSync(f))) : 0);
  }
  return sizeCache.get(url)!;
};
const weights = htmlFiles()
  .filter(({ url }) => url !== "/404.html" && !redirected.has(url))
  .map(({ url, file }) => {
    const html = read(file);
    const js = new Set([...html.matchAll(/<script\b[^>]*\ssrc="(\/[^"]+)"/g), ...html.matchAll(/<link\b[^>]*rel="modulepreload"[^>]*href="(\/[^"]+)"/g)].map((m) => m[1]!));
    const css = new Set([...html.matchAll(/<link\b[^>]*rel="stylesheet"[^>]*href="(\/[^"]+)"/g)].map((m) => m[1]!));
    const img = new Set([...html.matchAll(/<img\b[^>]*\ssrc="(\/[^"]+)"/g)].map((m) => m[1]!));
    const jsBytes = [...js].reduce((n, u) => n + sizeOf(u), 0);
    const total = gz(html) + jsBytes + [...css, ...img].reduce((n, u) => n + sizeOf(u), 0) + fonts;
    return { url, js: jsBytes / 1024, total: total / 1024 };
  });
const worst = (k: "js" | "total") => weights.reduce((a, b) => (b[k] > a[k] ? b : a));
const at = (url: string) => weights.find((w) => w.url === url);
for (const w of weights) {
  if (w.js > BUDGET.jsKb) fail.push(`${w.url}: ${w.js.toFixed(1)} KB of JavaScript, over the ${BUDGET.jsKb} KB budget`);
  if (w.total > BUDGET.totalKb) fail.push(`${w.url}: ${w.total.toFixed(1)} KB in all, over the ${BUDGET.totalKb} KB budget`);
}
const fmt = (w?: { url: string; js: number; total: number }) => (w ? `${w.url} ${w.js.toFixed(1)} KB JS, ${w.total.toFixed(1)} KB in all` : "n/a");
log(`page weight (gzipped; budget ${BUDGET.jsKb} KB JS, ${BUDGET.totalKb} KB in all):`);
log(`  home       ${fmt(at("/"))}`);
log(`  docs page  ${fmt(at("/start/installing/"))}`);
log(`  reference  ${fmt(at("/xutilities/next/commands/"))}`);
log(`  heaviest   ${fmt(worst("total"))}`);
fs.writeFileSync(path.join(DOCS, "dist/page-weight.json"), JSON.stringify({ budget: BUDGET, pages: weights }, null, 2));

if (fail.length) {
  console.error(`postbuild: ${fail.length} problem(s):\n  ${fail.slice(0, 200).join("\n  ")}`);
  process.exit(1);
}
log("done");
