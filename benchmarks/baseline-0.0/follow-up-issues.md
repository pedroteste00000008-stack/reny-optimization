# Follow-up issue drafts

These are drafts only. Issue #7's baseline does not implement an optimization,
open issues, or merge changes automatically.

## Draft 1 — Add render/shader phase attribution before optimization

**Measured problem:** Shader-on rendering is the strongest frame-budget effect.
Minimal B/BENCH-01 has 52.131 ms frame P95 and 76.115 ms P99.9 versus 4.747 ms
and 16.127 ms in shader-off A. Heavy D/BENCH-06 has 36.997 ms P95 and 54.335
ms P99.9 versus 19.499 ms and 30.276 ms in shader-off C.

**Intended metric:** Per-phase render/shader timing, frame P95/P99/P99.9, and
frame thresholds above 16.67/33.33/50 ms. Keep tick metrics separate.

**Implementation surface:** Instrument the legacy render-thread/shader-pass
boundaries with bounded, opt-in timing. Do not assume that the observed cost is
pure GPU time; add a hardware-timer path only when available.

**Compatibility considerations:** Preserve OptiFine E7 and the exact Sildur
pack behavior, shader-off behavior, and Forge 1.7.10 compatibility. Avoid
changing render order or shader contracts in the attribution issue.

**Acceptance benchmark:** Repeat the relevant A/B and C/D BENCH-01 and BENCH-06
cells with five runs each, confirm phase attribution is populated, and require
no new boot/render errors. Any optimization proposal must use those timings and
the existing 1280×720 protocol.

## Draft 2 — Attribute heavy-pack CPU/tick cost by subsystem

**Measured problem:** With shaders off, C versus A raises frame P95 by
15.440–17.936 ms and tick P95 by 2.766–5.698 ms across the four scenarios.
Heavy cells also have median GC time of 97–155 ms per 120-second run versus
10–21 ms in minimal A.

**Intended metric:** Separate section timings for chunk streaming/generation,
chunk rebuild/upload, entity ticking, TileEntity ticking, Forge events, and
lighting; report tick P50/P95/P99 and frame tails without summing unlike clocks.

**Implementation surface:** Add low-overhead section hooks around the existing
workload boundaries, with explicit enable/disable metadata and no behavior
change. Keep the hooks safe for the complete isolated The Reawakening mod set.

**Compatibility considerations:** The heavy snapshot is a real local legacy
pack with 72 loaded FML mods and known nonfatal compatibility noise. Hooks must
not alter event ordering, tick scheduling, chunk state, or TileEntity lifecycle.

**Acceptance benchmark:** Run five repeated C and D measurements for BENCH-01,
BENCH-02, BENCH-03, and BENCH-06; every export must contain valid section rows,
unchanged world/config identity, and the same completion gates as Baseline 0.0.

## Draft 3 — Correlate allocation/GC pauses with frame and tick tails

**Measured problem:** Heavy D/BENCH-06 has 243 ms median GC time per 120 s;
D/BENCH-02 has a 679 ms run-level maximum. This is a confirmed JVM signal, but
the current aggregate cannot prove that GC caused a particular frame tail.

**Intended metric:** Timestamp-aligned GC events, heap/allocation samples, frame
long-frame counts, and tick tails. Do not treat heap delta as allocation rate.

**Implementation surface:** Extend the safe profiler export or collect a
representative non-privileged external trace when available. Keep the default
benchmark overhead bounded and record the collection mode in environment.json.

**Compatibility considerations:** Do not change heap sizing, collector choice,
or object lifetimes as part of this diagnostic issue. Legacy mods may depend on
allocation and lifecycle behavior.

**Acceptance benchmark:** Re-run representative A/B/C/D BENCH-01 and BENCH-06
cells with five runs per cell, show aligned pause/tail evidence, and only then
draft a narrowly scoped allocation optimization with a before/after acceptance
threshold.
