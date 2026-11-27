// The closing band, as on every Xena site: where to get help, over the horizon.
import Link from "fumadocs-core/link";
import { External } from "./External.tsx";
import Horizon from "./Horizon.tsx";

export default function HelpBand() {
  return (
    <section className="cta not-prose" aria-labelledby="help-title">
      <div className="wrap">
        <h2 id="help-title">Stuck, or missing something?</h2>
        <p className="lead">Every page has the exact command or setting. If yours doesn't, ask: bugs and ideas go on GitHub, anything else by email.</p>
        <p className="actions">
          <a className="gui-button primary" href="https://github.com/xena-studios/plugins/issues/new/choose">
            Open an issue
            <External />
          </a>
          <Link className="gui-button" href="/start/support/">
            Getting help
          </Link>
        </p>
        <p className="mail">
          <a href="mailto:contact@xenastudios.co">contact@xenastudios.co</a>
        </p>
      </div>
      <Horizon id="cta-horizon" />
    </section>
  );
}
