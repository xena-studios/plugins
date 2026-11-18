// Per-page route data, from the brand catalog (src/data/plugins.json):
// - A plugin's pages wear its accent (public/accents/<id>.css). The rest of the site keeps
//   the family's neutral accent from theme.css.
// - Home, overviews and download pages have no contents column, pager or edit line.
// - The sidebar shows one plugin at a time. On a plugin's pages it's only that plugin's
//   pages (Sidebar.astro adds a switcher above them); everywhere else it's the start
//   pages, then each category's plugins as one link each, then the rest.
import { defineRouteMiddleware, type StarlightRouteData } from "@astrojs/starlight/route-data";
import catalog from "./data/plugins.json" with { type: "json" };

type Entry = StarlightRouteData["sidebar"][number];

const names = new Set(catalog.plugins.map((p) => p.name));
const isPluginGroup = (e: Entry) => e.type === "group" && names.has(e.label);

export const onRequest = defineRouteMiddleware((context) => {
  const route = context.locals.starlightRoute;
  const plugin = catalog.plugins.find((p) => route.id === p.id || route.id.startsWith(`${p.id}/`));
  // Pages that sell or hand over the plugin (home, each overview and download page) aren't
  // reading pages: no contents column, no previous and next, no edit or last-updated line.
  const isHome = route.id === "" || route.id === "index";
  if (isHome || (plugin && (route.id === plugin.id || route.id === `${plugin.id}/download`))) {
    route.toc = undefined;
    route.pagination = { prev: undefined, next: undefined };
    route.editUrl = undefined;
    route.lastUpdated = undefined;
  }
  if (plugin) {
    route.head.push({ tag: "link", attrs: { rel: "stylesheet", href: `/accents/${plugin.id}.css` } });
    const group = route.sidebar.find((e) => e.type === "group" && e.label === plugin.name);
    if (group?.type === "group") route.sidebar = group.entries;
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
