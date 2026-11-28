// The API reference pages (/api/ and /api/<plugin>/), on the server. Dokka's HTML for each
// rewritten plugin's latest API will be copied over its page at build once the Gradle build
// writes it (decisions/2026-11-27-the-site-on-fumadocs.md); until then each page says what's
// there and where its API is documented now.
import fs from "node:fs";
import path from "node:path";
import catalog from "../data/plugins.json" with { type: "json" };
import { guideOf, isUnreleased } from "../plugins.ts";
import { routeData } from "../routeData.ts";
import { manifestOf } from "./data.ts";

// The repository, above docs/, where the build runs.
const ROOT = path.resolve(process.cwd(), "..");

export function apiInfo(id?: string) {
  const plugins = catalog.plugins
    .filter((p) => p.api)
    .map((p) => {
      const manifest = manifestOf(p.id);
      // A rewritten plugin's API is Kotlin, in <plugin>/api/src/commonMain.
      const kotlin = fs.existsSync(path.join(ROOT, p.id, "api/src/commonMain"));
      return {
        id: p.id,
        name: p.name,
        maven: p.maven ?? null,
        version: p.version,
        released: p.released,
        guide: guideOf(p.id, p.docs),
        rewritten: kotlin,
        unreleasedRewrite: kotlin && isUnreleased(p.id),
        newVersion: manifest?.plugin.version ?? null,
        events: manifest?.events.length ?? 0,
      };
    });
  const url = id ? `/api/${id}/` : "/api/";
  const route = routeData(url, []);
  return { url, tree: route.tree, plugins, plugin: id ? (plugins.find((p) => p.id === id) ?? null) : null };
}
export type ApiInfo = ReturnType<typeof apiInfo>;
