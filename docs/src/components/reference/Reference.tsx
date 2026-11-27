// One section of a plugin's reference, from its manifest (src/generated/<plugin>/manifest.json,
// checked against the schema), so a page needs one line:
//
//   <Reference plugin="xsample" show="commands" />
//
// The section's rows are worked out as the page compiles (src/lib/component-data.ts) and come in
// as `data`, so the page carries only them. A plugin with nothing in a section gets a line saying
// so rather than an empty table. A plugin without a manifest fails the build. The default
// config.yml follows the config table as a highlighted code block.
import { Fragment, type ReactNode } from "react";
import type { ReferenceData } from "../../lib/component-data.ts";
import { Badge } from "../starlight.tsx";

type Of<S extends ReferenceData["show"]> = Extract<ReferenceData, { show: S }>["rows"];

const join = <T,>(items: T[], render: (item: T) => ReactNode, sep = ", ") =>
  items.map((item, i) => (
    <Fragment key={i}>
      {i > 0 && sep}
      {render(item)}
    </Fragment>
  ));

function Table({ name, head, children }: { name: string; head: string[]; children: ReactNode }) {
  return (
    <div className={`ref-table ref-${name}`}>
      <table>
        <thead>
          <tr>
            {head.map((h) => (
              <th key={h}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

const Hint = ({ children }: { children: ReactNode }) => <div className="ref-hint">{children}</div>;

function Platforms({ rows }: { rows: Of<"platforms"> }) {
  const kinds = { server: "Server", proxy: "Proxy", library: "Library" } as const;
  return (
    <Table name="platforms" head={["Platform", "Versions", "Java", "Since"]}>
      {rows.map((p) => (
        <tr key={p.name} className={p.planned ? "planned" : undefined}>
          <td>
            <strong>{p.name}</strong>
            {p.kind !== "server" && <span className="ref-hint"> {kinds[p.kind]}</span>}
          </td>
          <td>{p.versions}</td>
          <td>{p.java}</td>
          <td>{p.planned ? <Badge text="Planned" variant="note" /> : p.since}</td>
        </tr>
      ))}
    </Table>
  );
}

function Commands({ rows }: { rows: Of<"commands"> }) {
  return (
    <Table name="commands" head={["Command", "What it does"]}>
      {rows.map((c) => (
        <tr key={c.usage}>
          <td>
            <code>{c.usage}</code>
            {c.aliases.length > 0 && <Hint>also {join(c.aliases, (a) => <code>{a}</code>)}</Hint>}
            <Hint>{c.permission ? <code>{c.permission}</code> : "no permission of its own"}</Hint>
          </td>
          <td>
            {c.description}
            {c.playerOnly && <> <Badge text="Players only" /></>}
            {c.confirm && <> <Badge text="Asks to confirm" variant="caution" /></>}
            {c.builtIn && <> <Badge text="Every plugin" variant="note" /></>}
            {c.cooldown && (
              <Hint>
                Once every <code>{c.cooldown}</code>
                {c.cooldownBypass && (
                  <>
                    , except with <code>{c.cooldownBypass}</code>
                  </>
                )}
              </Hint>
            )}
            {c.arguments.length > 0 && (
              <dl className="ref-args">
                {c.arguments.map((a) => (
                  <Fragment key={a.label}>
                    <dt>
                      <code>{a.label}</code>
                    </dt>
                    <dd>
                      {a.description} <span className="ref-hint">{a.hint}</span>
                    </dd>
                  </Fragment>
                ))}
              </dl>
            )}
          </td>
        </tr>
      ))}
    </Table>
  );
}

function Permissions({ rows }: { rows: Of<"permissions"> }) {
  return (
    <Table name="permissions" head={["Permission", "Gives", "Default"]}>
      {rows.map((p) => (
        <tr key={p.node}>
          <td>
            <code>{p.node}</code>
          </td>
          <td>
            {p.description}
            {p.builtIn && <> <Badge text="Every plugin" variant="note" /></>}
            {p.children.length > 0 && (
              <Hint>
                {join(p.children, ([node, on]) => (
                  <>
                    {on ? "grants" : "takes away"} <code>{node}</code>
                  </>
                ))}
              </Hint>
            )}
            {p.commands.length > 0 && <Hint>for {join(p.commands, (c) => <code>/{c}</code>)}</Hint>}
          </td>
          <td>{p.default}</td>
        </tr>
      ))}
    </Table>
  );
}

function Config({ rows }: { rows: Of<"config"> }) {
  return (
    <Table name="config" head={["Key", "Default", "What it does"]}>
      {rows.map((e) =>
        e.section ? (
          <tr key={e.path} className="ref-section">
            <td colSpan={3}>
              <code>{e.path}</code> <span className="ref-hint">{e.comment}</span>
            </td>
          </tr>
        ) : (
          <tr key={e.path}>
            <td>
              <code>{e.path}</code>
            </td>
            <td>
              <code>{e.defaultText}</code>
            </td>
            <td>
              {e.comment}
              <Hint>{e.hint}</Hint>
            </td>
          </tr>
        ),
      )}
    </Table>
  );
}

function Messages({ rows }: { rows: Of<"messages"> }) {
  return (
    <Table name="messages" head={["Key", "English", "Fills in"]}>
      {rows.map((m) => (
        <tr key={m.key}>
          <td>
            <code>{m.key}</code>
            <Hint>{m.comment}</Hint>
          </td>
          <td>
            <code>{m.english}</code>
          </td>
          <td>{join(m.placeholders, (p) => <code>{`<${p}>`}</code>, " ")}</td>
        </tr>
      ))}
    </Table>
  );
}

function Placeholders({ rows }: { rows: Of<"placeholders"> }) {
  return (
    <Table name="placeholders" head={["Placeholder", "Shows", "Example"]}>
      {rows.map((p, i) => (
        <tr key={i}>
          <td>
            {p.forms.map(([form, plugin]) => (
              <div key={plugin}>
                <code>{form}</code> <span className="ref-hint">{plugin}</span>
              </div>
            ))}
          </td>
          <td>
            {p.description}
            {!p.needsPlayer && <Hint>the same for everyone</Hint>}
          </td>
          <td>
            <code>{p.example}</code>
          </td>
        </tr>
      ))}
    </Table>
  );
}

function Events({ rows }: { rows: Of<"events"> }) {
  return (
    <Table name="events" head={["Event", "When"]}>
      {rows.map((e) => (
        <tr key={e.className}>
          <td>
            <code title={e.className}>{e.short}</code>
            {e.hint && <Hint>{e.hint}</Hint>}
          </td>
          <td>
            {e.description}
            <Hint>
              <code>{e.key}</code>
            </Hint>
          </td>
        </tr>
      ))}
    </Table>
  );
}

const EMPTY: Record<ReferenceData["show"], (name: string) => string> = {
  platforms: (n) => `${n}'s platforms aren't set yet.`,
  commands: (n) => `${n} has no commands.`,
  permissions: (n) => `${n} has no permissions.`,
  config: (n) => `${n} has no config.yml.`,
  messages: (n) => `${n}'s messages aren't listed here yet.`,
  placeholders: (n) => `${n} has no placeholders.`,
  events: (n) => `${n}'s API has no events yet.`,
};

export default function Reference({ data }: { plugin: string; show: string; data?: ReferenceData }) {
  if (!data) throw new Error("Reference: no data (src/mdx/starlight.ts adds it as the page compiles)");
  if (!data.rows.length) return <p>{EMPTY[data.show](data.name)}</p>;
  switch (data.show) {
    case "platforms":
      return <Platforms rows={data.rows} />;
    case "commands":
      return <Commands rows={data.rows} />;
    case "permissions":
      return <Permissions rows={data.rows} />;
    case "config":
      return <Config rows={data.rows} />;
    case "messages":
      return <Messages rows={data.rows} />;
    case "placeholders":
      return <Placeholders rows={data.rows} />;
    case "events":
      return <Events rows={data.rows} />;
  }
}
