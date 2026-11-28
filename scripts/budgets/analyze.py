#!/usr/bin/env python3
"""Reads what scripts/budgets/check.sh measured, checks it against gradle/perf-budgets.txt and
writes report.md and results.json beside it. Exit 0 within every budget, 1 over one, 3 when the
measurement can't be read.

    analyze.py --out <folder> --budgets gradle/perf-budgets.txt --boots 3 --idle 60 --load 60
               --bots 10 plugin.jar...

How each number is read (as M0 did, sandbox/m0/budgets/analyze.py):
- startup: Paper's "[<plugin>] Loading N libraries" line to the first line after its "Loaded
  library" lines (the server resolving libraries:, then making the plugin's class loader and
  main class, on the server thread at every boot), or for a paper-plugin.yml plugin its
  loader's time handing Paper its libraries (the shim logs it), plus the onLoad and
  onEnable times the core's shim logs with -Dxena.startupTimes=true; for a plugin without the
  shim, its "Loading server plugin" and "Enabling" lines to the next line logged by anything
  else. The median of the timed boots (every boot but the first, which creates the world).
- tick cost: async-profiler's CPU samples (collapsed stacks, one line a stack with its count,
  200 us of CPU a sample); a sample is a plugin's when any frame is in its packages (its main
  class's first three names, co.xenastudios.<plugin>, which holds its relocated core, and the
  package R8 moved renamed classes to, the plugin's id).
  Server thread ms a tick = sampled ms / (seconds x 20); off-thread = sampled ms a second on
  every other thread.
- heap: used heap after two full GCs, less the same server's without the plugins, shared
  equally between the plugins; metaspace of the plugins' own class loaders (PluginClassLoader),
  with the loaders holding their libraries: (the stdlib) reported beside it but not budgeted;
  our instances' shallow bytes from the class histogram, before the bots and after them.
"""
import argparse
import json
import os
import re
import statistics
import sys
import zipfile

SAMPLE_MS = 0.2
LINE = re.compile(r"^(\d+) \S+ \[([^\]]+)/(\w+)\] \[([^\]]*)\]: (.*)$")


def read_log(path):
    lines = []
    with open(path, errors="replace") as f:
        for raw in f:
            m = LINE.match(raw.rstrip("\n"))
            if m:
                lines.append((int(m.group(1)), m.group(4), m.group(5)))
    return lines


def startup(path, names):
    """{plugin: {"libraries", "load", "enable", "total"}} in ms for one boot."""
    lines = read_log(path)
    result = {name: {"libraries": 0.0, "load": None, "enable": None} for name in names}
    for i, (t, logger, msg) in enumerate(lines):
        m = re.match(r"\[([^\]]+)\] Loading \d+ librar", msg)
        if m and m.group(1) in result:
            # To the first line after its "Loaded library" lines: the server then makes the
            # plugin's class loader and its main class before it logs anything else.
            end = next((u for u, _, later in lines[i + 1:] if not later.startswith(f"[{m.group(1)}] Loaded library")), t)
            result[m.group(1)]["libraries"] += end - t
            continue
        m = re.match(r"Startup times: (?:loader ([\d.]+) ms, )?onLoad ([\d.]+) ms, onEnable ([\d.]+) ms", msg)
        if m and logger in result:
            # A paper-plugin.yml plugin's loader handing Paper its libraries, which Paper logs
            # no line for. (Its class loader and main class aren't in it: about 5 ms, DECISIONS.md.)
            if m.group(1) is not None:
                result[logger]["libraries"] += float(m.group(1))
            result[logger]["load"] = float(m.group(2))
            result[logger]["enable"] = float(m.group(3))
            result[logger]["shim"] = True
            continue
        m = re.match(r"(Loading server plugin|Enabling) (\S+) v", msg)
        if m and m.group(2) in result and not result[m.group(2)].get("shim"):
            end = next((u for u, other, _ in lines[i + 1:] if other != logger), t)
            result[m.group(2)]["load" if m.group(1).startswith("Loading") else "enable"] = end - t
    for name, r in result.items():
        if r["enable"] is None:
            raise ValueError(f"{name} never enabled in {path}")
        r["total"] = r["libraries"] + (r["load"] or 0) + r["enable"]
    return result


def enable_order(path, names):
    order = []
    for _, _, msg in read_log(path):
        m = re.match(r"Enabling (\S+) v", msg)
        if m and m.group(1) in names and m.group(1) not in order:
            order.append(m.group(1))
    return order


