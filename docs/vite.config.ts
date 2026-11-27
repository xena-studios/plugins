// plugins.xenastudios.co: Fumadocs on TanStack Start, prerendered to static files in dist/client
// (every page, the search index and the static server functions' JSON), served by Cloudflare
// with no server code (wrangler.jsonc). scripts/postbuild.ts then finishes the build: redirects,
// the sitemap, the CSP's script hashes, the link check and the page-weight budget.
import { glob } from "node:fs/promises";
import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import { tanstackStart } from "@tanstack/react-start/plugin/vite";
import react from "@vitejs/plugin-react";
import { fumadocsMdx } from "fumadocs-mdx/vite";
import { defineConfig } from "vite";
import catalog from "./src/data/plugins.json" with { type: "json" };
import { servedId } from "./src/plugins.ts";

// Every page's address, so each is prerendered whether or not a link reaches it (the hidden
// /<id>/next/ pages and old majors among them), on top of what crawling the links finds.
const pages: string[] = [];
for await (const file of glob("**/*.{md,mdx}", { cwd: "src/content/docs" })) {
  const id = servedId(file.split(path.sep).join("/").replace(/\.\w+$/, "").replace(/(^|\/)index$/, ""));
  pages.push(id ? `/${id}/` : "/");
}
const apis = catalog.plugins.filter((p) => p.api).map((p) => `/api/${p.id}/`);

export default defineConfig({
  server: { port: 4321 },
  resolve: {
    tsconfigPaths: true,
    alias: { collections: path.resolve(import.meta.dirname, ".source") },
  },
  // Fonts and icons are files, never data: URLs (the CSP's font-src is 'self').
  build: { assetsInlineLimit: 0, sourcemap: !!process.env.SOURCEMAP },
  plugins: [
    fumadocsMdx(),
    tailwindcss(),
    tanstackStart({
      prerender: {
        enabled: true,
        crawlLinks: true,
        autoSubfolderIndex: true,
        failOnError: true,
      },
      pages: [
        ...[...pages, "/api/", ...apis].map((p) => ({ path: p })),
        { path: "/404/", prerender: { enabled: true, outputPath: "/404.html", autoSubfolderIndex: false } },
      ],
    }),
    react(),
  ],
});
