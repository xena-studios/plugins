// What the docs layouts share. The header is the site's own (SiteHeader.tsx, in the layout's
// header slot), so Fumadocs' title, links and theme switch are left out.
import type { BaseLayoutProps } from "fumadocs-ui/layouts/shared";
import { DocsHeader } from "./DocsHeader.tsx";

export function baseOptions(): BaseLayoutProps {
  return {
    nav: { title: "Xena Studios Plugins", url: "/" },
    // Dark only, as the brand is.
    themeSwitch: { enabled: false },
    links: [],
  };
}

export const docsSlots = { header: DocsHeader };
