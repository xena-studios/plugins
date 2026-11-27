// Starlight's components, with the names and props the pages use, so a page written for the
// Starlight site works unchanged: `import { LinkCard, CardGrid } from "@astrojs/starlight/components"`
// resolves here (src/mdx/starlight.ts). Each wears the family's GUI.
import { Tab as FdTab, Tabs as FdTabs } from "fumadocs-ui/components/tabs";
import Link from "fumadocs-core/link";
import { Children, isValidElement, type ReactNode } from "react";
import { External } from "./External.tsx";

const isExternal = (href: string) => /^https?:\/\//.test(href);

/** A fenced code block the page compiles into a highlighted one; this is only its fallback. */
export function Code({ code, lang, title }: { code: string; lang?: string; title?: string }) {
  return (
    <figure className="fallback-code" data-lang={lang}>
      {title && <figcaption>{title}</figcaption>}
      <pre>
        <code>{code}</code>
      </pre>
    </figure>
  );
}

/** A large link to another page: a raised GUI panel with its title and one line. */
export function LinkCard({ title, href, description }: { title: string; href: string; description?: string }) {
  const external = isExternal(href);
  return (
    <Link href={href} className="link-card gui-panel not-prose" {...(external ? { rel: "noreferrer" } : {})}>
      <span className="link-card-text">
        <span className="link-card-title">
          {title}
          {external && <External />}
        </span>
        {description && <span className="link-card-description">{description}</span>}
      </span>
      <svg className="link-card-arrow" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
        <path d="M3 8h9M8.5 4 12.5 8l-4 4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="square" />
      </svg>
    </Link>
  );
}

/** Cards side by side on wide screens, stacked on a phone. */
export function CardGrid({ children, stagger }: { children?: ReactNode; stagger?: boolean }) {
  return <div className={`card-grid not-prose${stagger ? " stagger" : ""}`}>{children}</div>;
}

/** A panel with a title, and anything inside. */
export function Card({ title, icon: _icon, children }: { title: string; icon?: string; children?: ReactNode }) {
  return (
    <article className="card gui-panel">
      <p className="card-title">{title}</p>
      <div className="card-body">{children}</div>
    </article>
  );
}

/** A small label: "Players only", "Planned". */
export function Badge({ text, variant = "default", size: _size }: { text: string; variant?: "default" | "note" | "tip" | "caution" | "danger" | "success"; size?: string }) {
  return <span className={`badge badge-${variant}`}>{text}</span>;
}

const ASIDE_LABELS = { note: "Note", tip: "Tip", caution: "Caution", danger: "Danger" } as const;

/** Starlight's aside (:::note[Title] in Markdown): a tooltip panel with an attribute-line title. */
export function Aside({ type = "note", title, children }: { type?: keyof typeof ASIDE_LABELS; title?: string; children?: ReactNode }) {
  return (
    <aside className={`aside aside-${type}`} aria-label={title ?? ASIDE_LABELS[type]}>
      <p className="aside-title">{title ?? ASIDE_LABELS[type]}</p>
      <div className="aside-body">{children}</div>
    </aside>
  );
}

/** One tab of <Tabs>. */
export function TabItem({ label, children }: { label: string; icon?: string; children?: ReactNode }) {
  return <FdTab value={label}>{children}</FdTab>;
}

/** Tabs; tabs that share a syncKey open on the same choice everywhere, and keep it. */
export function Tabs({ syncKey, children }: { syncKey?: string; children?: ReactNode }) {
  const items = Children.toArray(children).flatMap((c) => (isValidElement<{ label: string }>(c) && typeof c.props.label === "string" ? [c.props.label] : []));
  return (
    <FdTabs items={items} {...(syncKey ? { groupId: syncKey, persist: true } : {})}>
      {children}
    </FdTabs>
  );
}

/** Numbered steps: an ordered list, each step on the GUI's rail. */
export function Steps({ children }: { children?: ReactNode }) {
  return <div className="steps">{children}</div>;
}

/** A link styled as a button. */
export function LinkButton({ href, variant = "primary", children }: { href: string; variant?: "primary" | "secondary" | "minimal"; children?: ReactNode }) {
  return (
    <Link href={href} className={`gui-button not-prose${variant === "primary" ? " primary" : ""}`}>
      {children}
      {isExternal(href) && <External />}
    </Link>
  );
}

/** A file tree: the Markdown list inside, in mono. */
export function FileTree({ children }: { children?: ReactNode }) {
  return <div className="file-tree">{children}</div>;
}
