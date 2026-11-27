import { createFileRoute, notFound } from "@tanstack/react-router";
import { createServerFn } from "@tanstack/react-start";
import { staticFunctionMiddleware } from "@tanstack/start-static-server-functions";
import { ApiPage } from "../../components/api/ApiPage.tsx";
import { apiInfo } from "../../lib/api.ts";
import { SITE, SITE_TITLE } from "../__root.tsx";

const load = createServerFn({ method: "GET" })
  .validator((id: string) => id)
  .middleware([staticFunctionMiddleware])
  .handler(async ({ data: id }) => {
    const info = apiInfo(id);
    if (!info.plugin) throw notFound();
    return info;
  });

export const Route = createFileRoute("/api/$plugin")({
  loader: ({ params }) => load({ data: params.plugin }),
  component: () => <ApiPage info={Route.useLoaderData()} />,
  head: ({ loaderData }) => ({
    meta: [
      { title: `${loaderData?.plugin?.name ?? "API"} API reference | ${SITE_TITLE}` },
      { name: "description", content: `${loaderData?.plugin?.name ?? "A plugin"}'s developer API reference.` },
    ],
    links: [{ rel: "canonical", href: `${SITE}${loaderData?.url ?? "/api/"}` }],
  }),
});
