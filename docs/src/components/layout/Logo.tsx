// The XENA STUDIOS logo (src/assets/xena-studios.svg, from the brand repo by pnpm brand:sync),
// drawn inline in the text colour. The exports carry 50 units of clear space on every side; the
// header spaces the logo itself, so the viewBox drops it.
import lockup from "../../assets/xena-studios.svg?raw";

const [, w, h] = lockup.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/) ?? [];
const viewBox = `50 50 ${Number(w) - 100} ${Number(h) - 100}`;
const paths = lockup
  .replace(/^<svg[^>]*>/, "")
  .replace(/<\/svg>\s*$/, "")
  .replace(/fill="#[0-9A-Fa-f]{6}"/g, 'fill="currentColor"');

export function XenaStudiosLogo({ className }: { className?: string }) {
  return <svg className={className} viewBox={viewBox} role="img" aria-label="Xena Studios" dangerouslySetInnerHTML={{ __html: paths }} />;
}
