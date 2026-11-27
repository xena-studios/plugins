// How to install a rewritten plugin, one tab per platform it's released on (from its manifest),
// so an owner reads only their server's steps. The tabs share a sync key, so the platform you
// pick is the one every other page's tabs open on. A page can add its own steps below.
import Link from "fumadocs-core/link";
import type { InstallData } from "../../lib/component-data.ts";
import { TabItem, Tabs } from "../starlight.tsx";

export default function InstallTabs({ data }: { plugin: string; data?: InstallData }) {
  if (!data) throw new Error("InstallTabs: no data (src/mdx/starlight.ts adds it as the page compiles)");
  if (!data.platforms.length) return <p>{data.name} isn't released on any platform yet.</p>;
  return (
    <Tabs syncKey="platform">
      {data.platforms.map((p) => (
        <TabItem key={p.id} label={p.name}>
          <ol>
            <li>
              Check your server: {p.name} {p.versions}
              {p.java && <>, on Java {p.java} or newer</>}.
            </li>
            {p.needs && <li>Install {p.needs}</li>}
            <li>
              <Link href={data.download}>Download {data.name}</Link> for {p.name} and your Minecraft version, and put the jar in <code>{p.folder}</code>.
            </li>
            <li>
              {p.restart} {data.name} writes its config on the first start.
            </li>
          </ol>
        </TabItem>
      ))}
    </Tabs>
  );
}
