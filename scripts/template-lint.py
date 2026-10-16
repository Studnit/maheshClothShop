#!/usr/bin/env python3
"""Static checks for Thymeleaf templates: balanced HTML tags, balanced braces and quotes inside th:/sec: attributes.
It cannot evaluate expressions (only the Spring smoke test can) but it catches the typos that break rendering."""
import sys, pathlib, re
from html.parser import HTMLParser

VOID = {"area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr"}
root = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/templates"

class P(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True); self.stack=[]; self.errors=[]
    def handle_starttag(self, tag, attrs):
        for name, val in attrs:
            if val and (name.startswith("th:") or name.startswith("sec:") or name.startswith("layout:")):
                if val.count("{") != val.count("}"):
                    self.errors.append(f"line {self.getpos()[0]}: unbalanced braces in {name}=\"{val[:70]}\"")
                if val.count("'") % 2:
                    self.errors.append(f"line {self.getpos()[0]}: odd number of quotes in {name}=\"{val[:70]}\"")
        if tag not in VOID: self.stack.append((tag, self.getpos()[0]))
    def handle_startendtag(self, tag, attrs): pass
    def handle_endtag(self, tag):
        if tag in VOID: return
        if not self.stack: self.errors.append(f"line {self.getpos()[0]}: stray </{tag}>"); return
        if self.stack[-1][0] == tag: self.stack.pop(); return
        # tolerate optional-close tags
        for i in range(len(self.stack)-1, -1, -1):
            if self.stack[i][0] == tag:
                for t,l in self.stack[i+1:]: self.errors.append(f"line {l}: <{t}> not closed before </{tag}> at line {self.getpos()[0]}")
                del self.stack[i:]; return
        self.errors.append(f"line {self.getpos()[0]}: stray </{tag}>")

bad = 0
for f in sorted(root.rglob("*.html")):
    text = f.read_text(encoding="utf-8")
    p = P(); p.feed(text); p.close()
    errs = list(p.errors) + [f"line {l}: <{t}> never closed" for t,l in p.stack]
    for e in errs:
        print(f"{f.relative_to(root)}: {e}"); bad += 1
print(f"{len(list(root.rglob('*.html')))} templates, {bad} problem(s)")
sys.exit(1 if bad else 0)
