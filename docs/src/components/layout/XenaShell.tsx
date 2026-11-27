// The frame of the home, download and not-found pages, as every Xena site's: the site's header
// (SiteHeader.tsx, every page's), and the studio's footer (columns of links, then the
// XENA mark, the copyright and its note). The docs pages keep the plugin family's GUI frame.
import Link from "fumadocs-core/link";
import type { ReactNode } from "react";
import xena from "../../assets/xena.svg?raw";
import catalog from "../../data/plugins.json" with { type: "json" };
import { External } from "../External.tsx";
import { SiteHeader } from "./SiteHeader.tsx";

const [, w, h] = xena.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/) ?? [];
const markBox = `50 50 ${Number(w) - 100} ${Number(h) - 100}`;
const mark = xena
  .replace(/^<svg[^>]*>/, "")
  .replace(/<\/svg>\s*$/, "")
  .replace(/fill="#[0-9A-Fa-f]{6}"/g, 'fill="currentColor"');
const year = new Date().getFullYear();
const repo = "https://github.com/xena-studios/plugins";

export function XenaShell({ children }: { children: ReactNode }) {
  return (
    <div className="xena">
      <a className="skip" href="#main">
        Skip to content
      </a>
      <SiteHeader />
      <main id="main">{children}</main>
      <footer className="xena-footer">
        <div className="wrap foot">
          <p className="foot-about">Small plugins for Paper and Folia that each do one job. Every one is open source under GPL-3.0-only.</p>
          <nav className="foot-cols" aria-label="Footer">
            <div>
              <h2 className="label">Plugins</h2>
              <ul className="two">
                {catalog.plugins.map((p) => (
                  <li key={p.id}>
                    <Link href={p.docs}>{p.name}</Link>
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <h2 className="label">Docs</h2>
              <ul>
                <li>
                  <Link href="/start/requirements/">Get started</Link>
                </li>
                <li>
                  <Link href="/start/versions/">Supported versions</Link>
                </li>
                <li>
                  <Link href="/start/languages/">Languages</Link>
                </li>
                <li>
                  <Link href="/developers/">Developers</Link>
                </li>
              </ul>
            </div>
            <div>
              <h2 className="label">Studio</h2>
              <ul>
                <li>
                  <a href="https://xenastudios.co">
                    xenastudios.co
                    <External />
                  </a>
                </li>
                <li>
                  <a href="https://modrinth.com/organization/xena-studios">
                    Modrinth
                    <External />
                  </a>
                </li>
                <li>
                  <a href={repo}>
                    Source
                    <External />
                  </a>
                </li>
                <li>
                  <a href={`${repo}/issues/new/choose`}>
                    Report a bug
                    <External />
                  </a>
                </li>
              </ul>
            </div>
          </nav>
        </div>
        <div className="wrap legal">
          <p className="studio">
            <a href="https://xenastudios.co" aria-label="Xena Studios">
              <svg className="foot-logo" viewBox={markBox} aria-hidden="true" dangerouslySetInnerHTML={{ __html: mark }} />
            </a>
            <span className="label">© {year} Xena Studios</span>
          </p>
          <p>Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.</p>
        </div>
      </footer>
    </div>
  );
}
