// A plugin's download page: its latest version first, then every version before it, read when
// the site is built (src/lib/component-data.ts, downloadsData: Modrinth, or GitHub's releases).
import Link from "fumadocs-core/link";
import type { DownloadsData } from "../lib/component-data.ts";
import { External } from "./External.tsx";

export default function Downloads({ data }: { plugin: string; data?: DownloadsData }) {
  if (!data) throw new Error("Downloads: no data (src/mdx/starlight.ts adds it as the page compiles)");
  const { latest, versions, from } = data;
  return (
    <>
      <div className="downloads-latest gui-tooltip not-prose" style={{ ["--item" as string]: data.accent }}>
        {latest ? (
          <>
            <p className="downloads-version">
              {data.name} {latest.number}
            </p>
            <p className="gui-stat">{latest.facts}</p>
            <p className="downloads-actions">
              <a className="gui-button primary" href={latest.file.url} download>
                Download {latest.file.name} <span className="size">({latest.file.size})</span>
              </a>
              <Link className="gui-button" href={latest.changes}>
                What's new
              </Link>
              {from === "modrinth" ? (
                <a className="gui-button" href={data.modrinth!}>
                  Modrinth
                  <External />
                </a>
              ) : (
                <a className="gui-button" href={latest.github}>
                  GitHub
                  <External />
                </a>
              )}
            </p>
            <p className="downloads-needs">Needs Java 21 or newer. {data.folia ? "One jar runs on Paper, Purpur and Folia." : "Runs on Paper and Purpur."}</p>
          </>
        ) : (
          <>
            <p className="gui-stat">
              {!data.released
                ? `${data.name} isn't released yet. Its first version will be here.`
                : data.unreachable
                  ? `${data.name}'s versions couldn't be read when this page was built.`
                  : `${data.name}'s versions are on GitHub.`}
            </p>
            {data.released && (
              <p className="downloads-actions">
                <a className="gui-button primary" href={data.everyRelease}>
                  Every version on GitHub
                  <External />
                </a>
                {data.modrinth && (
                  <a className="gui-button" href={`${data.modrinth}/versions`}>
                    Modrinth
                    <External />
                  </a>
                )}
              </p>
            )}
          </>
        )}
      </div>
      {versions.length > 0 && (
        <>
          <h2 id="every-version">Every version</h2>
          <div className="ref-table downloads-versions">
            <table>
              <thead>
                <tr>
                  <th>Version</th>
                  <th>Released</th>
                  {from === "modrinth" && <th>Minecraft</th>}
                  {from === "modrinth" && <th>Runs on</th>}
                  <th>Download</th>
                </tr>
              </thead>
              <tbody>
                {versions.map((v) => (
                  <tr key={v.number}>
                    <td>
                      <Link href={v.changes}>{v.number}</Link>
                      {v.type !== "release" && <span className="downloads-type">{v.type}</span>}
                    </td>
                    <td>{v.date}</td>
                    {from === "modrinth" && <td>{v.minecraft}</td>}
                    {from === "modrinth" && <td>{v.loaders}</td>}
                    <td>
                      <a href={v.url} download>
                        .jar
                      </a>
                      {from === "modrinth" && v.mirror && (
                        <>
                          {" · "}
                          <a href={v.github}>GitHub</a>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p>
            {from === "modrinth" ? (
              <>
                {data.allMirrored ? "Every version" : "Each version marked GitHub"} is also a <a href={data.everyRelease}>GitHub release</a>, with the same jar.
              </>
            ) : (
              <>
                Every version is a <a href={data.everyRelease}>GitHub release</a>.
              </>
            )}{" "}
            The <Link href={`/${data.id}/changelog/`}>changelog</Link> says what changed in each.
          </p>
        </>
      )}
    </>
  );
}
