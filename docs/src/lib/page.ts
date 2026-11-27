// Everything a page shows besides its content, worked out on the server as it's prerendered
// (src/routes/$.tsx calls it through a static server function, so it's a JSON file in the build
// for client-side navigation): its sidebar, previous and next (src/routeData.ts), its accent,
// its title row, the version switch, and the edit and last-updated line.
import fs from "node:fs";
import path from "node:path";
import catalog from "../data/plugins.json" with { type: "json" };
import { type DocsPlugin, majorOf, pageUrl, pluginOf } from "../plugins.ts";
import { routeData, routeIdOf, type SidebarPage } from "../routeData.ts";
import { manifestOf } from "./data.ts";
import { DOCS_ROOT, source, urlOf } from "./source.ts";

const PUBLIC = path.join(DOCS_ROOT, "public");
const EDIT = "https://github.com/xena-studios/plugins/edit/main/docs/src/content/docs/";

type Frontmatter = {
  title: string;
  description: string;
  sidebar?: { order?: number; label?: string; hidden?: boolean; badge?: string | { text: string } };
  pagefind?: boolean;
  editUrl?: string | false;
  template?: "doc" | "splash";
  tableOfContents?: boolean | object;
  lastUpdated?: boolean | Date;
  lastModified?: Date;
};

let sidebarPages: SidebarPage[] | undefined;
/** Every page, as the sidebar needs it. */
function allPages(): SidebarPage[] {
  sidebarPages ??= source.getPages().map((p) => {
    const d = p.data as unknown as Frontmatter;
    const badge = d.sidebar?.badge;
    return {
      file: p.path.replace(/\.\w+$/, ""),
      url: urlOf(p.slugs),
      label: d.sidebar?.label ?? d.title,
      order: d.sidebar?.order,
      hidden: d.sidebar?.hidden ?? false,
      badge: typeof badge === "string" ? badge : badge?.text,
    };
  });
  return sidebarPages;
}

const runsOn = (folia: boolean) => (folia ? "Paper, Purpur and Folia" : "Paper and Purpur");

/** The overview's title row: the plugin as an item, its stats and its first actions. */
function overviewHeader(plugin: DocsPlugin, catalogPlugin: (typeof catalog.plugins)[number], newestServed: boolean) {
  const base = pageUrl(plugin, undefined, "");
  // A rewritten plugin's newest major describes itself from its manifest (#162): its version and
  // every platform it ships on. Anything else, from the brand catalog.
  const manifest = newestServed ? manifestOf(catalogPlugin.id) : undefined;
  let stats: string[];
  if (manifest) {
    const shipping = manifest.platforms.filter((p) => p.status === "release");
    const servers = shipping.filter((p) => p.kind === "server");
    const proxies = shipping.filter((p) => p.kind === "proxy");
    const java = Math.min(...shipping.flatMap((p) => (p.java ? [p.java] : [])));
    const from = (ps: typeof shipping) => {
      const mins = [...new Set(ps.map((p) => p.minVersion).filter((v) => v !== "latest"))];
      return mins.length === 1 ? ` ${mins[0]}+` : "";
    };
    const names = (ps: typeof shipping) => ps.map((p) => p.name).join(", ").replace(/, ([^,]*)$/, " and $1");
    stats = [
      `Version ${manifest.plugin.version}`,
      ...(servers.length ? [`${names(servers)}${from(servers)}`] : []),
      ...(proxies.length ? [names(proxies)] : []),
      ...(Number.isFinite(java) ? [`Java ${java}+`] : []),
      `Jar under ${catalogPlugin.jarBudgetKb} KB`,
      ...(catalogPlugin.api ? ["Developer API"] : []),
    ];
  } else {
    stats = [
      ...(catalogPlugin.released ? [`Version ${catalogPlugin.version}`] : []),
      `${runsOn(catalogPlugin.folia)} 1.21.11+`,
      "Java 21+",
      `Jar under ${catalogPlugin.jarBudgetKb} KB`,
      ...(catalogPlugin.api ? ["Developer API"] : []),
    ];
  }
  const released = catalogPlugin.released && !(newestServed && plugin.id.endsWith("/next"));
  return {
    kind: "overview" as const,
    id: catalogPlugin.id,
    name: catalogPlugin.name,
    tagline: catalogPlugin.tagline,
    stats,
    released,
    actions: [
      ...(released ? [{ label: `Download ${catalogPlugin.name}`, href: `${base}download/`, primary: true }] : []),
      { label: "Commands", href: `${base}commands/`, primary: false },
      { label: "Configuration", href: `${base}configuration/`, primary: false },
    ],
  };
}

