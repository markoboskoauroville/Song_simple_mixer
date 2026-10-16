#!/usr/bin/env python3
"""Structural checks a compiler will not run (MANTRA_MANIFEST android-app.md §1)."""
import pathlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
fail = []
for name in ("Mix.kt", "Space.kt"):
    src = (root / "app/src/main/java/community/auroville/mantra/mixer" / name).read_text()
    if re.search(r"^import android\.", src, re.M):
        fail.append(f"{name} imports Android: the mechanism must stay testable on a desk")
props = (root / "gradle.properties").read_text()
m = re.search(r"^appVersion=(\d+)$", props, re.M)
if not m:
    fail.append("gradle.properties has no appVersion=N")
for p in root.rglob("*"):
    if p.is_file() and p.suffix in {".jks", ".p12", ".keystore"}:
        fail.append(f"a keystore is in the tree: {p.relative_to(root)}")
tests = sum(len(re.findall(r"@Test", p.read_text())) for p in (root / "app/src/test").rglob("*Test.kt"))
print(f"Mix.kt, Space.kt android-free: {'yes' if 'imports Android' not in ' '.join(fail) else 'NO'}; appVersion: {m.group(1) if m else '?'}; Test 1 cases: {tests}")
if tests < 11:
    fail.append(f"Test 1 has {tests} cases, the floor is 11")
if fail:
    print("\n".join("FAIL: " + f for f in fail)); sys.exit(1)
print("verify: OK")
