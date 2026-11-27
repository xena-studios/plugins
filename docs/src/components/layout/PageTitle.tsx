// Page titles in the GUI's material:
// - A plugin's overview: the plugin as an item in a large slot, its stats (from its manifest once
//   it's rewritten, from the brand catalog before), and Download first, so the page opens like
//   the plugin's own page rather than a doc.
// - A plugin's other pages: its icon in a slot beside the title.
// - Everything else: the title.
// Under it, the description, and for a plugin whose docs cover more than one major, the version
// switch (a <details>, so it works without JavaScript), with a note on an old major's pages.
import Link from "fumadocs-core/link";
import type { CSSProperties } from "react";
import type { PageInfo } from "../../lib/page.ts";
import { iconOf } from "../icons.ts";
import { Aside } from "../starlight.tsx";

function VersionSwitch({ versions }: { versions: NonNullable<PageInfo["versions"]> }) {
  return (
    <>
      <nav className="version-switch" aria-label={versions.label}>
        <details>
          <summary>
            <span className="label">Version</span> {versions.current}
          </summary>
          <ul>
            {versions.majors.map((m) => (
              <li key={m.label}>
                <Link href={m.url} aria-current={m.current ? "page" : undefined}>
                  {m.label}
                  {m.newest && <span className="hint"> newest</span>}
                </Link>
              </li>
            ))}
          </ul>
        </details>
      </nav>
      {versions.old && (
        <Aside type="caution" title={versions.old.title}>
          <p>
            {versions.old.text}
            <Link href={versions.old.url}>{versions.old.label}</Link>.
          </p>
        </Aside>
      )}
    </>
  );
}

export function PageTitle({ info }: { info: PageInfo }) {
  const h = info.header;
  const icon = h && iconOf(h.id);
  return (
    <>
      {h?.kind === "overview" && icon ? (
        <header className="product" style={{ "--item": info.plugin?.accent } as CSSProperties}>
          <div className="gui-slot big-slot">
            <img src={icon} alt="" width="96" height="96" />
          </div>
          <div className="product-text">
            <h1>{h.name}</h1>
            <p className="product-tagline">{h.tagline}</p>
            <ul className="product-stats gui-stat" role="list">
              {h.stats.map((s) => (
                <li key={s}>{s}</li>
              ))}
            </ul>
            {!h.released && (
              <p className="soon">
                Coming soon. <a href="https://github.com/xena-studios/plugins/releases">Follow releases on GitHub</a>.
              </p>
            )}
            <p className="product-actions">
              {h.actions.map((a) => (
                <Link key={a.href} className={`gui-button${a.primary ? " primary" : ""}`} href={a.href}>
                  {a.label}
                </Link>
              ))}
            </p>
          </div>
        </header>
      ) : (
        <div className="page-title">
          {icon && (
            <span className="gui-slot small-slot">
              <img src={icon} alt="" width="40" height="40" />
            </span>
          )}
          <div>
            <h1>{info.title}</h1>
            {info.description && !info.bare && <p className="page-description">{info.description}</p>}
          </div>
        </div>
      )}
      {info.versions && <VersionSwitch versions={info.versions} />}
    </>
  );
}
