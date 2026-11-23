// Each plugin's GitHub releases, read from GitHub's API once per build, however many pages
// ask. Every release (.github/workflows/release.yml) is tagged <id>-v<version> and carries
// the plugin's jar. A plugin's download page lists them while Modrinth is still reviewing the
// plugin (src/data/modrinth.json), and whenever Modrinth can't be reached.

export interface Release {
  version: string;
  prerelease: boolean;
  /** When it was published (ISO 8601). */
  date: string;
  /** Its page on GitHub. */
  page: string;
  jar: { name: string; url: string; size: number };
}

interface GitHubRelease {
  tag_name: string;
  html_url: string;
  draft: boolean;
  prerelease: boolean;
  published_at: string | null;
  assets: { name: string; browser_download_url: string; size: number }[];
}

let all: Promise<GitHubRelease[] | null> | undefined;

/** Every release in the repo, or null if GitHub couldn't be reached. */
async function load(): Promise<GitHubRelease[] | null> {
  const releases: GitHubRelease[] = [];
  try {
    // 100 a page. Ten pages is years of releases away.
    for (let page = 1; page <= 10; page++) {
      const answer = await fetch(`https://api.github.com/repos/xena-studios/plugins/releases?per_page=100&page=${page}`, {
        headers: {
          Accept: "application/vnd.github+json",
          "User-Agent": "xena-studios/plugins docs build (contact@xenastudios.co)",
          "X-GitHub-Api-Version": "2022-11-28",
        },
        signal: AbortSignal.timeout(15_000),
      });
      if (!answer.ok) return null;
      const batch: GitHubRelease[] = await answer.json();
      releases.push(...batch);
      if (batch.length < 100) break;
    }
  } catch {
    return null;
  }
  return releases;
}

/** [id]'s published releases that have a jar, newest first, or null if GitHub couldn't be reached. */
export async function releases(id: string): Promise<Release[] | null> {
  all ??= load();
  const list = await all;
  if (!list) return null;
  const prefix = `${id}-v`;
  return list
    .filter((r) => !r.draft && r.published_at && r.tag_name.startsWith(prefix) && /^\d/.test(r.tag_name.slice(prefix.length)))
    .flatMap((r) => {
      const version = r.tag_name.slice(prefix.length);
      const jar = r.assets.find((a) => a.name === `${id}-${version}.jar`);
      if (!jar) return [];
      return [{ version, prerelease: r.prerelease, date: r.published_at!, page: r.html_url, jar: { name: jar.name, url: jar.browser_download_url, size: jar.size } }];
    })
    .sort((a, b) => b.date.localeCompare(a.date));
}
