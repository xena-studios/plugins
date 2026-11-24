// Minecraft versions as the picker compares them: 1.8.8 < 1.21.11 < 26.3.

/** Negative when [a] is older than [b], positive when newer. */
export function compareVersions(a: string, b: string): number {
  const x = a.split(".").map(Number);
  const y = b.split(".").map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? 0) - (y[i] ?? 0);
    if (d) return d;
  }
  return 0;
}

/** Newest first, without repeats. */
export const newestFirst = (versions: Iterable<string>) => [...new Set(versions)].sort((a, b) => compareVersions(b, a));

/**
 * Whether [version] is in a manifest platform's range. "latest" as the newest bound means no
 * bound; as the oldest, only the newest version anything ships for ([newest]).
 */
export function inRange(version: string, min: string, max: string, newest: string): boolean {
  const lo = min === "latest" ? newest : min;
  return compareVersions(version, lo) >= 0 && (max === "latest" || compareVersions(version, max) <= 0);
}

/** "1.8.8 to 26.3", or the one version. */
export function span(versions: string[]): string {
  const sorted = newestFirst(versions);
  return sorted.length > 1 ? `${sorted.at(-1)} to ${sorted[0]}` : (sorted[0] ?? "");
}
