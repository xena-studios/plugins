// How complete each language is, per plugin: worked out from the plugin's lang files against its
// manifest as the page compiles (src/lib/component-data.ts, languagesData).
import Link from "fumadocs-core/link";
import type { CSSProperties } from "react";
import type { LanguagesData } from "../lib/component-data.ts";

export default function Languages({ data }: { data?: LanguagesData }) {
  if (!data) throw new Error("Languages: no data (src/mdx/starlight.ts adds it as the page compiles)");
  if (!data.plugins.length) return <p>No plugin ships translations yet.</p>;
  return (
    <div className="ref-table languages">
      <table>
        <thead>
          <tr>
            <th>Plugin</th>
            {data.languages.map((l) => (
              <th key={l.tag} title={l.native}>
                {l.name}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.plugins.map((p) => (
            <tr key={p.id}>
              <td>
                <Link href={p.docs}>
                  {p.name} {p.version.replace(/\.0$/, "")}
                </Link>
                <div className="ref-hint">{p.messages} messages</div>
              </td>
              {data.languages.map((l) => {
                const lang = p.languages.find((x) => x.tag === l.tag);
                if (!lang) return <td key={l.tag}>English</td>;
                const pct = Math.floor((lang.translated / p.messages) * 100);
                return (
                  <td key={l.tag}>
                    {pct}%<div className="ref-hint">{lang.machine ? "machine-drafted" : "reviewed"}</div>
                    <span className="bar" aria-hidden="true">
                      <span style={{ width: `${pct}%` } as CSSProperties} />
                    </span>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
