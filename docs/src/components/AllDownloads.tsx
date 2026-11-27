// The site's download page: every plugin's latest jar first, with when it came out, the Minecraft
// versions and servers it runs on, and every version before it. Read when the site is built, as
// each plugin's own download page is (src/lib/component-data.ts, downloadsData): from Modrinth,
// or from the plugin's GitHub releases while Modrinth reviews it or can't be reached.
import Link from "fumadocs-core/link";
import type { AllDownloadsData } from "../lib/component-data.ts";
import { External } from "./External.tsx";
import { iconOf } from "./icons.ts";

export default function AllDownloads({ data }: { data?: AllDownloadsData }) {
  if (!data) throw new Error("AllDownloads: no data (src/mdx/starlight.ts adds it as the page compiles)");
  return (
    <section className="not-prose">
      <div className="wrap">
        <ul className="download-list" role="list">
          {data.map((p) => {
            const latest = p.versions.find((v) => v.number === p.latest?.number);
            const older = p.versions.filter((v) => v !== latest);
            return (
              <li className="download" key={p.id} id={p.id}>
                <img className="plugin-icon" src={iconOf(p.id)} alt="" width="48" height="48" loading="lazy" />
                <div className="download-name">
                  <h2>
                    <Link href={p.docs}>{p.name}</Link>
                  </h2>
                  <span className="label">{p.category}</span>
                </div>
                <div className="download-facts">
                  {p.latest && latest ? (
                    <>
                      <span>
                        <strong>{p.latest.number}</strong> · {latest.date}
                      </span>
                      <span>{latest.minecraft ? `Minecraft ${latest.minecraft} · ${latest.loaders}` : p.folia ? "Paper, Purpur and Folia" : "Paper and Purpur"}</span>
                    </>
                  ) : (
                    <span>{!p.released ? "Not released yet." : p.unreachable ? "Its versions couldn't be read when this page was built." : "Its versions are on GitHub."}</span>
                  )}
                </div>
                <div className="download-actions">
                  {p.latest ? (
                    <a className="gui-button primary small" href={p.latest.file.url} download>
                      Download <span className="sr-only">{p.name} </span>
                      {p.latest.number}
                    </a>
                  ) : (
                    p.released && (
                      <a className="gui-button small" href={p.everyRelease}>
                        GitHub
                        <External />
                      </a>
                    )
                  )}
                  {p.latest && (
                    <Link className="gui-button small" href={p.latest.changes}>
                      What's new<span className="sr-only"> in {p.name}</span>
                    </Link>
                  )}
                  {p.from === "modrinth" && p.modrinth && (
                    <a className="gui-button small" href={p.modrinth}>
                      Modrinth
                      <External />
                    </a>
                  )}
                </div>
                {older.length > 0 && (
                  <details className="download-older">
                    <summary>
                      {older.length} earlier {older.length === 1 ? "version" : "versions"}
                    </summary>
                    <table>
                      <thead>
                        <tr>
                          <th className="label">Version</th>
                          <th className="label">Released</th>
                          {p.from === "modrinth" && <th className="label">Minecraft</th>}
                          <th className="label">Get it</th>
                        </tr>
                      </thead>
                      <tbody>
                        {older.map((v) => (
                          <tr key={v.number}>
                            <td>
                              <Link href={v.changes}>{v.number}</Link>
                              {v.type !== "release" && <span className="label"> {v.type}</span>}
                            </td>
                            <td>{v.date}</td>
                            {p.from === "modrinth" && <td>{v.minecraft}</td>}
                            <td>
                              <a href={v.url} download>
                                .jar
                              </a>
                              {p.from === "modrinth" && v.mirror && <a href={v.github}>GitHub</a>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </details>
                )}
              </li>
            );
          })}
        </ul>
        <p className="download-note">
          Every plugin needs Java 21 or newer. Each version is also a <a href="https://github.com/xena-studios/plugins/releases">GitHub release</a> with the same jar, and each plugin's
          changelog says what changed. New to the plugins? <Link href="/start/installing/">Installing</Link> takes a minute.
        </p>
      </div>
    </section>
  );
}
