// Builds every list of the plugins from one place, so a plugin is added once. The brand
// repo's catalog (src/data/catalog.json, copied by `pnpm brand:sync`) gives each plugin's
// name, words, category and colours, and whether it's released; each plugin's
// build.gradle.kts (a rewritten plugin's bukkit/build.gradle.kts) gives its version and whether it runs on Folia; an api/ folder means it
// has a developer API; src/data/versions.json gives an unreleased rewrite's last release, whose
// version and jar budget the lists keep until the rewrite ships; src/data/modrinth.json lists the released plugins Modrinth is still
// reviewing, whose Modrinth pages nothing may link yet. From those:
//
//   docs/src/data/plugins.json       what the docs site reads: sidebar, home, titles, footer
//   docs/public/accents/<id>.css     each plugin's accent on its pages
//   README.md                        the table of plugins
//   .github/labeler.yml              a label per plugin for pull requests
//   .github/workflows/labels.yml     a label per plugin for bug reports
//   .github/ISSUE_TEMPLATE/bug_report.yml   the plugin list and the /<plugin> info hint
//   .github/ISSUE_TEMPLATE/feature_request.yml   the plugin list
//   <plugin>/modrinth.md             "More from Xena Studios": the plugins that go with it
//   README.md, <plugin>/README.md, <plugin>/modrinth.md   the badge row under the tagline
//
// Generated parts of hand-written files sit between "catalog:start" and "catalog:end"
// markers (the badge rows between "badges:start" and "badges:end"); everything outside them is left alone. Both also report a README, Modrinth page or
// docs page that links the Modrinth page of a plugin still in review, and the check fails on it.
//
//   pnpm catalog          # write them
//   pnpm catalog:check    # fail if any is out of date (CI)
import fs from "node:fs";
import path from "node:path";

const DOCS = path.resolve(import.meta.dirname, "..");
const ROOT = path.resolve(DOCS, "..");
const check = process.argv.includes("--check");
const MODRINTH = "https://modrinth.com/plugin";
const MODRINTH_ORG = "https://modrinth.com/organization/xena-studios";
const RELEASES = "https://github.com/xena-studios/plugins/releases";
// The plugin site.
const SITE = "https://plugins.xenastudios.co";

interface CatalogPlugin {
  id: string;
  name: string;
  prefix: string;
  category: string;
  tagline: string;
  headline: [string, string];
  blurb: string;
  summary: string;
  released: boolean;
  accent: string;
  accentText: string;
  accentLow: string;
  accentHigh: string;
}
interface Catalog {
  version: number;
  categories: string[];
  plugins: CatalogPlugin[];
}

const catalog: Catalog = JSON.parse(fs.readFileSync(path.join(DOCS, "src/data/catalog.json"), "utf8"));
if (catalog.version !== 1) throw new Error(`src/data/catalog.json is version ${catalog.version}; this script reads version 1`);

// Released plugins whose Modrinth project Modrinth hasn't approved yet: a project in review is
// a 404 to everyone else, so it's linked nowhere, and it downloads from GitHub until then.
const review: { inReview: string[] } = JSON.parse(fs.readFileSync(path.join(DOCS, "src/data/modrinth.json"), "utf8"));
for (const id of review.inReview) {
  if (!catalog.plugins.some((p) => p.id === id)) throw new Error(`src/data/modrinth.json: ${id} isn't in the catalog`);
}
const inReview = new Set(review.inReview);

// A rewrite merged but not released: the lists keep its last release's facts (src/plugins.ts).
const versions: { plugins: { id: string; unreleased?: { version: string; jarBudgetKb: number } }[] } = JSON.parse(
  fs.readFileSync(path.join(DOCS, "src/data/versions.json"), "utf8"),
);
const unreleasedOf = (id: string) => versions.plugins.find((v) => v.id === id)?.unreleased;

