// The family's planet horizon, drawn from the brand catalog so its rim carries every plugin: the
// arc is stepped in pixel blocks, as the brand draws it, and each plugin owns one stretch of the
// rim in its own accent, in catalog order. A new plugin joins the rim on its own.
//
// On the home page it's interactive: the chosen plugin's stretch lights up, and pointing at or
// clicking a stretch chooses that plugin (`active`, `onSelect`). Elsewhere it's still.
import catalog from "../data/plugins.json" with { type: "json" };

const W = 2400;
const H = 520;
const R = 3200; // the planet's radius: a gentle arc
const top = 70; // the arc's highest point
const step = 24; // one pixel block, as on the brand's horizon
const cy = top + R;
const plugins = catalog.plugins;
const yAt = (x: number) => cy - Math.sqrt(Math.max(0, R * R - (x - W / 2) ** 2));
const ownerAt = (x: number) => Math.min(plugins.length - 1, Math.floor((x / W) * plugins.length));

const blocks: { x: number; y: number; owner: number }[] = [];
for (let x = 0; x < W; x += step) blocks.push({ x, y: Math.round(yAt(x + step / 2) / 4) * 4, owner: ownerAt(x) });
const body = `M0 ${H} ` + blocks.map((b) => `L${b.x} ${b.y + 4} L${b.x + step} ${b.y + 4}`).join(" ") + ` L${W} ${H} Z`;
const stops = plugins.map((p, i) => ({ offset: (i + 0.5) / plugins.length, color: p.accent }));
/** Each plugin's stretch of the rim, for its glow and its hit area. */
const stretches = plugins.map((p, i) => {
  const own = blocks.filter((b) => b.owner === i);
  const x0 = own[0]!.x;
  const x1 = own.at(-1)!.x + step;
  return { id: p.id, name: p.name, x0, x1, y: Math.min(...own.map((b) => b.y)), glow: own.map((b) => `M${b.x} ${b.y} h${step}`).join(" "), color: p.accent };
});

/** Where each plugin's block stands: the middle of its stretch of the rim, as fractions of the scene. */
export const standing = stretches.map((s) => ({ id: s.id, x: (s.x0 + s.x1) / 2 / W, y: (Math.round(yAt((s.x0 + s.x1) / 2) / 4) * 4) / H }));

export default function Horizon({
  active,
  onSelect,
  id = "horizon",
  children,
}: {
  active?: string;
  onSelect?: (id: string) => void;
  id?: string;
  /** Drawn over the scene at its own scale (the home page's blocks, placed with `standing`). */
  children?: React.ReactNode;
}) {
  const interactive = !!onSelect;
  return (
    <div className={`horizon not-prose${interactive ? " interactive" : ""}`} aria-hidden={interactive ? undefined : true}>
      {children}
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio={interactive ? "xMidYMin meet" : "xMidYMin slice"} focusable="false" role={interactive ? "group" : undefined} aria-label={interactive ? "The plugins along the horizon" : undefined}>
        <defs>
          <linearGradient id={`${id}-body`} x1="0" y1={top} x2="0" y2={H} gradientUnits="userSpaceOnUse">
            <stop offset="0" style={{ stopColor: "var(--horizon-top)" }} />
            <stop offset="1" style={{ stopColor: "var(--horizon-bottom)" }} />
          </linearGradient>
          <linearGradient id={`${id}-glow`} x1="0" y1="0" x2={W} y2="0" gradientUnits="userSpaceOnUse">
            {stops.map((s) => (
              <stop key={s.offset} offset={s.offset} stopColor={s.color} />
            ))}
          </linearGradient>
          <filter id={`${id}-blur`} x="-10%" y="-200%" width="120%" height="500%">
            <feGaussianBlur stdDeviation="22" />
          </filter>
          <filter id={`${id}-lit`} x="-20%" y="-400%" width="140%" height="900%">
            <feGaussianBlur stdDeviation="14" />
          </filter>
        </defs>
        <path d={body} fill={`url(#${id}-body)`} />
        <path d={blocks.map((b) => `M${b.x} ${b.y} h${step}`).join(" ")} stroke={`url(#${id}-glow)`} strokeWidth="18" opacity="0.32" filter={`url(#${id}-blur)`} />
        {stretches.map((s) => (
          <path key={`glow-${s.id}`} className="rim-glow" data-lit={s.id === active || undefined} d={s.glow} stroke={s.color} strokeWidth="26" filter={`url(#${id}-lit)`} />
        ))}
        {blocks.map((b) => (
          <rect key={b.x} className="rim" data-lit={plugins[b.owner]!.id === active || undefined} x={b.x} y={b.y} width={step} height="4" fill={plugins[b.owner]!.accent} />
        ))}
        {interactive &&
          stretches.map((s) => (
            <rect
              key={`hit-${s.id}`}
              className="rim-hit"
              x={s.x0}
              y={s.y - 60}
              width={s.x1 - s.x0}
              height="140"
              tabIndex={-1}
              onPointerEnter={() => onSelect(s.id)}
              onClick={() => onSelect(s.id)}
            >
              <title>{s.name}</title>
            </rect>
          ))}
      </svg>
    </div>
  );
}
