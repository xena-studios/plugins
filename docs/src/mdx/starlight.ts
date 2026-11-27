// The pages are written for Starlight, and stay that way: three plugins' pages are being written
// against it right now, and every page should keep working as written. These remark plugins make
// Fumadocs read Starlight's Markdown:
//
// - Imports: "@astrojs/starlight/components" resolves to src/components/starlight.tsx (Code,
//   LinkCard, CardGrid, Card, Tabs, TabItem, Badge, Aside, Steps, LinkButton, with Starlight's
//   props), a ".astro" component resolves to the React component beside where it was
//   (components/reference/Reference.astro is components/reference/Reference.tsx), and a "?raw"
//   import becomes the file's text.
// - <Code code={...} lang title /> becomes a fenced code block, highlighted as the page compiles.
// - Asides: :::note, :::tip, :::caution and :::danger, each with an optional [title].
// - Data: <Reference plugin show />, <InstallTabs plugin />, <DownloadPicker plugin />,
//   <Downloads plugin /> and <Languages /> get a `data` prop worked out here, in Node (src/lib/component-data.ts),
//   and <Reference show="config"> the default config.yml under its table.
// - `class` on HTML in MDX becomes React's `className`.
import fs from "node:fs";
import path from "node:path";
import type { ImportDeclaration, Program } from "estree";
import { valueToEstree } from "estree-util-value-to-estree";
import type { Code, Paragraph, Root, RootContent, Text } from "mdast";
import type { ContainerDirective, LeafDirective, TextDirective } from "mdast-util-directive";
import type { MdxJsxAttribute, MdxJsxFlowElement, MdxJsxTextElement } from "mdast-util-mdx-jsx";
import type { MdxjsEsm } from "mdast-util-mdxjs-esm";
import { SKIP, visit } from "unist-util-visit";
import type { VFile } from "vfile";
import { allDownloadsData, downloadsData, installData, languagesData, pickerData, referenceConfigFile, referenceData } from "../lib/component-data.ts";

const SRC = path.resolve(import.meta.dirname, "..");
const STARLIGHT = path.join(SRC, "components/starlight.tsx");

type Jsx = MdxJsxFlowElement | MdxJsxTextElement;
const attr = (node: Jsx, name: string): MdxJsxAttribute | undefined =>
  node.attributes.find((a): a is MdxJsxAttribute => a.type === "mdxJsxAttribute" && a.name === name);
const stringAttr = (node: Jsx, name: string): string | undefined => {
  const v = attr(node, name)?.value;
  return typeof v === "string" ? v : undefined;
};
/** A literal or identifier attribute's expression, such as {config} or {"a"}. */
const expressionAttr = (node: Jsx, name: string): string | undefined => {
  const v = attr(node, name)?.value;
  return v && typeof v === "object" ? v.value.trim() : undefined;
};
const where = (file: VFile, node: { position?: { start: { line: number } } }) =>
  `${path.relative(process.cwd(), file.path)}:${node.position?.start.line ?? "?"}`;

/** A JSX attribute holding [value] as a JavaScript expression. */
function dataAttribute(name: string, value: unknown): MdxJsxAttribute {
  const program: Program = {
    type: "Program",
    sourceType: "module",
    body: [{ type: "ExpressionStatement", expression: valueToEstree(value) as never }],
  };
  return {
    type: "mdxJsxAttribute",
    name,
    value: { type: "mdxJsxAttributeValueExpression", value: JSON.stringify(value), data: { estree: program } },
  };
}

/** Imports: Starlight's components and .astro files to their React ports, ?raw to text. */
function rewriteImports(tree: Root, file: VFile, raw: Map<string, string>) {
  const dir = path.dirname(file.path);
  visit(tree, "mdxjsEsm", (node: MdxjsEsm, index, parent) => {
    const program = node.data?.estree;
    if (!program) return;
    const kept = program.body.filter((statement) => {
      if (statement.type !== "ImportDeclaration") return true;
      const decl = statement as ImportDeclaration;
      const source = String(decl.source.value);
      let to: string | undefined;
      if (source === "@astrojs/starlight/components") to = STARLIGHT;
      else if (source.endsWith(".astro")) {
        const tsx = path.resolve(dir, source.replace(/\.astro$/, ".tsx"));
        if (!fs.existsSync(tsx)) throw new Error(`${where(file, node)}: ${source} has no React component at ${path.relative(SRC, tsx)}`);
        to = tsx;
      } else if (source.endsWith("?raw")) {
        const target = path.resolve(dir, source.slice(0, -4));
        const text = fs.readFileSync(target, "utf8");
        for (const s of decl.specifiers) raw.set(s.local.name, text);
        file.data.rawImports = [...((file.data.rawImports as string[] | undefined) ?? []), target];
        return false;
      }
      if (to) {
        decl.source = { type: "Literal", value: to, raw: JSON.stringify(to) };
      }
      return true;
    });
    program.body = kept;
    if (!kept.length && parent && typeof index === "number") {
      parent.children.splice(index, 1);
      return [SKIP, index];
    }
    node.value = "";
    return undefined;
  });
}

const ASIDES: Record<string, string> = { note: "Note", tip: "Tip", caution: "Caution", danger: "Danger" };

