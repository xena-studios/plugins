// Starts a new plugin: everything it needs, building and booting green, from one command.
//
//   node scripts/new-plugin.ts <id> <Name> <category> [--no-build]
//   node scripts/new-plugin.ts xshop Shop Economy
//
// The id is the plugin's lowercase name: its folder, package, Modrinth slug, command and
// permission prefix. The name follows the prefix letter (the id's first letter), as it's
// shown: xShop. The category is one of the brand catalog's (Players, Moderation, Economy,
// Server).
//
// It needs xena-studios/brand checked out beside this repo (or BRAND_DIR), with its fonts
// for `pnpm build:plugins` (see that repo's docs/building.md), because a plugin's name,
// words and colours live there. In order, it:
//   1. adds the plugin to the brand repo (its `pnpm new-plugin`): a placeholder glyph, an
//      accent clear of every other Xena accent, and its catalog entry, then builds its art;
//   2. writes the module from scripts/new-plugin/template/: the plugin and its config, the
//      api/ subproject, a test, boot checks, CHANGELOG, README, Modrinth page and docs pages;
//   3. copies the brand's art and catalog into the docs and rebuilds every list of plugins;
//   4. writes the API's signatures and the docs' default config, then builds the plugin.
// Then it prints what's left to write: everything marked TODO.
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const ROOT = path.resolve(import.meta.dirname, "..");
const TEMPLATE = path.join(ROOT, "scripts/new-plugin/template");
const BRAND = path.resolve(process.env.BRAND_DIR ?? path.join(ROOT, "../brand"));
const args = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const build = !process.argv.includes("--no-build");
const [id, name, category] = args;

const fail = (message: string): never => {
  console.error(message);
  process.exit(1);
};
const run = (cmd: string, argv: string[], cwd: string, env: Record<string, string> = {}) => {
  console.log(`\n$ ${[cmd, ...argv].join(" ")}   (in ${path.relative(ROOT, cwd) || "."})`);
  execFileSync(cmd, argv, { cwd, stdio: "inherit", env: { ...process.env, ...env } });
};

if (!id || !name || !category) fail("Usage: node scripts/new-plugin.ts <id> <Name> <category>, such as: node scripts/new-plugin.ts xshop Shop Economy");
if (!/^[a-z][a-z0-9]{1,30}$/.test(id)) fail(`${id}: an id is lowercase letters and digits, such as xshop`);
if (fs.existsSync(path.join(ROOT, id))) fail(`${id}/ already exists`);
if (!fs.existsSync(path.join(BRAND, "scripts/new-plugin.ts"))) {
  fail(`No brand repo with pnpm new-plugin at ${BRAND}. Clone xena-studios/brand beside this repo (and pull it), or set BRAND_DIR.`);
}

// The Modrinth slug is the id, and the update checker asks Modrinth for that slug's
// versions: if another project has it, every server would be told about that project's
// releases. Xena's own drafts aren't public, so they answer 404 here, like a free slug.
let slugTaken = "";
try {
  const answer = await fetch(`https://api.modrinth.com/v2/project/${id}`, { headers: { "User-Agent": "xena-studios/plugins new-plugin" } });
  if (answer.ok) slugTaken = (await answer.json()).title;
} catch {
  console.warn(`Couldn't ask Modrinth whether the slug ${id} is free; check by hand before its first release.`);
}
if (slugTaken) {
  console.warn(`\nWARNING: the Modrinth slug "${id}" belongs to "${slugTaken}". The update checker would tell servers about its releases.`);
  console.warn("Pick another id, unless that project is yours.\n");
}

// 1. The brand: its catalog entry, accent and art.
const catalogFile = path.join(BRAND, "tokens/plugins-catalog.json");
const inCatalog = () => JSON.parse(fs.readFileSync(catalogFile, "utf8")).plugins.find((p: { id: string }) => p.id === id);
if (!inCatalog()) {
  run("node", ["scripts/new-plugin.ts", id, name, category], BRAND);
  run("node", ["scripts/catalog.ts"], BRAND);
}
try {
  run("node", ["scripts/build.ts", "--plugins"], BRAND);
} catch {
  fail(`The brand repo couldn't build ${id}'s icon and banner: see the error above (usually a missing font, in docs/building.md there). Fix it, then run this again: the brand entry is kept, and nothing has been written here yet.`);
}
const entry = inCatalog();

// 2. The module, from the template.
const display = entry.name as string;
const vars: Record<string, string> = {
  id,
  display,
  Class: display[0].toUpperCase() + display.slice(1),
  tagline: entry.tagline,
  taglineLower: entry.tagline[0].toLowerCase() + entry.tagline.slice(1),
  accentText: entry.accentText,
};
const fill = (text: string) => text.replace(/\{\{(\w+)\}\}/g, (_, key) => vars[key] ?? fail(`template uses {{${key}}}, which isn't set`));
const written: string[] = [];
const copy = (from: string, to: string) => {
  for (const item of fs.readdirSync(from, { withFileTypes: true })) {
    const target = path.join(to, item.name.replace(/__id__/g, id).replace(/__Class__/g, vars.Class));
    if (item.isDirectory()) {
      fs.mkdirSync(target, { recursive: true });
      copy(path.join(from, item.name), target);
    } else {
      if (fs.existsSync(target)) fail(`${path.relative(ROOT, target)} already exists`);
      fs.writeFileSync(target, fill(fs.readFileSync(path.join(from, item.name), "utf8")));
      written.push(path.relative(ROOT, target));
    }
  }
};
copy(TEMPLATE, ROOT);
console.log(`\nWrote ${written.length} files for ${display}.`);

// 3. The docs: the brand's art and catalog, and every list of plugins.
const docs = path.join(ROOT, "docs");
run("node", ["scripts/sync-brand.ts"], docs, { BRAND_DIR: BRAND });
run("node", ["scripts/catalog.ts"], docs);

// 4. The API's signatures, the docs' default config, and a build.
if (build) {
  const gradle = path.join(ROOT, "gradlew");
  run(gradle, [`:${id}:api:apiDump`, `:${id}:test`, "-PupdateGolden"], ROOT);
  run(gradle, [`:${id}:build`], ROOT);
}

const todos = written.filter((file) => fs.readFileSync(path.join(ROOT, file), "utf8").includes("TODO"));
console.log(`
${display} is set up${build ? " and builds" : ""}. What's left:
  - In the brand repo (${BRAND}): draw its glyph and write its headline, summary and blurb
    in scripts/plugin-brand.ts, run pnpm build:plugins, and open a pull request there.
    Then run node scripts/sync-brand.ts and node scripts/catalog.ts in docs/ again.
  - The TODOs in: ${todos.join(", ")}.
  - Its features, as modules in ${vars.Class}.java's setup, each with a test and a line in boot-checks.txt.
  - Reserve the Modrinth slug "${id}" (a draft project in the xena-studios organization), so
    nobody else takes it: the update checker reads that slug's versions.${slugTaken ? ` It's TAKEN by "${slugTaken}" now.` : ""}
  - A bStats id for bStatsId() before its first release.
Commit both repos' changes on their own branches.`);
