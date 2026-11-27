// Every page, from fumadocs-mdx (source.config.ts), at the address Starlight gave it: its path
// in src/content/docs less the extension and a last "index", or where src/plugins.ts serves an
// unreleased plugin's page (servedId), or its frontmatter slug. Server only.
import { loader } from "fumadocs-core/source";
import { docs } from "collections/server";
import { servedId } from "../plugins.ts";

/** The docs folder: the build runs there (the server bundle's own path is somewhere in dist/). */
export const DOCS_ROOT = process.cwd();

/** "xutilities/v2/commands.md" as its file id, "xutilities/v2/commands". */
export const fileIdOf = (path: string) => path.replace(/\.\w+$/, "").replace(/(^|\/)index$/, "");

export const source = loader({
  baseUrl: "/",
  source: docs.toFumadocsSource(),
  slugs: (file) => {
    const data = file.data as { slug?: string };
    if (typeof data.slug === "string") return data.slug.split("/").filter(Boolean);
    if (!/^([a-z0-9-]+\/)*[a-z0-9-]+\.(md|mdx)$/.test(file.path)) {
      throw new Error(`src/content/docs/${file.path}: name pages in lowercase, digits and hyphens`);
    }
    const id = servedId(fileIdOf(file.path));
    return id ? id.split("/") : [];
  },
  // Addresses end in a slash, as they always have.
  url: (slugs) => urlOf(slugs),
});

/** A page's address from its slugs, with the trailing slash every address has. */
export function urlOf(slugs: string[]): string {
  return slugs.length ? `/${slugs.join("/")}/` : "/";
}
