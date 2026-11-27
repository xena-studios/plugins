// Reads one Minecraft version's items and recipes into xMarket's compact form, from what
// Mojang's data generator writes (or, for 1.12.2, the recipes in its server jar), into
// xmarket/prices/vanilla/<version>/: items.txt (one id a line) and recipes.txt. The price list's
// generator (PriceListGenerator) and the no-profit-loop test read them; the newest version's
// recipes also ship in the jar, for /shop audit.
//
//   java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --server --output out
//   node xmarket/prices/extract.ts --version 26.3 --data out
//   node xmarket/prices/extract.ts --version 1.12.2 --legacy <unzipped assets/minecraft/recipes> --flat <flat.txt>
//
// recipes.txt, one recipe a line, ids without "minecraft:":
//   #planks oak_planks spruce_planks ...       a tag a recipe uses, expanded
//   c oak_stairs 4 oak_planks*6                crafting: result, count, ingredients (x*n is n of x)
//   c cake 1 milk_bucket*3 sugar*2 #eggs wheat*3
//   f iron_ingot 1 raw_iron                    a furnace, blast furnace, smoker or campfire
//   s stone_slab 2 stone                       the stonecutter
//   m netherite_sword 1 netherite_upgrade_smithing_template diamond_sword #netherite_tool_materials
// An ingredient that's any of several is a|b. Recipes that only copy or decorate an item
// (dyeing armour, banners, maps, books, fireworks with stars, repairs, trims) aren't listed.
//
// Only node: built-ins, so it runs with plain Node 24.
import fs from "node:fs";
import path from "node:path";

const args = process.argv.slice(2);
function arg(name: string): string | undefined {
  const i = args.indexOf(`--${name}`);
  return i < 0 ? undefined : args[i + 1];
}
const version = arg("version");
if (!version) throw new Error("--version is needed");
const out = path.join(import.meta.dirname, "vanilla", version);
fs.mkdirSync(out, { recursive: true });

const strip = (id: string) => id.replace(/^minecraft:/, "");
const readJson = (file: string) => JSON.parse(fs.readFileSync(file, "utf8"));

type Ingredient = string; // "a|b" or "#tag"
type Recipe = { kind: string; result: string; count: number; ingredients: Ingredient[] };

const recipes: Recipe[] = [];
const usedTags = new Map<string, string[]>();
let items: string[] = [];

function lines(recipe: Recipe): string {
  const counted = new Map<string, number>();
  for (const i of recipe.ingredients) counted.set(i, (counted.get(i) ?? 0) + 1);
  const parts = [...counted].map(([i, n]) => (n === 1 ? i : `${i}*${n}`));
  return `${recipe.kind} ${recipe.result} ${recipe.count} ${parts.join(" ")}`;
}