/** :::note[Title] ... ::: as <Aside type="note" title="Title">, and stray directives back to text. */
function asides(tree: Root) {
  visit(tree, (node, index, parent) => {
    if (!parent || typeof index !== "number") return;
    if (node.type === "containerDirective") {
      const d = node as ContainerDirective;
      if (!(d.name in ASIDES)) return;
      let title = ASIDES[d.name]!;
      const children = d.children.filter((c) => {
        if (c.type === "paragraph" && c.data && (c.data as { directiveLabel?: boolean }).directiveLabel) {
          title = c.children.map((t) => ("value" in t ? t.value : "")).join("");
          return false;
        }
        return true;
      });
      const el: MdxJsxFlowElement = {
        type: "mdxJsxFlowElement",
        name: "Aside",
        attributes: [
          { type: "mdxJsxAttribute", name: "type", value: d.name },
          { type: "mdxJsxAttribute", name: "title", value: title },
        ],
        children: children as MdxJsxFlowElement["children"],
      };
      parent.children.splice(index, 1, el as never);
      return;
    }
    // remark-directive reads ":word" in text as a directive. Starlight puts those back as text;
    // so does this.
    if (node.type === "textDirective") {
      const d = node as TextDirective;
      const text: Text = { type: "text", value: `:${d.name}` };
      parent.children.splice(index, 1, text as never, ...(d.children as never[]));
      return [SKIP, index + 1 + d.children.length] as const;
    }
    if (node.type === "leafDirective") {
      const d = node as LeafDirective;
      const p: Paragraph = { type: "paragraph", children: [{ type: "text", value: `::${d.name}` }, ...d.children] };
      parent.children.splice(index, 1, p as never);
    }
    return undefined;
  });
}

/** Data-driven components and Starlight's <Code>. */
async function components(tree: Root, file: VFile, raw: Map<string, string>) {
  const jobs: Promise<void>[] = [];
  visit(tree, (node, index, parent) => {
    if (node.type !== "mdxJsxFlowElement" && node.type !== "mdxJsxTextElement") return;
    const el = node as Jsx;
    // class="..." on HTML elements, as React spells it.
    if (el.name && /^[a-z]/.test(el.name)) {
      for (const a of el.attributes) if (a.type === "mdxJsxAttribute" && a.name === "class") a.name = "className";
      return;
    }
    const plugin = stringAttr(el, "plugin");
    switch (el.name) {
      case "Code": {
        if (node.type !== "mdxJsxFlowElement" || !parent || typeof index !== "number") return;
        const expr = expressionAttr(el, "code");
        const value = stringAttr(el, "code") ?? (expr && raw.has(expr) ? raw.get(expr) : expr && /^(["'`]).*\1$/s.test(expr) ? (JSON.parse(`"${expr.slice(1, -1).replace(/"/g, '\\"')}"`) as string) : undefined);
        if (value === undefined) return;
        const title = stringAttr(el, "title");
        const code: Code = { type: "code", lang: stringAttr(el, "lang") ?? null, meta: title ? `title=${JSON.stringify(title)}` : null, value: value.replace(/\n$/, "") };
        parent.children.splice(index, 1, code as never);
        return;
      }
      case "Reference": {
        if (!plugin) throw new Error(`${where(file, el)}: <Reference> needs plugin`);
        const show = stringAttr(el, "show") ?? "";
        el.attributes.push(dataAttribute("data", referenceData(plugin, show)));
        if (show === "config" && parent && typeof index === "number") {
          const cfg = referenceConfigFile(plugin);
          if (cfg) {
            const code: Code = { type: "code", lang: "yaml", meta: `title=${JSON.stringify(cfg.title)}`, value: cfg.yaml.replace(/\n$/, "") };
            parent.children.splice(index + 1, 0, code as never);
            return [SKIP, index + 2] as const;
          }
        }
        return;
      }
      case "InstallTabs":
        if (!plugin) throw new Error(`${where(file, el)}: <InstallTabs> needs plugin`);
        el.attributes.push(dataAttribute("data", installData(plugin)));
        return;
      case "DownloadPicker":
        if (!plugin) throw new Error(`${where(file, el)}: <DownloadPicker> needs plugin`);
        el.attributes.push(dataAttribute("data", pickerData(plugin)));
        return;
      case "AllDownloads":
        jobs.push(allDownloadsData().then((d) => void el.attributes.push(dataAttribute("data", d))));
        return;
      case "Languages":
        jobs.push(languagesData().then((d) => void el.attributes.push(dataAttribute("data", d))));
        return;
      case "Downloads":
        if (!plugin) throw new Error(`${where(file, el)}: <Downloads> needs plugin`);
        jobs.push(downloadsData(plugin).then((d) => void el.attributes.push(dataAttribute("data", d))));
        return;
    }
    return undefined;
  });
  await Promise.all(jobs);
}

/** Remark plugin: Starlight's Markdown and components, for Fumadocs. Runs after remark-directive. */
export function remarkStarlight() {
  return async (tree: Root, file: VFile) => {
    const raw = new Map<string, string>();
    rewriteImports(tree, file, raw);
    asides(tree);
    await components(tree, file, raw);
  };
}

export type { RootContent };
