// plugins.xenastudios.co: the site for Xena Studios' plugins (every plugin in src/data/plugins.json),
// a static Starlight site served by a Cloudflare Worker. It wears the plugin
// family's brand (xena-studios/brand), as docs.raptorpanel.net wears Raptor's:
// deepslate, Space Grotesk, each plugin's pages in its accent with its icon by
// the title, framed as a game GUI (src/styles/gui.css, DESIGN.md).
import starlight from "@astrojs/starlight";
import { defineConfig } from "astro/config";
import starlightLinksValidator from "starlight-links-validator";
// Every plugin, from the brand catalog (scripts/catalog.ts writes it).
import catalog from "./src/data/plugins.json" with { type: "json" };

export default defineConfig({
  site: "https://plugins.xenastudios.co",
  trailingSlash: "ignore",
  // Hashed build files go in /assets, not Astro's default /_astro.
  build: { inlineStylesheets: "never", assets: "assets" },
  // Fonts are files, not data: URLs, since the CSP's font-src is 'self'.
  vite: { build: { assetsInlineLimit: 0 } },
  integrations: [
    starlight({
      title: "Xena Studios Plugins",
      description: "Docs for Xena Studios' plugins: small, fast plugins for Paper and Folia servers.",
      // XENA STUDIOS, then "Plugins" as a mono label from theme.css.
      logo: { dark: "./src/assets/xena-studios-dark.svg", light: "./src/assets/xena-studios-light.svg", replacesTitle: true },
      favicon: "/favicon.svg",
      // One link preview for every page (xena-studios/brand social/docs-og.png).
      head: [
        { tag: "meta", attrs: { property: "og:image", content: "https://plugins.xenastudios.co/og.png" } },
        { tag: "meta", attrs: { property: "og:image:width", content: "2400" } },
        { tag: "meta", attrs: { property: "og:image:height", content: "1260" } },
        { tag: "meta", attrs: { property: "og:image:alt", content: "XENA STUDIOS plugins over a planet horizon: small, fast plugins for Paper and Folia." } },
        { tag: "meta", attrs: { name: "twitter:image", content: "https://plugins.xenastudios.co/og.png" } },
      ],
      customCss: ["./src/styles/gui.css", "./src/styles/theme.css"],
      // Code on the family's raised surface with a hairline.
      expressiveCode: {
        themes: ["github-dark-default"],
        styleOverrides: {
          borderRadius: "0px",
          borderColor: "#2A2832",
          codeFontFamily: '"JetBrains Mono Variable", ui-monospace, monospace',
          uiFontFamily: '"Space Grotesk Variable", system-ui, sans-serif',
          codeBackground: "#0D0C10",
          frames: {
            shadowColor: "transparent",
            frameBoxShadowCssValue: "none",
            terminalBackground: "#0D0C10",
            terminalTitlebarBackground: "#0D0C10",
            terminalTitlebarBorderBottomColor: "#2A2832",
            editorBackground: "#0D0C10",
            editorTabBarBackground: "#0D0C10",
            editorActiveTabBackground: "#0D0C10",
            editorActiveTabIndicatorBottomColor: "#46434F",
            editorTabBarBorderBottomColor: "#2A2832",
          },
        },
      },
      components: {
        Footer: "./src/components/Footer.astro",
        Header: "./src/components/Header.astro",
        Sidebar: "./src/components/Sidebar.astro",
        PageTitle: "./src/components/PageTitle.astro",
        ThemeProvider: "./src/components/ThemeProvider.astro",
        ThemeSelect: "./src/components/ThemeSelect.astro",
      },
      social: [{ icon: "github", label: "Source code", href: "https://github.com/xena-studios/plugins" }],
      editLink: { baseUrl: "https://github.com/xena-studios/plugins/edit/main/docs/" },
      lastUpdated: true,
      plugins: [starlightLinksValidator()],
      // Each plugin's pages load that plugin's accent (public/accents/).
      routeMiddleware: "./src/routeData.ts",
      // src/pages/404.astro instead, with noindex.
      disable404Route: true,
      sidebar: [
        { label: "Start here", items: [{ autogenerate: { directory: "start" } }] },
        // One group per plugin; src/routeData.ts shows only the current one, or a link to each.
        ...catalog.plugins.map((p) => ({ label: p.name, items: [{ autogenerate: { directory: p.id } }] })),
        { label: "Developers", link: "/developers/" },
        {
          label: "More from Xena Studios",
          items: [
            { label: "Raptor docs", link: "https://docs.raptorpanel.net" },
            { label: "NeoSkript", link: "https://github.com/xena-studios/neoskript", badge: { text: "Soon", variant: "note" } },
            { label: "xenastudios.co", link: "https://xenastudios.co" },
          ],
        },
      ],
    }),
  ],
});
