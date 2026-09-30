#!/usr/bin/env python3
"""Markdown table from two bench.json files: python3 bench_table.py before.json after.json"""
import json, sys

before, after = (json.load(open(p)) for p in sys.argv[1:3])


def fmt(ns):
    if ns >= 1e6:
        return f"{ns / 1e6:,.1f} ms"
    if ns >= 1e3:
        return f"{ns / 1e3:,.1f} µs"
    return f"{ns:,.0f} ns"


print("| Benchmark (median per operation) | Original | SQL | Ratio |")
print("| --- | ---: | ---: | ---: |")
for name, b in before.items():
    a = after.get(name)
    if a is None:
        continue
    ratio = a["ns_per_op"] / b["ns_per_op"]
    print(f"| {name} | {fmt(b['ns_per_op'])} | {fmt(a['ns_per_op'])} | {ratio:,.1f}× |")
