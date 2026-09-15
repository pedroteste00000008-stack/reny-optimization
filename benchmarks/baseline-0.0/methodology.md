# Methodology

## Execution matrix

The issue-controlled matrix is 4 × 4 × 5 = 80 formal runs:

- BENCH-01 — Stationary render;
- BENCH-02 — Chunk traversal;
- BENCH-03 — Industrial base;
- BENCH-06 — Shader torture;
- A — minimal/vanilla-like, shaders off;
- B — minimal/vanilla-like, shaders on;
- C — heavy The Reawakening reference, shaders off;
- D — heavy The Reawakening reference, shaders on.

Each run uses the instrumented Reny core with no active optimization patches,
60 seconds of warmup, and 120 seconds of measurement. Warmup samples are not
exported as measurement samples. Each formal run uses a unique exporter
directory and the campaign event log is append-only so rejected attempts remain
auditable.

## Runtime identity

The tested production artifact is `renyoptimization-30d88b5.jar`, SHA-256
`fcc579a5108810787198020f1d75a6ae1209a7e9da73b4af8bc2a39c8160de5b`. The
runtime UniMixins dependency is `unimixins-0.2.1-dev.jar`, SHA-256
`0910de6614a827a73dc38e857b56231cbedda8e42ac717136ce499ffcd243927`.

The target is a dedicated CurseForge instance for Minecraft 1.7.10 and Forge
10.13.4.1614. Build-time JDK 25 is kept separate from the Java 8 runtime used
by Minecraft. Exported environment metadata records the exact commit, mod
list, Java/JVM, OS/hardware/OpenGL information when available, display
settings, shader state, world identity, route, and config hash.

## Workload identity

The minimal world is `RenyBaseline00`, seed `170710`, with a fixed route and
snapshot. The heavy workload is an isolated copy of the local `Forja Industrial
1710` / The Reawakening instance, source Git commit
`c8e06abab9515473c1d54dbdebc3b9ae890b4686`. The original instance is not used
as a launch target and is not modified.

The shader-on cells use the existing local pack
`Sildur's Enhanced Default v1.19 Fast.zip`, SHA-256
`65969f5c7a381dfdd924cd1a07cfd5cfd9bf184a504ddd678abc147ccb314549`.
The heavy copy also uses the source pack's OptiFine E7 jar. The pack loaded and
entered a world in the isolated D boot gate; its legacy block-mapping and
missing-texture warnings are retained as evidence.

## Operator protocol

The runtime command is `/reny benchmark start <BENCH-ID>`. The controller logs
CREATED → WARMUP → MEASURING → COMPLETE and prints the export directory. The
campaign runner stages the disposable profile, launches it through the
CurseForge profile, selects the designated snapshot, and observes the
world-entry log before sending the benchmark command. A manual-interface mode
exists as a fallback, but the final 80-run campaign used the automated path.
The X11 helper activates Minecraft before typing the complete command, uses a
held key/mouse press where the 1.7.10 GUI samples button state, and restores the
previous window after injection. It does not send Escape after Return.

The pilot is a separate gate. It must prove output identity, duration bounds,
sample counts, sane IDs, populated percentiles, JVM/GC metadata, unique output,
and error-free profiler shutdown before formal measurements are accepted.

## Aggregation

`aggregate.py` accepts only terminal `VALID` campaign events for the requested
commit by default. It validates environment identity, shader/config state,
durations, files, and CSV sample counts. Cell frame/tick distributions are
computed over concatenated measurement samples; run-level median/min/max/mean/
standard deviation are retained for the exported percentile and runtime
metrics. P99.9 is emitted when at least 1,000 samples support the tail.

## Final campaign validation

The final event log contains 80 accepted terminal `VALID` formal runs at commit
`30d88b554addd4a610d6fe1efd319bda4b677635`, with five runs in every one of the
16 cells. The final aggregate was regenerated with strict cell-coverage
validation. Rejected attempts remain in `rejections.json`; they are not
silently reused as formal measurements.
