// Releases plugins in two steps: open a release pull request, then, once it's merged,
// tag it. The tags are what publish (.github/workflows/release.yml), to Modrinth, GitHub
// and Maven Central, and Maven Central can't take a release back.
//
//   node scripts/release.ts <plugin>[@<version>]... [--dry-run] [--no-push]
//   node scripts/release.ts --tag <pr-number | plugin...> [--dry-run]
//
// The first opens one pull request, on a branch named release/<plugins>, that releases
// each plugin. For each one it:
//   - turns "## Unreleased" in <plugin>/CHANGELOG.md into "## <version>";
//   - bumps version = "..." in <plugin>/build.gradle.kts, and the version boot-checks.txt
//     expects from /<plugin> info;
//   - updates the docs: the "Current version" line, the API's Gradle and Maven snippets,
//     and the note that the API reaches Maven Central with the release;
//   - regenerates docs/src/data/plugins.json (docs/scripts/catalog.ts).
// Without @<version>, the version comes from the Unreleased section: a "### Fixed" (or
// "### Security") heading alone is a patch, any other heading a minor. A build version
// that's never been tagged, like a new plugin's 1.0.0, is released as it is; if its
// changelog already has a section for exactly that version, the changelog is left alone.
// A major release, or a section that removes or breaks something, needs @<version>.
// --dry-run prints the edits and changes nothing; --no-push commits on the release
// branch but doesn't push it or open the pull request.
//
// The second, after the pull request is merged, reads each plugin's version and
// changelog at the merge commit on main, checks the Plugins workflow passed on exactly
// that commit, and pushes an annotated <plugin>-v<version> tag for each, one at a time.
// Given plugin ids instead of a pull request number, it tags main's latest commit.
//
// Only node: built-ins, so it runs with plain Node 24 (which runs TypeScript), git and gh.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..");
const DOCS = path.join(ROOT, "docs/src/content/docs");

interface Plugin {
  id: string;
  name: string;
  api: boolean;
}
const catalog: Plugin[] = JSON.parse(fs.readFileSync(path.join(ROOT, "docs/src/data/plugins.json"), "utf8")).plugins;
const plugins = new Map(catalog.map((p) => [p.id, p]));

// ---------------------------------------------------------------------------------------
// Helpers

function run(cmd: string, args: string[], input?: string): string {
  try {
    return execFileSync(cmd, args, { cwd: ROOT, encoding: "utf8", input, stdio: ["pipe", "pipe", "pipe"] }).trim();
  } catch (e: any) {
    const detail = (e.stderr || e.stdout || e.message || "").toString().trim();
    throw new Error(`${cmd} ${args.join(" ")} failed${detail ? `:\n${detail}` : ""}`);
  }
}
const git = (...args: string[]) => run("git", args);

function fail(...lines: string[]): never {
  for (const line of lines) console.error(`release: ${line}`);
  process.exit(1);
}

/** "a", "a and b", "a, b and c", as the release commits have always been titled. */
function list(items: string[]): string {
  return items.length < 2 ? items.join("") : `${items.slice(0, -1).join(", ")} and ${items.at(-1)}`;
}

interface Version {
  major: number;
  minor: number;
  patch: number;
  pre: string;
}
function parse(v: string): Version | null {
  const m = v.match(/^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/);
  return m ? { major: +m[1], minor: +m[2], patch: +m[3], pre: m[4] ?? "" } : null;
}
/** Semantic version order: a prerelease comes before its release. */
function compare(a: string, b: string): number {
  const x = parse(a)!;
  const y = parse(b)!;
  for (const k of ["major", "minor", "patch"] as const) if (x[k] !== y[k]) return x[k] - y[k];
  if (x.pre === y.pre) return 0;
  if (!x.pre) return 1;
  if (!y.pre) return -1;
  const xs = x.pre.split(".");
  const ys = y.pre.split(".");
  for (let i = 0; i < Math.max(xs.length, ys.length); i++) {
    if (xs[i] === undefined) return -1;
    if (ys[i] === undefined) return 1;
    const xn = /^\d+$/.test(xs[i]);
    const yn = /^\d+$/.test(ys[i]);
    if (xn && yn && +xs[i] !== +ys[i]) return +xs[i] - +ys[i];
    if (xn !== yn) return xn ? -1 : 1;
    if (xs[i] !== ys[i]) return xs[i] < ys[i] ? -1 : 1;
  }
  return 0;
}

