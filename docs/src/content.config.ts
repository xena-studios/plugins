import { defineCollection } from "astro:content";
import { glob } from "astro/loaders";
import { z } from "astro/zod";
import { docsLoader } from "@astrojs/starlight/loaders";
import { docsSchema } from "@astrojs/starlight/schema";
// The reference manifests' JSON Schema, written by the same Kotlin build as the manifests
// (./gradlew updateXenaManifest), so a manifest this site can't read fails the build here.
import manifestSchema from "./generated/manifest.schema.json" with { type: "json" };

export const collections = {
  docs: defineCollection({ loader: docsLoader(), schema: docsSchema() }),
  // Each rewritten plugin's reference manifest (src/generated/<plugin>/manifest.json), by plugin
  // id, for the components in src/components/reference/. xsample is the core's sample plugin:
  // validated here, shown on no page.
  references: defineCollection({
    loader: glob({ pattern: "*/manifest.json", base: "./src/generated", generateId: ({ entry }) => entry.split("/")[0]! }),
    schema: z.fromJSONSchema(manifestSchema as Parameters<typeof z.fromJSONSchema>[0]),
  }),
};
