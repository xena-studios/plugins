#!/usr/bin/env python3
"""Writes a plugin's startup class list from a boot's class-loading log, for build-logic's R8Shrink
(xenaR8's startupClasses: the project's startup-classes.txt), which maps it to the shrunk jar's
names. scripts/budgets/check.sh --record-startup runs it after a boot with
-Xlog:class+load=info,class+init=info:file=<log>:uptimemillis.

    startup_classes.py --log classload.log --boot boot.log --jar Plugin.jar --out startup-classes.txt
    startup_classes.py --log classload.log --done-ms 4210 --jar Plugin.jar --out startup-classes.txt

--done-ms is for a log without millisecond timestamps (a proxy's, scripts/boot-proxy.sh
--record-startup): the JVM's uptime when the server said it was done.

Kept: every class loaded before the server's "Done (" line from the plugin's jar or from a
library its META-INF/xena/libraries.txt lists (from plugins/Xena/libraries/, or the server's
libraries/ for libraries:), the jar's
own back in the names they have before R8 (the mapping file beside the jar), in the order they
loaded, which is the order the shim loads them in.

Only the classes the boot linked, when the log has class+init's "Start class verification" lines:
a class the JVM only loaded to verify another (to check one type is assignable to another) is never
linked on its own, but the shim's preloader links every class it's given (getDeclaredConstructors),
which verifies it and loads what its own code names in turn. Listing those cost xStats about 9 KB of
metaspace, which a hand trim took back and a record put back; now a record leaves them out. A log
without those lines (class+load alone) keeps every class loaded, as before.
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
# The classes the boot verified, so linked, before it was done: None if the log doesn't say.
linked = None
for line in open(a.log, errors="replace"):
    m = re.match(r"^\[(\d+)ms\](?:\[info\]\[class,init\])? Start class verification for: (\S+)", line.rstrip("\n"))
    if m:
        if linked is None:
            linked = set()
        if int(m.group(1)) <= done:
            linked.add(m.group(2))

names = []
seen = set()
unlinked = 0
for line in open(a.log, errors="replace"):
    m = re.match(r"^\[(\d+)ms\](?:\[info\]\[class,load\])? (\S+) source: (.*)$", line.rstrip("\n"))
    if not m or int(m.group(1)) > done:
        continue
    name, source = m.group(2), m.group(3)
    ours = jar_name in source
    library = any(path in source for path in libraries)
    if not (ours or library) or "$$Lambda" in name or "/0x" in name:
        continue
    if linked is not None and name not in linked:
        unlinked += 1
        continue
    name = original.get(name, name) if ours else name
    # The core's Paper loader: Paper loads it in a class loader of its own before the plugin's,
    # and no other server can load it, so the shim never should (build-logic's R8Shrink drops it).
    if ours and re.match(r"^co\.xenastudios\.[^.]+\.core\.loader\.paper\.", name):
        continue
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
left = "" if linked is None else f", {unlinked} loaded only to verify others left out"
print(f"{len(names)} startup classes -> {a.out}{left}")
