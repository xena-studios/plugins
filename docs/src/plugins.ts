// Every plugin with pages on the site, and which major a page belongs to.
//
// The brand catalog (src/data/plugins.json) lists the plugins people see. src/data/versions.json
// adds, per plugin, the majors its docs cover once its rewrite ships: /<id>/ is always the
// newest major, and each older one's pages live in /<id>/v<major>/. Plugins only in
// versions.json (the core's sample) are hidden: built, so their pages are checked, but out of
// navigation, search, the sitemap and search engines.
//
// A plugin whose rewrite is merged but not released is "unreleased" there. Its files are laid
// out as they will be once it ships (the new major in <id>/, the old in <id>/v<old>/), but the
// site serves them as it did before: the old major's pages at /<id>/, as the current docs, and
// the new major's at /<id>/next/, hidden. servedId says where each file is served, and
// links.ts rewrites the links on the plugin's own pages to match, so they're written once, as
// they'll be after the release. Deleting the "unreleased" field is the whole switch.
//
// A new plugin's first major (xVanish 1.0) is unreleased with no older major: nothing is served
// at /<id>/ until it ships, and its pages, in <id>/, are built at /<id>/next/, hidden, as a
// rewrite's are. Its "unreleased" has no last release's facts, as there was none.
import catalog from "./data/plugins.json" with { type: "json" };
import versions from "./data/versions.json" with { type: "json" };

export interface DocsPlugin {
  id: string;
  name: string;
  /** Out of navigation, search, the sitemap and search engines. */
  hidden: boolean;
  /** The newest major, which /<id>/ describes, once the docs cover more than one. */
  newest?: number;
  /** Older majors with pages in /<id>/v<major>/, newest first. */
  older: number[];
  /** The catalog plugin whose accent and icon its pages wear: [id] but for an unreleased major's pages. */
  catalogId: string;
  /** The sidebar group its pages are in (astro.config.ts makes one per plugin folder). */
  group: string;
  /** For an unreleased plugin, the subgroup its pages are in: the current major's ("v2"). */
  folder?: string;
  /** Whether its sidebar and previous and next are cut to one major's pages (src/routeData.ts). */
  versioned: boolean;
}

interface Versioned {
  id: string;
  name?: string;
  hidden?: boolean;
  newest: number;
  older: number[];
  /**
   * Merged but not released: its last release's facts, which the brand catalog keeps until it
   * ships. Empty for a new plugin, which has had no release.
   */
  unreleased?: { version?: string; jarBudgetKb?: number; api?: boolean };
}
const versioned: Versioned[] = versions.plugins;
const versionsOf = (id: string) => versioned.find((v) => v.id === id);
const newestFirst = (majors: number[]) => [...majors].sort((a, b) => b - a);

/**
 * The unreleased plugins, by id: the major they're on now (none for a new plugin's first), and
 * the one still to ship.
 */
const unreleased = new Map(
  versioned.filter((v) => v.unreleased).map((v) => {
    const [current, ...rest] = newestFirst(v.older);
    if (current === undefined && v.unreleased?.version !== undefined) {
      throw new Error(`src/data/versions.json: ${v.id} has an unreleased major and a last release (${v.unreleased.version}), but no older major's pages to show it`);
    }
    return [v.id, { current, rest, next: v.newest }] as const;
  }),
);

/** Whether [id]'s newest major is merged but not released. */
export const isUnreleased = (id: string) => unreleased.has(id);

/**
 * Where [id]'s developer API guide is, given its docs' root [docs] ("/xplots/"): the released
 * major's, or the unreleased one's (/<id>/next/) when the release had no API (`"api": false` in
 * its "unreleased", as xPlots 1.x had none).
 */
export const guideOf = (id: string, docs: string) =>
  versionsOf(id)?.unreleased?.api === false ? `/${nextId(id)}/developer-api/` : `${docs}developer-api/`;

/** Where a plugin's unreleased major is served meanwhile: "xutilities/next". */
const nextId = (id: string) => `${id}/next`;