def samples(path, packages):
    """{"server"|"other": {plugin or "*": samples}} from async-profiler's collapsed stacks, where
    packages is {plugin: [its packages]}."""
    out = {"server": {p: 0 for p in list(packages) + ["*"]}, "other": {p: 0 for p in list(packages) + ["*"]}}
    pattern = {p: re.compile("(?:" + "|".join(re.escape(q).replace(r"\.", r"[./]") for q in qs) + r")[./]")
               for p, qs in packages.items()}
    with open(path, errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            stack, _, n = line.rpartition(" ")
            kind = "server" if stack.startswith("[Server thread") else "other"
            out[kind]["*"] += int(n)
            for p in packages:
                if pattern[p].search(stack):
                    out[kind][p] += int(n)
    return out


def heap_used_mb(path):
    """Used heap after full GCs, in MB: the lowest of the readings snapshot() took (path.1..3),
    or path alone from an older run. A full GC's leftover only ever adds noise, so the lowest
    reading is the steadiest."""
    readings = []
    for candidate in [f"{path}.{k}" for k in (1, 2, 3)] + [path]:
        if os.path.exists(candidate):
            m = re.search(r"used (\d+)K", open(candidate).read())
            if m:
                readings.append(int(m.group(1)) / 1024)
    if not readings:
        raise ValueError(f"no used heap in {path}")
    return min(readings)


def plugin_metaspace(path):
    """Metaspace (MB) and classes of the plugins' own class loaders, and of the loaders holding
    their libraries: (the Kotlin stdlib, the YAML parser), from VM.classloader_stats."""
    rows = []
    for line in open(path):
        cols = line.split()
        if len(cols) >= 7 and cols[0].startswith("0x"):
            rows.append({"loader": cols[0], "parent": cols[1], "classes": int(cols[3]), "bytes": int(cols[4]),
                         "type": cols[6]})
        elif len(cols) >= 5 and cols[0].isdigit() and "hidden" in line and rows:
            rows[-1]["classes"] += int(cols[0])
            rows[-1]["bytes"] += int(cols[1])
    # A plugin's PluginClassLoader, and the URLClassLoader Spigot and Paper give its libraries:
    # (Paper's BytecodeModifyingURLClassLoader). The server's own URLClassLoader (Paperclip's)
    # is a hundred times bigger, so it's told apart by its size.
    own = [r for r in rows if r["type"].endswith("PluginClassLoader")]
    libraries = [r for r in rows if r["type"].endswith("URLClassLoader") and r["classes"] < 5000]
    return {"own_mb": sum(r["bytes"] for r in own) / 1048576, "own_classes": sum(r["classes"] for r in own),
            "libraries_mb": sum(r["bytes"] for r in libraries) / 1048576,
            "libraries_classes": sum(r["classes"] for r in libraries)}


def our_instances_kb(path, packages):
    total = 0
    for line in open(path):
        cols = line.split()
        if len(cols) >= 4 and cols[0].rstrip(":").isdigit() and any(cols[3].startswith(p + ".") for p in packages):
            total += int(cols[2])
    return total / 1024


def read_budgets(path):
    budgets = {}
    for line in open(path):
        line = line.split("#", 1)[0].strip()
        if line:
            name, value = line.split()
            budgets[name] = float(value)
    return budgets


def plugin_info(jar):
    """(name, packages, size): its packages are its main class's first three names
    (co.xenastudios.<plugin>, with its relocated core) and the package R8 moved renamed
    classes to (the plugin's id, xena.r8's packageName), so every class of the jar's own."""
    with zipfile.ZipFile(jar) as z:
        # plugin.yml, or paper-plugin.yml for a Paper-only plugin (xLimbo), which has no plugin.yml.
        names = z.namelist()
        yml = z.read("plugin.yml" if "plugin.yml" in names else "paper-plugin.yml").decode()
        classes = [n for n in z.namelist() if n.endswith(".class")]
    name = re.search(r"(?m)^name:\s*['\"]?([^'\"\n]+)", yml).group(1).strip()
    main = re.search(r"(?m)^main:\s*['\"]?([\w.$]+)", yml).group(1)
    own = ".".join(main.split(".")[:3])
    others = sorted({n.rsplit("/", 1)[0].replace("/", ".") for n in classes
                     if "/" in n and not n.startswith(own.replace(".", "/") + "/")})
    return name, [own] + others, os.path.getsize(jar)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--budgets", required=True)
    ap.add_argument("--boots", type=int, default=3)
    ap.add_argument("--idle", type=int, default=60)
    ap.add_argument("--load", type=int, default=60)
    ap.add_argument("--bots", type=int, default=10)
    ap.add_argument("--report-only", default="",
                    help="budgets (names in perf-budgets.txt, comma-separated) reported when over but not failed")
    ap.add_argument("jars", nargs="+")
    args = ap.parse_args()
    out = args.out
    budgets = read_budgets(args.budgets)
    plugins = [plugin_info(j) for j in args.jars]
    names = [p[0] for p in plugins]
    package_of = dict(zip(names, (p[1] for p in plugins)))
    packages = [q for p in plugins for q in p[1]]

    checks = []  # (what, value, limit, unit)

    def check(what, value, limit_name, unit):
        checks.append({"what": what, "value": value, "limit": budgets[limit_name], "unit": unit,
                       "budget": limit_name, "ok": value <= budgets[limit_name]})

    # ---- startup ----
    timed = [os.path.join(out, f"boot{b}.log") for b in range(2, args.boots + 2)]
    boots = [startup(p, names) for p in timed]
    first = startup(os.path.join(out, "boot1.log"), names)
    order = enable_order(timed[-1], names)
    startup_rows = {}
    for name in names:
        row = {k: statistics.median(b[name][k] or 0 for b in boots) for k in ("libraries", "load", "enable", "total")}
        row["boots"] = [round(b[name]["total"], 1) for b in boots]
        row["first boot"] = round(first[name]["total"], 1)
        startup_rows[name] = row
        is_first = order and order[0] == name
        check(f"{name} startup{' (first plugin)' if is_first else ''}", row["total"],
              "startup-first-ms" if is_first else "startup-ms", "ms")

    # ---- heap ----
    base = heap_used_mb(os.path.join(out, "base.heap"))
    used = heap_used_mb(os.path.join(out, "before.heap"))
    per_plugin = (used - base) / len(names)
    meta = plugin_metaspace(os.path.join(out, "before.loaders"))
    ours_before = our_instances_kb(os.path.join(out, "before.histogram"), packages)
    check("heap over the server without plugins, per plugin", per_plugin, "heap-mb", "MB")
    check("metaspace in the plugins' own class loaders, per plugin", meta["own_mb"] / len(names), "metaspace-mb", "MB")

    # ---- tick cost ----
    ticks = {}
    for phase, seconds in (("idle", args.idle), ("load", args.load if args.bots else 0)):
        path = os.path.join(out, f"{phase}.collapsed")
        if not seconds or not os.path.exists(path):
            continue
        s = samples(path, package_of)
        ticks[phase] = {
            "server thread, everything (ms/tick)": s["server"]["*"] * SAMPLE_MS / (seconds * 20),
            **{name: {"tick": s["server"][name] * SAMPLE_MS / (seconds * 20),
                      "offthread": s["other"][name] * SAMPLE_MS / seconds} for name in names},
        }
        for name in names:
            t = ticks[phase][name]
            check(f"{name} {phase}: server thread", t["tick"], f"{phase}-tick-ms", "ms/tick")
            check(f"{name} {phase}: other threads", t["offthread"], f"{phase}-offthread-ms-per-s", "ms/s")
        if phase == "load":
            check("all plugins under load: server thread", sum(ticks[phase][n]["tick"] for n in names),
                  "load-family-tick-ms", "ms/tick")

    growth = None
    if os.path.exists(os.path.join(out, "after.histogram")):
        ours_after = our_instances_kb(os.path.join(out, "after.histogram"), packages)
        growth = ours_after - ours_before
        allowance = max(ours_before * budgets["heap-growth-percent"] / 100, budgets["heap-growth-mb"] * 1024)
        checks.append({"what": "our instances' growth across the load", "value": growth / 1024,
                       "limit": allowance / 1024, "unit": "MB", "budget": "heap-growth-percent, heap-growth-mb",
                       "ok": growth <= allowance})

    bots = None
    bots_log = os.path.join(out, "bots.log")
    if os.path.exists(bots_log):
        finals = [l for l in open(bots_log) if l.startswith("final ") or l.startswith("{")]
        if finals:
            bots = json.loads(finals[-1].removeprefix("final ").strip())

    # ---- the report ----
    report_only = {b.strip() for b in args.report_only.split(",") if b.strip()}
    for c in checks:
        c["report_only"] = c["budget"] in report_only
    failed = [c for c in checks if not c["ok"] and not c["report_only"]]
    warned = [c for c in checks if not c["ok"] and c["report_only"]]
    md = [f"## Runtime budgets: {'PASS' if not failed else 'FAIL'}{' (with warnings)' if warned and not failed else ''}", ""]
    md.append("Plugins: " + ", ".join(f"{n} ({size / 1024:.0f} KiB)" for n, _, size in plugins))
    md.append("")
    md.append("| Budget | Measured | Limit | |")
    md.append("|---|--:|--:|---|")
    for c in checks:
        md.append(f"| {c['what']} | {c['value']:.4g} {c['unit']} | {c['limit']:.4g} {c['unit']} | {'ok' if c['ok'] else ('over (report only)' if c['report_only'] else '**over**')} |")
    md.append("")
    for c in warned:
        md.append(f"> [!WARNING]\n> {c['what']} is over its budget ({c['value']:.4g} {c['unit']} against {c['limit']:.4g}),"
                  f" which is reported but doesn't fail the run yet ({c['budget']}).")
        md.append("")
    md.append(f"### Startup (ms, median of {len(boots)} boots)")
    md.append("")
    md.append("| Plugin | Server (libraries:, class loader) | onLoad | onEnable | Total | Each boot | First boot |")
    md.append("|---|--:|--:|--:|--:|---|--:|")
    for name, r in startup_rows.items():
        md.append(f"| {name} | {r['libraries']:.0f} | {r['load']:.1f} | {r['enable']:.1f} | {r['total']:.1f} | "
                  f"{', '.join(str(b) for b in r['boots'])} | {r['first boot']} |")
    md.append("")
    md.append("### Heap (after two full GCs)")
    md.append("")
    md.append(f"Without plugins {base:.1f} MB, with them {used:.1f} MB. The plugins' own class loaders hold"
              f" {meta['own_classes']} classes in {meta['own_mb']:.2f} MB of metaspace, their libraries: loaders"
              f" {meta['libraries_classes']} in {meta['libraries_mb']:.2f} MB (not budgeted: the Kotlin stdlib's)."
              f" Our instances {ours_before:.0f} KB"
              + (f", {growth:+.0f} KB after the load." if growth is not None else "."))
    for phase, t in ticks.items():
        md.append("")
        md.append(f"### {phase.capitalize()} ({args.idle if phase == 'idle' else args.load} s)")
        md.append("")
        md.append(f"Server thread, everything: {t['server thread, everything (ms/tick)']:.3f} ms a tick.")
        for name in names:
            md.append(f"- {name}: {t[name]['tick']:.5f} ms a tick on the server thread, {t[name]['offthread']:.3f} ms a second off it")
    if bots:
        md.append("")
        md.append("Bots: " + ", ".join(f"{k} {v}" for k, v in bots.items()))
    report = "\n".join(md) + "\n"
    open(os.path.join(out, "report.md"), "w").write(report)
    json.dump({"pass": not failed, "checks": checks, "startup": startup_rows, "ticks": ticks, "bots": bots,
               "heap": {"base_mb": base, "used_mb": used, "metaspace": meta,
                        "ours_kb": ours_before, "growth_kb": growth}},
              open(os.path.join(out, "results.json"), "w"), indent=2)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        open(summary, "a").write(report)
    print(report)
    for c in failed:
        print(f"OVER BUDGET: {c['what']}: {c['value']:.4g} {c['unit']}, limit {c['limit']:.4g} ({c['budget']})",
              file=sys.stderr)
    for c in warned:
        print(f"OVER BUDGET (report only): {c['what']}: {c['value']:.4g} {c['unit']}, limit {c['limit']:.4g}"
              f" ({c['budget']})", file=sys.stderr)
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::warning title=Over budget (report only)::{c['what']}: {c['value']:.4g} {c['unit']},"
                  f" budget {c['limit']:.4g} ({c['budget']})")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, StopIteration, AttributeError) as e:
        print(f"MEASUREMENT FAILED: couldn't read the measurements: {e!r}", file=sys.stderr)
        sys.exit(3)
