// The home page's first screen, laid out as xenastudios.co's: the kicker, the claim, the lead and
// the paths, over the planet horizon. The horizon is the plugin family's, and it's interactive:
// every plugin stands on its own stretch of the rim as a pixel block, and the chosen one lifts
// and turns while its stretch of the rim lights up and its tooltip shows beside the copy.
// Pointing at a block or a stretch of the rim, a dot or the arrows chooses a plugin; the blocks
// lean toward the pointer. It moves on to the next plugin every few seconds, stops while you point
// at it or use it, and never moves for someone who asks for reduced motion. CSS 3D, no library.
import Link from "fumadocs-core/link";
import { useSearchContext } from "fumadocs-ui/contexts/search";
import { type CSSProperties, useEffect, useRef, useState } from "react";
import catalog from "../data/plugins.json" with { type: "json" };
import Horizon, { standing } from "./Horizon.tsx";
import { iconOf } from "./icons.ts";

const plugins = catalog.plugins;
const n = plugins.length;
const words = ["no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen"];
const count = words[n] ?? String(n);
const runsOn = (p: (typeof plugins)[number]) => (p.folia ? "Paper, Purpur and Folia" : "Paper and Purpur");
const ADVANCE_MS = 4000;
/** The step from [from] to [to] the short way round the ring. */
const shortest = (from: number, to: number) => ((((to - from) % n) + n + Math.floor(n / 2)) % n) - Math.floor(n / 2);

function Block({ id, accent, index, active, onChoose }: { id: string; accent: string; index: number; active: boolean; onChoose: () => void }) {
  const icon = iconOf(id);
  const at = standing[index]!;
  return (
    <div
      className="block-slot"
      style={{ "--i": index, "--item": accent, left: `${at.x * 100}%`, top: `${at.y * 100}%` } as CSSProperties}
      data-active={active || undefined}
      onClick={onChoose}
      onPointerEnter={onChoose}
    >
      <div className="block">
        <span className="face front">
          <img src={icon} alt="" width="64" height="64" />
        </span>
        <span className="face back">
          <img src={icon} alt="" width="64" height="64" />
        </span>
        <span className="face right" />
        <span className="face left" />
        <span className="face top" />
        <span className="face bottom" />
      </div>
    </div>
  );
}

export default function Showcase() {
  const [at, setAt] = useState(0);
  const [paused, setPaused] = useState(false);
  const [still, setStill] = useState(true);
  const hero = useRef<HTMLElement>(null);
  const { setOpenSearch, enabled: searchEnabled } = useSearchContext();
  useEffect(() => {
    const reduce = matchMedia("(prefers-reduced-motion: reduce)");
    const on = () => setStill(reduce.matches);
    on();
    reduce.addEventListener("change", on);
    return () => reduce.removeEventListener("change", on);
  }, []);
  useEffect(() => {
    if (paused || still) return;
    const t = setInterval(() => setAt((a) => a + 1), ADVANCE_MS);
    return () => clearInterval(t);
  }, [paused, still]);
  const current = ((at % n) + n) % n;
  const p = plugins[current]!;
  const choose = (id: string) => {
    const i = plugins.findIndex((x) => x.id === id);
    if (i >= 0) setAt((a) => a + shortest(((a % n) + n) % n, i));
  };
  const lean = (e: React.PointerEvent) => {
    if (still || e.pointerType !== "mouse" || !hero.current) return;
    const r = hero.current.getBoundingClientRect();
    hero.current.style.setProperty("--mx", (((e.clientX - r.left) / r.width) * 2 - 1).toFixed(3));
    hero.current.style.setProperty("--my", (((e.clientY - r.top) / r.height) * 2 - 1).toFixed(3));
  };

  return (
    <section className="hero not-prose" ref={hero} aria-labelledby="hero-title" onPointerMove={lean}>
      <div className="wrap hero-grid">
        <div className="hero-copy">
          <p className="kicker">// Plugins</p>
          <h1 id="hero-title">Small plugins that each do one job.</h1>
          <p className="lead">
            {count[0]!.toUpperCase() + count.slice(1)} plugins for Paper and Folia servers, from chat and teleports to bans, backups and stats. Free, every feature, open source, and light enough
            to forget they're running.
          </p>
          <p className="actions">
            <Link className="gui-button primary" href="/start/requirements/">
              Get started
            </Link>
            <a className="gui-button" href="#plugins">
              Every plugin
            </a>
            {searchEnabled && (
              <button type="button" className="gui-button hero-search" onClick={() => setOpenSearch(true)}>
                Search the docs <kbd>⌘K</kbd>
              </button>
            )}
          </p>
        </div>

        <div
          className="hero-stage"
          role="region"
          aria-roledescription="carousel"
          aria-label="The plugins"
          onPointerEnter={() => setPaused(true)}
          onPointerLeave={() => setPaused(false)}
          onFocus={() => setPaused(true)}
        >
          <article className="gui-tooltip stage-card" style={{ "--item": p.accentText } as CSSProperties} aria-live={paused || still ? "polite" : "off"}>
            <p className="stage-count gui-stat">
              {String(current + 1).padStart(2, "0")} / {n} · {p.category}
            </p>
            <h2 className="item-name">{p.name}</h2>
            <p className="item-tagline">{p.tagline}</p>
            <p className="stage-stats gui-stat">
              {p.released ? `Version ${p.version} · ` : ""}
              {runsOn(p)} · Jar under {p.jarBudgetKb} KB
            </p>
            <div className="stage-foot">
              <p className="item-actions">
                {p.released && (
                  <Link className="gui-button primary" href={`${p.docs}download/`} style={{ "--b": p.accentText } as CSSProperties}>
                    Download
                  </Link>
                )}
                <Link className="gui-button" href={p.docs}>
                  How it works
                </Link>
              </p>
              <div className="stage-controls">
                <button type="button" className="gui-button arrow" aria-label="Previous plugin" onClick={() => setAt((a) => a - 1)}>
                  ‹
                </button>
                <button type="button" className="gui-button arrow" aria-label="Next plugin" onClick={() => setAt((a) => a + 1)}>
                  ›
                </button>
              </div>
            </div>
          </article>
          <div className="stage-dots" role="group" aria-label="Choose a plugin">
            {plugins.map((x, i) => (
              <button key={x.id} type="button" className="dot" style={{ "--item": x.accentText } as CSSProperties} aria-label={x.name} aria-current={i === current ? "true" : undefined} onClick={() => choose(x.id)} />
            ))}
          </div>
        </div>
      </div>
      <div className="hero-horizon" onPointerEnter={() => setPaused(true)} onPointerLeave={() => setPaused(false)}>
        <Horizon id="hero-horizon" active={p.id} onSelect={choose}>
          <div className="blocks" aria-hidden="true">
            {plugins.map((x, i) => (
              <Block key={x.id} id={x.id} accent={x.accent} index={i} active={i === current} onChoose={() => choose(x.id)} />
            ))}
          </div>
        </Horizon>
      </div>
    </section>
  );
}
