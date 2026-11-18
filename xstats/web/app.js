// xStats' website: plain JavaScript, no framework, no build step.
//
// Safety: everything the server sends (player names, the owner's text, other plugins'
// profile sections) goes into the page as textContent, never as HTML. Links and images
// only take http(s) addresses or paths the site builds itself, and colours only #rrggbb.
"use strict";

(() => {
  const view = document.getElementById("view");
  const PERIODS = ["all", "monthly", "weekly", "daily"];
  const NAME = /^\w{1,16}$/;
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const COLOUR = /^#[0-9a-f]{6}$/i;
  let api = "";
  let server = null;

  // ---- building the page safely ----

  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === null || value === undefined || value === false) continue;
      if (key === "class") node.className = value;
      else if (key === "text") node.textContent = value;
      else if (key === "href") node.setAttribute("href", value);
      else if (key === "src") node.setAttribute("src", value);
      else node.setAttribute(key, String(value));
    }
    for (const child of children) {
      if (child === null || child === undefined || child === false) continue;
      node.append(typeof child === "string" ? document.createTextNode(child) : child);
    }
    return node;
  }

  function show(...nodes) {
    view.replaceChildren(...nodes);
  }

  function notice(text) {
    show(el("p", { class: "notice", text }));
  }

  /** An address from the server: http(s) as is, or a path on the API's server. Anything else is dropped. */
  function address(value) {
    if (typeof value !== "string" || value.length === 0) return null;
    if (/^https?:\/\/[^\s"'<>\\]+$/i.test(value)) return value;
    if (/^\/[\w\-./{}?=&%]*$/.test(value) && !value.startsWith("//")) return api + value;
    return null;
  }

  function playerHref(name) {
    return "#/player/" + encodeURIComponent(name);
  }

  function head(uuid, name, size) {
    const template = address(server && server.avatar);
    if (!template || !UUID.test(uuid)) return el("span", { class: "head" });
    const src = template.replaceAll("{uuid}", uuid).replaceAll("{name}", encodeURIComponent(name || ""))
      .replaceAll("{size}", String(size || 32));
    return el("img", { class: "head", src, alt: "", loading: "lazy", width: size || 32, height: size || 32 });
  }

  function statName(id) {
    const stat = server.stats.find((s) => s.id === id);
    return stat ? stat.name : id;
  }

  function periodName(id) {
    const period = server.periods.find((p) => p.id === id);
    return period ? period.name : id;
  }

  function date(millis) {
    return millis > 0 ? new Date(millis).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : "-";
  }

  function ago(millis) {
    if (!(millis > 0)) return "-";
    const minutes = Math.max(0, Math.round((Date.now() - millis) / 60000));
    if (minutes < 2) return "just now";
    if (minutes < 120) return minutes + " minutes ago";
    const hours = Math.round(minutes / 60);
    if (hours < 48) return hours + " hours ago";
    return Math.round(hours / 24) + " days ago";
  }

  async function get(path) {
    const response = await fetch(api + path, { headers: { Accept: "application/json" }, referrerPolicy: "no-referrer" });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(response.status === 429 ? "Too many requests: wait a minute." : "The server said " + response.status + ".");
    return response.json();
  }

  // ---- branding ----

  function brand() {
    const root = document.documentElement.style;
    for (const [name, value] of Object.entries(server.colours || {})) {
      if (COLOUR.test(value) && /^[a-z]+$/.test(name)) root.setProperty("--" + name, value);
    }
    document.title = server.name;
    document.getElementById("site-name").textContent = server.name;
    const tagline = document.getElementById("tagline");
    tagline.textContent = server.tagline || "";
    tagline.hidden = !server.tagline;
    const logo = document.getElementById("logo");
    const src = address(server.logo);
    if (src) {
      logo.src = src;
      logo.hidden = false;
    }
    updated();
  }

  function updated() {
    document.getElementById("updated").textContent = server.saved > 0 ? "Updated " + ago(server.saved) : "";
  }

  // ---- pages ----

  function periodLinks(current, href, allowed) {
    return el("div", { class: "periods", role: "group", "aria-label": "Period" },
      ...PERIODS.filter((p) => !allowed || allowed(p)).map((p) =>
        el("a", { href: href(p), "aria-current": p === current ? "true" : null, text: periodName(p) })));
  }

  function boardList(stat, entries, big) {
    if (!entries || entries.length === 0) return el("p", { class: "muted", text: "Nobody yet." });
    return el("ol", { class: big ? "board big" : "board" }, ...entries.map((entry) =>
      el("li", {},
        el("span", { class: "place", text: String(entry.rank) }),
        head(entry.uuid, entry.name, big ? 32 : 24),
        el("a", { class: "who", href: playerHref(entry.name), text: entry.name }),
        el("span", { class: "amount", text: entry.display }))));
  }

  async function home(period) {
    period = PERIODS.includes(period) ? period : "all";
    const summary = await get("/api/v1/leaderboards/" + period);
    const totals = server.stats.map((stat) => el("div", { class: "card" },
      el("div", { class: "label", text: stat.name }),
      el("div", { class: "value", text: stat.totalDisplay })));
    const boards = (summary ? summary.boards : []).map((board) => el("section", { class: "card" },
      el("h3", { text: statName(board.stat) }),
      boardList(board.stat, board.entries, false),
      el("a", { class: "more", href: "#/top/" + board.stat + "/" + period, text: "Full leaderboard →" })));
    show(
      el("h1", { text: server.name }),
      el("p", { class: "muted", text: server.players + " players" + (server.network ? " across the network" : "") }),
      el("h2", { text: "Server totals" }),
      el("div", { class: "grid" }, ...totals),
      el("h2", { text: "Leaderboards" }),
      periodLinks(period, (p) => "#/period/" + p),
      boards.length ? el("div", { class: "grid" }, ...boards) : el("p", { class: "muted", text: "Nothing here yet." }));
  }

  async function top(statId, period) {
    const stat = server.stats.find((s) => s.id === statId) || server.stats[0];
    if (!stat) return notice("This server shows no stats yet.");
    period = stat.periods && PERIODS.includes(period) ? period : "all";
    const board = await get("/api/v1/leaderboard/" + encodeURIComponent(stat.id) + "/" + period);
    const picker = el("div", { class: "periods", role: "group", "aria-label": "Stat" }, ...server.stats.map((s) =>
      el("a", { href: "#/top/" + s.id + "/" + (s.periods ? period : "all"), "aria-current": s.id === stat.id ? "true" : null, text: s.name })));
    show(
      el("h1", { text: stat.name }),
      picker,
      stat.periods ? periodLinks(period, (p) => "#/top/" + stat.id + "/" + p) : null,
      el("section", { class: "card" }, boardList(stat.id, board ? board.entries : [], true)));
  }

  function spark(values) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("class", "spark");
    svg.setAttribute("viewBox", "0 0 " + values.length * 4 + " 34");
    svg.setAttribute("preserveAspectRatio", "none");
    svg.setAttribute("aria-hidden", "true");
    const most = Math.max(1, ...values);
    values.forEach((value, i) => {
      if (value <= 0) return;
      const height = Math.max(2, Math.round((value / most) * 32));
      const bar = document.createElementNS(ns, "rect");
      bar.setAttribute("x", String(i * 4));
      bar.setAttribute("y", String(34 - height));
      bar.setAttribute("width", "3");
      bar.setAttribute("height", String(height));
      svg.append(bar);
    });
    return svg;
  }

  async function player(name) {
    if (!NAME.test(name) && !UUID.test(name)) return notice("That isn't a player's name.");
    const profile = await get("/api/v1/player/" + encodeURIComponent(name));
    if (!profile) return notice("No player called " + name + " here, or they keep their stats private.");
    const history = (profile.history && profile.history.stats) || {};
    const cards = profile.stats.map((stat) => {
      const periods = stat.periods ? ["daily", "weekly", "monthly"].map((p) =>
        periodName(p) + ": " + stat.periods[p].display).join(" · ") : null;
      return el("div", { class: "card" },
        el("div", { class: "label", text: statName(stat.id) }),
        el("div", { class: "value", text: stat.display }),
        stat.rank > 0 ? el("a", { class: "rank", href: "#/top/" + stat.id + "/all", text: "#" + stat.rank }) : null,
        periods ? el("div", { class: "small", text: periods }) : null,
        Array.isArray(history[stat.id]) ? spark(history[stat.id]) : null);
    });
    const sections = (profile.sections || []).map((section) => el("section", { class: "card" },
      el("h3", { text: section.title }),
      el("div", { class: "lines" }, ...section.lines.map((line) =>
        el("div", {}, el("span", { text: line.label }), el("span", { text: line.value }))))));
    let servers = null;
    if (Array.isArray(profile.servers) && profile.servers.length > 1) {
      const ids = server.stats.map((s) => s.id);
      servers = el("div", { class: "card scroll" }, el("table", {},
        el("thead", {}, el("tr", {}, el("th", { text: "Server" }), ...ids.map((id) => el("th", { text: statName(id) })))),
        el("tbody", {}, ...profile.servers.map((row) => el("tr", {},
          el("td", { text: row.server }), ...ids.map((id) => el("td", { text: String(row.stats[id] || 0) })))))));
    }
    show(
      el("div", { class: "profile" },
        head(profile.uuid, profile.name, 96),
        el("div", {},
          el("h1", { text: profile.name }),
          el("p", { class: "muted" },
            profile.online ? el("span", { class: "dot" }) : null,
            profile.online ? "Online · " : "Last seen " + ago(profile.lastSeen) + " · ",
            "Joined " + date(profile.firstJoin)))),
      el("h2", { text: "Stats" }),
      el("div", { class: "grid" }, ...cards),
      sections.length ? el("h2", { text: "More" }) : null,
      sections.length ? el("div", { class: "grid" }, ...sections) : null,
      servers ? el("h2", { text: "By server" }) : null,
      servers);
  }

  // ---- routing ----

  async function route() {
    const parts = location.hash.replace(/^#\/?/, "").split("/").map((part) => {
      try { return decodeURIComponent(part); } catch { return ""; }
    });
    const page = parts[0] === "player" ? "player" : parts[0] === "top" ? "top" : "home";
    for (const tab of document.querySelectorAll(".tabs a")) {
      tab.setAttribute("aria-current", tab.dataset.page === (page === "player" ? "" : page) ? "page" : "false");
    }
    try {
      if (page === "player") await player(parts[1] || "");
      else if (page === "top") await top(parts[1], parts[2]);
      else await home(parts[0] === "period" ? parts[1] : "all");
    } catch (error) {
      notice("Couldn't load this page. " + (error && error.message ? error.message : ""));
    }
    window.scrollTo(0, 0);
  }

  // ---- search ----

  function search() {
    const form = document.getElementById("search");
    const input = document.getElementById("q");
    const list = document.getElementById("suggest");
    let timer = 0;
    let asked = "";
    input.addEventListener("input", () => {
      clearTimeout(timer);
      const typed = input.value.trim();
      if (!NAME.test(typed)) {
        list.hidden = true;
        return;
      }
      timer = setTimeout(async () => {
        asked = typed;
        const found = await get("/api/v1/search?q=" + encodeURIComponent(typed)).catch(() => null);
        if (asked !== typed || !found) return;
        list.replaceChildren(...found.players.map((p) => el("li", {},
          el("a", { href: playerHref(p.name) }, head(p.uuid, p.name, 24), el("span", { text: p.name })))));
        list.hidden = found.players.length === 0;
      }, 200);
    });
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const typed = input.value.trim();
      if (NAME.test(typed)) location.hash = playerHref(typed);
      list.hidden = true;
    });
    list.addEventListener("click", () => {
      list.hidden = true;
      input.value = "";
    });
    document.addEventListener("click", (event) => {
      if (!form.contains(event.target)) list.hidden = true;
    });
  }

  // ---- start ----

  async function start() {
    // A copy hosted elsewhere says in config.json where the API is; the built-in site is on the same server.
    try {
      const response = await fetch("config.json", { cache: "no-cache" });
      if (response.ok) {
        const config = await response.json();
        if (typeof config.api === "string" && (config.api === "" || /^https?:\/\/[^\s"'<>\\]+$/i.test(config.api))) {
          api = config.api.replace(/\/+$/, "");
        }
      }
    } catch {
      api = "";
    }
    try {
      server = await get("/api/v1/server");
    } catch (error) {
      server = null;
    }
    if (!server) return notice("Couldn't reach the stats server. Try again in a moment.");
    brand();
    search();
    window.addEventListener("hashchange", route);
    setInterval(updated, 60000);
    route();
  }

  start();
})();
