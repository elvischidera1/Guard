#!/usr/bin/env python3
"""Compares two scenario transcripts key by key.

    python3 compare_transcripts.py golden.json new.json [expected-differences.json]

Entries listed in the expected-differences file (key -> reason) are reported but do not fail.
"""
import json, sys, difflib

a, b = (json.load(open(p)) for p in sys.argv[1:3])
expected = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else {}
diffs = [k for k in a.keys() | b.keys() if a.get(k) != b.get(k)]
order = list(a.keys()) + [k for k in b if k not in a]
for k in sorted(diffs, key=order.index):
    print(f"=== {k}" + (" (expected: " + expected[k] + ")" if k in expected else ""))
    left = json.dumps(a.get(k), indent=1, sort_keys=True).splitlines()
    right = json.dumps(b.get(k), indent=1, sort_keys=True).splitlines()
    for line in difflib.unified_diff(left, right, "golden", "new", n=1, lineterm=""):
        print(line)
unexpected = [k for k in diffs if k not in expected]
print(f"\n{len(diffs)} of {len(order)} entries differ, {len(unexpected)} unexpectedly")
sys.exit(1 if unexpected else 0)
