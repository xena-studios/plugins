// What each data-driven component shows, worked out in Node while its page compiles
// (src/mdx/starlight.ts hands it to the component as a `data` prop). So a page carries only its
// own slice of a manifest or a release list, the browser never fetches one, and the checks that
// used to fail the Astro build (a jar for a platform the manifest doesn't release on, releases
// out of order, a plugin without a manifest) fail this one the same way.
import fs from "node:fs";
import path from "node:path";
import GithubSlugger from "github-slugger";
import { parse } from "yaml";
import catalog from "../data/plugins.json" with { type: "json" };
import { isUnreleased, servedId, servedLink } from "../plugins.ts";
import { releases as githubReleases } from "../releases.ts";
import { type Command, type ConfigEntry, flatten, type Platform, range } from "../components/reference/manifest.ts";
import { inRange, newestFirst, span } from "../components/plugin/minecraft.ts";
import { defaultConfig, manifestOf, releasesOf, requireManifest } from "./data.ts";

export type ReferenceShow = "platforms" | "commands" | "permissions" | "config" | "messages" | "placeholders" | "events";
const SHOWS: ReferenceShow[] = ["platforms", "commands", "permissions", "config", "messages", "placeholders", "events"];

const versionsOf = (p: Platform, and: string) =>
  p.minVersion === p.maxVersion ? p.minVersion : p.maxVersion === "latest" ? `${p.minVersion} ${and} newer` : `${p.minVersion} to ${p.maxVersion}`;

/** One section of a plugin's reference (<Reference plugin show />). */
export function referenceData(plugin: string, show: string) {
  if (!SHOWS.includes(show as ReferenceShow)) throw new Error(`Reference: show="${show}" isn't one of ${SHOWS.join(", ")}`);
  const m = requireManifest(plugin, "Reference");
  const name = m.plugin.name;
  switch (show as ReferenceShow) {
    case "platforms":
      return {
        name,
        show: "platforms" as const,
        rows: (m.platforms ?? []).map((p) => ({
          name: p.name,
          kind: p.kind,
          versions: versionsOf(p, "and"),
          java: p.java ? `${p.java}+` : "n/a",
          planned: p.status === "planned",
          since: p.since ?? "",
        })),
      };
    case "commands":
      return {
        name,
        show: "commands" as const,
        rows: flatten(m.commands ?? [])
          .filter((c) => c.executable)
          .map((c: Command) => ({
            usage: c.usage,
            aliases: c.aliases,
            permission: c.permission ?? null,
            description: c.description,
            playerOnly: c.playerOnly,
            confirm: c.confirm,
            builtIn: c.builtIn,
            cooldown: c.cooldown ?? null,
            cooldownBypass: c.cooldownBypass ?? null,
            arguments: c.arguments.map((a) => ({
              label: a.required ? `<${a.name}>` : `[${a.name}]`,
              description: a.description,
              hint: [a.type, range(a.min, a.max), a.choices.join(" | "), a.greedy ? "the rest of the line" : "", a.required ? "" : "optional"]
                .filter(Boolean)
                .join(", "),
            })),
          })),
      };
    case "permissions": {
      const defaults = { op: "Operators", everyone: "Everyone", nobody: "Nobody", "not-op": "Non-operators" } as const;
      return {
        name,
        show: "permissions" as const,
        rows: (m.permissions ?? []).map((p) => ({
          node: p.node,
          description: p.description,
          builtIn: p.builtIn,
          children: Object.entries(p.children),
          commands: p.commands,
          default: defaults[p.default],
        })),
      };
    }
    case "config": {
      const flat = (entries: ConfigEntry[]): ConfigEntry[] => entries.flatMap((e) => [e, ...flat(e.entries)]);
      return {
        name,
        show: "config" as const,
        rows: flat(m.config?.entries ?? []).map((e) => ({
          path: e.path,
          section: e.type === "section",
          comment: e.comment.join(" "),
          defaultText: e.defaultText ?? "",
          hint: [e.type, range(e.min, e.max), e.choices.join(" | ")].filter(Boolean).join(", "),
        })),
      };
    }
    case "messages":
      return {
        name,
        show: "messages" as const,
        rows: (m.messages ?? []).map((x) => ({ key: x.key, comment: x.comment.join(" "), english: x.english, placeholders: x.placeholders })),
      };
    case "placeholders": {
      const ps = m.placeholders ?? [];
      const plugins = [...new Set(ps.flatMap((p) => Object.keys(p.forms)))];
      return {
        name,
        show: "placeholders" as const,
        rows: ps.map((p) => ({
          forms: plugins.flatMap((n) => (p.forms[n] ? [[p.forms[n]!, n] as [string, string]] : [])),
          description: p.description,
          needsPlayer: p.needsPlayer,
          example: p.example,
        })),
      };
    }
    case "events":
      return {
        name,
        show: "events" as const,
        rows: (m.events ?? []).map((e) => ({
          className: e.className,
          short: e.className.slice(e.className.lastIndexOf(".") + 1),
          hint: [e.cancellable ? "cancellable" : "", e.since ? `since ${e.since}` : ""].filter(Boolean).join(", "),
          description: e.description,
          key: e.key,
        })),
      };
  }
}
export type ReferenceData = ReturnType<typeof referenceData>;

