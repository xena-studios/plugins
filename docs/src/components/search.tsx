// Search: Fumadocs' dialog over Pagefind's index (scripts/postbuild.ts writes it to /pagefind/
// from the built pages, as the Starlight site's was). Pagefind's index comes in small chunks, so
// a search downloads only the parts it needs, and none of it loads until search first opens.
// Hidden pages (the core's sample, an unreleased major's /<id>/next/, and pages with
// `pagefind: false`) carry no data-pagefind-body, so they're left out.
import type { SearchResult, SearchResultRecord } from "fumadocs-core/search/client";
import {
  SearchDialog,
  SearchDialogClose,
  SearchDialogContent,
  SearchDialogFooter,
  SearchDialogHeader,
  SearchDialogIcon,
  SearchDialogInput,
  SearchDialogList,
  SearchDialogOverlay,
  type SharedProps,
} from "fumadocs-ui/components/dialog/search";
import type { ElementContent, Root } from "hast";
import { useEffect, useRef, useState } from "react";

interface PagefindHit {
  url: string;
  excerpt: string;
  meta: { title?: string; plugin?: string };
  sub_results?: { title: string; url: string; excerpt: string }[];
}
interface Pagefind {
  options(o: Record<string, unknown>): Promise<void>;
  debouncedSearch(q: string, o?: object, ms?: number): Promise<{ results: { data(): Promise<PagefindHit> }[] } | null>;
}

let pagefind: Promise<Pagefind> | undefined;
// A path import Vite leaves alone: the index is written after the build.
const load = () => (pagefind ??= import(/* @vite-ignore */ `${"/pagefind/"}pagefind.js`).then(async (p: Pagefind) => (await p.options({ excerptLength: 18 }), p)));

const ENTITIES: Record<string, string> = { "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&#39;": "'", "&#x27;": "'" };
const unescape = (s: string) => s.replace(/&(amp|lt|gt|quot|#39|#x27);/g, (m) => ENTITIES[m] ?? m);
/** Pagefind's excerpt (escaped text with <mark>s) as hast. */
function excerpt(html: string): Root {
  const children: ElementContent[] = [];
  html.split(/<mark>(.*?)<\/mark>/).forEach((part, i) => {
    if (!part) return;
    const text = { type: "text" as const, value: unescape(part) };
    children.push(i % 2 ? { type: "element", tagName: "mark", properties: {}, children: [text] } : text);
  });
  return { type: "root", children };
}
const record = (id: string, type: SearchResultRecord["type"], url: string, content: string, html: string | null, breadcrumbs?: string[]): SearchResultRecord => ({
  id,
  type,
  url: url.replace(/\.html$/, "").replace(/\/index$/, "/"),
  content,
  hastContent: html === null ? { type: "root", children: [{ type: "text", value: content }] } : excerpt(html),
  ...(breadcrumbs ? { breadcrumbs } : {}),
});

function usePagefind(open: boolean) {
  const [search, setSearch] = useState("");
  const [data, setData] = useState<SearchResult | undefined>();
  const [isLoading, setLoading] = useState(false);
  const [error, setError] = useState<Error | undefined>();
  const latest = useRef("");
  useEffect(() => {
    if (open) void load().catch(() => undefined);
  }, [open]);
  useEffect(() => {
    latest.current = search;
    if (!search.trim()) {
      setData(undefined);
      return;
    }
    setLoading(true);
    void (async () => {
      try {
        const found = await (await load()).debouncedSearch(search, {}, 120);
        if (!found || latest.current !== search) return;
        const hits = await Promise.all(found.results.slice(0, 8).map((r) => r.data()));
        const items: SearchResultRecord[] = [];
        for (const hit of hits) {
          const crumbs = hit.meta.plugin ? [hit.meta.plugin] : undefined;
          items.push(record(hit.url, "page", hit.url, hit.meta.title ?? hit.url, null, crumbs));
          const subs = (hit.sub_results ?? []).filter((s) => s.url !== hit.url && s.url.includes("#")).slice(0, 3);
          for (const s of subs) items.push(record(s.url, "heading", s.url, s.title, null));
          if (!subs.length && hit.excerpt) items.push(record(`${hit.url}#text`, "text", hit.url, hit.excerpt, hit.excerpt));
        }
        setData({ query: search, items });
        setError(undefined);
      } catch (e) {
        setError(e instanceof Error ? e : new Error(String(e)));
      } finally {
        if (latest.current === search) setLoading(false);
      }
    })();
  }, [search]);
  return { search, onSearchChange: setSearch, data, isLoading, error };
}

export default function Search(props: SharedProps) {
  const search = usePagefind(props.open);
  return (
    <SearchDialog {...search} {...props}>
      <SearchDialogOverlay />
      <SearchDialogContent>
        <SearchDialogHeader>
          <SearchDialogIcon />
          <SearchDialogInput placeholder="Search commands, settings, permissions…" />
          <SearchDialogClose />
        </SearchDialogHeader>
        <SearchDialogList />
        <SearchDialogFooter>
          <span className="search-footer">Every plugin's pages, with their commands and settings</span>
        </SearchDialogFooter>
      </SearchDialogContent>
    </SearchDialog>
  );
}