/** Whether a plugin is in the rewrite's layout (<id>/api, common and a folder per platform). */
const isRewritten = (id: string) =>
  fs.existsSync(path.join(ROOT, id, "common/build.gradle.kts")) && !fs.existsSync(path.join(ROOT, id, "build.gradle.kts"));

/** A plugin's facts from its own folder. */
function facts(id: string) {
  // A rewritten plugin's version and Folia switch are in its Bukkit jar's build (its xenaRelease),
  // and its jar budget in gradle/jar-budgets.txt.
  const rewritten = isRewritten(id);
  const file = rewritten ? `${id}/bukkit/build.gradle.kts` : `${id}/build.gradle.kts`;
  const build = path.join(ROOT, file);
  if (!fs.existsSync(build)) throw new Error(`${id} is in the brand catalog but has no ${file}`);
  const text = fs.readFileSync(build, "utf8");
  const version = text.match(/^version = "([^"]+)"/m)?.[1];
  if (!version) throw new Error(`${file}: no version = "..." line`);
  const budgets = rewritten ? fs.readFileSync(path.join(ROOT, "gradle/jar-budgets.txt"), "utf8") : "";
  const unreleased = unreleasedOf(id);
  return {
    version: unreleased?.version ?? version,
    /** The build's version, which an unreleased rewrite's own pages show. */
    buildVersion: version,
    // xena.folia is on unless the plugin turns it off.
    folia: !/^\s*folia = false/m.test(text),
    // The most its jar may weigh: the build fails over it.
    jarBudgetKb: unreleased
      ? unreleased.jarBudgetKb
      : rewritten
      ? Number(budgets.match(new RegExp(`^:${id}:bukkit (\\d+)`, "m"))?.[1] ?? 150)
      : Number(text.match(/jarBudgetKb = (\d+)/)?.[1] ?? 150),
    api: fs.existsSync(path.join(ROOT, id, "api/build.gradle.kts")),
    rewritten,
    unreleased: !!unreleased,
  };
}

const plugins = catalog.plugins.map((p) => {
  const f = facts(p.id);
  return {
    ...p,
    ...f,
    docs: `/${p.id}/`,
    // Only a plugin Modrinth has approved has a public page there. A draft's, or one still in
    // review, is a 404 for everyone else.
    modrinth: p.released && !inReview.has(p.id) ? `${MODRINTH}/${p.id}` : null,
    maven: f.api ? `co.xenastudios.${p.id}:${p.id}-api` : null,
  };
});

// Every folder that's a plugin is in the catalog, so none is left off the lists.
for (const dir of fs.readdirSync(ROOT)) {
  const build = path.join(ROOT, dir, "build.gradle.kts");
  if (dir === "sandbox" || !fs.existsSync(build)) continue;
  if (/id\("xena\.plugin"\)/.test(fs.readFileSync(build, "utf8")) && !plugins.some((p) => p.id === dir)) {
    throw new Error(`${dir} is a plugin but isn't in the brand catalog: add it there (pnpm new-plugin in xena-studios/brand), then pnpm brand:sync`);
  }
}

const outputs = new Map<string, string>();
const write = (file: string, text: string) => outputs.set(file, text);

/** [file] with the lines between its `<marker>:start` and `<marker>:end` markers replaced by [body]. */
function between(file: string, body: string, marker = "catalog") {
  const full = path.join(ROOT, file);
  // A file may have both kinds of markers: start from what this run has already written to it.
  const text = outputs.get(file) ?? fs.readFileSync(full, "utf8");
  const start = text.match(new RegExp(`^.*${marker}:start.*$`, "m"));
  const end = text.match(new RegExp(`^.*${marker}:end.*$`, "m"));
  if (!start || !end || start.index! > end.index!) throw new Error(`${file}: no ${marker}:start / ${marker}:end markers`);
  const head = text.slice(0, start.index! + start[0].length);
  const tail = text.slice(end.index!);
  write(file, `${head}\n${body}${body.endsWith("\n") ? "" : "\n"}${tail}`);
}

