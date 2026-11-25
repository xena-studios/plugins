// Per-page route data, from the brand catalog (src/data/plugins.json):
// - A plugin's pages wear its accent (public/accents/<id>.css). The rest of the site keeps
//   the family's neutral accent from theme.css.
// - Home, overviews and download pages have no contents column, pager or edit line.
// - The sidebar shows one plugin at a time. On a plugin's pages it's only that plugin's
//   pages (Sidebar.astro adds a switcher above them); everywhere else it's the start
//   pages, then each category's plugins as one link each, then the rest.
// - A plugin whose docs have more than one major (src/data/versions.json) shows only the
//   pages of the major you're reading: its v<old>/ folders are sidebar groups, dropped from
//   the newest major's sidebar and standing in for it on their own pages.
// - Hidden plugins (the core's sample, and an unreleased major's pages at /<id>/next/) are left
//   out of navigation and search, and kept from search engines. The unreleased major's folder
//   pages sit in the plugin's folder, as they will once it ships, so its sidebar is that
//   folder's pages less its v<old>/ groups, and the current major's is its v<old>/ group.
import { defineRouteMiddleware, type StarlightRouteData } from "@astrojs/starlight/route-data";
import catalog from "./data/plugins.json" with { type: "json" };
import { docsPlugins, isUnlisted, majorOf, pluginOf } from "./plugins.ts";

type Entry = StarlightRouteData["sidebar"][number];

const names = new Set(docsPlugins.map((p) => p.name));
const isPluginGroup = (e: Entry) => e.type === "group" && names.has(e.label);
const isMajorGroup = (e: Entry) => e.type === "group" && /^v\d+$/.test(e.label);
type Link = Extract<Entry, { type: "link" }>;
const links = (entries: Entry[]): Link[] => entries.flatMap((e) => (e.type === "link" ? [e] : links(e.entries)));

export const onRequest = defineRouteMiddleware((context) => {
  const route = context.locals.starlightRoute;
  const plugin = pluginOf(route.id);
  const page = plugin && majorOf(plugin, route.id);
  // Previous and next skip an unreleased major's pages, which are in its plugin's sidebar group
  // ahead of the current major's, except among themselves.
  const { prev, next } = route.pagination;
  if (!(plugin && plugin.group !== plugin.name) && [prev, next].some((l) => l && isUnlisted(l.href))) {
    const list = links(route.sidebar).filter((l) => l.isCurrent || !isUnlisted(l.href));
    const at = list.findIndex((l) => l.isCurrent);
    if (at >= 0) route.pagination = { prev: prev && list[at - 1], next: next && list[at + 1] };
  }
  // Pages that sell or hand over the plugin (home, each overview and download page) aren't
  // reading pages: no contents column, no previous and next, no edit or last-updated line.
  const isHome = route.id === "" || route.id === "index";
  if (isHome || (page && (page.rest === "" || page.rest === "download"))) {
    route.toc = undefined;
    route.pagination = { prev: undefined, next: undefined };
    route.editUrl = undefined;
    route.lastUpdated = undefined;
  }
  if (plugin && page) {
    if (catalog.plugins.some((p) => p.id === plugin.catalogId)) {
      route.head.push({ tag: "link", attrs: { rel: "stylesheet", href: `/accents/${plugin.catalogId}.css` } });
    }
    if (plugin.hidden) {
      route.head.push({ tag: "meta", attrs: { name: "robots", content: "noindex" } });
      route.entry.data.pagefind = false;
    }
    const group = route.sidebar.find((e) => e.type === "group" && e.label === plugin.group);
    if (group?.type === "group") {
      // An old major's pages are its v<old>/ group, and so are an unreleased plugin's current
      // pages (plugin.folder); the newest major's are the rest.
      const folder = plugin.folder ?? (page.newest ? undefined : `v${page.major}`);
      const old = folder ? group.entries.find((e) => e.type === "group" && e.label === folder) : undefined;
      route.sidebar = old?.type === "group" ? old.entries : group.entries.filter((e) => !isMajorGroup(e));
      // Previous and next stay within the major, as the sidebar does.
      if (plugin.versioned && (route.pagination.prev || route.pagination.next)) {
        const list = links(route.sidebar);
        const at = list.findIndex((l) => l.isCurrent);
        if (at >= 0) route.pagination = { prev: list[at - 1], next: list[at + 1] };
      }
    }
    return;
  }
  const rest = route.sidebar.filter((e) => !isPluginGroup(e));
  const byCategory: Entry[] = catalog.categories.map((category) => ({
    type: "group",
    label: category,
    collapsed: false,
    badge: undefined,
    entries: catalog.plugins
      .filter((p) => p.category === category)
      .map((p) => ({
        type: "link",
        label: p.name,
        href: p.docs,
        isCurrent: false,
        badge: p.released ? undefined : { text: "Soon", variant: "note" },
        attrs: {},
      })),
  }));
  // Start here, then the plugins, then Developers and the rest.
  route.sidebar = [...rest.slice(0, 1), ...byCategory, ...rest.slice(1)];
});
