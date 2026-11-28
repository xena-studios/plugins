// Every plugin's developer API, from the brand catalog and the build files: its Maven
// coordinates, its javadoc on javadoc.io once it's released, its API reference and its guide.
import Link from "fumadocs-core/link";
import catalog from "../data/plugins.json" with { type: "json" };
import { iconOf } from "./icons.ts";
import { guideOf } from "../plugins.ts";

const apis = catalog.plugins.filter((p) => p.maven);

export default function ApiList() {
  return (
    <div className="ref-table api-list">
      <table>
        <thead>
          <tr>
            <th>Plugin</th>
            <th>Maven coordinates</th>
            <th>Links</th>
          </tr>
        </thead>
        <tbody>
          {apis.map((p) => (
            <tr key={p.id}>
              <td className="api-plugin">
                <img src={iconOf(p.id)} alt="" width="24" height="24" />
                <Link href={guideOf(p.id, p.docs)}>{p.name}</Link>
              </td>
              <td>
                <code>
                  {p.maven}:{p.version}
                </code>
              </td>
              <td>
                <Link href={guideOf(p.id, p.docs)}>Guide</Link>
                {" · "}
                <Link href={`/api/${p.id}/`}>Reference</Link>
                {p.released ? (
                  <>
                    {" · "}
                    <a href={`https://javadoc.io/doc/co.xenastudios.${p.id}/${p.id}-api`}>Javadoc</a>
                  </>
                ) : (
                  " · on Maven Central at its first release"
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