/** The versions of a plugin that have been tagged, newest first. */
function tagged(id: string): string[] {
  return git("tag", "-l", `${id}-v*`)
    .split("\n")
    .filter(Boolean)
    .map((t) => t.slice(id.length + 2))
    .filter((v) => parse(v))
    .sort((a, b) => compare(b, a));
}

function buildVersion(id: string, text: string): string {
  const version = text.match(/^version = "([^"]+)"/m)?.[1];
  if (!version) fail(`${id}/build.gradle.kts has no version = "..." line`);
  return version;
}

/** The lines under a "## <heading>" line, up to the next "## ", as scripts/changelog.sh reads them. */
function section(changelog: string, heading: string): string | null {
  const lines = changelog.split("\n");
  const start = lines.findIndex((l) => l === `## ${heading}` || l.startsWith(`## ${heading} `));
  if (start < 0) return null;
  let end = lines.findIndex((l, i) => i > start && l.startsWith("## "));
  if (end < 0) end = lines.length;
  return lines.slice(start + 1, end).join("\n").trim();
}

function requireClean() {
  const status = run("git", ["status", "--porcelain"]);
  if (status) fail("the working tree has changes; commit or discard them first:", ...status.split("\n"));
}

function fetchOrigin() {
  try {
    git("fetch", "--quiet", "--tags", "origin", "main");
  } catch (e: any) {
    console.warn(`release: couldn't fetch from origin, so the tags may be out of date: ${e.message}`);
  }
}

// ---------------------------------------------------------------------------------------
// Opening the release pull request

interface Edit {
  file: string;
  old: string;
  new: string;
}
interface Plan {
  plugin: Plugin;
  from: string;
  to: string;
  notes: string;
  edits: Edit[];
}

