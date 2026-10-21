// A plugin's pages wear its accent: amber for xUtilities, green for zChat,
// violet for xLimbo (xena-studios/brand, scripts/plugin-brand.mjs). The rest
// of the site keeps the family's neutral accent from theme.css.
import { defineRouteMiddleware } from "@astrojs/starlight/route-data";

const plugins = ["xutilities", "zchat", "xlimbo"];

export const onRequest = defineRouteMiddleware((context) => {
  const route = context.locals.starlightRoute;
  const plugin = plugins.find((p) => route.id === p || route.id.startsWith(`${p}/`));
  if (plugin) route.head.push({ tag: "link", attrs: { rel: "stylesheet", href: `/accents/${plugin}.css` } });
});
