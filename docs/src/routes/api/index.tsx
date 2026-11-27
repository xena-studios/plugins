import { createFileRoute } from "@tanstack/react-router";
import { createServerFn } from "@tanstack/react-start";
import { staticFunctionMiddleware } from "@tanstack/start-static-server-functions";
import { ApiPage } from "../../components/api/ApiPage.tsx";
import { apiInfo } from "../../lib/api.ts";
import { SITE, SITE_TITLE } from "../__root.tsx";

const load = createServerFn({ method: "GET" })
  .middleware([staticFunctionMiddleware])
  .handler(async () => apiInfo());

export const Route = createFileRoute("/api/")({
  loader: () => load(),
  component: () => <ApiPage info={Route.useLoaderData()} />,
  head: () => ({
    meta: [{ title: `API reference | ${SITE_TITLE}` }, { name: "description", content: "Every Xena Studios plugin's developer API reference." }],
    links: [{ rel: "canonical", href: `${SITE}/api/` }],
  }),
});
