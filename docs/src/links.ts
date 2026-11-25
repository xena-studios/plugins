// The links on an unreleased plugin's pages, pointed where those pages are served until it
// ships (src/plugins.ts). They're written as they'll be after the release ("/xutilities/commands/"
// is the new major's, "/xutilities/v2/commands/" the old one's), and this rewrites them, in
// Markdown links and in components' href attributes (<LinkCard href>), as each page renders.
// It also gives the links validator each page's served address, so the build still fails on
// a broken link, checked against where the pages really are. It must come before Starlight in
// astro.config.ts, so its plugin runs before the validator's.
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { AstroIntegration } from "astro";
import { isUnreleased, servedId, servedLink } from "./plugins.ts";

const DOCS = path.resolve(import.meta.dirname, "content/docs");

interface Node {
  type: string;
  properties?: Record<string, unknown>;
  attributes?: { type: string; name?: string; value?: unknown }[];
}
interface Context {
  data: { astro?: { frontmatter?: Record<string, unknown> } };
  setProperty(node: Node, key: string, value: unknown): void;
}

/** The page's id by its file, such as "xutilities/v2/commands", if it's an unreleased plugin's. */
function unreleasedPage(fileURL: URL | undefined): string | undefined {
  if (!fileURL) return undefined;
  const file = path.relative(DOCS, fileURLToPath(fileURL));
  if (file.startsWith("..") || path.isAbsolute(file)) return undefined;
  const id = file.split(path.sep).join("/").replace(/\.\w+$/, "").replace(/\/index$/, "");
  return isUnreleased(id.split("/")[0]!) ? id : undefined;
}

// The changelog, which the stores get too, links the site in full, as it was when each section
// was written: those links are rewritten only on the new major's pages, which read them as its own.
const SITE = "https://plugins.xenastudios.co";
const rewriter = (full: boolean) => (href: unknown) => {
  if (typeof href !== "string") return href;
  if (href.startsWith(`${SITE}/`)) return full ? SITE + servedLink(href.slice(SITE.length)) : href;
  return servedLink(href);
};

const plugin = ({ fileURL }: { fileURL: URL | undefined }) => {
  const id = unreleasedPage(fileURL);
  if (!id) return undefined;
  const rewrite = rewriter(/^[^/]+\/next(\/|$)/.test(servedId(id)));
  return {
    name: "unreleased-links",
    before(_root: unknown, ctx: Context) {
      // Where the validator files the page's headings, for links to it.
      const frontmatter = ctx.data.astro?.frontmatter;
      if (frontmatter && typeof frontmatter.slug !== "string") frontmatter.slug = servedId(id);
    },
    element: {
      filter: ["a"],
      visit(node: Node, ctx: Context) {
        const href = node.properties?.href;
        if (rewrite(href) !== href) ctx.setProperty(node, "href", rewrite(href));
      },
    },
    mdxJsxFlowElement: {
      filter: [],
      visit(node: Node, ctx: Context) {
        const href = node.attributes?.find((a) => a.type === "mdxJsxAttribute" && a.name === "href")?.value;
        if (rewrite(href) !== href) ctx.setProperty(node, "href", rewrite(href));
      },
    },
  };
};

export function unreleasedLinks(): AstroIntegration {
  return {
    name: "unreleased-links",
    hooks: {
      "astro:config:setup": ({ config }) => {
        const processor = config.markdown.processor as { name: string; options: { hastPlugins?: unknown[] } };
        if (processor.name !== "satteri" || !Array.isArray(processor.options.hastPlugins)) {
          throw new Error(`src/links.ts: expected Astro's satteri Markdown processor, found ${processor.name}`);
        }
        processor.options.hastPlugins.push(plugin);
      },
    },
  };
}