const data = arg("data");
if (data) {
  const base = path.join(data, "data", "minecraft");
  const recipeDir = fs.existsSync(path.join(base, "recipe")) ? path.join(base, "recipe") : path.join(base, "recipes");
  const tagDir = fs.existsSync(path.join(base, "tags", "item")) ? path.join(base, "tags", "item") : path.join(base, "tags", "items");
  const registries = path.join(data, "reports", "registries.json");
  // In protocol id order, the game's own, which keeps families together.
  const byProtocol = (entries: Record<string, { protocol_id: number }>) =>
    Object.entries(entries).sort(([, a], [, b]) => a.protocol_id - b.protocol_id).map(([id]) => strip(id));
  items = fs.existsSync(registries)
    ? byProtocol(readJson(registries)["minecraft:item"].entries)
    : byProtocol(readJson(path.join(data, "reports", "items.json")));
  // In the registry's own order, which keeps families together (the generator lists them so).
  items = items.filter((i) => i !== "air");

  const tagCache = new Map<string, string[]>();
  function tag(name: string): string[] {
    const key = strip(name.replace(/^#/, ""));
    const known = tagCache.get(key);
    if (known) return known;
    const file = path.join(tagDir, `${key}.json`);
    const values: string[] = [];
    if (fs.existsSync(file)) {
      for (const v of readJson(file).values) {
        const id = typeof v === "string" ? v : v.id;
        if (id.startsWith("#")) values.push(...tag(id));
        else values.push(strip(id));
      }
    }
    tagCache.set(key, values);
    return values;
  }

  // One ingredient, as each version writes it: "minecraft:x", "#minecraft:t", {"item": ...},
  // {"tag": ...}, or a list of any of those.
  function ingredient(json: unknown): Ingredient | null {
    if (json == null) return null;
    if (typeof json === "string") {
      if (json.startsWith("#")) {
        const key = strip(json.slice(1));
        usedTags.set(key, tag(key));
        return `#${key}`;
      }
      return strip(json);
    }
    if (Array.isArray(json)) {
      const options = json.map(ingredient).filter((x): x is string => x != null);
      const flat = options.flatMap((o) => (o.startsWith("#") ? usedTags.get(o.slice(1)) ?? [] : o.split("|")));
      return [...new Set(flat)].join("|") || null;
    }
    const o = json as Record<string, unknown>;
    if (typeof o.item === "string") return strip(o.item);
    if (typeof o.tag === "string") return ingredient(`#${o.tag}`);
    return null;
  }

  function result(json: any): { id: string; count: number } {
    if (typeof json === "string") return { id: strip(json), count: 1 };
    return { id: strip(json.id ?? json.item), count: json.count ?? 1 };
  }

  for (const file of fs.readdirSync(recipeDir).sort()) {
    if (!file.endsWith(".json")) continue;
    const r = readJson(path.join(recipeDir, file));
    const type = strip(r.type);
    const add = (kind: string, ingredients: (Ingredient | null)[], res: any) => {
      if (ingredients.some((i) => i == null) || ingredients.length === 0) return;
      if (res == null || (typeof res !== "string" && !res.id && !res.item)) return;
      const { id, count } = result(res);
      recipes.push({ kind, result: id, count, ingredients: ingredients as Ingredient[] });
    };
    switch (type) {
      case "crafting_shaped": {
        const keys = new Map<string, Ingredient | null>(Object.entries(r.key).map(([k, v]) => [k, ingredient(v)]));
        const ings: (Ingredient | null)[] = [];
        for (const row of r.pattern) for (const ch of row) if (ch !== " ") ings.push(keys.get(ch) ?? null);
        add("c", ings, r.result);
        break;
      }
      case "crafting_shapeless":
        add("c", r.ingredients.map(ingredient), r.result);
        break;
      case "crafting_transmute":
        add("c", [ingredient(r.input), ingredient(r.material)], r.result);
        break;
      case "smelting":
      case "blasting":
      case "smoking":
      case "campfire_cooking":
        add("f", [ingredient(r.ingredient)], r.result);
        break;
      case "stonecutting":
        // Before 1.20.5, a stonecutter recipe's count is beside its result.
        add("s", [ingredient(r.ingredient)], typeof r.result === "string" ? { id: r.result, count: r.count ?? 1 } : r.result);
        break;
      case "smithing_transform":
        add("m", [ingredient(r.template), ingredient(r.base), ingredient(r.addition)], r.result);
        break;
      case "smithing":
        // 1.16 to 1.19: base and addition, no template.
        add("m", [ingredient(r.base), ingredient(r.addition)], r.result);
        break;
      case "crafting_decorated_pot":
      case "crafting_special_decorated_pot":
        recipes.push({ kind: "c", result: "decorated_pot", count: 1, ingredients: ["brick", "brick", "brick", "brick"] });
        break;
      case "crafting_special_firework_rocket":
        recipes.push({ kind: "c", result: "firework_rocket", count: 3, ingredients: ["paper", "gunpowder"] });
        break;
      default:
        // Recipes that copy, dye, decorate or repair an item: no new item from them.
        break;
    }
  }
}

const legacy = arg("legacy");
if (legacy) {
  // 1.12.2's own recipes: items by their old names and data values, flattened to today's ids
  // with flat.txt (each "<old name>:<data> <new id>", from Mojang's own data fixer).
  const flat = new Map<string, string>();
  for (const line of fs.readFileSync(arg("flat")!, "utf8").split("\n")) {
    const [from, to] = line.trim().split(/\s+/);
    if (from && to) flat.set(from, strip(to));
  }
  const id = (o: any): string | null => {
    const name = strip(o.item);
    const dataValue = o.data ?? 0;
    if (dataValue === 32767) {
      const all = [...flat].filter(([k]) => k.startsWith(`${name}:`)).map(([, v]) => v);
      return [...new Set(all)].join("|") || null;
    }
    return flat.get(`${name}:${dataValue}`) ?? flat.get(`${name}:0`) ?? null;
  };
  const ing = (json: any): string | null => {
    if (Array.isArray(json)) {
      const options = json.map(ing).filter((x): x is string => x != null);
      return [...new Set(options.flatMap((o) => o.split("|")))].join("|") || null;
    }
    return id(json);
  };
  const itemSet = new Set<string>();
  for (const file of fs.readdirSync(legacy).sort()) {
    if (!file.endsWith(".json")) continue;
    const r = readJson(path.join(legacy, file));
    const res = id(r.result);
    if (!res) continue;
    let ings: (string | null)[] = [];
    if (r.type === "crafting_shaped") {
      for (const row of r.pattern) for (const ch of row) if (ch !== " ") ings.push(ing(r.key[ch]));
    } else if (r.type === "crafting_shapeless") {
      ings = r.ingredients.map(ing);
    } else continue;
    if (ings.some((i) => i == null)) continue;
    recipes.push({ kind: "c", result: res, count: r.result.count ?? 1, ingredients: ings as string[] });
    itemSet.add(res);
  }
  for (const v of flat.values()) itemSet.add(v);
  items = [...itemSet].filter((i) => i !== "air").sort();
}

const tagLines = [...usedTags].sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `#${k} ${[...new Set(v)].join(" ")}`);
const recipeLines = [...new Set(recipes.map(lines))].sort();
fs.writeFileSync(path.join(out, "recipes.txt"), [`# Minecraft ${version}'s recipes, from xmarket/prices/extract.ts.`, ...tagLines, ...recipeLines].join("\n") + "\n");
fs.writeFileSync(path.join(out, "items.txt"), [`# Minecraft ${version}'s items, from xmarket/prices/extract.ts.`, ...items].join("\n") + "\n");
console.log(`${version}: ${items.length} items, ${recipeLines.length} recipes, ${tagLines.length} tags`);
