// The pages (src/content/docs/**), compiled by fumadocs-mdx. Their frontmatter keeps Starlight's
// meaning (title, description, sidebar.order and sidebar.label, pagefind, editUrl, template,
// slug...), and their Markdown keeps Starlight's syntax (src/mdx/starlight.ts).
import {
  transformerMetaHighlight,
  transformerMetaWordHighlight,
  transformerNotationDiff,
  transformerNotationFocus,
  transformerNotationHighlight,
  transformerNotationWordHighlight,
} from "@shikijs/transformers";
import { pageSchema } from "fumadocs-core/source/schema";
import { defineConfig, defineDocs } from "fumadocs-mdx/config";
import remarkDirective from "remark-directive";
import { z } from "zod";
import { remarkUnreleasedLinks } from "./src/links.ts";
import { remarkStarlight } from "./src/mdx/starlight.ts";

const badge = z.union([z.string(), z.object({ text: z.string(), variant: z.string().optional() }).loose()]);

export const docs = defineDocs({
  dir: "src/content/docs",
  docs: {
    schema: pageSchema
      .extend({
        description: z.string(),
        /** Starlight's sidebar entry: its place among its siblings, and a shorter label. */
        sidebar: z
          .object({ order: z.number().optional(), label: z.string().optional(), hidden: z.boolean().optional(), badge: badge.optional() })
          .loose()
          .optional(),
        /** false keeps the page out of search. */
        pagefind: z.boolean().optional(),
        /** Where "Edit page" goes, or false for none. */
        editUrl: z.union([z.string(), z.literal(false)]).optional(),
        /** "splash": the home page's layout, with no sidebar. */
        template: z.enum(["doc", "splash"]).optional(),
        tableOfContents: z.union([z.boolean(), z.object({ minHeadingLevel: z.number(), maxHeadingLevel: z.number() }).loose()]).optional(),
        lastUpdated: z.union([z.boolean(), z.coerce.date()]).optional(),
        slug: z.string().optional(),
        draft: z.boolean().optional(),
        prev: z.unknown().optional(),
        next: z.unknown().optional(),
        head: z.array(z.unknown()).optional(),
      })
      .loose(),
    async: true,
    lastModified: true,
    postprocess: { valueToExport: [] },
  },
});

export default defineConfig({
  mdxOptions: {
    remarkPlugins: (v) => [remarkDirective, remarkUnreleasedLinks, remarkStarlight, ...v],
    rehypeCodeOptions: {
      themes: { light: "github-light-default", dark: "github-dark-default" },
      // Fumadocs' own (// [!code highlight] and the like), and Starlight's {1,3} and "word" marks.
      transformers: [
        transformerNotationHighlight({ matchAlgorithm: "v3" }),
        transformerNotationWordHighlight({ matchAlgorithm: "v3" }),
        transformerNotationDiff({ matchAlgorithm: "v3" }),
        transformerNotationFocus({ matchAlgorithm: "v3" }),
        transformerMetaHighlight(),
        transformerMetaWordHighlight(),
      ],
    },
  },
});
