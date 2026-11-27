// The data the site is built from, read in Node as the pages compile (src/mdx/starlight.ts) and
// as they're prerendered (src/lib/page.ts), never shipped whole to the browser: a page gets only
// the slice it shows.
//
// - Each rewritten plugin's reference manifest (src/generated/<plugin>/manifest.json), written by
//   the same Kotlin build as its JSON Schema (./gradlew updateXenaManifest), and checked against
//   src/generated/manifest.schema.json, so a manifest the site can't read fails the build.
// - Each rewritten plugin's releases (src/data/releases/<plugin>.json), newest first, for the
//   download picker: every jar, the platforms and Minecraft versions it's for, and the stores that
//   carry it. Written by the release once a rewrite ships; xsample.json is a fixture.
import fs from "node:fs";
import path from "node:path";
import { z } from "zod";
import type { Manifest } from "../components/reference/manifest.ts";

// The build runs in docs/; the server bundle's own path is somewhere in dist/.
const SRC = path.join(process.cwd(), "src");
export const GENERATED = path.join(SRC, "generated");
const RELEASES = path.join(SRC, "data/releases");

let schema: z.ZodType | undefined;
const manifests = new Map<string, Manifest | null>();

/** [plugin]'s manifest, checked against the schema; undefined if it has none. */
export function manifestOf(plugin: string): Manifest | undefined {
  if (!manifests.has(plugin)) {
    const file = path.join(GENERATED, plugin, "manifest.json");
    if (!fs.existsSync(file)) {
      manifests.set(plugin, null);
    } else {
      schema ??= z.fromJSONSchema(JSON.parse(fs.readFileSync(path.join(GENERATED, "manifest.schema.json"), "utf8")));
      const parsed = schema.safeParse(JSON.parse(fs.readFileSync(file, "utf8")));
      if (!parsed.success) {
        throw new Error(`src/generated/${plugin}/manifest.json doesn't match manifest.schema.json:\n${z.prettifyError(parsed.error)}`);
      }
      manifests.set(plugin, parsed.data as Manifest);
    }
  }
  return manifests.get(plugin) ?? undefined;
}

/** Every plugin with a manifest, checked: the build fails on any that doesn't match the schema. */
export function checkAllManifests(): string[] {
  const ids = fs.readdirSync(GENERATED).filter((d) => fs.existsSync(path.join(GENERATED, d, "manifest.json")));
  for (const id of ids) manifestOf(id);
  return ids;
}

/** [plugin]'s manifest, or a build error naming the component that needed it. */
export function requireManifest(plugin: string, who: string): Manifest {
  const m = manifestOf(plugin);
  if (!m) throw new Error(`${who}: ${plugin} has no manifest (src/generated/${plugin}/manifest.json). Run ./gradlew updateXenaManifest.`);
  return m;
}

/** The default config.yml the plugin's build writes beside its manifest. */
export function defaultConfig(plugin: string): string | undefined {
  const file = path.join(GENERATED, plugin, "config.yml");
  return fs.existsSync(file) ? fs.readFileSync(file, "utf8") : undefined;
}

export const releasesSchema = z.strictObject({
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
});
export type Releases = z.infer<typeof releasesSchema>;

/** [plugin]'s releases file, checked; undefined if it has none yet. */
export function releasesOf(plugin: string): Releases | undefined {
  const file = path.join(RELEASES, `${plugin}.json`);
  if (!fs.existsSync(file)) return undefined;
  const parsed = releasesSchema.safeParse(JSON.parse(fs.readFileSync(file, "utf8")));
  if (!parsed.success) throw new Error(`src/data/releases/${plugin}.json:\n${z.prettifyError(parsed.error)}`);
  return parsed.data;
}

/** Every releases file, checked. */
export function checkAllReleases(): void {
  if (!fs.existsSync(RELEASES)) return;
  for (const f of fs.readdirSync(RELEASES)) if (f.endsWith(".json")) releasesOf(f.slice(0, -5));
}