/** The default config.yml shown under the config table, and its title. */
export function referenceConfigFile(plugin: string): { yaml: string; title: string } | undefined {
  const m = requireManifest(plugin, "Reference");
  if (!m.config?.entries.length) return undefined;
  const yaml = defaultConfig(plugin);
  return yaml ? { yaml, title: `plugins/${m.plugin.name}/config.yml` } : undefined;
}

/** Install steps, a tab per platform the manifest releases on (<InstallTabs plugin />). */
export function installData(plugin: string) {
  const m = requireManifest(plugin, "InstallTabs");
  const steps = (p: Platform): { folder: string; needs?: string; restart: string } => {
    switch (p.id) {
      case "fabric":
        return {
          folder: "mods/",
          needs: "Fabric Loader and Fabric Language Kotlin, which gives the mod Kotlin. Fabric jars are one per Minecraft line, so pick the one for your version.",
          restart: "Start the server.",
        };
      case "sponge":
        return {
          folder: "mods/",
          needs: "SpongeVanilla or SpongeForge, API 8 or newer. One jar runs on every Sponge version and carries the Kotlin it needs, so nothing else is installed.",
          restart: "Start the server.",
        };
      case "neoforge":
      case "forge":
        return {
          folder: "mods/",
          needs: "Kotlin for Forge, which gives the mod Kotlin. The jars are one per Minecraft line, so pick the one for your version.",
          restart: "Start the server.",
        };
      case "velocity":
      case "bungeecord":
        return { folder: "plugins/ on the proxy", restart: "Restart the proxy." };
      default:
        return { folder: "plugins/", restart: "Restart the server (not /reload, which no plugin supports well)." };
    }
  };
  return {
    name: m.plugin.name,
    // Its newest major's download page, wherever that's served (src/plugins.ts).
    download: servedLink(`/${plugin}/download/`),
    platforms: m.platforms
      .filter((p) => p.status === "release")
      .map((p) => ({ id: p.id, name: p.name, versions: versionsOf(p, "or"), java: p.java ?? null, ...steps(p) })),
  };
}
export type InstallData = ReturnType<typeof installData>;

const STORES = {
  modrinth: "Modrinth",
  hangar: "Hangar",
  curseforge: "CurseForge",
  voxel: "voxel.shop",
  spigotmc: "SpigotMC",
  builtbybit: "BuiltByBit",
  github: "GitHub",
} as const;
const size = (bytes: number) => `${Math.round(bytes / 1024)} KiB`;
const isoDate = (iso: string) =>
  new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" });

