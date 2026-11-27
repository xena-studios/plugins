// The site footer, laid out as on Xena's other sites (xenastudios.co, raptorpanel.net,
// NeoSkript's site): the XENA mark and the copyright, a line on the licence, then the site's
// links. XENA appears only here, as the brand says for a product's own site.
import Link from "fumadocs-core/link";
import xena from "../../assets/xena.svg?raw";
import catalog from "../../data/plugins.json" with { type: "json" };
import { External } from "../External.tsx";

const year = new Date().getFullYear();
const repo = "https://github.com/xena-studios/plugins";
// The logo exports carry 50 units of clear space on every side (scripts/build.mjs in the brand
// repo); the footer spaces the mark itself, so the viewBox drops it. The mark takes the text colour.
const [, w, h] = xena.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/) ?? [];
const viewBox = `50 50 ${Number(w) - 100} ${Number(h) - 100}`;
const mark = xena
  .replace(/^<svg[^>]*>/, "")
  .replace(/<\/svg>\s*$/, "")
  .replace(/fill="#[0-9A-Fa-f]{6}"/g, 'fill="currentColor"');

const links = [
  ...catalog.plugins.map((p) => ({ label: p.name, href: p.docs, external: false })),
  { label: "Modrinth", href: "https://modrinth.com/organization/xena-studios", external: true },
  { label: "Source", href: repo, external: true },
  { label: "Report a bug", href: `${repo}/issues/new/choose`, external: true },
];

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <p className="studio">
        <a className="xena" href="https://xenastudios.co" aria-label="Xena Studios">
          <svg className="logo" viewBox={viewBox} role="img" aria-hidden="true" dangerouslySetInnerHTML={{ __html: mark }} />
        </a>
        <span className="label">© {year} Xena Studios</span>
      </p>
      <p className="note">
        Every plugin is open source under GPL-3.0-only. Not an official Minecraft product; not approved by or associated with Mojang or Microsoft.
      </p>
      <nav aria-label="Footer">
        <ul>
          {links.map((link) => (
            <li key={link.href}>
              {link.external ? (
                <a href={link.href}>
                  {link.label}
                  <External />
                </a>
              ) : (
                <Link href={link.href}>{link.label}</Link>
              )}
            </li>
          ))}
        </ul>
      </nav>
    </footer>
  );
}
