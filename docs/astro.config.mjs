// docs.xenastudios.co: the docs for Xena's plugins (xUtilities, zChat, xLimbo),
// a static Starlight site served by a Cloudflare Worker. Styled like
// xenastudios.co (Ion Blue on deep space, JetBrains Mono, dark only), with each
// plugin's icon beside its pages' titles.
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
      // Code in the site's own surfaces: a `space` panel with a `line` hairline.
      expressiveCode: {
        themes: ["github-dark-default"],
        styleOverrides: {
          borderRadius: "12px",
          borderColor: "#222B3B",
          codeFontFamily: '"JetBrains Mono Variable", ui-monospace, monospace',
          uiFontFamily: '"JetBrains Mono Variable", ui-monospace, monospace',
          codeBackground: "#0A0E16",
          frames: {
            shadowColor: "transparent",
            frameBoxShadowCssValue: "none",
            terminalBackground: "#0A0E16",
            terminalTitlebarBackground: "#0A0E16",
            terminalTitlebarBorderBottomColor: "#222B3B",
            editorBackground: "#0A0E16",
            editorTabBarBackground: "#0A0E16",
            editorActiveTabBackground: "#0A0E16",
            editorActiveTabIndicatorBottomColor: "#5CB4FF",
            editorTabBarBorderBottomColor: "#222B3B",
          },
        },
      },
      components: {
        PageTitle: "./src/components/PageTitle.astro",
        ThemeProvider: "./src/components/ThemeProvider.astro",
        ThemeSelect: "./src/components/ThemeSelect.astro",
      },
      social: [{ icon: "github", label: "Source code", href: "https://github.com/xena-studios/plugins" }],
      editLink: { baseUrl: "https://github.com/xena-studios/plugins/edit/main/docs/" },
      lastUpdated: true,
      plugins: [starlightLinksValidator()],
      sidebar: [
        { label: "Start here", items: [{ autogenerate: { directory: "start" } }] },
        { label: "xUtilities", items: [{ autogenerate: { directory: "xutilities" } }] },
        { label: "zChat", items: [{ autogenerate: { directory: "zchat" } }] },
        { label: "xLimbo", items: [{ autogenerate: { directory: "xlimbo" } }] },
      ],
    }),
  ],
});
