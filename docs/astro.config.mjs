// docs.xenastudios.co: the docs for Xena's plugins (xUtilities, zChat, xLimbo),
// a static Starlight site served by a Cloudflare Worker. Styled in the plugin
// family's identity from xena-studios/brand, each plugin's pages in its accent.
import starlight from "@astrojs/starlight";
import { defineConfig } from "astro/config";
import starlightLinksValidator from "starlight-links-validator";

export default defineConfig({
  site: "https://docs.xenastudios.co",
  trailingSlash: "ignore",
  // Hashed build files go in /assets, not Astro's default /_astro.
  build: { inlineStylesheets: "never", assets: "assets" },
  // Fonts are files, not data: URLs, since the CSP's font-src is 'self'.
  vite: { build: { assetsInlineLimit: 0 } },
  integrations: [
    starlight({
      title: "Xena Plugin Docs",
      description: "Docs for xUtilities, zChat and xLimbo: small, fast plugins for Paper servers, by Xena Studios.",
      // XENA STUDIOS, then "Plugin docs" as a mono label from theme.css.
      logo: { dark: "./src/assets/xena-studios-dark.svg", light: "./src/assets/xena-studios-light.svg", replacesTitle: true },
      favicon: "/favicon.svg",
      customCss: ["./src/styles/theme.css"],
      expressiveCode: {
        themes: ["github-dark-default"],
        styleOverrides: {
          borderRadius: "6px",
          codeBackground: "#0D0C10",
          frames: {
            terminalBackground: "#0D0C10",
            terminalTitlebarBackground: "#0D0C10",
            editorTabBarBackground: "#0D0C10",
          },
        },
      },
      social: [{ icon: "github", label: "Source code", href: "https://github.com/xena-studios/plugins" }],
      editLink: { baseUrl: "https://github.com/xena-studios/plugins/edit/main/docs/" },
      lastUpdated: true,
      plugins: [starlightLinksValidator()],
      // Each plugin's pages load that plugin's accent (public/accents/).
      routeMiddleware: "./src/routeData.ts",
      sidebar: [
        { label: "Start here", items: [{ autogenerate: { directory: "start" } }] },
        { label: "xUtilities", items: [{ autogenerate: { directory: "xutilities" } }] },
        { label: "zChat", items: [{ autogenerate: { directory: "zchat" } }] },
        { label: "xLimbo", items: [{ autogenerate: { directory: "xlimbo" } }] },
      ],
    }),
  ],
});
