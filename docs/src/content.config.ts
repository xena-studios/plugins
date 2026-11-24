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
  // id, for the components in src/components/reference/ (<Reference plugin show />). xsample is
  // the core's sample plugin, whose hidden pages (/xsample/) prove them.
  references: defineCollection({
    loader: glob({ pattern: "*/manifest.json", base: "./src/generated", generateId: ({ entry }) => entry.split("/")[0]! }),
    schema: z.fromJSONSchema(manifestSchema as Parameters<typeof z.fromJSONSchema>[0]),
  }),
  // Each rewritten plugin's releases (src/data/releases/<plugin>.json), newest first, for the
  // download picker (src/components/plugin/DownloadPicker.astro): every jar, the platforms and
  // Minecraft versions it's for, and the stores that carry it. Written by the release once a
  // rewrite ships; xsample.json is a fixture. Which platforms exist, and from which Minecraft
  // version, comes from the plugin's manifest, so the picker checks every jar against it.
  releases: defineCollection({
    loader: glob({ pattern: "*.json", base: "./src/data/releases" }),
    schema: z.strictObject({
      $schema: z.string().optional(),
      "//": z.string().optional(),
      plugin: z.string(),
      releases: z
        .array(
          z.strictObject({
            version: z.string().regex(/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/),
            date: z.iso.date(),
            channel: z.enum(["release", "beta", "alpha"]),
            files: z
              .array(
                z.strictObject({
                  name: z.string().regex(/^[\w.+-]+\.jar$/),
                  url: z.url({ protocol: /^https$/ }),
                  size: z.number().int().positive(),
                  sha256: z.string().regex(/^[0-9a-f]{64}$/).optional(),
                  /** Platform ids from the manifest, such as paper and folia. */
                  platforms: z.array(z.string()).min(1),
                  /** Every Minecraft version the jar runs on, as the stores list them. */
                  minecraft: z.array(z.string().regex(/^\d+(\.\d+)*$/)).min(1),
                }),
              )
              .min(1),
            stores: z.array(
              z.strictObject({
                store: z.enum(["modrinth", "hangar", "curseforge", "voxel", "spigotmc", "builtbybit", "github"]),
                url: z.url({ protocol: /^https$/ }),
                /** The platforms whose jars it carries; every one when left out. */
                platforms: z.array(z.string()).min(1).optional(),
              }),
            ),
          }),
        )
        .min(1),
    }),
  }),
};
