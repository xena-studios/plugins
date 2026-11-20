// xStats' website: plain JavaScript, no framework, no build step.
//
// Safety: everything the server sends (player names, the owner's text, other plugins'
// profile sections) goes into the page as textContent, never as HTML. Links and images
// only take http(s) addresses or paths the site builds itself, and colours only #rrggbb.
"use strict";

(() => {
  const view = document.getElementById("view");
  const PERIODS = ["all", "monthly", "weekly", "daily"];
  // Java names, and Bedrock players' through Floodgate, which start with its prefix (a dot by default).
  const NAME = /^[\w.*+~-]{1,16}$/;
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const COLOUR = /^#[0-9a-f]{6}$/i;
  const DAY = 86400000;
  const PAGE = 25;
  const SVG = "http://www.w3.org/2000/svg";
  let api = "";
  let server = null;
  let colours = {};
  let routeToken = 0;

  // ---- building the page safely ----

  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === null || value === undefined || value === false) continue;
      // Never a handler or inline style, whatever a caller passes: the page's policy forbids both anyway.
      if (/^on/i.test(key) || key.toLowerCase() === "style") throw new Error("el() doesn't set " + key);
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

  /** An SVG element. Attributes only: no handlers, no styles. */
  function svg(tag, attributes, ...children) {
    const node = document.createElementNS(SVG, tag);
    for (const [key, value] of Object.entries(attributes || {})) {
      if (/^on/i.test(key) || key.toLowerCase() === "style") throw new Error("svg() doesn't set " + key);
      node.setAttribute(key, String(value));
    }
    for (const child of children) if (child) node.append(child);
    return node;
  }

  function show(...nodes) {
    view.replaceChildren(...nodes.filter((node) => node !== null && node !== undefined && node !== false));
  }

  function notice(title, text) {
    show(el("div", { class: "notice" }, el("h1", { text: title }), text ? el("p", { text }) : null));
  }

  function loading() {
    show(el("div", { class: "skeleton hero-size", "aria-hidden": "true" }),
      el("div", { class: "section" }, el("div", { class: "skeleton row", "aria-hidden": "true" })),
      el("p", { class: "hidden", text: "Loading…" }));
  }

  function toast(text) {
    const node = el("div", { class: "toast", role: "status", text });
    document.body.append(node);
    setTimeout(() => node.remove(), 2200);
  }

  // ---- icons ----

  const PATHS = {
    clock: ["c12,12,9", "M12 7v5l3 2"],
    login: ["M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4", "M10 17l5-5-5-5", "M15 12H3"],
    sword: ["M14.5 17.5 3 6V3h3l11.5 11.5", "M13 19l6-6", "M16 16l4 4", "M19 21l2-2"],
    skull: ["M12 3a8 8 0 0 0-8 8c0 3 1.5 5 3.5 6v3h9v-3c2-1 3.5-3 3.5-6a8 8 0 0 0-8-8z", "c9,11,1.5", "c15,11,1.5", "M12 15v2"],
    target: ["c12,12,9", "c12,12,5", "c12,12,1"],
    cube: ["M21 8l-9-5-9 5v8l9 5 9-5z", "M3 8l9 5 9-5", "M12 13v8"],
    route: ["c6,19,2", "c18,5,2", "M8 19h8.5a3.5 3.5 0 0 0 0-7h-9a3.5 3.5 0 0 1 0-7H16"],
    fish: ["M6.5 12c3.5-6 10-6.5 14.5 0-4.5 6.5-11 6-14.5 0z", "M2 7.5l4.5 4.5L2 16.5", "M16.5 11.5h.01"],
    up: ["M12 19V5", "M5 12l7-7 7 7"],
    bolt: ["M13 2 4 14h7l-1 8 9-12h-7z"],
    heart: ["M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8z"],
    swap: ["M17 2l4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "M7 22l-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3"],
    sparkle: ["M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z", "M19 16v5", "M16.5 18.5h5"],
    flag: ["M4 22V4", "M4 4h13l-2 4 2 4H4"],
    coin: ["c12,12,9", "M15 9.5c-.5-1-1.6-1.5-3-1.5-1.7 0-3 .8-3 2s1.3 1.8 3 2 3 .8 3 2-1.3 2-3 2c-1.4 0-2.5-.5-3-1.5", "M12 6v2", "M12 16v2"],
    vote: ["M9 12l2 2 4-4", "M5 4h14v16H5z"],
    bars: ["M4 20V11", "M10 20V5", "M16 20v-6", "M21 20H3"],
    search: ["c11,11,7", "M20 20l-3.5-3.5"],
    sun: ["c12,12,4", "M12 2v2", "M12 20v2", "M4.9 4.9l1.4 1.4", "M17.7 17.7l1.4 1.4", "M2 12h2", "M20 12h2", "M4.9 19.1l1.4-1.4", "M17.7 6.3l1.4-1.4"],
    moon: ["M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"],
    calendar: ["M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M16 2v4", "M8 2v4", "M3 10h18"],
    share: ["M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7", "M16 6l-4-4-4 4", "M12 2v13"],
    crown: ["M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5z"],
    trophy: ["M8 21h8", "M12 17v4", "M7 4h10v5a5 5 0 0 1-10 0z", "M17 5h3v2a3 3 0 0 1-3 3", "M7 5H4v2a3 3 0 0 0 3 3"],
    users: ["c9,8,4", "M2 21v-1a6 6 0 0 1 6-6h2a6 6 0 0 1 6 6v1", "M16 3.5a4 4 0 0 1 0 8", "M22 21v-1a6 6 0 0 0-4-5.6"],
  };

  function icon(name) {
    const node = svg("svg", { class: "icon", viewBox: "0 0 24 24", "aria-hidden": "true", focusable: "false" });
    for (const shape of PATHS[name] || PATHS.bars) {
      if (shape.startsWith("c")) {
        const [cx, cy, r] = shape.slice(1).split(",");
        node.append(svg("circle", { cx, cy, r }));
      } else {
        node.append(svg("path", { d: shape }));
      }
    }
    return node;
  }

  // An icon for a stat from its id and name, so owners' own stats get a fitting one too.
  const STAT_ICONS = [
    [/play_?time|time_?played|online|minutes|hours/, "clock"], [/session|join|login/, "login"],
    [/mob|zombie|creeper|skeleton|monster/, "target"], [/kill|pvp/, "sword"], [/death|died|die/, "skull"],
    [/mine|mined|block|dig|break/, "cube"], [/distance|walk|travel|fly|swim|move|sprint|boat|horse/, "route"],
    [/fish/, "fish"], [/jump/, "up"], [/damage|dealt|hurt/, "bolt"], [/bred|breed|animal|tame/, "heart"],
    [/trade|villager|sell|shop/, "swap"], [/enchant|craft|brew/, "sparkle"], [/raid|win|won|quest/, "flag"],
    [/balance|money|coin|eco|bank|cash|gold/, "coin"], [/vote/, "vote"],
  ];

  function statIcon(stat) {
    const key = ((stat && stat.id) || "") + " " + ((stat && stat.name) || "").toLowerCase();
    for (const [pattern, name] of STAT_ICONS) if (pattern.test(key)) return icon(name);
    const unit = stat && stat.unit;
    return icon(unit === "time" ? "clock" : unit === "distance" ? "route" : unit === "damage" ? "bolt" : "bars");
  }

  // ---- reading the server's data ----

  /** An address from the server: http(s) as is, or a path on the API's server. Anything else is dropped. */
  function address(value) {
    if (typeof value !== "string" || value.length === 0) return null;
    if (/^https?:\/\/[^\s"'<>\\]+$/i.test(value)) return value;
    if (/^\/[\w\-./{}?=&%]*$/.test(value) && !value.startsWith("//")) return api + value;
    return null;
  }

  /** A player's page: by uuid when there is one (it never changes, and works for any name), else by name. */
  function playerHref(uuid, name) {
    return "#/player/" + encodeURIComponent(UUID.test(uuid || "") ? uuid : name);
  }

  function head(uuid, name, size) {
    const template = address(server && server.avatar);
    if (!template || !UUID.test(uuid || "")) return el("span", { class: "head" });
    // Twice the size it's shown at, for sharp heads on high-density screens.
    const asked = String(Math.min(256, (size || 32) * 2));
    const src = template.replaceAll("{uuid}", uuid).replaceAll("{name}", encodeURIComponent(name || ""))
      .replaceAll("{size}", asked);
    return el("img", { class: "head", src, alt: "", loading: "lazy", decoding: "async", width: size || 32, height: size || 32 });
  }

  function statOf(id) {
    return server.stats.find((s) => s.id === id) || { id, name: id, unit: "count", decimals: 0, periods: false };
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
    if (minutes < 60) return minutes + " minutes ago";
    const hours = Math.round(minutes / 60);
    if (hours < 2) return "an hour ago";
    if (hours < 48) return hours + " hours ago";
    const days = Math.round(hours / 24);
    if (days < 60) return days + " days ago";
    return "on " + date(millis);
  }

  const WHOLE = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });
  const ONE = new Intl.NumberFormat("en-US", { maximumFractionDigits: 1 });

  /** A stat's value as the server writes it (stat/Format.java), for numbers the site adds up itself. */
  function format(stat, value) {
    switch (stat.unit) {
      case "time": {
        if (value < 60) return value + "s";
        const minutes = Math.floor(value / 60);
        if (minutes < 60) return minutes + "m";
        const hours = Math.floor(minutes / 60);
        if (hours < 24) return hours + "h " + (minutes % 60) + "m";
        return WHOLE.format(Math.floor(hours / 24)) + "d " + (hours % 24) + "h";
      }
      case "distance": {
        const metres = Math.floor(value / 100);
        if (metres < 1000) return metres + " m";
        return (metres < 100000 ? ONE.format(metres / 1000) : WHOLE.format(Math.floor(metres / 1000))) + " km";
      }
      case "damage":
        return ONE.format(value / 20) + (value === 20 ? " heart" : " hearts");
      default:
        return stat.decimals > 0
          ? new Intl.NumberFormat("en-US", { minimumFractionDigits: stat.decimals, maximumFractionDigits: stat.decimals })
            .format(value / 10 ** stat.decimals)
          : WHOLE.format(value);
    }
  }

  async function get(path) {
    const response = await fetch(api + path, { headers: { Accept: "application/json" }, referrerPolicy: "no-referrer" });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(response.status === 429 ? "Too many requests: wait a minute." : "The server said " + response.status + ".");
    return response.json();
  }

  // ---- branding and theme ----

  function readStored(key) {
    try { return localStorage.getItem(key); } catch { return null; }
  }

  function store(key, value) {
    try { localStorage.setItem(key, value); } catch { /* private mode: it just isn't remembered */ }
  }

  function luminance(hex) {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  function applyTheme(theme) {
    const root = document.documentElement;
    const light = theme === "light";
    if (light) root.dataset.theme = "light";
    else delete root.dataset.theme;
    // The owner's palette is the dark theme; light keeps only their accent.
    for (const [name, value] of Object.entries(colours)) {
      if (light && name !== "accent") root.style.removeProperty("--" + name);
      else root.style.setProperty("--" + name, value);
    }
    if (colours.accent) root.style.setProperty("--on-accent", luminance(colours.accent) > 0.35 ? "#0b0f14" : "#ffffff");
    const button = document.getElementById("theme");
    button.replaceChildren(icon(light ? "moon" : "sun"));
    button.setAttribute("aria-label", light ? "Switch to dark mode" : "Switch to light mode");
    button.setAttribute("title", light ? "Dark mode" : "Light mode");
  }

  function brand() {
    colours = {};
    for (const [name, value] of Object.entries(server.colours || {})) {
      if (COLOUR.test(value) && /^[a-z]+$/.test(name)) colours[name] = value;
    }
    applyTheme(readStored("xstats-theme") === "light" ? "light" : "dark");
    document.getElementById("theme").addEventListener("click", () => {
      const next = document.documentElement.dataset.theme === "light" ? "dark" : "light";
      store("xstats-theme", next);
      applyTheme(next);
    });
    document.getElementById("site-name").textContent = server.name;
    const tagline = document.getElementById("tagline");
    tagline.textContent = server.tagline || "";
    tagline.hidden = !server.tagline;
    const logo = document.getElementById("logo");
    const mark = document.getElementById("brand-mark");
    const src = address(server.logo);
    if (src) {
      logo.src = src;
      logo.hidden = false;
      mark.hidden = true;
    } else {
      mark.replaceChildren(icon("trophy"));
    }
    document.getElementById("search-icon").replaceChildren(icon("search"));
    updated();
  }

  function title(page) {
    document.title = page ? page + " · " + server.name : server.name;
  }

  function updated() {
    document.getElementById("updated").textContent = server.saved > 0 ? "Updated " + ago(server.saved) : "";
  }

  // ---- shared pieces ----

  function segments(label, items) {
    return el("div", { class: "segments", role: "group", "aria-label": label }, ...items.map((item) =>
      el("a", { href: item.href, "aria-current": item.current ? "true" : null, text: item.text })));
  }

  function periodSegments(current, href, allowed) {
    return segments("Period", PERIODS.filter((p) => !allowed || allowed(p)).map((p) =>
      ({ href: href(p), current: p === current, text: periodName(p) })));
  }

  function medalClass(rank) {
    return rank === 1 ? "gold" : rank === 2 ? "silver" : rank === 3 ? "bronze" : "";
  }

  function sectionHead(heading, ...extra) {
    return el("div", { class: "section-head" }, el("h2", { text: heading }), ...extra);
  }

  // ---- the overview ----

  async function home(period, token) {
    period = PERIODS.includes(period) ? period : "all";
    title(null);
    const summary = await get("/api/v1/leaderboards/" + period);
    if (token !== routeToken) return;
    const playtime = server.stats.find((s) => s.unit === "time");
    const facts = [
      { value: WHOLE.format(server.players), label: server.network ? "Players across the network" : "Players" },
      playtime ? { value: playtime.totalDisplay, label: playtime.name + ", together" } : null,
      { value: String(server.stats.length), label: "Stats kept" },
    ].filter(Boolean);
    const hero = el("section", { class: "card hero" },
      el("div", {},
        el("h1", { text: server.name }),
        el("p", { text: server.tagline || "Leaderboards and stats for every player." })),
      el("div", { class: "hero-facts" }, ...facts.map((fact) =>
        el("div", { class: "fact" }, el("div", { class: "value num", text: fact.value }), el("div", { class: "label", text: fact.label })))));

    const totals = el("div", { class: "totals" }, ...server.stats.map((stat) =>
      el("a", { class: "card total", href: "#/top/" + stat.id + "/all", "aria-label": stat.name + ": " + stat.totalDisplay + ". See the leaderboard" },
        el("span", { class: "icon-chip" }, statIcon(stat)),
        el("div", {}, el("div", { class: "label", text: stat.name }), el("div", { class: "value num", text: stat.totalDisplay })))));

    const boards = (summary ? summary.boards : [])
      .filter((board) => period === "all" || board.entries.length > 0)
      .map((board) => leaderCard(statOf(board.stat), board.entries, period));

    show(hero,
      el("section", { class: "section" },
        sectionHead("Leaders", periodSegments(period, (p) => (p === "all" ? "#/" : "#/period/" + p))),
        boards.length ? el("div", { class: "grid" }, ...boards)
          : el("div", { class: "card empty-state", text: period === "all" ? "Nobody has any stats yet." : "Nobody has played " + periodName(period).toLowerCase() + " yet." })),
      el("section", { class: "section" },
        sectionHead("Server totals", el("span", { class: "muted", text: "Everyone's stats added up" })),
        totals));
  }

  function leaderCard(stat, entries, period) {
    const boardHref = "#/top/" + stat.id + "/" + (stat.periods ? period : "all");
    const [first, ...rest] = entries || [];
    return el("section", { class: "card leader-card" },
      el("header", {},
        el("span", { class: "icon-chip" }, statIcon(stat)),
        el("h3", { text: stat.name }),
        el("a", { href: boardHref, "aria-label": "All of " + stat.name, text: "View all" })),
      first ? el("a", { class: "champion", href: playerHref(first.uuid, first.name) },
        head(first.uuid, first.name, 40),
        el("span", { class: "who" }, el("span", { class: "name", text: first.name }), el("span", { class: "amount num", text: first.display })),
        el("span", { class: "crown", title: "First place" }, icon("crown"))) : el("p", { class: "empty-state", text: "Nobody yet." }),
      rest.length ? el("ol", { class: "mini", start: 2 }, ...rest.map((entry) => el("li", {},
        el("a", { href: playerHref(entry.uuid, entry.name) },
          el("span", { class: "place num", text: String(entry.rank) }),
          head(entry.uuid, entry.name, 22),
          el("span", { class: "who", text: entry.name }),
          el("span", { class: "amount num", text: entry.display }))))) : null);
  }

  // ---- leaderboards ----

  async function top(statId, period, token) {
    const stat = server.stats.find((s) => s.id === statId) || server.stats[0];
    if (!stat) return notice("No stats yet", "This server doesn't show any stats on the website.");
    period = stat.periods && PERIODS.includes(period) ? period : "all";
    title(stat.name);
    const board = await get("/api/v1/leaderboard/" + encodeURIComponent(stat.id) + "/" + period);
    if (token !== routeToken) return;
    const entries = board ? board.entries : [];
    const linkFor = (s) => "#/top/" + s.id + "/" + (s.periods ? period : "all");

    const list = el("nav", { class: "card stat-list", "aria-label": "Stats" }, ...server.stats.map((s) =>
      el("a", { href: linkFor(s), "aria-current": s.id === stat.id ? "true" : null }, statIcon(s), el("span", { text: s.name }))));
    const select = el("select", { class: "stat-select", "aria-label": "Stat" }, ...server.stats.map((s) =>
      el("option", { value: s.id, selected: s.id === stat.id ? "selected" : null, text: s.name })));
    select.addEventListener("change", () => { location.hash = linkFor(statOf(select.value)); });

    const header = el("div", { class: "board-head" },
      el("span", { class: "icon-chip big" }, statIcon(stat)),
      el("div", { class: "title" },
        el("h1", { text: stat.name }),
        el("p", {}, stat.periods ? periodName(period) : "All time",
          " · " + entries.length + (entries.length === 1 ? " player" : " players") + " · Server total " + stat.totalDisplay)));

    const finder = el("input", { type: "search", placeholder: "Find a player on this board", "aria-label": "Find a player on this board", maxlength: 16, spellcheck: "false" });
    const finderNote = el("p", { class: "muted hidden", role: "status" });
    const tools = el("div", { class: "board-tools" },
      stat.periods ? periodSegments(period, (p) => "#/top/" + stat.id + "/" + p) : el("span", { class: "muted", text: "This stat is kept for all time only." }),
      el("div", { class: "find" }, finder, finderNote));

    const content = el("div", {});
    const best = entries.length ? Math.max(1, entries[0].value) : 1;
    let shown = 0;
    const ranks = el("ol", { class: "ranks" });
    const moreRow = el("div", { class: "more-row" });
    const rest = entries.slice(3);
    function showMore(until) {
      const end = Math.min(rest.length, Math.max(shown + PAGE, until || 0));
      for (const entry of rest.slice(shown, end)) ranks.append(rankRow(entry, best));
      shown = end;
      moreRow.replaceChildren();
      if (shown < rest.length) {
        const button = el("button", { class: "button", type: "button", text: "Show " + Math.min(PAGE, rest.length - shown) + " more" });
        button.addEventListener("click", () => showMore());
        moreRow.append(button);
      }
    }
    if (entries.length === 0) {
      content.append(el("div", { class: "card empty-state", text: stat.periods && period !== "all"
        ? "Nobody has any " + stat.name.toLowerCase() + " " + periodName(period).toLowerCase() + " yet." : "Nobody is on this leaderboard yet." }));
    } else {
      content.append(podium(entries.slice(0, 3)));
      if (rest.length) {
        showMore();
        content.append(el("div", { class: "card" }, ranks, moreRow));
      }
    }

    let finderTimer = 0;
    finder.addEventListener("input", () => {
      clearTimeout(finderTimer);
      finderTimer = setTimeout(() => {
        for (const row of content.querySelectorAll(".found")) row.classList.remove("found");
        const typed = finder.value.trim().toLowerCase();
        finderNote.classList.add("hidden");
        if (!typed) return;
        const index = entries.findIndex((e) => e.name.toLowerCase() === typed)
          >= 0 ? entries.findIndex((e) => e.name.toLowerCase() === typed) : entries.findIndex((e) => e.name.toLowerCase().startsWith(typed));
        if (index < 0) {
          finderNote.textContent = "Not on this leaderboard.";
          finderNote.classList.remove("hidden");
          return;
        }
        if (index >= 3 && index - 3 >= shown) showMore(index - 3 + 1);
        const target = content.querySelector('[data-uuid="' + entries[index].uuid + '"]');
        if (target) {
          target.classList.add("found");
          target.scrollIntoView({ block: "center", behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
        }
      }, 180);
    });

    show(el("div", { class: "boards" },
      list,
      el("div", {}, select, header, tools, content)));
  }

  function podium(entries) {
    const places = ["first", "second", "third"];
    // Second, first, third, as on a real podium; missing places keep the shape.
    const order = [1, 0, 2];
    return el("div", { class: "podium" }, ...order.map((i) => {
      const entry = entries[i];
      if (!entry) return el("div", { class: "spacer", "aria-hidden": "true" });
      const place = places[Math.min(2, entry.rank - 1)] || "third";
      return el("a", { class: "card step " + place, href: playerHref(entry.uuid, entry.name), "data-uuid": entry.uuid,
        "aria-label": "Place " + entry.rank + ": " + entry.name + ", " + entry.display },
      el("span", { class: "medal num", text: String(entry.rank) }),
      head(entry.uuid, entry.name, place === "first" ? 80 : 64),
      el("span", { class: "name", text: entry.name }),
      el("span", { class: "amount num", text: entry.display }));
    }));
  }

  function rankRow(entry, best) {
    const meter = el("span");
    meter.style.setProperty("--share", Math.max(1.5, Math.min(100, (entry.value / best) * 100)).toFixed(1) + "%");
    return el("li", { "data-uuid": entry.uuid },
      el("a", { href: playerHref(entry.uuid, entry.name) },
        el("span", { class: "place num", text: String(entry.rank) }),
        head(entry.uuid, entry.name, 30),
        el("span", { class: "who" }, el("span", { class: "name", text: entry.name }), el("span", { class: "meter", "aria-hidden": "true" }, meter)),
        el("span", { class: "amount num", text: entry.display })));
  }

  // ---- a player ----

  async function player(name, token) {
    if (!NAME.test(name) && !UUID.test(name)) return notice("That isn't a player's name", "Names are 1 to 16 letters, digits and _.");
    const profile = await get("/api/v1/player/" + encodeURIComponent(name));
    if (token !== routeToken) return;
    if (!profile) {
      title("Not found");
      return notice(UUID.test(name) ? "No such player" : "No player called " + name,
        "They haven't played here, or they keep their stats private.");
    }
    title(profile.name);
    const stats = profile.stats.map((s) => ({ ...s, def: statOf(s.id) }));
    const history = (profile.history && profile.history.stats) || {};
    const charted = stats.filter((s) => Array.isArray(history[s.id]));

    // Best places first, for the badges under their name.
    const placed = stats.filter((s) => s.rank > 0 && s.rank <= 10).sort((a, b) => a.rank - b.rank).slice(0, 4);
    const badges = placed.length ? el("div", { class: "badges" }, ...placed.map((s) =>
      el("a", { class: "badge " + (medalClass(s.rank) || "top"), href: "#/top/" + s.id + "/all" },
        el("span", { class: "medal num", text: String(s.rank) }),
        (s.rank === 1 ? "Best in " : "#" + s.rank + " in ") + s.def.name))) : null;

    const share = el("button", { class: "button", type: "button" }, icon("share"), "Share");
    share.addEventListener("click", async () => {
      const url = location.href.split("#")[0] + playerHref(profile.uuid, profile.name);
      try {
        if (navigator.share) await navigator.share({ title: profile.name + " on " + server.name, url });
        else {
          await navigator.clipboard.writeText(url);
          toast("Link copied");
        }
      } catch { /* closed, or no clipboard: nothing to do */ }
    });

    const status = profile.online ? el("span", { class: "pill online", text: "Online now" })
      : profile.lastSeen > 0 ? el("span", {}, icon("clock"), "Last seen " + ago(profile.lastSeen)) : null;
    const header = el("section", { class: "card profile" },
      el("div", { class: "avatar" }, head(profile.uuid, profile.name, 104),
        profile.online ? el("span", { class: "status-dot", title: "Online", "aria-hidden": "true" }) : null),
      el("div", {},
        el("h1", { text: profile.name }),
        el("div", { class: "meta" }, status,
          profile.firstJoin > 0 ? el("span", {}, icon("calendar"), "Joined " + date(profile.firstJoin)) : null),
        badges),
      el("div", { class: "actions" }, share));

    let chartStat = charted.find((s) => history[s.id].some((v) => v > 0)) || charted[0] || null;
    const activity = el("section", { class: "card activity" });
    const cards = [];
    function drawActivity() {
      activity.replaceChildren(...activityPanel(chartStat, history, profile.history.from, charted, (s) => {
        chartStat = s;
        drawActivity();
        for (const card of cards) if (card.tagName === "BUTTON") card.setAttribute("aria-pressed", card.dataset.stat === s.id ? "true" : "false");
      }));
    }

    for (const s of stats) {
      const chartable = Array.isArray(history[s.id]);
      const card = statCard(s, chartable, chartable && chartStat && chartStat.id === s.id);
      if (chartable) {
        card.addEventListener("click", () => {
          chartStat = s;
          drawActivity();
          for (const other of cards) if (other.tagName === "BUTTON") other.setAttribute("aria-pressed", other.dataset.stat === s.id ? "true" : "false");
          activity.scrollIntoView({ block: "nearest", behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
        });
      }
      cards.push(card);
    }

    const sections = (profile.sections || []).map((section) => el("section", { class: "card pad section-card" },
      el("h3", { text: section.title }),
      el("div", { class: "lines" }, ...section.lines.map((line) =>
        el("div", {}, el("span", { text: line.label }), el("span", { class: "num", text: line.value }))))));

    let servers = null;
    if (Array.isArray(profile.servers) && profile.servers.length > 1) {
      const defs = server.stats;
      servers = el("div", { class: "card scroll" }, el("table", {},
        el("thead", {}, el("tr", {}, el("th", { scope: "col", text: "Server" }), ...defs.map((d) => el("th", { scope: "col", text: d.name })))),
        el("tbody", {}, ...profile.servers.map((row) => el("tr", {},
          el("th", { scope: "row", text: row.server }), ...defs.map((d) => el("td", { class: "num", text: format(d, row.stats[d.id] || 0) })))))));
    }

    if (chartStat) drawActivity();
    // Every stat in the server's order: the ones with days to chart are buttons.
    const grid = el("div", { class: "stat-cards" }, ...cards);
    show(header,
      chartStat ? el("div", { class: "section" }, activity) : null,
      el("section", { class: "section" }, sectionHead("Stats", el("span", { class: "muted", text: "All time, with today, this week and this month" })), grid),
      sections.length ? el("section", { class: "section" }, sectionHead("More"), el("div", { class: "grid" }, ...sections)) : null,
      servers ? el("section", { class: "section" }, sectionHead("By server"), servers) : null);
  }

  /** A stat's card: its value and place, and today's, this week's and this month's. A button when it has days to chart. */
  function statCard(s, chartable, pressed) {
    const periods = s.periods ? el("div", { class: "periods-row" }, ...["daily", "weekly", "monthly"].map((p) =>
      el("div", { title: periodName(p) + (s.periods[p].rank > 0 ? ", #" + s.periods[p].rank : "") },
        el("span", { class: "p-label", text: p === "daily" ? "Today" : p === "weekly" ? "Week" : "Month" }),
        el("span", { class: "p-value num", text: s.periods[p].display })))) : null;
    return el(chartable ? "button" : "div", {
      class: "card stat-card", type: chartable ? "button" : null, "data-stat": s.id,
      "aria-pressed": chartable ? String(Boolean(pressed)) : null,
      "aria-label": chartable ? s.def.name + ": " + s.display + (s.rank > 0 ? ", place " + s.rank : "") + ". Show its last 30 days" : null },
    el("div", { class: "top-row" },
      el("span", { class: "icon-chip" }, statIcon(s.def)),
      el("span", { class: "label", text: s.def.name }),
      s.rank > 0 ? el("span", { class: "rank num " + medalClass(s.rank), title: "Place on the all-time leaderboard", text: "#" + s.rank }) : null),
    el("div", { class: "value num", text: s.display }),
    periods);
  }

  function activityPanel(stat, history, from, charted, pick) {
    const values = history[stat.id] || [];
    const total = values.reduce((a, b) => a + b, 0);
    const bestValue = Math.max(0, ...values);
    const bestIndex = values.indexOf(bestValue);
    const activeDays = values.filter((v) => v > 0).length;
    const dayDate = (i) => new Date((from + i) * DAY);
    const dayName = (i) => dayDate(i).toLocaleDateString(undefined, { month: "short", day: "numeric", timeZone: "UTC" });

    const picker = charted.length > 1 ? (() => {
      const node = el("div", { class: "segments", role: "group", "aria-label": "Stat to chart" });
      for (const s of charted) {
        const button = el("button", { type: "button", "aria-pressed": s.id === stat.id ? "true" : "false", text: s.def.name });
        button.addEventListener("click", () => pick(s));
        node.append(button);
      }
      return node;
    })() : null;

    const head = el("div", { class: "activity-head" },
      el("div", {}, el("h2", { text: "Last 30 days" }),
        el("p", { class: "muted", text: stat.def.name + ", day by day" })),
      picker);

    const facts = el("div", { class: "activity-facts" },
      el("div", {}, el("div", { class: "value num", text: format(stat.def, total) }), el("div", { class: "label", text: "In 30 days" })),
      el("div", {}, el("div", { class: "value num", text: bestValue > 0 ? format(stat.def, bestValue) : "-" }),
        el("div", { class: "label", text: bestValue > 0 ? "Best day, " + dayName(bestIndex) : "Best day" })),
      el("div", {}, el("div", { class: "value num", text: activeDays + " of " + values.length }), el("div", { class: "label", text: "Days played" })));

    if (total === 0) {
      return [head, facts, el("div", { class: "empty-state", text: "No " + stat.def.name.toLowerCase() + " in the last 30 days." })];
    }

    const width = 600;
    const height = 180;
    const gap = 3;
    const slot = width / values.length;
    const chart = svg("svg", { class: "bars", viewBox: "0 0 " + width + " " + height, preserveAspectRatio: "none", role: "img",
      "aria-label": stat.def.name + " each day for the last 30 days, best " + format(stat.def, bestValue) });
    for (const fraction of [0.25, 0.5, 0.75, 1]) {
      const y = height - fraction * (height - 8);
      chart.append(svg("line", { class: "grid-line", x1: 0, x2: width, y1: y.toFixed(1), y2: y.toFixed(1), "vector-effect": "non-scaling-stroke" }));
    }
    const tip = el("div", { class: "tip", hidden: "hidden", "aria-hidden": "true" });
    const wrap = el("div", { class: "chart" }, chart, tip);
    values.forEach((value, i) => {
      const barHeight = value > 0 ? Math.max(3, (value / bestValue) * (height - 8)) : 2;
      const x = i * slot + gap / 2;
      const group = svg("g", { class: "day", tabindex: "0", role: "img", "aria-label": dayName(i) + ": " + format(stat.def, value) },
        svg("rect", { class: "hit", x: (i * slot).toFixed(1), y: 0, width: slot.toFixed(1), height }),
        svg("rect", { class: value > 0 ? "bar" : "bar zero", x: x.toFixed(1), y: (height - barHeight).toFixed(1),
          width: Math.max(1, slot - gap).toFixed(1), height: barHeight.toFixed(1), rx: 2 }));
      const reveal = () => {
        tip.replaceChildren(el("strong", { class: "num", text: format(stat.def, value) }), dayName(i));
        tip.hidden = false;
        tip.style.setProperty("left", (((i + 0.5) / values.length) * 100).toFixed(2) + "%");
      };
      group.addEventListener("mouseenter", reveal);
      group.addEventListener("focus", reveal);
      group.addEventListener("mouseleave", () => { tip.hidden = true; });
      group.addEventListener("blur", () => { tip.hidden = true; });
      chart.append(group);
    });
    const axis = el("div", { class: "axis", "aria-hidden": "true" },
      el("span", { text: dayName(0) }), el("span", { text: dayName(Math.floor(values.length / 2)) }), el("span", { text: "Today" }));
    return [head, facts, wrap, axis];
  }

  // ---- routing ----

  async function route() {
    const token = ++routeToken;
    const parts = location.hash.replace(/^#\/?/, "").split("/").map((part) => {
      try { return decodeURIComponent(part); } catch { return ""; }
    });
    const page = parts[0] === "player" ? "player" : parts[0] === "top" ? "top" : "home";
    for (const tab of document.querySelectorAll(".tabs a")) {
      if (tab.dataset.page === page) tab.setAttribute("aria-current", "page");
      else tab.removeAttribute("aria-current");
    }
    loading();
    try {
      if (page === "player") await player(parts[1] || "", token);
      else if (page === "top") await top(parts[1], parts[2], token);
      else await home(parts[0] === "period" ? parts[1] : "all", token);
    } catch (error) {
      if (token === routeToken) notice("Couldn't load this page", error && error.message ? error.message : "Try again in a moment.");
    }
    if (token === routeToken) window.scrollTo(0, 0);
  }

  // ---- search ----

  function search() {
    const form = document.getElementById("search");
    const input = document.getElementById("q");
    const list = document.getElementById("suggest");
    let timer = 0;
    let asked = "";
    let active = -1;

    function close() {
      list.hidden = true;
      input.setAttribute("aria-expanded", "false");
      active = -1;
    }

    function options() {
      return [...list.querySelectorAll("a")];
    }

    function highlight(index) {
      const all = options();
      active = all.length ? (index + all.length) % all.length : -1;
      all.forEach((option, i) => option.setAttribute("aria-selected", i === active ? "true" : "false"));
      if (active >= 0) input.setAttribute("aria-activedescendant", all[active].id);
    }

    input.addEventListener("input", () => {
      clearTimeout(timer);
      const typed = input.value.trim();
      if (!NAME.test(typed)) return close();
      timer = setTimeout(async () => {
        asked = typed;
        const found = await get("/api/v1/search?q=" + encodeURIComponent(typed)).catch(() => null);
        if (asked !== typed || !found) return;
        list.replaceChildren(...(found.players.length ? found.players.map((p, i) => el("li", { role: "presentation" },
          el("a", { id: "suggest-" + i, role: "option", href: playerHref(p.uuid, p.name) }, head(p.uuid, p.name, 24), el("span", { text: p.name }))))
          : [el("li", { class: "empty", text: "No players called that" })]));
        list.hidden = false;
        input.setAttribute("aria-expanded", "true");
        active = -1;
      }, 180);
    });
    input.addEventListener("keydown", (event) => {
      if (list.hidden) return;
      if (event.key === "ArrowDown") { event.preventDefault(); highlight(active + 1); }
      else if (event.key === "ArrowUp") { event.preventDefault(); highlight(active - 1); }
      else if (event.key === "Escape") close();
      else if (event.key === "Enter" && active >= 0) {
        event.preventDefault();
        location.hash = options()[active].getAttribute("href");
        input.value = "";
        input.blur();
        close();
      }
    });
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const typed = input.value.trim();
      if (NAME.test(typed)) {
        location.hash = playerHref(null, typed);
        input.value = "";
        input.blur();
      }
      close();
    });
    list.addEventListener("click", () => {
      close();
      input.value = "";
    });
    document.addEventListener("click", (event) => {
      if (!form.contains(event.target)) close();
    });
    // "/" jumps to the search box, as on most sites.
    document.addEventListener("keydown", (event) => {
      if (event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey) return;
      const target = event.target;
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement) return;
      event.preventDefault();
      input.focus();
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
    if (!server) return notice("Couldn't reach the stats server", "Try again in a moment.");
    brand();
    search();
    window.addEventListener("hashchange", route);
    setInterval(updated, 60000);
    route();
  }

  start();
})();
