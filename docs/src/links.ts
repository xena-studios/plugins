// The links on an unreleased plugin's pages, pointed where those pages are served until it
// ships (src/plugins.ts). They're written as they'll be after the release ("/xutilities/commands/"
// is the new major's, "/xutilities/v2/commands/" the old one's), and this rewrites them, in
// Markdown links and in components' href attributes (<LinkCard href>), as each page compiles.
// The link checker (scripts/postbuild.ts) then checks the built pages, so a broken link fails
// the build either way, checked against where the pages really are.
import path from "node:path";
import type { Root } from "mdast";
import type { MdxJsxAttribute } from "mdast-util-mdx-jsx";
import { visit } from "unist-util-visit";
import type { VFile } from "vfile";
import { isUnreleased, servedId, servedLink } from "./plugins.ts";

const DOCS = path.resolve(import.meta.dirname, "content/docs");

/** The page's id by its file, such as "xutilities/v2/commands", if it's an unreleased plugin's. */
function unreleasedPage(file: string | undefined): string | undefined {
  if (!file) return undefined;
  const rel = path.relative(DOCS, file);
  if (rel.startsWith("..") || path.isAbsolute(rel)) return undefined;
  const id = rel.split(path.sep).join("/").replace(/\.\w+$/, "").replace(/\/index$/, "");
  return isUnreleased(id.split("/")[0]!) ? id : undefined;
}

// The changelog, which the stores get too, links the site in full, as it was when each section
// was written: those links are rewritten only on the new major's pages, which read them as its own.
const SITE = "https://plugins.xenastudios.co";
const rewriter = (full: boolean) => (href: string) => {
  if (href.startsWith(`${SITE}/`)) return full ? SITE + servedLink(href.slice(SITE.length)) : href;
  return servedLink(href);
};

/** Remark plugin: an unreleased plugin's links, pointed where its pages are served. */
export function remarkUnreleasedLinks() {
  return (tree: Root, file: VFile) => {
    const id = unreleasedPage(file.path);
    if (!id) return;
    const rewrite = rewriter(/^[^/]+\/next(\/|$)/.test(servedId(id)));
    visit(tree, (node) => {
      if (node.type === "link" || node.type === "definition") node.url = rewrite(node.url);
      else if (node.type === "mdxJsxFlowElement" || node.type === "mdxJsxTextElement") {
        for (const a of node.attributes) {
          if (a.type === "mdxJsxAttribute" && a.name === "href" && typeof a.value === "string") (a as MdxJsxAttribute).value = rewrite(a.value);
        }
      }
    });
  };
}
