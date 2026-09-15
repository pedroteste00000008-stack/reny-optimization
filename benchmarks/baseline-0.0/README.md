# Performance Baseline 0.0

**Status: COMPLETE for execution; PARTIAL for subsystem attribution.** The
instrumented Forge client completed all 80 formal runs: 16/16 cells have five
valid runs and the deterministic aggregator accepts 80 runs. The separate
BENCH-01 A pilot remains excluded from the formal count. The measured ranking
is in `bottlenecks.md`; exact subsystem attribution remains explicitly
`UNPROVEN` where the runtime has no active hooks.

## Scope

This is the execution of Reny Optimization issue #7:

| Dimension | Scope |
| --- | --- |
| Reny | `30d88b554addd4a610d6fe1efd319bda4b677635` |
| Minecraft / Forge | 1.7.10 / 10.13.4.1614 |
| Runtime | Temurin/OpenJDK 1.8.0_312 |
| Build JVM | Temurin JDK 25.0.4.1 |
| Formal matrix | 4 scenarios × 4 configurations × 5 runs = 80 accepted |
| Timing | 60 s warmup + 120 s measurement |
| Primary display | 1280×720, render distance 8, VSync off, FPS cap 260 (1.7.10 maximum) |
| Optimization patches | None |

The four configurations are A: minimal/shaders off, B: minimal/shaders on,
C: The Reawakening/shaders off, and D: The Reawakening/shaders on. The
canonical scenarios are BENCH-01, BENCH-02, BENCH-03, and BENCH-06.

## Final evidence

The corrected A/BENCH-01 pilot passed the exporter gate, followed by 80 formal
runs with unique result directories. Each formal cell has five runs, warmup is
excluded from the measurement CSVs, and `aggregate.json`/`aggregate.csv` were
generated from terminal `VALID` events for the exact commit.

The strongest measured effect is shader-on rendering. In minimal BENCH-01,
shader-on B versus shader-off A raises combined frame P95 from 4.747 ms to
52.131 ms and P99.9 from 16.127 ms to 76.115 ms; 37.603% of frame samples
exceed 33.33 ms in B. In the heavy shader torture cell, D/BENCH-06 reaches
36.997 ms P95, 54.335 ms P99.9, and 15.565% above 33.33 ms.

The heavy pack independently raises both frame and tick cost and has materially
more GC activity, but the data does not identify which Forge/mod subsystem is
responsible. See `bottlenecks.md` and `known-limitations.md` for the confidence
boundaries.

## Reproduction

From the repository checkout, aggregate terminal `VALID` events for the exact
commit with:

```text
python3 benchmarks/baseline-0.0/aggregate.py \
  --events /path/to/Reny\ Optimization/benchmark-work/campaign-runner.jsonl \
  --results-root /path/to/Reny\ Optimization/benchmarks/results \
  --commit 30d88b554addd4a610d6fe1efd319bda4b677635 \
  --output-dir benchmarks/baseline-0.0
```

Add `--strict` for the completion gate. The default event mode excludes
rejected attempts and the pilot unless a formal terminal `VALID` event exists.
The script concatenates measurement-only CSV samples for cell percentiles and
also reports run-level dispersion, threshold rates, MSPT, GC, and heap delta.

## Artifact locations

- `methodology.md` — scope and execution protocol.
- `environment-summary.json` — reproducible runtime and workload identity.
- `runtime-manifest.json` — exact runtime artifacts, profiles, worlds, and
  safety boundaries.
- `aggregate.py` — deterministic aggregation and validation.
- `aggregate.csv` / `aggregate.json` — generated only after the campaign has
  enough valid result exports; these are the final 80-run outputs.
- `rejections.json` — rejected attempts/exports retained as audit evidence.
- `known-limitations.md` — confirmed limits and unproven claims.
- `bottlenecks.md` — data-derived ranking and confidence boundaries.
- `follow-up-issues.md` — focused issue drafts; no optimization is implemented
  by this baseline.

Large raw exports and logs stay in the local benchmark workspace and are not
committed to the repository.
