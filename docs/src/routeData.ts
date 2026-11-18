// A plugin's pages wear its accent (public/accents/<id>.css, generated from the brand
// catalog by scripts/catalog.ts). The rest of the site keeps the family's neutral accent
// from theme.css.
import { defineRouteMiddleware } from "@astrojs/starlight/route-data";
import catalog from "./data/plugins.json" with { type: "json" };

const plugins = catalog.plugins.map((p) => p.id);

export const onRequest = defineRouteMiddleware((context) => {
  const route = context.locals.starlightRoute;
  const plugin = plugins.find((p) => route.id === p || route.id.startsWith(`${p}/`));
  if (plugin) route.head.push({ tag: "link", attrs: { rel: "stylesheet", href: `/accents/${plugin}.css` } });
});