// --- The docs site's data ---
write(
  "docs/src/data/plugins.json",
  JSON.stringify(
    // What the site reads; the rest only steers this script.
    { categories: catalog.categories, plugins: plugins.map(({ buildVersion, rewritten, unreleased, ...p }) => p) },
    null,
    2,
  ) + "\n",
);

// --- Accents ---
for (const p of plugins) {
  write(
    `docs/public/accents/${p.id}.css`,
    `/* ${p.name}'s accent, loaded on its pages. Generated by scripts/catalog.ts from the brand
   catalog (xena-studios/brand, tokens/plugins-catalog.json): don't edit by hand.
   Starlight puts route head entries before the bundled theme, so the doubled
   :root outranks theme.css instead of relying on order.${p.accentText !== p.accent ? `
   The brand's ${p.accent} is too dark for text here, so the site uses ${p.accentText}.` : ""} */
:root:root {
  --accent: ${p.accentText};
  --sl-color-accent-low: ${p.accentLow};
  --sl-color-accent: ${p.accentText};
  --sl-color-accent-high: ${p.accentHigh};
}
`,
  );
}

// --- README: the plugins, by category ---
const rows: string[] = [];
for (const category of catalog.categories) {
  const inCategory = plugins.filter((p) => p.category === category);
  if (!inCategory.length) continue;
  rows.push(`**${category}**`, "", "| | Plugin | | |", "|---|---|---|---|");
  for (const p of inCategory) {
    // Its Modrinth page once Modrinth has approved it; until then its download page, which
    // lists its GitHub releases.
    const get = p.modrinth ? `[Modrinth](${p.modrinth})` : p.released ? `[Download](${SITE}${p.docs}download/)` : "Coming soon";
    rows.push(`| <img src="docs/src/assets/plugins/${p.id}.svg" width="40" alt=""> | **[${p.name}](${p.id})** | ${p.tagline} | [Docs](${SITE}${p.docs}) · ${get} |`);
  }
  rows.push("");
}
const folia = plugins.filter((p) => p.folia).map((p) => p.name);
const list = (names: string[]) => (names.length < 2 ? names.join("") : `${names.slice(0, -1).join(", ")} and ${names.at(-1)}`);
const count = ["no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"][plugins.length] ?? String(plugins.length);
const onModrinth = plugins.filter((p) => p.modrinth).map((p) => p.name);
const where = onModrinth.length === plugins.length
  ? `Get them on [Modrinth](${MODRINTH_ORG})`
  : `Download them from [GitHub](${RELEASES})${onModrinth.length ? `, and ${list(onModrinth)} from [Modrinth](${MODRINTH_ORG}) too` : ""}`;
rows.push(
  `All ${count} need Paper 1.21.11+ and Java 21+. ${list(folia)} also run on Folia. ${where}; the [docs](${SITE}) cover setup, commands, permissions and config.`,
);
between("README.md", rows.join("\n"));

// --- Labels ---
between(
  ".github/labeler.yml",
  plugins.map((p) => `${p.id}:\n  - changed-files:\n      - any-glob-to-any-file: ${p.id}/**`).join("\n"),
);
between(
  ".github/workflows/labels.yml",
  plugins.map((p) => `            ${p.name}) label=${p.id} ;;`).join("\n"),
);

// --- The bug report and feature request forms ---
between(".github/ISSUE_TEMPLATE/bug_report.yml", plugins.map((p) => `        - ${p.name}`).join("\n"));
between(".github/ISSUE_TEMPLATE/feature_request.yml", plugins.map((p) => `        - ${p.name}`).join("\n"));
{
  const file = ".github/ISSUE_TEMPLATE/bug_report.yml";
  const text = outputs.get(file)!;
  const hint = `From ${list(plugins.map((p) => `\`/${p.id} info\``)).replace(/ and (?=`[^`]+`$)/, " or ")}.`;
  write(file, text.replace(/^(\s*description: )From `\/[a-z]+ info`.*$/m, `$1${hint}`));
}

// --- Modrinth pages: "More from Xena Studios", the plugins that go with this one ---
// src/data/related.json says which, and why. One on Modrinth links to its Modrinth page. One
// Modrinth is still reviewing links to its docs, as does one that isn't out yet, marked coming
// soon: their Modrinth pages are a 404 for everyone else. Only pages with the markers
// (new-plugin's template has them).
const related: Record<string, Record<string, string>> = JSON.parse(fs.readFileSync(path.join(DOCS, "src/data/related.json"), "utf8"));
for (const [id, list] of Object.entries(related)) {
  if (id === "//") continue;
  if (!plugins.some((p) => p.id === id)) throw new Error(`src/data/related.json: ${id} isn't in the catalog`);
  for (const other of Object.keys(list)) {
    if (other === id || !plugins.some((p) => p.id === other)) throw new Error(`src/data/related.json: ${id} lists ${other}, which isn't another plugin in the catalog`);
  }
}
for (const p of plugins) {
  const file = `${p.id}/modrinth.md`;
  const full = path.join(ROOT, file);
  if (!fs.existsSync(full) || !/catalog:start/.test(fs.readFileSync(full, "utf8"))) continue;
  // Released ones first, each group in the order related.json gives. A plugin it doesn't list
  // yet (a new one) gets every other released plugin, with its blurb.
  const why = related[p.id] ?? Object.fromEntries(plugins.filter((o) => o.id !== p.id && o.released).map((o) => [o.id, o.blurb]));
  const shown = Object.keys(why).map((id) => plugins.find((o) => o.id === id)!).sort((a, b) => Number(!a.released) - Number(!b.released));
  const lines = shown.map((o) => `- **[${o.name}](${o.modrinth ?? SITE + o.docs})**${o.released ? "" : " (coming soon)"}: ${o.tagline} ${why[o.id]}`);
  if (!lines.length) lines.push("- More are on the way.");
  between(file, [...lines, "", `Every plugin in the family: **[plugins.xenastudios.co](${SITE}/)**`].join("\n"));
}

// --- Badges: where to get it, what it runs on, the docs ---
// Xena's badges, from the brand repo's v1 releases through jsDelivr (badges/; its docs/badges.md has the
// rules): each plugin's in its own accent, the family's in the studio's. Compact on GitHub,
// cozy on Modrinth, and no Modrinth badge on the Modrinth page itself. Modrinth shows images
// from hosts it doesn't list (jsDelivr among them) through its wsrv.nl proxy, which turns an
// SVG into a PNG at its 1x size, blurry on a sharp screen: so Modrinth gets the 2x PNGs,
// GitHub the SVGs.
const BADGES = "https://cdn.jsdelivr.net/gh/xena-studios/brand@1/badges";
type Style = "compact" | "cozy";
type BadgeName = "modrinth" | "github" | "paper" | "purpur" | "folia" | "docs";
const ALT: Record<BadgeName, string> = {
  modrinth: "Available on Modrinth",
  github: "Available on GitHub",
  paper: "Runs on Paper",
  purpur: "Runs on Purpur",
  folia: "Runs on Folia",
  docs: "Read the Docs",
};
const HEIGHT: Record<Style, number> = { compact: 32, cozy: 48 };
const FORMAT: Record<Style, "svg" | "png"> = { compact: "svg", cozy: "png" };
function badgeRow(theme: string, style: Style, items: [BadgeName, string | null][]) {
  return items.map(([name, href]) => {
    const img = `<img alt="${ALT[name]}" height="${HEIGHT[style]}" src="${BADGES}/${theme}/${style}/${name}.${FORMAT[style]}">`;
    return href ? `<a href="${href}">${img}</a>` : img;
  }).join("\n");
}
const runsOn = (p: { folia: boolean }): [BadgeName, null][] => [["paper", null], ["purpur", null], ...(p.folia ? [["folia", null] as [BadgeName, null]] : [])];
for (const p of plugins) {
  const theme = `plugins/${p.id}`;
  // GitHub: where to get it (its Modrinth page once it's public, else its download page, which
  // lists its GitHub releases), what it runs on, the docs. Not out yet: no "get it" badge.
  const get: [BadgeName, string][] = p.modrinth ? [["modrinth", p.modrinth]] : p.released ? [["github", `${SITE}${p.docs}download/`]] : [];
  between(`${p.id}/README.md`, badgeRow(theme, "compact", [...get, ...runsOn(p), ["docs", `${SITE}${p.docs}`]]), "badges");
  // Modrinth: what it runs on and the docs; the page itself is where to get it.
  const modrinthPage = `${p.id}/modrinth.md`;
  if (fs.existsSync(path.join(ROOT, modrinthPage))) {
    between(modrinthPage, badgeRow(theme, "cozy", [...runsOn(p), ["docs", `${SITE}${p.docs}`]]), "badges");
  }
}
// The family, in the studio's colours: on Modrinth once any is, and the docs. Paper and
// Purpur only, as not all run on Folia.
between(
  "README.md",
  badgeRow("xena", "compact", [
    onModrinth.length ? ["modrinth", MODRINTH_ORG] : ["github", RELEASES],
    ["paper", null],
    ["purpur", null],
    ["docs", `${SITE}/`],
  ]),
  "badges",
);

// --- Each plugin's changelog and download pages, and its current version ---
const GENERATED = "Generated by docs/scripts/catalog.ts: don't edit by hand.";
for (const p of plugins) {
  const changelog = fs.readFileSync(path.join(ROOT, p.id, "CHANGELOG.md"), "utf8")
    // The page has its own title.
    .replace(/^# .*\n+/, "")
    .replace(/^## Unreleased$/m, "## Unreleased\n\nIn the next release.");
  const changelogPage = (dir: string, text: string) =>
    write(
      `docs/src/content/docs/${dir}/changelog.md`,
      // Its Edit page link opens the changelog it's made from, not this generated copy.
      `---\n# ${GENERATED} From ${p.id}/CHANGELOG.md.\ntitle: Changelog\ndescription: "What changed in each ${p.name} release."\neditUrl: https://github.com/xena-studios/plugins/edit/main/${p.id}/CHANGELOG.md\nsidebar:\n  order: 90\n---\n\n${text.trimEnd()}\n`,
    );
  changelogPage(p.id, changelog);
  // An unreleased rewrite's old major, which the site shows meanwhile, has every section but
  // the new major's.
  if (p.unreleased) {
    const major = p.version.split(".")[0]!;
    const sections = changelog.split(/^(?=## )/m);
    const kept = sections.filter((s) => !s.startsWith("## ") || /^## (\d+)/.exec(s)?.[1] === undefined || Number(/^## (\d+)/.exec(s)![1]) <= Number(major));
    changelogPage(`${p.id}/v${major}`, kept.join(""));
  }
  // A rewritten plugin's download page is its own: the picker, from its manifest and releases.
  if (!p.rewritten) write(
    `docs/src/content/docs/${p.id}/download.mdx`,
    `---\n# ${GENERATED}\ntitle: Download\ndescription: "Download ${p.name}: the latest version and every one before it, with the Minecraft versions each supports."\nsidebar:\n  order: 1.5\n---\n\nimport Downloads from "../../../components/Downloads.astro";\n\n<Downloads plugin="${p.id}" />\n`,
  );
  // The overview's "Current version: **x.y.z**" follows the build file. An unreleased
  // rewrite's says the build's on its own (hidden) pages, and the old major's overview, which
  // the site shows meanwhile, says the last release.
  const overviewIn = (dir: string) =>
    fs.existsSync(path.join(ROOT, `docs/src/content/docs/${dir}/index.mdx`))
      ? `docs/src/content/docs/${dir}/index.mdx`
      : `docs/src/content/docs/${dir}/index.md`;
  const overviews: [string, string][] = [[overviewIn(p.id), p.buildVersion]];
  if (p.unreleased) overviews.push([overviewIn(`${p.id}/v${p.version.split(".")[0]}`), p.version]);
  for (const [overview, version] of overviews) {
    const text = outputs.get(overview) ?? fs.readFileSync(path.join(ROOT, overview), "utf8");
    const updated = text.replace(/^(Current version: \*\*)[^*]+(\*\*)/m, `$1${version}$2`);
    if (updated !== text) write(overview, updated);
  }
}

// --- Nothing links a Modrinth page that's still in review: it's a 404 for everyone else ---
// Checked in what this writes and in the hand-written pages: the README, each plugin's README
// and Modrinth page (which may link its own project), the docs' pages and components, and
// xStats' website, which owners serve to everyone.
const reviewLinks: string[] = [];
if (inReview.size) {
  const link = new RegExp(`modrinth\\.com/(?:plugin|project)/(${[...inReview].join("|")})(?![a-z0-9-])`, "g");
  const docsFiles = ["src/content", "src/components"].flatMap((dir) =>
    fs.readdirSync(path.join(DOCS, dir), { recursive: true, encoding: "utf8" })
      .filter((file) => /\.(md|mdx|astro)$/.test(file))
      .map((file) => `docs/${dir}/${file.split(path.sep).join("/")}`));
  const site = fs.readdirSync(path.join(ROOT, "xstats/web")).map((file) => `xstats/web/${file}`);
  for (const file of ["README.md", ...plugins.flatMap((p) => [`${p.id}/README.md`, `${p.id}/modrinth.md`]), ...docsFiles, ...site]) {
    let text = outputs.get(file);
    if (text === undefined) {
      try {
        text = fs.readFileSync(path.join(ROOT, file), "utf8");
      } catch {
        continue;
      }
    }
    for (const match of text.matchAll(link)) {
      if (file === `${match[1]}/modrinth.md`) continue;
      reviewLinks.push(`${file}:${text.slice(0, match.index).split("\n").length}: ${match[0]}`);
    }
  }
}

// --- Write, or check ---
const stale: string[] = [];
for (const [file, text] of outputs) {
  const full = path.join(ROOT, file);
  // Read in one step (no exists-then-read): a missing file is simply new.
  let old: string | null = null;
  try {
    old = fs.readFileSync(full, "utf8");
  } catch {
    // Not written yet.
  }
  if (old === text) continue;
  if (check) stale.push(file);
  else fs.writeFileSync(full, text);
}
// Accent files for plugins that left the catalog.
for (const file of fs.readdirSync(path.join(DOCS, "public/accents"))) {
  if (!plugins.some((p) => `${p.id}.css` === file)) {
    if (check) stale.push(`docs/public/accents/${file}`);
    else fs.rmSync(path.join(DOCS, "public/accents", file));
  }
}
if (check && stale.length) {
  console.error(`Out of date (run pnpm catalog in docs/):\n  ${stale.join("\n  ")}`);
}
// Writing still succeeds, so release.ts and new-plugin.ts, which write and then commit, never
// stop half-way; CI's check fails on it.
if (reviewLinks.length) {
  console.error(
    `Links to Modrinth pages still in review, which are a 404 for everyone else (src/data/modrinth.json). ` +
      `Link the plugin's docs or its download page (${SITE}/<id>/download/) instead:\n  ${reviewLinks.join("\n  ")}`,
  );
}
if (check && (stale.length || reviewLinks.length)) process.exit(1);
console.log(check ? `catalog: ${outputs.size} files up to date` : `catalog: wrote ${outputs.size} files`);