/** A plugin's version and edits, or the reasons it can't be released. */
function plan(arg: string): Plan | string[] {
  const [id, wanted] = arg.split("@");
  const plugin = plugins.get(id);
  if (!plugin) return [`${id} isn't a plugin; the plugins are ${[...plugins.keys()].join(", ")}`];
  const problems: string[] = [];
  const read = (file: string) => fs.readFileSync(path.join(ROOT, file), "utf8");
  const buildFile = `${id}/build.gradle.kts`;
  const changelogFile = `${id}/CHANGELOG.md`;
  const build = read(buildFile);
  const changelog = read(changelogFile);
  const from = buildVersion(id, build);
  const tags = tagged(id);
  const last = tags[0];
  const unreleased = section(changelog, "Unreleased");

  // A build version that's never been tagged, with a changelog section of its own: a new
  // plugin's first release, written up as it was built.
  const firstRelease = !tags.includes(from) && unreleased === null && !!section(changelog, from);

  let to = wanted;
  if (wanted !== undefined && !parse(wanted)) problems.push(`${id}@${wanted}: ${wanted} isn't a version like 1.2.3`);
  if (!firstRelease) {
    if (unreleased === null) {
      problems.push(`${changelogFile} has no "## Unreleased" section, so there's nothing to release`);
    } else if (!unreleased) {
      problems.push(`${changelogFile}'s "## Unreleased" section is empty, so there's nothing to release`);
    }
  }
  if (problems.length) return problems;

  if (to === undefined) {
    if (firstRelease || !tags.includes(from)) {
      to = from;
    } else {
      const headings = [...unreleased!.matchAll(/^### +(.+?)\s*$/gm)].map((m) => m[1].toLowerCase());
      const fixes = ["fixed", "fixes", "security"];
      const breaking = headings.filter((h) => /remov|break|incompatib/.test(h));
      if (!headings.length) {
        return [`${changelogFile}'s Unreleased section has no "### " headings, so its version can't be told; pass ${id}@<version>`];
      }
      if (breaking.length) {
        return [`${changelogFile}'s Unreleased section has "### ${breaking[0]}", which may need a major version; pass ${id}@<version>`];
      }
      const v = parse(from)!;
      to = headings.every((h) => fixes.includes(h))
        ? `${v.major}.${v.minor}.${v.patch + 1}`
        : `${v.major}.${v.minor + 1}.0`;
    }
  }

  if (tags.includes(to)) problems.push(`${id}-v${to} is already tagged`);
  else if (last && compare(to, last) <= 0) problems.push(`${id} ${to} isn't newer than its last release, ${last}`);
  if (firstRelease && to !== from) {
    problems.push(`${changelogFile} has a "## ${from}" section, not "## Unreleased"; release it as ${from}, or rename the section`);
  }
  if (!firstRelease && section(changelog, to) !== null) problems.push(`${changelogFile} already has a "## ${to}" section`);
  if (problems.length) return problems;

  // The id and name go into patterns as literal text.
  const lit = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const edits: Edit[] = [];
  const edit = (file: string, pattern: RegExp, replacement: string) => {
    const text = read(file);
    for (const m of text.matchAll(new RegExp(pattern, pattern.flags.includes("g") ? pattern.flags : pattern.flags + "g"))) {
      const after = m[0].replace(new RegExp(pattern.source, pattern.flags.replace("g", "")), replacement);
      if (after !== m[0]) edits.push({ file, old: m[0], new: after });
    }
  };

  if (!firstRelease) edit(changelogFile, /^## Unreleased.*$/m, `## ${to}`);
  edit(buildFile, /^version = "[^"]+"$/m, `version = "${to}"`);
  const bootChecks = `${id}/boot-checks.txt`;
  if (fs.existsSync(path.join(ROOT, bootChecks))) {
    edit(bootChecks, new RegExp(`^((?:\\w+ )?expect: ${lit(plugin.name)} )\\S+( on)$`, "m"), `$1${to}$2`);
  }
  const docs = path.join(DOCS, id);
  if (fs.existsSync(docs)) {
    for (const page of fs.readdirSync(docs).sort()) {
      if (!/\.mdx?$/.test(page)) continue;
      const file = path.relative(ROOT, path.join(docs, page));
      edit(file, /Current version: \*\*[^*]+\*\*/, `Current version: **${to}**`);
      if (plugin.api) {
        edit(file, new RegExp(`("co\\.xenastudios\\.${lit(id)}:${lit(id)}-api:)[^"]+(")`), `$1${to}$2`);
        edit(file, new RegExp(`(<artifactId>${lit(id)}-api</artifactId>\\s*<version>)[^<]+(</version>)`), `$1${to}$2`);
        // From "it's published when X is released" to "it's on Maven Central".
        edit(
          file,
          /:::note\n[^\n]*published to Maven Central when[^\n]*\n:::/,
          `It's on Maven Central, so \`mavenCentral()\` is all the repository you need, and its Javadoc is on [javadoc.io](https://javadoc.io/doc/co.xenastudios.${id}/${id}-api).`,
        );
      }
    }
  }
  if (from !== to) edits.push({ file: "docs/src/data/plugins.json", old: `${id}: "version": "${from}"`, new: `${id}: "version": "${to}" (docs/scripts/catalog.ts)` });

  const notes = firstRelease ? section(changelog, from)! : unreleased!;
  return { plugin, from, to, notes, edits };
}

function release(args: string[], dryRun: boolean, push: boolean) {
  if (!args.length) fail("usage: node scripts/release.ts <plugin>[@<version>]... [--dry-run] [--no-push]");
  const ids = args.map((a) => a.split("@")[0]);
  const dupe = ids.find((id, i) => ids.indexOf(id) !== i);
  if (dupe) fail(`${dupe} is listed twice`);
  requireClean();
  fetchOrigin();

  const plans: Plan[] = [];
  const problems: string[] = [];
  for (const arg of args) {
    const p = plan(arg);
    if (Array.isArray(p)) problems.push(...p);
    else plans.push(p);
  }
  if (problems.length) fail(...problems, "nothing was changed");
  if (plans.every((p) => !p.edits.length)) {
    fail("nothing needs changing, so there's no pull request to open; tag main as it is:", `node scripts/release.ts --tag ${ids.join(" ")}`);
  }

  const title = `release: ${list(plans.map((p) => `${p.plugin.name} ${p.to}`))}`;
  const branch = `release/${ids.join("-")}`;
  for (const p of plans) {
    console.log(`${p.plugin.name} ${p.from === p.to ? p.to : `${p.from} -> ${p.to}`}`);
    const indent = (s: string) => s.replaceAll("\n", "\n      ");
    for (const e of p.edits) console.log(`  ${e.file}\n    - ${indent(e.old)}\n    + ${indent(e.new)}`);
  }
  console.log(`\nbranch ${branch}\ncommit "${title}"`);
  if (dryRun) {
    console.log("\n--dry-run: nothing was changed");
    return;
  }

  if (push) {
    const head = git("rev-parse", "HEAD");
    const main = git("rev-parse", "origin/main");
    if (head !== main) fail(`HEAD (${head.slice(0, 7)}) isn't origin/main (${main.slice(0, 7)}); release from the latest main: git switch --detach origin/main`);
    if (git("ls-remote", "--heads", "origin", branch)) fail(`origin already has a ${branch} branch`);
  }
  if (git("branch", "--list", branch)) fail(`there's already a ${branch} branch here; delete it first`);

  git("switch", "--quiet", "-c", branch);
  for (const p of plans) {
    for (const e of p.edits) {
      if (e.file === "docs/src/data/plugins.json") continue;
      const full = path.join(ROOT, e.file);
      const text = fs.readFileSync(full, "utf8");
      if (!text.includes(e.old)) fail(`${e.file} changed while releasing`);
      fs.writeFileSync(full, text.replace(e.old, () => e.new));
    }
  }
  // The catalog reads each build file's version.
  run(process.execPath, [path.join(ROOT, "docs/scripts/catalog.ts")]);
  git("add", "-A");
  git("commit", "--quiet", "-m", title);
  console.log(`\ncommitted ${git("rev-parse", "--short", "HEAD")} on ${branch}`);
  if (!push) {
    console.log("--no-push: not pushed, and no pull request opened");
    return;
  }

  git("push", "--quiet", "-u", "origin", branch);
  const body = [
    `Releases ${list(plans.map((p) => `${p.plugin.name} ${p.to}`))}.`,
    "",
    "Once this is merged and main's Plugins workflow has passed on the merge commit, tag it:",
    "",
    "```sh",
    `node scripts/release.ts --tag <this pull request's number>`,
    "```",
    "",
    ...plans.flatMap((p) => [`## ${p.plugin.name} ${p.to}`, "", p.notes.replace(/^(#+) /gm, "#$1 "), ""]),
  ].join("\n");
  const url = run("gh", ["pr", "create", "--base", "main", "--head", branch, "--title", title, "--body-file", "-"], body);
  console.log(`opened ${url}`);
}

// ---------------------------------------------------------------------------------------
// Tagging the merged pull request

/**
 * Which plugins a merged release pull request is for: from its release/<ids> branch (ids may
 * have hyphens too, so the longest match wins), or, for a branch named some other way, the
 * plugins whose folder or docs the merge commit changed.
 */
function releasedPlugins(branch: string, sha: string): string[] {
  const parts = branch.replace(/^release\//, "").split("-");
  const ids: string[] = [];
  for (let i = 0; i < parts.length; ) {
    let j = parts.length;
    while (j > i && !plugins.has(parts.slice(i, j).join("-"))) j--;
    if (j === i) break;
    ids.push(parts.slice(i, j).join("-"));
    i = j;
  }
  if (ids.join("-") === parts.join("-")) return ids;
  const changed = git("diff", "--name-only", `${sha}^`, sha).split("\n");
  const touched = [...plugins.keys()].filter((id) =>
    changed.some((f) => f.startsWith(`${id}/`) || f.startsWith(`docs/src/content/docs/${id}/`)),
  );
  if (!touched.length) fail(`can't tell which plugins ${branch} releases; name them: node scripts/release.ts --tag <plugin>...`);
  return touched;
}

function tag(args: string[], dryRun: boolean) {
  if (!args.length) fail("usage: node scripts/release.ts --tag <pr-number | plugin...> [--dry-run]");
  fetchOrigin();
  let sha: string;
  let ids: string[];
  if (args.length === 1 && /^#?\d+$/.test(args[0])) {
    const number = args[0].replace("#", "");
    const pr = JSON.parse(run("gh", ["pr", "view", number, "--json", "state,mergeCommit,headRefName,baseRefName,url"]));
    if (pr.state !== "MERGED") fail(`${pr.url} is ${pr.state.toLowerCase()}, not merged`);
    if (pr.baseRefName !== "main") fail(`${pr.url} was merged into ${pr.baseRefName}, not main`);
    sha = pr.mergeCommit.oid;
    ids = releasedPlugins(pr.headRefName, sha);
  } else {
    sha = git("rev-parse", "origin/main");
    ids = args;
  }
  try {
    git("merge-base", "--is-ancestor", sha, "origin/main");
  } catch {
    fail(`${sha.slice(0, 7)} isn't on main`);
  }

  const problems: string[] = [];
  const tags: { name: string; message: string }[] = [];
  for (const id of ids) {
    const plugin = plugins.get(id);
    if (!plugin) {
      problems.push(`${id} isn't a plugin`);
      continue;
    }
    const show = (file: string) => git("show", `${sha}:${file}`);
    const version = buildVersion(id, show(`${id}/build.gradle.kts`));
    const name = `${id}-v${version}`;
    const last = tagged(id)[0];
    if (!section(show(`${id}/CHANGELOG.md`), version)) problems.push(`${id}/CHANGELOG.md has no "## ${version}" section at ${sha.slice(0, 7)}`);
    if (git("tag", "-l", name) || git("ls-remote", "--tags", "origin", `refs/tags/${name}`)) problems.push(`${name} is already tagged`);
    else if (last && compare(version, last) <= 0) problems.push(`${id} ${version} isn't newer than its last release, ${last}`);
    tags.push({ name, message: `${plugin.name} ${version}` });
  }

  // Releases can't be taken back from Maven Central, so only from a commit main's checks passed on.
  const runs: { headSha: string; status: string; conclusion: string; url: string }[] = JSON.parse(
    run("gh", ["run", "list", "--branch", "main", "--workflow", "Plugins", "--limit", "100", "--json", "headSha,status,conclusion,url"]),
  );
  const ci = runs.find((r) => r.headSha === sha);
  if (!ci) problems.push(`the Plugins workflow hasn't run on ${sha.slice(0, 7)}; run it with: gh workflow run plugins.yml --ref main`);
  else if (ci.status !== "completed") problems.push(`the Plugins workflow is still ${ci.status.replace("_", " ")} on ${sha.slice(0, 7)}: ${ci.url}`);
  else if (ci.conclusion !== "success") problems.push(`the Plugins workflow ended in ${ci.conclusion} on ${sha.slice(0, 7)}: ${ci.url}`);
  if (problems.length) fail(...problems, "no tags were pushed");

  for (const t of tags) console.log(`${t.name} on ${sha.slice(0, 7)}: "${t.message}"`);
  if (dryRun) {
    console.log("\n--dry-run: no tags were made");
    return;
  }
  const pushed: string[] = [];
  for (const t of tags) {
    git("tag", "-a", t.name, sha, "-m", t.message);
    try {
      git("push", "--quiet", "origin", `refs/tags/${t.name}`);
    } catch (e: any) {
      git("tag", "-d", t.name);
      fail(e.message, pushed.length ? `pushed before this: ${pushed.join(", ")}` : "no tags were pushed");
    }
    pushed.push(t.name);
    console.log(`pushed ${t.name}`);
  }
  console.log(`\nThe Release workflow publishes each one: https://github.com/xena-studios/plugins/actions/workflows/release.yml`);
}

// ---------------------------------------------------------------------------------------

const argv = process.argv.slice(2);
const flags = new Set(argv.filter((a) => a.startsWith("--")));
const rest = argv.filter((a) => !a.startsWith("--"));
for (const f of flags) if (!["--dry-run", "--no-push", "--tag"].includes(f)) fail(`unknown flag ${f}`);
try {
  if (flags.has("--tag")) tag(rest, flags.has("--dry-run"));
  else release(rest, flags.has("--dry-run"), !flags.has("--no-push"));
} catch (e: any) {
  fail(e.message);
}
