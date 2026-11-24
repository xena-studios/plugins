#!/usr/bin/env python3
"""Reads the folders measure.sh wrote and prints the tables in RESULTS.md.

    python3 analyze.py <SERVERS> <jars-folder> [run-name ...]

Per run: startup (each timed boot's "Done" and every plugin's load and enable time, from the
milliseconds since JVM start that log4j2.xml adds), heap after two full GCs, classes and
metaspace in plugin class loaders, shallow bytes of our own classes' instances, and the CPU
samples whose stack passes through each plugin's package. A sample counts for a plugin when any
frame is in co.xenastudios.<plugin> (the core is relocated into each plugin). IDLE and LOAD must
match the seconds measure.sh profiled.
"""
import collections
import os
import re
import statistics
import subprocess
import sys

JAVA_HOME = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True).stdout.strip()
IDLE = int(os.environ.get("IDLE", 90))
LOAD = int(os.environ.get("LOAD", 120))
LINE = re.compile(r"^(\d+) \S+ \[([^/\]]+)/\w+\] \[([^\]]*)\]: (.*)$")


def log_lines(path):
    out = []
    with open(path, errors="replace") as f:
        for raw in f:
            m = LINE.match(raw.rstrip("\n"))
            if m:
                out.append((int(m.group(1)), m.group(2), m.group(3), m.group(4)))
    return out


def startup(path):
    """Done ms, and {plugin: (load ms, enable ms)} where each phase ends at the next line
    logged by anything other than that plugin."""
    lines = log_lines(path)
    done = next((t for t, _, _, msg in lines if msg.startswith("Done (")), None)
    phases = collections.defaultdict(dict)
    for i, (t, _, logger, msg) in enumerate(lines):
        m = re.match(r"(Loading server plugin|Enabling) (\S+) v", msg)
        if not m:
            continue
        name = m.group(2)
        end = next((u for u, _, lg, _ in lines[i + 1:] if lg != logger), t)
        phases[name]["load" if m.group(1).startswith("Loading") else "enable"] = end - t
    return done, phases


def heap(run):
    txt = open(os.path.join(run, "out/heap.txt")).read()
    used = int(re.search(r"used (\d+)K", txt).group(1)) / 1024
    meta = int(re.search(r"Metaspace\s+used (\d+)K", txt).group(1)) / 1024
    classes = chunk = 0
    for line in open(os.path.join(run, "out/loaders.txt")):
        if "PluginClassLoader" in line:
            cols = line.split()
            classes += int(cols[3])
            chunk += int(cols[4])
    ours = 0
    for line in open(os.path.join(run, "out/histogram.txt")):
        cols = line.split()
        if len(cols) >= 4 and cols[3].startswith("co.xenastudios."):
            ours += int(cols[2])
    return used, meta, classes, chunk / 1048576, ours / 1024


def mspt(run):
    vals = []
    for t, _, _, msg in log_lines(os.path.join(run, "out/measured.log")):
        m = re.match(r"◴ ([\d.]+)/[\d.]+/[\d.]+, ([\d.]+)/[\d.]+/[\d.]+, ([\d.]+)/[\d.]+/([\d.]+)", msg)
        if m:
            vals.append(float(m.group(3)))
    return vals  # [idle 1m avg, load 1m avg]


def samples(path):
    """{'server'|'other': Counter(plugin or '*')} of 1 ms CPU samples from async-profiler's
    collapsed output ('[thread tid=n];frame;frame... count'); '*' counts every sample."""
    out = {"server": collections.Counter(), "other": collections.Counter()}
    for line in open(path, errors="replace"):
        stack, n = line.rstrip("\n").rsplit(" ", 1)
        kind = "server" if stack.startswith("[Server thread ") else "other"
        out[kind]["*"] += int(n)
        for p in set(re.findall(r"co[/.]xenastudios[/.](\w+)[/.]", stack)):
            out[kind][p] += int(n)
    return out


def main():
    root, jars = sys.argv[1], sys.argv[2]
    runs = sys.argv[3:] or sorted(os.listdir(root))
    print("## Jar sizes\n\n| Plugin | Jar (KB) | Classes |\n|---|--:|--:|")
    for j in sorted(os.listdir(jars)):
        n = int(subprocess.run(["sh", "-c", f"unzip -l '{os.path.join(jars, j)}' | grep -c '\\.class$'"],
                               capture_output=True, text=True).stdout)
        print(f"| {j} | {os.path.getsize(os.path.join(jars, j)) / 1024:.0f} | {n} |")

    print("\n## Startup (ms; median of the timed boots, first boot in brackets)\n")
    print("| Run | Done | Plugin | Load | Enable |\n|---|--:|---|--:|--:|")
    for r in runs:
        d = os.path.join(root, r, "out")
        boots = sorted(f for f in os.listdir(d) if re.match(r"boot\d+\.log", f)) + ["measured.log"]
        results = [startup(os.path.join(d, b)) for b in boots]
        first, timed = results[0], results[1:]
        done = statistics.median(x[0] for x in timed)
        names = sorted(set().union(*(x[1].keys() for x in timed)))
        if not names:
            print(f"| {r} | {done:.0f} [{first[0]}] | | | |")
        for i, p in enumerate(names):
            load = statistics.median(x[1][p].get("load", 0) for x in timed)
            enable = statistics.median(x[1][p].get("enable", 0) for x in timed)
            f = first[1].get(p, {})
            print(f"| {r if i == 0 else ''} | {f'{done:.0f} [{first[0]}]' if i == 0 else ''} | {p} | "
                  f"{load:.0f} [{f.get('load', 0)}] | {enable:.0f} [{f.get('enable', 0)}] |")

    print("\n## Heap and classes, 30 s after Done, after two full GCs\n")
    print("| Run | Heap used (MB) | Metaspace (MB) | Plugin classes | Plugin metaspace (MB) | Our instances (KB) |")
    print("|---|--:|--:|--:|--:|--:|")
    for r in runs:
        used, meta, classes, chunk, ours = heap(os.path.join(root, r))
        print(f"| {r} | {used:.1f} | {meta:.1f} | {classes} | {chunk:.1f} | {ours:.0f} |")

    print("\n## Tick cost (async-profiler, 1 ms CPU samples through each plugin's package)\n")
    print("Server-thread ms a tick = samples / (seconds x 20). Off-thread = CPU ms a second on every other thread.\n")
    print("| Run | Phase | /mspt 1m avg | Server thread, all (ms/tick) | Plugin | Server thread (ms/tick) | Off-thread (ms/s) |")
    print("|---|---|--:|--:|---|--:|--:|")
    for r in runs:
        ms = mspt(os.path.join(root, r))
        for phase, idx, secs in (("idle", 0, IDLE), ("load", 1, LOAD)):
            f = os.path.join(root, r, "out", phase + ".txt")
            if not os.path.exists(f) or idx >= len(ms):
                continue
            s = samples(f)
            ticks = secs * 20
            ours = sorted({p for k in s.values() for p in k if p != "*"}) or ["(none)"]
            for i, p in enumerate(ours):
                head = f"| {r} | {phase} | {ms[idx]:.2f} | {s['server']['*'] / ticks:.2f} |" if i == 0 else "| | | | |"
                print(f"{head} {p} | {s['server'][p] / ticks:.3f} | {s['other'][p] / secs:.2f} |")


if __name__ == "__main__":
    main()
