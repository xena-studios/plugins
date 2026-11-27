// A rewritten plugin's downloads: pick your platform and Minecraft version, get the one jar for
// it and every store that has it. The rows come from the plugin's manifest and its releases file,
// worked out as the page compiles (src/lib/component-data.ts).
//
// Without JavaScript it's the whole list: each platform's jars for the latest release, with the
// Minecraft versions each runs on and its stores. Once the page is interactive, two menus pick
// the one jar and the rows that don't match are hidden.
import { Fragment, useEffect, useMemo, useState } from "react";
import type { PickerData } from "../../lib/component-data.ts";
import { External } from "../External.tsx";
import { compareVersions } from "./minecraft.ts";

export default function DownloadPicker({ data }: { plugin: string; data?: PickerData }) {
  if (!data) throw new Error("DownloadPicker: no data (src/mdx/starlight.ts adds it as the page compiles)");
  const [ready, setReady] = useState(false);
  const [platform, setPlatform] = useState(data.platformOptions[0]?.id ?? "");
  const versions = useMemo(
    () => [...new Set(data.rows.filter((r) => r.platform === platform).flatMap((r) => r.minecraft))].sort((a, b) => compareVersions(b, a)),
    [data.rows, platform],
  );
  const [minecraft, setMinecraft] = useState(versions[0] ?? "");
  useEffect(() => setReady(true), []);
  // The choice stays if the new platform has it too.
  useEffect(() => {
    if (!versions.includes(minecraft)) setMinecraft(versions[0] ?? "");
  }, [versions, minecraft]);
  const shown = (r: PickerData["rows"][number]) => !ready || (r.platform === platform && r.minecraft.includes(minecraft));
  const none = ready && !data.rows.some(shown);
  return (
    <>
      <div className="picker not-prose">
        {data.latest ? (
          <>
            <div className="picker-head">
              <p className="picker-version">
                {data.name} {data.latest.version}
              </p>
              <p className="gui-stat">
                Released {data.latest.date} · {data.latest.platforms}
              </p>
            </div>
            {ready && (
              <div className="picker-choose">
                <label>
                  <span>Platform</span>
                  <select name="platform" value={platform} onChange={(e) => setPlatform(e.target.value)}>
                    {data.platformOptions.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Minecraft</span>
                  <select name="minecraft" value={minecraft} onChange={(e) => setMinecraft(e.target.value)}>
                    {versions.map((v) => (
                      <option key={v} value={v}>
                        {v}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            )}
            <table className="picker-jars">
              <thead>
                <tr>
                  <th>Platform</th>
                  <th>Minecraft</th>
                  <th>Download</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.map((r) => (
                  <tr key={`${r.platform} ${r.file.name}`} hidden={!shown(r)}>
                    <td>
                      <strong>{r.platformName}</strong>
                      {r.java && <div className="ref-hint">Java {r.java}+</div>}
                    </td>
                    <td>{r.span}</td>
                    <td>
                      <a className="gui-button primary" href={r.file.url} download>
                        {r.file.name} <span className="size">({r.file.size})</span>
                      </a>
                      {r.stores.length > 0 && (
                        <div className="picker-stores">
                          Also on{" "}
                          {r.stores.map((s, i) => (
                            <Fragment key={s.url}>
                              {i > 0 && " · "}
                              <a href={s.url}>
                                {s.name}
                                <External />
                              </a>
                            </Fragment>
                          ))}
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {none && (
              <p className="picker-none">
                {data.name} {data.latest.version} has no jar for that. Try another Minecraft version, or ask on GitHub.
              </p>
            )}
          </>
        ) : (
          <p className="gui-stat">{data.name} isn't released yet. Its first version will be here.</p>
        )}
        {data.planned.length > 0 && <p className="ref-hint">Coming later: {data.planned.join(", ")}.</p>}
      </div>
      {data.preview && (
        <p>
          A {data.preview.channel} is out:{" "}
          <strong>
            {data.name} {data.preview.version}
          </strong>
          , from {data.preview.date}.{" "}
          {data.preview.files.map((f, i) => (
            <Fragment key={f.url}>
              {i > 0 && " · "}
              <a href={f.url}>{f.name}</a>
            </Fragment>
          ))}
        </p>
      )}
      {data.earlier.length > 0 && (
        <details className="picker-earlier">
          <summary>Earlier versions</summary>
          <ul>
            {data.earlier.map((r) => (
              <li key={r.version}>
                <strong>{r.version}</strong> {r.channel !== "release" && <span className="ref-hint">{r.channel}</span>} <span className="ref-hint">{r.date}</span>
                <div>
                  {r.files.map((f, i) => (
                    <Fragment key={f.url}>
                      {i > 0 && " · "}
                      <a href={f.url}>{f.name}</a> <span className="ref-hint">{f.span}</span>
                    </Fragment>
                  ))}
                </div>
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  );
}
