// Every page's header, laid out as every Xena site's (xenastudios.co, neoskript.dev): the XENA
// STUDIOS logo with the site's name as a label beside it ("XENA STUDIOS | Plugins", as
// brand.xenastudios.co's "| Brand"), then the site's sections, search and the source, in the plugin
// family's colours. The section you're in is underlined in the accent. On a phone the sections fold into a menu, and on a docs page a
// second button opens the page's sidebar.
import { useLocation } from "@tanstack/react-router";
import Link from "fumadocs-core/link";
import { useSearchContext } from "fumadocs-ui/contexts/search";
import { type ReactNode, useEffect, useState } from "react";
import catalog from "../../data/plugins.json" with { type: "json" };
import { External } from "../External.tsx";
import { XenaStudiosLogo } from "./Logo.tsx";

const isPluginPage = (p: string) => catalog.plugins.some((x) => p.startsWith(x.docs)) && !/\/download\/$/.test(p);
export const SECTIONS = [
  { text: "Plugins", url: "/#plugins", current: (p: string) => p === "/" || isPluginPage(p) },
  { text: "Download", url: "/download/", current: (p: string) => p.startsWith("/download/") || /^\/[^/]+\/(next\/)?download\/$/.test(p) },
  { text: "Guides", url: "/start/requirements/", current: (p: string) => p.startsWith("/start/") },
  { text: "Developers", url: "/developers/", current: (p: string) => p.startsWith("/developers/") || p.startsWith("/api/") || /\/developer-api\/$/.test(p) },
];
const REPO = "https://github.com/xena-studios/plugins";

function SearchButton() {
  const { setOpenSearch, enabled } = useSearchContext();
  if (!enabled) return null;
  return (
    <button type="button" className="xena-search" onClick={() => setOpenSearch(true)} aria-label="Search the docs">
      <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true">
        <path d="M7 12A5 5 0 1 0 7 2a5 5 0 0 0 0 10ZM10.5 10.5 14 14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="square" />
      </svg>
      <span className="wide">Search</span>
      <kbd className="wide">⌘K</kbd>
    </button>
  );
}

export function SiteHeader({ sidebarTrigger, className, ...props }: { sidebarTrigger?: ReactNode; className?: string } & React.ComponentProps<"header">) {
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [pathname]);
  return (
    <header className={`xena-header${className ? ` ${className}` : ""}`} data-open={open || undefined} {...props}>
      <div className="xena-bar">
        <Link className="xena-home" href="/" aria-label="Xena Studios Plugins, home">
          <XenaStudiosLogo className="logo" />
          <span className="xena-label">Plugins</span>
        </Link>
        <nav className="xena-nav" aria-label="Main" id="site-menu">
          <ul>
            {SECTIONS.map((s) => (
              <li key={s.url}>
                <Link href={s.url} aria-current={s.current(pathname) ? "page" : undefined}>
                  {s.text}
                </Link>
              </li>
            ))}
            <li className="wide">
              <a href={REPO}>
                GitHub
                <External />
              </a>
            </li>
          </ul>
        </nav>
        <div className="xena-tools">
          <SearchButton />
          <button type="button" className="xena-menu" aria-expanded={open} aria-controls="site-menu" aria-label="Menu" onClick={() => setOpen((o) => !o)}>
            <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
              <path d={open ? "M3 3l10 10M13 3 3 13" : "M2 4h12M2 8h12M2 12h12"} fill="none" stroke="currentColor" strokeWidth="1.6" />
            </svg>
          </button>
          {sidebarTrigger}
        </div>
      </div>
    </header>
  );
}
