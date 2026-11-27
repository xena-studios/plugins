// Every plugin on the home page, as xenastudios.co lists them: one hairline row each, its icon,
// name and category, what it does, where it runs, and its docs and download. From the brand
// catalog and the build files (src/data/plugins.json).
import Link from "fumadocs-core/link";
import catalog from "../data/plugins.json" with { type: "json" };
import { iconOf } from "./icons.ts";

export default function PluginList() {
  return (
    <section id="plugins" className="section not-prose" aria-labelledby="plugins-title">
      <div className="wrap">
        <h2 id="plugins-title">Every plugin</h2>
        <p className="lead">Each one stands alone, and they work together through Vault and PlaceholderAPI. Switch off anything you don't use and it costs nothing.</p>
        <ul className="plugin-list" role="list">
          {catalog.plugins.map((p) => (
            <li className="plugin" key={p.id}>
              <img className="plugin-icon" src={iconOf(p.id)} alt="" width="48" height="48" loading="lazy" />
              <div className="plugin-name">
                <h3>
                  <Link href={p.docs}>{p.name}</Link>
                </h3>
                <span className="label">{p.category}</span>
              </div>
              <p className="what">{p.summary}</p>
              <p className="where">
                <span className="label">Runs on</span> {p.folia ? "Paper, Folia" : "Paper"}
              </p>
              <ul className="plugin-links" role="list">
                <li>
                  <Link href={p.docs}>
                    Docs<span className="sr-only"> for {p.name}</span>
                  </Link>
                </li>
                {p.released && (
                  <li>
                    <Link href={`${p.docs}download/`}>
                      Download<span className="sr-only"> {p.name}</span>
                    </Link>
                  </li>
                )}
              </ul>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
