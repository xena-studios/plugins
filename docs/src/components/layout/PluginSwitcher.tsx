// On a plugin's pages, a switcher above its sidebar: the plugin's icon and name, opening every
// plugin's (a <details>, so it works without JavaScript), and a link back to them all.
import Link from "fumadocs-core/link";
import type { CSSProperties } from "react";
import catalog from "../../data/plugins.json" with { type: "json" };
import { iconOf } from "../icons.ts";

export function PluginSwitcher({ current }: { current: string }) {
  const plugin = catalog.plugins.find((p) => p.id === current);
  if (!plugin) return null;
  return (
    <nav className="switcher" aria-label="Plugins">
      <details>
        <summary className="gui-panel" style={{ "--item": plugin.accentText } as CSSProperties}>
          <span className="gui-slot mini">
            <img src={iconOf(plugin.id)} alt="" width="24" height="24" />
          </span>
          <span className="name">{plugin.name}</span>
          <span className="hint">Switch</span>
        </summary>
        <ul>
          {catalog.plugins.map((p) => (
            <li key={p.id}>
              <Link href={p.docs} aria-current={p.id === current ? "page" : undefined} style={{ "--item": p.accentText } as CSSProperties}>
                <span className="gui-slot mini">
                  <img src={iconOf(p.id)} alt="" width="20" height="20" />
                </span>
                {p.name}
                {!p.released && <span className="soon">Soon</span>}
              </Link>
            </li>
          ))}
        </ul>
      </details>
      <Link className="all" href="/">
        All plugins
      </Link>
    </nav>
  );
}