/** A rewritten plugin's downloads: the platforms from its manifest, the jars from its releases file (<DownloadPicker plugin />). */
export function pickerData(plugin: string) {
  const manifest = requireManifest(plugin, "DownloadPicker");
  const file = releasesOf(plugin);
  // A plugin whose rewrite is merged but not released (src/data/versions.json) has none yet: its
  // release writes the file.
  if (!file && !isUnreleased(plugin)) throw new Error(`DownloadPicker: ${plugin} has no releases (src/data/releases/${plugin}.json)`);
  const data = file ?? { plugin, releases: [] };
  if (data.plugin !== plugin) throw new Error(`src/data/releases/${plugin}.json says it's ${data.plugin}'s`);
  const name = manifest.plugin.name;
  const shipping = manifest.platforms.filter((p) => p.status === "release");
  const planned = manifest.platforms.filter((p) => p.status === "planned");
  const where = `src/data/releases/${plugin}.json`;
  for (const r of data.releases) {
    for (const f of r.files) {
      for (const id of f.platforms) {
        if (!shipping.some((p) => p.id === id)) throw new Error(`${where}: ${r.version}'s ${f.name} is for ${id}, which ${name}'s manifest doesn't release on`);
      }
    }
  }
  const dates = data.releases.map((r) => r.date);
  if (dates.some((d, i) => i > 0 && d > dates[i - 1]!)) throw new Error(`${where}: releases must be newest first`);

  const latest = data.releases.find((r) => r.channel === "release");
  const preview = data.releases[0] !== latest ? data.releases[0] : undefined;
  const earlier = data.releases.filter((r) => r !== latest && r !== preview);
  const newestMinecraft = newestFirst(data.releases.flatMap((r) => r.files.flatMap((f) => f.minecraft)))[0] ?? "";
  const rows = latest
    ? shipping.flatMap((p) =>
        latest.files.flatMap((f) => {
          if (!f.platforms.includes(p.id)) return [];
          const minecraft = newestFirst(f.minecraft.filter((v) => inRange(v, p.minVersion, p.maxVersion, newestMinecraft)));
          if (!minecraft.length) return [];
          return [
            {
              platform: p.id,
              platformName: p.name,
              java: p.java ?? null,
              file: { name: f.name, url: f.url, size: size(f.size) },
              minecraft,
              span: span(minecraft),
              stores: latest.stores.filter((s) => !s.platforms || s.platforms.includes(p.id)).map((s) => ({ name: STORES[s.store], url: s.url })),
            },
          ];
        }),
      )
    : [];
  return {
    name,
    latest: latest ? { version: latest.version, date: isoDate(latest.date), platforms: shipping.map((p) => p.name).join(", ") } : null,
    platformOptions: shipping.filter((p) => rows.some((r) => r.platform === p.id)).map((p) => ({ id: p.id, name: p.name })),
    rows,
    preview: preview
      ? { channel: preview.channel, version: preview.version, date: isoDate(preview.date), files: preview.files.map((f) => ({ name: f.name, url: f.url })) }
      : null,
    earlier: earlier.map((r) => ({
      version: r.version,
      channel: r.channel,
      date: isoDate(r.date),
      files: r.files.map((f) => ({ name: f.name, url: f.url, span: span(f.minecraft) })),
    })),
    planned: planned.map((p) => p.name),
  };
}
export type PickerData = ReturnType<typeof pickerData>;

interface ModrinthVersion {
  version_number: string;
  version_type: "release" | "beta" | "alpha";
  date_published: string;
  game_versions: string[];
  loaders: string[];
  files: { url: string; filename: string; primary: boolean; size: number }[];
}

/**
 * A plugin's download page (<Downloads plugin />): its latest version first, then every version
 * before it, read when the site is built (a release redeploys the site, and it's rebuilt daily).
 * For a plugin on Modrinth they come from Modrinth's API and downloads go through Modrinth's CDN,
 * so they count on Modrinth; a version that's also a GitHub release links it as a mirror. A plugin
 * Modrinth is still reviewing (src/data/modrinth.json) has no public Modrinth page yet, so its
 * versions and jars come from its GitHub releases, as they do when Modrinth can't be reached.
 * Before a plugin's first release, or if neither can be reached, the page says so and links what
 * there is instead.
 */
