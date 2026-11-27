// The not-found page, prerendered as /404.html for Cloudflare (wrangler.jsonc, not_found_handling).
import { createFileRoute } from "@tanstack/react-router";
import { NotFound } from "../components/layout/NotFound.tsx";
import { SITE_TITLE } from "./__root.tsx";

export const Route = createFileRoute("/404")({
  component: NotFound,
  head: () => ({
    meta: [
      { title: `Page not found | ${SITE_TITLE}` },
      { name: "description", content: "There's nothing at this address." },
      { name: "robots", content: "noindex" },
    ],
  }),
});
