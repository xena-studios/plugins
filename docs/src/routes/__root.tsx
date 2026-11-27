// Every page's document: the brand's fonts and styles, the link preview and search. Dark only,
// like every plugin banner and icon: no theme switch, and so no theme script.
import { createRootRoute, HeadContent, Outlet, Scripts } from "@tanstack/react-router";
import { RootProvider } from "fumadocs-ui/provider/tanstack";
import { lazy } from "react";
import mono from "@fontsource-variable/jetbrains-mono/files/jetbrains-mono-latin-wght-normal.woff2?url";
import sans from "@fontsource-variable/space-grotesk/files/space-grotesk-latin-wght-normal.woff2?url";
import appCss from "../styles/app.css?url";

// Search and its engine load the first time search opens, never with a page.
const Search = lazy(() => import("../components/search.tsx"));

export const SITE = "https://plugins.xenastudios.co";
export const SITE_TITLE = "Xena Studios Plugins";

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      { name: "theme-color", content: "#121116" },
      // One link preview for every page (xena-studios/brand social/docs-og.png).
      { property: "og:image", content: `${SITE}/og.png` },
      { property: "og:image:width", content: "2400" },
      { property: "og:image:height", content: "1260" },
      { property: "og:image:alt", content: "XENA STUDIOS plugins over a planet horizon: small, fast plugins for Paper and Folia." },
      { property: "og:site_name", content: SITE_TITLE },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
      { name: "twitter:image", content: `${SITE}/og.png` },
    ],
    links: [
      { rel: "stylesheet", href: appCss },
      // The Latin fonts are fetched with the page, so text never reflows when they arrive.
      { rel: "preload", href: sans, as: "font", type: "font/woff2", crossOrigin: "anonymous" },
      { rel: "preload", href: mono, as: "font", type: "font/woff2", crossOrigin: "anonymous" },
      { rel: "icon", href: "/favicon.svg", type: "image/svg+xml" },
      { rel: "sitemap", href: "/sitemap-index.xml" },
    ],
  }),
  component: Root,
});

function Root() {
  return (
    <html lang="en" className="dark">
      <head>
        <HeadContent />
      </head>
      <body>
        <RootProvider theme={{ enabled: false }} search={{ SearchDialog: Search }}>
          <Outlet />
        </RootProvider>
        <Scripts />
      </body>
    </html>
  );
}
