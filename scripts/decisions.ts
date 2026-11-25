// Writes DECISIONS.md, the index of decisions/: one row per decision, by date then file name,
// each with the anchor its heading had when every decision was in DECISIONS.md, so old
// DECISIONS.md#... links still land on its row.
//
//   node scripts/decisions.ts           rewrites DECISIONS.md
//   node scripts/decisions.ts --check   fails if a decision file or the index is wrong
//
// A decision is decisions/<date>-<slug>.md, whose first line is "# <title> (<date>)", the
// same date as its name. A pull request adds its file and leaves DECISIONS.md alone, so two
// open pull requests that each add a decision never conflict (decisions/2026-11-24-decisions-
// one-file-each.md). --check, which the Docs workflow runs on every pull request, therefore
// lets the index lag: it fails on a bad file, or a row that's wrong, out of order or for a
// file that's gone, and only notes the decisions not listed yet. Release pull requests
// (scripts/release.ts) rewrite the index, and so can anyone with this script.
//
// Only node: built-ins, so it runs with plain Node 24 (which runs TypeScript).
import fs from "node:fs";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..");
const DIR = path.join(ROOT, "decisions");
const INDEX = path.join(ROOT, "DECISIONS.md");
const check = process.argv.includes("--check");

const HEADER = `# Decisions

<!-- Written by node scripts/decisions.ts from decisions/. Don't edit it by hand. -->

Why the plugins are built the way they are, oldest first. Each decision is a file in [decisions/](decisions/), named by its date. Release pull requests bring this list up to date, so the newest few may be in that folder alone until the next one; [CONTRIBUTING.md](CONTRIBUTING.md#decisions) says how to add one.

| Decision | Date |
|---|---|
`;

const errors: string[] = [];

/** GitHub's anchor for a heading: lowercase, punctuation dropped, spaces as hyphens. */
const anchor = (heading: string) =>
  heading.toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, "").replace(/\s/g, "-");

interface Decision {
  file: string;
  title: string;
  date: string;
  row: string;
}

const decisions: Decision[] = [];
for (const file of fs.readdirSync(DIR).sort()) {
  const name = /^(\d{4}-\d{2}-\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$/.exec(file);
  if (!name) {
    errors.push(`decisions/${file}: name it <date>-<slug>.md, as 2026-11-24-smaller-jars.md (lowercase letters, digits and hyphens)`);
    continue;
  }
  const first = fs.readFileSync(path.join(DIR, file), "utf8").split("\n", 1)[0];
  const heading = /^# (.+) \((\d{4}-\d{2}-\d{2})\)$/.exec(first);
  if (!heading) {
    errors.push(`decisions/${file}: its first line must be "# <title> (${name[1]})"`);
    continue;
  }
  const [, title, date] = heading;
  if (date !== name[1]) errors.push(`decisions/${file}: dated ${date} in its heading but ${name[1]} in its name`);
  const id = anchor(`${title} (${date})`);
  decisions.push({ file, title, date, row: `| <a id="${id}"></a>[${title}](decisions/${file}) | ${date} |` });
}
// By date, then file name: the order the folder lists them in.
decisions.sort((a, b) => a.date.localeCompare(b.date) || a.file.localeCompare(b.file));
const ids = new Map<string, string>();
for (const d of decisions) {
  const id = /id="([^"]+)"/.exec(d.row)![1];
  if (ids.has(id)) errors.push(`decisions/${d.file}: same title and date as decisions/${ids.get(id)}, so the same anchor`);
  ids.set(id, d.file);
}

if (errors.length) {
  for (const e of errors) console.error(e);
  process.exit(1);
}

const index = HEADER + decisions.map((d) => d.row + "\n").join("");
if (!check) {
  fs.writeFileSync(INDEX, index);
  console.log(`DECISIONS.md: ${decisions.length} decisions`);
  process.exit(0);
}

// The check: the header as written, then rows that are each a decision's row, in order. The
// decisions with no row yet are fine.
const text = fs.readFileSync(INDEX, "utf8");
const fix = "run node scripts/decisions.ts and commit DECISIONS.md";
if (!text.startsWith(HEADER)) {
  console.error(`DECISIONS.md: its header isn't what scripts/decisions.ts writes; ${fix}`);
  process.exit(1);
}
const rows = text.slice(HEADER.length).split("\n");
if (rows.pop() !== "") errors.push("DECISIONS.md: it must end with a newline");
const order = new Map(decisions.map((d, i) => [d.row, i]));
let last = -1;
for (const [i, row] of rows.entries()) {
  const at = order.get(row);
  if (at === undefined) errors.push(`DECISIONS.md, row ${i + 1}: no decision file has this row (a file renamed, retitled or removed?): ${row}`);
  else if (at <= last) errors.push(`DECISIONS.md, row ${i + 1}: out of order: ${row}`);
  else last = at;
}
if (errors.length) {
  for (const e of errors) console.error(e);
  console.error(fix);
  process.exit(1);
}
const listed = new Set(rows);
const missing = decisions.filter((d) => !listed.has(d.row));
for (const d of missing) {
  const note = `decisions/${d.file} isn't in DECISIONS.md yet; the next release pull request adds it`;
  console.log(process.env.GITHUB_ACTIONS ? `::notice file=decisions/${d.file}::${note}` : note);
}
console.log(`DECISIONS.md: ${rows.length} of ${decisions.length} decisions listed, every row right`);
