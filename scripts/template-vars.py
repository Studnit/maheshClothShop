#!/usr/bin/env python3
"""Heuristic: every ${variable} a template reads must be a model attribute some controller sets, a th:each/th:with
local, or a Spring/Thymeleaf built-in. Catches typos such as ${invoce}; it does not prove a controller sets it for THAT page."""
import re, pathlib, sys
root = pathlib.Path(__file__).resolve().parent.parent / "src/main"
attrs = set()
for f in root.joinpath("java").rglob("*.java"):
    t = f.read_text(encoding="utf-8")
    attrs |= set(re.findall(r'(?:addAttribute|addFlashAttribute|put)\(\s*"([A-Za-z0-9_]+)"', t))
    attrs |= set(re.findall(r'@ModelAttribute\("([A-Za-z0-9_]+)"\)', t))
builtin = {"param","session","request","response","application","springMacroRequestContext","springRequestContext",
           "locale","success","currentUri","shop","_csrf","#locale","true","false","null","T","fmt","msg","environment"}
# model attributes added by advice / interceptors
for f in root.joinpath("java").rglob("*.java"):
    t = f.read_text(encoding="utf-8")
    if "@ControllerAdvice" in t or "@ModelAttribute" in t:
        attrs |= set(re.findall(r'(?:addAttribute|put)\(\s*"([A-Za-z0-9_]+)"', t))
bad = 0
for f in sorted(root.joinpath("resources/templates").rglob("*.html")):
    t = f.read_text(encoding="utf-8")
    local = set()
    for m in re.finditer(r'th:each="\s*([A-Za-z0-9_]+)\s*(?:,\s*([A-Za-z0-9_]+))?\s*:', t):
        local |= {g for g in m.groups() if g}
    for m in re.finditer(r'th:with="([^"]*)"', t):
        local |= set(re.findall(r'(?:^|[,;]\s*)([A-Za-z0-9_]+)\s*=', m.group(1)))
    for m in re.finditer(r'(?:^|[\s(])(?:th:fragment|th:replace|th:insert)="[^"]*?\(([^)]*)\)', t):
        local |= set(re.findall(r'[A-Za-z0-9_]+', m.group(1)))
    for m in re.finditer(r'th:fragment="[^(]*\(([^)]*)\)"', t):
        local |= {x.strip() for x in m.group(1).split(",")}
    used = set()
    for m in re.finditer(r'\$\{\s*!?\s*\(?\s*([A-Za-z_][A-Za-z0-9_]*)', t):
        used.add(m.group(1))
    for v in sorted(used):
        if v not in attrs and v not in local and v not in builtin:
            print(f"{f.relative_to(root/'resources/templates')}: ${{{v}}} is not set by any controller"); bad += 1
print(f"{len(attrs)} model attributes known, {bad} unknown variable(s)")
sys.exit(1 if bad else 0)
