#!/usr/bin/env python3
"""Markdown table from bench.json files.

    python3 bench_table.py before.json after.json
    python3 bench_table.py before1.json,before2.json,... after1.json,after2.json,...

With several files per side (repeated runs), each case reports the median of the runs' medians.
"""
import json, statistics, sys


def load(arg):
    runs = [json.load(open(p)) for p in arg.split(",")]
    return {name: statistics.median(r[name]["ns_per_op"] for r in runs if name in r)
            for name in runs[0]}


before, after = (load(a) for a in sys.argv[1:3])


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
    ratio = a / b
    print(f"| {name} | {fmt(b)} | {fmt(a)} | {ratio:,.2f}× |" if ratio < 0.1 else
          f"| {name} | {fmt(b)} | {fmt(a)} | {ratio:,.1f}× |")
