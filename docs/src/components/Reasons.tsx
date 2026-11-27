// How the plugins are built: hairline rows, statement left and explanation right, as
// xenastudios.co's "How we work". Each is a claim the build backs (the largest jar budget and
// the Folia count come from the build files).
import catalog from "../data/plugins.json" with { type: "json" };

const plugins = catalog.plugins;
const folia = plugins.filter((p) => p.folia).length;
const heaviest = Math.max(...plugins.map((p) => p.jarBudgetKb));
const count = ["no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"][plugins.length] ?? String(plugins.length);
const reasons = [
  {
    title: "Light enough to forget",
    text: `Every jar is under ${heaviest} KB, and the build fails if one grows past its budget. Nothing runs in the background unless a feature needs it, and a feature you turn off registers nothing at all.`,
  },
  {
    title: "Built for busy servers",
    text: "What's checked often, such as bans, mutes, homes and balances, is answered from memory, and saves are written in the background.",
  },
  {
    title: "Change anything on reload",
    text: "Settings apply with /<plugin> reload; only a few storage settings ask for a restart. An update carries your config forward, and a bad value falls back to its default with a warning: a plugin never stops over its config.",
  },
  {
    title: "Paper and Folia",
    text: `${folia} of the ${count} run on Folia from the same jar. Every plugin is started on real servers in CI, on the oldest and newest Minecraft it supports.`,
  },
  {
    title: "Free and open source",
    text: "Every feature in every plugin, under GPL-3.0. No premium versions, no early-access builds, nothing behind a Patreon.",
  },
  {
    title: "Easy to switch to",
    text: "Where a plugin replaces a popular one, it speaks its commands and imports its data, so your staff and your history come with you.",
  },
];

export default function Reasons() {
  return (
    <section className="section principles not-prose" aria-labelledby="reasons-title">
      <div className="wrap">
        <h2 id="reasons-title">Built the same way, every one</h2>
        <ul className="principle-list" role="list">
          {reasons.map((r) => (
            <li key={r.title} className="principle">
              <h3>{r.title}.</h3>
              <p>{r.text}</p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