const downloadsCache = new Map<string, ReturnType<typeof readDownloads>>();
/** [id]'s downloads, read once per build however many pages show them. */
export function downloadsData(id: string) {
  if (!downloadsCache.has(id)) downloadsCache.set(id, readDownloads(id));
  return downloadsCache.get(id)!;
}

async function readDownloads(id: string) {
  const p = catalog.plugins.find((x) => x.id === id);
  if (!p) throw new Error(`Downloads: ${id} isn't in the catalog`);
  const repo = "https://github.com/xena-studios/plugins";
  const everyRelease = `${repo}/releases?q=${id}-v`;
  const minecraft = (v: ModrinthVersion) =>
    v.game_versions.length > 1 ? `${v.game_versions[0]} to ${v.game_versions.at(-1)}` : (v.game_versions[0] ?? "");
  const loaders = (v: ModrinthVersion) =>
    v.loaders.map((l) => ({ paper: "Paper", folia: "Folia", purpur: "Purpur", spigot: "Spigot", bukkit: "Bukkit" })[l] ?? l).join(", ");

  type Version = { number: string; type: string; date: string; minecraft?: string; loaders?: string; file: { url: string; name: string; size: number }; github: string };
  let versions: Version[] = [];
  let from: "modrinth" | "github" | null = null;
  let unreachable = false;
  if (p.modrinth) {
    try {
      const answer = await fetch(`https://api.modrinth.com/v2/project/${id}/version`, {
        headers: { "User-Agent": "xena-studios/plugins docs build (contact@xenastudios.co)" },
        signal: AbortSignal.timeout(15_000),
      });
      if (answer.ok) {
        versions = ((await answer.json()) as ModrinthVersion[]).flatMap((v) => {
          const file = v.files.find((f) => f.primary) ?? v.files[0];
          if (!file) return [];
          return [
            {
              number: v.version_number,
              type: v.version_type,
              date: v.date_published,
              minecraft: minecraft(v),
              loaders: loaders(v),
              file: { url: file.url, name: file.filename, size: file.size },
              github: `${repo}/releases/tag/${id}-v${v.version_number}`,
            },
          ];
        });
        if (versions.length) from = "modrinth";
      } else if (answer.status !== 404) unreachable = true;
    } catch {
      unreachable = true;
    }
  }
  if (!versions.length && p.released) {
    const found = await githubReleases(id);
    if (found) {
      unreachable = false;
      versions = found.map((r) => ({ number: r.version, type: r.prerelease ? "pre-release" : "release", date: r.date, file: r.jar, github: r.page }));
      if (versions.length) from = "github";
    } else unreachable = true;
  }
  const latest = versions.find((v) => v.type === "release") ?? versions[0];
  const mirrored = from === "modrinth" ? await githubReleases(id) : null;
  const hasMirror = (v: Version) => !mirrored || mirrored.some((r) => r.version === v.number);
  const date = (iso: string) => new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" });
  // The changelog page's heading for a version ("## 2.1.0" becomes #210), if it has one: Modrinth
  // also lists versions from before this repository, which the changelog doesn't.
  const anchors = changelogAnchors(id);
  const changes = (v: Version) => {
    const anchor = v.number.toLowerCase().replace(/[^a-z0-9-]/g, "");
    return anchors.has(anchor) ? `/${id}/changelog/#${anchor}` : `/${id}/changelog/`;
  };
  const runsOn = p.folia ? "Paper, Purpur and Folia" : "Paper and Purpur";
  const facts = (v: Version) => (from === "modrinth" ? `Minecraft ${v.minecraft} · ${v.loaders} · ${date(v.date)}` : `${runsOn} 1.21.11+ · ${date(v.date)}`);
  return {
    id,
    name: p.name,
    accent: p.accentText,
    released: p.released,
    folia: p.folia,
    modrinth: p.modrinth ?? null,
    everyRelease,
    from,
    unreachable,
    latest: latest
      ? { number: latest.number, facts: facts(latest), file: { url: latest.file.url, name: latest.file.name, size: size(latest.file.size) }, changes: changes(latest), github: latest.github }
      : null,
    versions: versions.map((v) => ({
      number: v.number,
      type: v.type,
      date: date(v.date),
      minecraft: v.minecraft ?? "",
      loaders: v.loaders ?? "",
      url: v.file.url,
      github: v.github,
      mirror: hasMirror(v),
      changes: changes(v),
    })),
    allMirrored: versions.every(hasMirror),
  };
}
export type DownloadsData = Awaited<ReturnType<typeof downloadsData>>;

