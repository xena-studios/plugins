// Every plugin with pages on the site, and which major a page belongs to.
//
// The brand catalog (src/data/plugins.json) lists the plugins people see. src/data/versions.json
// adds, per plugin, the majors its docs cover once its rewrite ships: /<id>/ is always the
// newest major, and each older one's pages live in /<id>/v<major>/. Plugins only in
// versions.json (the core's sample) are hidden: built, so their pages are checked, but out of
// navigation, search, the sitemap and search engines.
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
}

interface Versioned {
  id: string;
  name?: string;
  hidden?: boolean;
  newest: number;
  older: number[];
}
const versioned: Versioned[] = versions.plugins;
const versionsOf = (id: string) => versioned.find((v) => v.id === id);

export const docsPlugins: DocsPlugin[] = [
  ...catalog.plugins.map((p) => {
    const v = versionsOf(p.id);
    return { id: p.id, name: p.name, hidden: false, newest: v?.newest, older: [...(v?.older ?? [])].sort((a, b) => b - a) };
  }),
  ...versioned
    .filter((v) => !catalog.plugins.some((p) => p.id === v.id))
    .map((v) => {
      if (!v.name) throw new Error(`src/data/versions.json: ${v.id} isn't in the brand catalog, so it needs a name`);
      return { id: v.id, name: v.name, hidden: v.hidden ?? false, newest: v.newest, older: [...v.older].sort((a, b) => b - a) };
    }),
];

/** The plugin a page (by its Starlight id, such as "xutilities/commands") belongs to. */
export const pluginOf = (routeId: string) => docsPlugins.find((p) => routeId === p.id || routeId.startsWith(`${p.id}/`));

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

/** The ids of plugins kept out of navigation, search, the sitemap and search engines. */
export const hiddenIds = docsPlugins.filter((p) => p.hidden).map((p) => p.id);