/** Under the title of a plugin whose docs cover more than one major: the version switch. */
function versionSwitch(plugin: DocsPlugin, id: string, ids: Set<string>) {
  const page = majorOf(plugin, id);
  const majors = plugin.newest !== undefined && plugin.older.length ? [plugin.newest, ...plugin.older] : [];
  if (!majors.length) return undefined;
  const target = (major: number) => {
    const base = major === plugin.newest ? plugin.id : `${plugin.id}/v${major}`;
    return ids.has(page.rest ? `${base}/${page.rest}` : base) ? pageUrl(plugin, major, page.rest) : pageUrl(plugin, major, "");
  };
  return {
    label: `${plugin.name} versions`,
    current: `${page.major}.x`,
    majors: majors.map((major) => ({ label: `${plugin.name} ${major}.x`, url: target(major), current: major === page.major, newest: major === plugin.newest })),
    old: page.newest ? undefined : { title: `${plugin.name} ${page.major}.x`, text: `These pages are for ${plugin.name} ${page.major}.x, which gets only fixes now. The newest is `, url: target(plugin.newest!), label: `${plugin.name} ${plugin.newest}.x` },
  };
}

export type PageInfo = NonNullable<ReturnType<typeof getPageInfo>>;

/** What the page at [slugs] shows besides its content; undefined if there's no such page. */
export function getPageInfo(slugs: string[]) {
  const page = source.getPage(slugs);
  if (!page) return undefined;
  const d = page.data as unknown as Frontmatter;
  const url = urlOf(page.slugs);
  const id = routeIdOf(url);
  const route = routeData(url, allPages());
  const plugin = pluginOf(id);
  const catalogPlugin = plugin ? catalog.plugins.find((p) => p.id === plugin.catalogId) : undefined;
  // The page as Starlight titled it: the catalog plugin's id, or its unreleased major's.
  const titleOwner = catalog.plugins.find((p) => id === p.id || id.startsWith(`${p.id}/`));
  const isOverview = !!plugin && !!titleOwner && majorOf(plugin, id).rest === "" && (id === titleOwner.id || (plugin.id === `${titleOwner.id}/next` && id === plugin.id));
  const ids = new Set(source.getPages().map((p) => routeIdOf(urlOf(p.slugs))));
  const accentFile = route.catalogId && path.join(PUBLIC, "accents", `${route.catalogId}.css`);
  const editUrl = route.bare || d.editUrl === false ? null : typeof d.editUrl === "string" ? d.editUrl : `${EDIT}${page.path}`;
  const updated = route.bare || d.lastUpdated === false ? undefined : d.lastUpdated instanceof Date ? d.lastUpdated : d.lastModified;
  return {
    path: page.path,
    url,
    title: d.title,
    description: d.description,
    splash: d.template === "splash",
    tree: route.tree,
    previous: route.previous ?? null,
    next: route.next ?? null,
    bare: route.bare,
    toc: !route.bare && d.tableOfContents !== false,
    /** Out of search and search engines. */
    hidden: route.hidden || d.pagefind === false,
    noindex: route.hidden,
    accent: accentFile && fs.existsSync(accentFile) ? fs.readFileSync(accentFile, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").trim() : null,
    plugin: catalogPlugin ? { id: catalogPlugin.id, name: catalogPlugin.name, accent: catalogPlugin.accentText } : null,
    header:
      plugin && titleOwner && isOverview
        ? overviewHeader(plugin, titleOwner, plugin.id.endsWith("/next") || !plugin.folder)
        : titleOwner
          ? { kind: "plugin" as const, id: titleOwner.id }
          : null,
    versions: plugin ? (versionSwitch(plugin, id, ids) ?? null) : null,
    editUrl,
    lastUpdated: updated ? updated.toISOString() : null,
  };
}
