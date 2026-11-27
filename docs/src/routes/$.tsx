// Every page in src/content/docs, at the address Starlight gave it. Its content is the compiled
// page (fumadocs-mdx, loaded per page); everything around it (sidebar, title row, version switch,
// previous and next, accent) comes from a server function that runs once per page at build
// (src/lib/page.ts) and is a static JSON file for client-side navigation.
import { createFileRoute, isNotFound, notFound } from "@tanstack/react-router";
import { createServerFn } from "@tanstack/react-start";
import { staticFunctionMiddleware } from "@tanstack/start-static-server-functions";
import browserCollections from "collections/browser";
import type * as PageTree from "fumadocs-core/page-tree";
import type { TOCItemType } from "fumadocs-core/toc";
import { DocsLayout } from "fumadocs-ui/layouts/notebook";
import { DocsBody, DocsPage } from "fumadocs-ui/layouts/notebook/page";
import type { MDXComponents } from "mdx/types";
import { type ComponentType, Suspense } from "react";
import { PageFooter } from "../components/layout/PageFooter.tsx";
import { PageTitle } from "../components/layout/PageTitle.tsx";
import { PluginSwitcher } from "../components/layout/PluginSwitcher.tsx";
import { baseOptions, docsSlots } from "../components/layout/shared.tsx";
import { SiteFooter } from "../components/layout/SiteFooter.tsx";
import { XenaShell } from "../components/layout/XenaShell.tsx";
import { getMDXComponents } from "../components/mdx.tsx";
import { getPageInfo, type PageInfo } from "../lib/page.ts";
import { SITE, SITE_TITLE } from "./__root.tsx";

const loadPage = createServerFn({ method: "GET" })
  .validator((slugs: string[]) => slugs)
  .middleware([staticFunctionMiddleware])
  .handler(async ({ data: slugs }) => {
    const info = getPageInfo(slugs);
    if (!info) throw notFound();
    return info;
  });

const clientLoader = browserCollections.docs.createClientLoader({
  id: "docs",
  component({ toc, default: MDX }, { info }: { info: PageInfo }) {
    return <PageView info={info} toc={toc} MDX={MDX} />;
  },
});

export const Route = createFileRoute("/$")({
  component: Page,
  loader: async ({ params }) => {
    const slugs = (params._splat ?? "").split("/").filter(Boolean);
    // A page that isn't built has no static data: that's the not-found page, not an error.
    const info = await loadPage({ data: slugs }).catch((e: unknown) => {
      throw isNotFound(e) || e instanceof SyntaxError ? notFound() : e;
    });
    await clientLoader.preload(info.path);
    return info;
  },
  head: ({ loaderData: info }) =>
    info
      ? {
          meta: [
            { title: info.url === "/" ? `${info.title} | ${SITE_TITLE}` : `${info.title} | ${SITE_TITLE}` },
            { name: "description", content: info.description },
            { property: "og:title", content: info.title },
            { property: "og:description", content: info.description },
            { property: "og:url", content: `${SITE}${info.url}` },
            ...(info.noindex ? [{ name: "robots", content: "noindex" }] : []),
          ],
          links: [{ rel: "canonical", href: `${SITE}${info.url}` }],
        }
      : {},
});

function Page() {
  const info = Route.useLoaderData();
  const Content = clientLoader.getComponent(info.path);
  const body = (
    <Suspense>
      <Content info={info} />
    </Suspense>
  );
  if (info.splash) {
    return <XenaShell>{body}</XenaShell>;
  }
  const base = baseOptions();
  return (
    <DocsLayout
      {...base}
      nav={{ ...base.nav, mode: "top" }}
      slots={docsSlots}
      tree={info.tree as unknown as PageTree.Root}
      tabs={false}
      sidebar={{ collapsible: false, banner: info.plugin ? <PluginSwitcher current={info.plugin.id} /> : undefined }}
    >
      {body}
    </DocsLayout>
  );
}

function PageView({ info, toc, MDX }: { info: PageInfo; toc: TOCItemType[]; MDX: ComponentType<{ components?: MDXComponents }> }) {
  const accent = info.accent ? <style>{info.accent}</style> : null;
  if (info.splash) {
    return (
      <>
        {accent}
        <MDX components={getMDXComponents()} />
      </>
    );
  }
  return (
    <DocsPage
      toc={toc}
      full={info.bare}
      tableOfContent={{ enabled: info.toc && toc.length > 0 }}
      tableOfContentPopover={{ enabled: info.toc && toc.length > 0 }}
      breadcrumb={{ enabled: false }}
      footer={{ enabled: true }}
      slots={{ footer: () => <PageFooter info={info} /> }}
      className={info.header?.kind === "overview" ? "is-overview" : undefined}
    >
      {accent}
      {/* What search indexes: the title and the content, on every page but the hidden ones. */}
      <div {...(info.hidden ? {} : { "data-pagefind-body": "" })} {...(info.plugin ? { "data-pagefind-meta": `plugin:${info.plugin.name}` } : {})} className="page-main">
        <PageTitle info={info} />
        <DocsBody>
          <MDX components={getMDXComponents()} />
        </DocsBody>
      </div>
    </DocsPage>
  );
}
