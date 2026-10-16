#!/usr/bin/env python3
"""Lists every message key referenced by templates and Java, and checks both bundles define them.
Usage: scripts/i18n-keys.py [--list]   (exit code 1 when something is missing or the bundles differ)"""
import re, sys, pathlib, glob

root = pathlib.Path(__file__).resolve().parent.parent
res = root / "src/main/resources"
used, prefixes = {}, {}

def add(k, f):
    if k.endswith("."):
        return
    used.setdefault(k, set()).add(f)

for f in list(res.glob("templates/**/*.html")):
    t = f.read_text(encoding="utf-8")
    rel = str(f.relative_to(res))
    for m in re.finditer(r"#\{\s*'([A-Za-z0-9_.\-]+)'\s*\+", t):
        prefixes.setdefault(m.group(1), set()).add(rel)
    for m in re.finditer(r"#\{\s*([A-Za-z][A-Za-z0-9_.\-]*)\s*[}(]", t):
        add(m.group(1), rel)
    for m in re.finditer(r"#messages\.msg\('([^']+)'", t):
        add(m.group(1), rel)
    for m in re.finditer(r"@msg\.get\('([^']+)'", t):
        add(m.group(1), rel)
for f in root.glob("src/main/java/**/*.java"):
    t = f.read_text(encoding="utf-8")
    rel = str(f.relative_to(root))
    for m in re.finditer(r'(?:BusinessException|NotFoundException|msg\.get|messages\.get|getMessage)\(\s*"([a-z][A-Za-z0-9_.\-]+)"', t):
        add(m.group(1), rel)
    for m in re.finditer(r'"(successKey|errorKey)"\s*,\s*"([^"]+)"', t):
        add(m.group(2), rel)
for f in glob.glob(str(res / "static/js/*.js")):
    t = pathlib.Path(f).read_text(encoding="utf-8")
    for m in re.finditer(r"data-i18n-([a-z0-9\-]+)", t):
        pass

def load(p):
    d = {}
    for line in p.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            d[k.strip()] = v
    return d

en = load(res / "i18n/messages.properties") if (res / "i18n/messages.properties").exists() else {}
mr = load(res / "i18n/messages_mr.properties") if (res / "i18n/messages_mr.properties").exists() else {}

if "--list" in sys.argv:
    for k in sorted(used):
        print(k)
    print("PREFIXES:", sorted(prefixes))
    sys.exit(0)

bad = 0
for k in sorted(used):
    for name, d in (("en", en), ("mr", mr)):
        if k not in d:
            print(f"MISSING[{name}] {k}   <- {sorted(used[k])[0]}")
            bad += 1
for p in sorted(prefixes):
    if not any(k.startswith(p) for k in en):
        print(f"NO KEYS for dynamic prefix {p}   <- {sorted(prefixes[p])[0]}")
        bad += 1
for k in sorted(set(en) ^ set(mr)):
    print(f"ONLY IN ONE BUNDLE: {k}")
    bad += 1
# placeholder parity
for k in en.keys() & mr.keys():
    a = sorted(re.findall(r"\{\d+\}", en[k])); b = sorted(re.findall(r"\{\d+\}", mr[k]))
    if a != b:
        print(f"PLACEHOLDER MISMATCH {k}: {a} vs {b}")
        bad += 1
print(f"{len(used)} keys referenced, {len(en)} en, {len(mr)} mr, {bad} problem(s)")
sys.exit(1 if bad else 0)
