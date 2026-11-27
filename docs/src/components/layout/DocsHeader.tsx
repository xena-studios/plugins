// The docs layout's header slot: the site's header, with the button that opens the page's sidebar
// on a phone (Fumadocs' own trigger, so it drives Fumadocs' sidebar).
import { useNotebookLayout } from "fumadocs-ui/layouts/notebook";
import type { ComponentProps } from "react";
import { SiteHeader } from "./SiteHeader.tsx";

export function DocsHeader(props: ComponentProps<"header">) {
  const { slots } = useNotebookLayout();
  const Trigger = slots.sidebar?.trigger;
  return (
    <SiteHeader
      {...props}
      id="nd-subnav"
      className="docs-header"
      sidebarTrigger={
        Trigger ? (
          <Trigger className="xena-sidebar-trigger" aria-label="Pages in this section">
            <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
              <path d="M2 2.5h12v11H2zM6 2.5v11" fill="none" stroke="currentColor" strokeWidth="1.4" />
            </svg>
          </Trigger>
        ) : null
      }
    />
  );
}
