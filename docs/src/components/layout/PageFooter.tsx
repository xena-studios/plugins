// Under a reading page: when it last changed and where to edit it, previous and next (two GUI
// panels), then the site footer. Pages that sell or hand over a plugin (home, each overview and
// download page) have only the site footer.
import Link from "fumadocs-core/link";
import type { PageInfo } from "../../lib/page.ts";
import { SiteFooter } from "./SiteFooter.tsx";

const date = (iso: string) => new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" });

export function PageFooter({ info }: { info: PageInfo }) {
  return (
    <div className="page-footer">
      {(info.editUrl || info.lastUpdated) && (
        <div className="page-meta">
          {info.editUrl && (
            <a href={info.editUrl} className="edit-link">
              <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
                <path d="M2 14h3l8-8-3-3-8 8v3ZM9 4l3 3" fill="none" stroke="currentColor" strokeWidth="1.5" />
              </svg>
              Edit page
            </a>
          )}
          {info.lastUpdated && <span>Last updated {date(info.lastUpdated)}</span>}
        </div>
      )}
      {(info.previous || info.next) && (
        <nav className="pagination" aria-label="Previous and next">
          {info.previous ? (
            <Link href={info.previous.url} className="gui-panel prev" rel="prev">
              <span className="label">Previous</span>
              <span className="name">{info.previous.name}</span>
            </Link>
          ) : (
            <span />
          )}
          {info.next && (
            <Link href={info.next.url} className="gui-panel next" rel="next">
              <span className="label">Next</span>
              <span className="name">{info.next.name}</span>
            </Link>
          )}
        </nav>
      )}
      <SiteFooter />
    </div>
  );
}
