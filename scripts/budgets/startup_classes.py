#!/usr/bin/env python3
"""Writes a plugin's startup class list from a boot's class-loading log, for build-logic's R8Shrink
(xenaR8's startupClasses: the project's startup-classes.txt), which maps it to the shrunk jar's
names. scripts/budgets/check.sh --record-startup runs it after a boot with
-Xlog:class+load=info:file=<log>:uptimemillis.

    startup_classes.py --log classload.log --boot boot.log --jar Plugin.jar --out startup-classes.txt
    startup_classes.py --log classload.log --done-ms 4210 --jar Plugin.jar --out startup-classes.txt

--done-ms is for a log without millisecond timestamps (a proxy's, scripts/boot-proxy.sh
--record-startup): the JVM's uptime when the server said it was done.

Kept: every class loaded before the server's "Done (" line from the plugin's jar or from a
library its META-INF/xena/libraries.txt lists (from plugins/Xena/libraries/, or the server's
libraries/ for libraries:), the jar's
own back in the names they have before R8 (the mapping file beside the jar), in the order they
loaded, which is the order the shim loads them in.
"""
import argparse
import os
import re
import zipfile

ap = argparse.ArgumentParser()
ap.add_argument("--log", required=True)
ap.add_argument("--boot")
ap.add_argument("--done-ms", type=int)
ap.add_argument("--jar", required=True)
ap.add_argument("--out", required=True)
a = ap.parse_args()

done = a.done_ms
if done is None:
    if a.boot is None:
        raise SystemExit("--boot or --done-ms says when the server was done")
    for line in open(a.boot, errors="replace"):
        m = re.match(r"^(\d+) .*Done \(", line)
        if m:
            done = int(m.group(1))
            break
    if done is None:
        raise SystemExit(f"no Done line in {a.boot}")

original = {}
mapping = os.path.splitext(a.jar)[0] + ".map"
if os.path.isfile(mapping):
    for line in open(mapping):
        m = re.match(r"^(\S+) -> (\S+):$", line.rstrip("\n"))
        if m:
            original[m.group(2)] = m.group(1)

jar_name = os.path.basename(a.jar)
# The libraries' jars, by their path in a Maven layout (the family's cache and the server's
# libraries/ both use one): org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar.
with zipfile.ZipFile(a.jar) as z:
    listed = z.read("META-INF/xena/libraries.txt").decode() if "META-INF/xena/libraries.txt" in z.namelist() else ""
libraries = []
for line in listed.splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0].count(":") == 2:
        group, artifact, version = parts[0].split(":")
        libraries.append(f"/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar")
names = []
seen = set()
for line in open(a.log, errors="replace"):
    m = re.match(r"^\[(\d+)ms\](?:\[info\]\[class,load\])? (\S+) source: (.*)$", line.rstrip("\n"))
    if not m or int(m.group(1)) > done:
        continue
    name, source = m.group(2), m.group(3)
    ours = jar_name in source
    library = any(path in source for path in libraries)
    if not (ours or library) or "$$Lambda" in name or "/0x" in name:
        continue
    name = original.get(name, name) if ours else name
    if name not in seen:
        seen.add(name)
        names.append(name)

with open(a.out, "w") as f:
    if a.done_ms is None:
        f.write("# The classes this plugin loads as it starts, recorded on Paper by scripts/budgets/check.sh\n")
        f.write("# --record-startup: the shim loads them on a thread of its own before onEnable (DECISIONS.md).\n")
    else:
        f.write("# The classes this plugin loads as it starts, recorded on the proxy by scripts/boot-proxy.sh\n")
        f.write("# --record-startup: the shim loads them on a thread of its own as it starts (DECISIONS.md).\n")
    f.write("# In their names before R8; R8Shrink maps them. Record again after big changes.\n")
    for name in names:
        f.write(name + "\n")
print(f"{len(names)} startup classes -> {a.out}")