function plugin(id: string, name: string, hidden: boolean, v: Versioned | undefined): DocsPlugin[] {
  const u = unreleased.get(id);
  const older = newestFirst(v?.older ?? []);
  if (!u) return [{ id, name, hidden, newest: v?.newest, older, catalogId: id, group: name, versioned: older.length > 0 }];
  const next: DocsPlugin = { id: nextId(id), name: `${name} ${u.next}`, hidden: true, older: [], catalogId: id, group: name, versioned: true };
  // A new plugin: nothing at /<id>/ yet. Its entry there is only its pages' sidebar group, hidden.
  if (u.current === undefined) return [{ id, name, hidden: true, older: [], catalogId: id, group: name, versioned: false }, next];
  return [
    // What people see: the current major at /<id>/, as if the new one weren't there.
    { id, name, hidden, newest: u.rest.length ? u.current : undefined, older: u.rest, catalogId: id, group: name, folder: `v${u.current}`, versioned: u.rest.length > 0 },
    // The new major at /<id>/next/, hidden, on its own.
    next,
  ];
}

export const docsPlugins: DocsPlugin[] = [
  ...catalog.plugins.flatMap((p) => plugin(p.id, p.name, false, versionsOf(p.id))),
  ...versioned
    .filter((v) => !catalog.plugins.some((p) => p.id === v.id))
    .flatMap((v) => {
      if (!v.name) throw new Error(`src/data/versions.json: ${v.id} isn't in the brand catalog, so it needs a name`);
      return plugin(v.id, v.name, v.hidden ?? false, v);
    }),
];

/**
 * Where the page in file [fileId] (its Starlight id by its place in src/content/docs, such as
 * "xutilities/commands") is served: the same, but for an unreleased plugin's pages.
 */
export function servedId(fileId: string): string {
  const id = fileId.split("/")[0]!;
  const u = unreleased.get(id);
  if (!u) return fileId;
  const rest = fileId.slice(id.length + 1);
  const old = /^v(\d+)(?:\/(.*))?$/.exec(rest);
  if (old && u.current !== undefined && Number(old[1]) === u.current) return old[2] ? `${id}/${old[2]}` : id;
  if (old) return fileId;
  return rest ? `${nextId(id)}/${rest}` : nextId(id);
}

/** A root-relative link as written on an unreleased plugin's pages, pointed where that page is served. */
export function servedLink(href: string): string {
  const match = /^\/([^?#]*?)\/?([?#].*)?$/.exec(href);
  if (!match || !href.startsWith("/") || href.startsWith("//")) return href;
  const served = servedId(match[1]!);
  return served === match[1] ? href : `/${served}/${match[2] ?? ""}`;
}

/** The plugin a page (by its Starlight id, such as "xutilities/commands") belongs to. */
export const pluginOf = (routeId: string) =>
  [...docsPlugins].sort((a, b) => b.id.length - a.id.length).find((p) => routeId === p.id || routeId.startsWith(`${p.id}/`));

export interface PageMajor {
  /** The major this page describes, or undefined for a plugin whose docs have one. */
  major?: number;
  /** Whether that's the newest major (also true for a plugin whose docs have one). */
  newest: boolean;
  /** The page within its major: "" for the overview, "commands", "setup/networks". */
  rest: string;
}

/** Which major of [plugin] a page describes, and the page within it. */
export function majorOf(plugin: DocsPlugin, routeId: string): PageMajor {
  const inPlugin = routeId === plugin.id ? "" : routeId.slice(plugin.id.length + 1);
  const match = /^v(\d+)(?:\/(.*))?$/.exec(inPlugin);
  if (match && plugin.older.includes(Number(match[1]))) return { major: Number(match[1]), newest: false, rest: match[2] ?? "" };
  return { major: plugin.newest, newest: true, rest: inPlugin };
}

/** The URL of page [rest] in [major] of [plugin] ("" for the overview). */
export const pageUrl = (plugin: DocsPlugin, major: number | undefined, rest: string) => {
  const base = major === undefined || major === plugin.newest ? `/${plugin.id}/` : `/${plugin.id}/v${major}/`;
  return rest ? `${base}${rest}/` : base;
};

/** Whether [href] is an unreleased major's page (/<id>/next/...), which no other page's previous or next leads to. */
export const isUnlisted = (href: string) => docsPlugins.some((p) => p.group !== p.name && (href === `/${p.id}/` || href.startsWith(`/${p.id}/`)));

/** The ids of plugins kept out of navigation, search, the sitemap and search engines. */
export const hiddenIds = docsPlugins.filter((p) => p.hidden).map((p) => p.id);
