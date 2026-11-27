import { createRouter } from "@tanstack/react-router";
import { NotFound } from "./components/layout/NotFound.tsx";
import { routeTree } from "./routeTree.gen.ts";

export function getRouter() {
  return createRouter({
    routeTree,
    // Addresses end in a slash, as they always have.
    trailingSlash: "always",
    defaultPreload: "intent",
    scrollRestoration: true,
    defaultNotFoundComponent: NotFound,
  });
}