/** Every plugin's downloads, for the site's download page (<AllDownloads />). */
export async function allDownloadsData() {
  return Promise.all(
    catalog.plugins.map(async (p) => ({ ...(await downloadsData(p.id)), category: p.category, tagline: p.tagline, docs: p.docs })),
  );
}
export type AllDownloadsData = Awaited<ReturnType<typeof allDownloadsData>>;

/** The heading ids of the changelog served at /<id>/changelog/. */
function changelogAnchors(id: string): Set<string> {
  const docs = path.join(process.cwd(), "src/content/docs");
  const file = fs
    .readdirSync(path.join(docs, id), { recursive: true, encoding: "utf8" })
    .map((f) => `${id}/${f.split(path.sep).join("/")}`)
    .find((f) => /changelog\.mdx?$/.test(f) && servedId(f.replace(/\.mdx?$/, "")) === `${id}/changelog`);
  if (!file) return new Set();
  const slugger = new GithubSlugger();
  return new Set([...fs.readFileSync(path.join(docs, file), "utf8").matchAll(/^#{2,6} (.+)$/gm)].map((m) => slugger.slug(m[1]!.trim())));
}

const LANGUAGE_NAMES: Record<string, string> = {
  de: "German",
  es: "Spanish",
  fr: "French",
  it: "Italian",
  nl: "Dutch",
  pl: "Polish",
  "pt-BR": "Portuguese (Brazil)",
  ru: "Russian",
  tr: "Turkish",
  uk: "Ukrainian",
  "zh-CN": "Chinese (Simplified)",
  "zh-TW": "Chinese (Traditional)",
  ja: "Japanese",
  ko: "Korean",
};

/**
 * How complete each language is, per rewritten plugin (<Languages />): its messages from its
 * manifest (English is the code's), against each lang/<tag>.yml it ships, whose `translation:`
 * section says whether it's machine-drafted (decisions/2026-11-24-translations.md).
 */
export async function languagesData() {
  const root = path.resolve(process.cwd(), "..");
  const plugins = catalog.plugins.flatMap((p) => {
    const m = manifestOf(p.id);
    const dir = path.join(root, p.id, "common/src/commonMain/resources/lang");
    if (!m || !fs.existsSync(dir)) return [];
    const keys = m.messages.map((x) => x.key);
    const languages = fs
      .readdirSync(dir)
      .filter((f) => f.endsWith(".yml"))
      .sort()
      .map((f) => {
        const tag = f.slice(0, -4);
        const doc = (parse(fs.readFileSync(path.join(dir, f), "utf8")) ?? {}) as Record<string, unknown> & { translation?: { name?: string; "machine-drafted"?: boolean } };
        const translated = keys.filter((k) => typeof doc[k] === "string" && (doc[k] as string).length > 0).length;
        return {
          tag,
          name: LANGUAGE_NAMES[tag] ?? tag,
          native: doc.translation?.name ?? tag,
          translated,
          machine: doc.translation?.["machine-drafted"] !== false,
        };
      });
    return [{ id: p.id, name: p.name, version: m.plugin.version, released: !isUnreleased(p.id), messages: keys.length, docs: p.docs, languages }];
  });
  const tags = [...new Set(plugins.flatMap((p) => p.languages.map((l) => l.tag)))].sort();
  return {
    plugins,
    languages: tags.map((tag) => {
      const any = plugins.flatMap((p) => p.languages).find((l) => l.tag === tag)!;
      return { tag, name: any.name, native: any.native };
    }),
  };
}
export type LanguagesData = Awaited<ReturnType<typeof languagesData>>;
