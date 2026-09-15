#!/usr/bin/env python3
"""Deterministically aggregate Issue #7 result exports by scenario and cell.

The formal campaign event log is the authoritative input for the default mode:
only terminal VALID events for the requested commit are included.  This keeps
the pre-metadata-fix pilot and rejected attempts out of the formal baseline.
Use --scan only for an explicitly identified directory of result exports.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


SCENARIOS = ("BENCH-01", "BENCH-02", "BENCH-03", "BENCH-06")
CONFIGS = ("A", "B", "C", "D")
RUNS_PER_CELL = 5
SHADER_NAME = "Sildur's Enhanced Default v1.19 Fast.zip"
OPTIFINE_VERSION = "OptiFine_1.7.10_HD_U_E7"
EXPECTED_SCHEMA_VERSION = 2
SEEDS = {"A": "170710", "B": "170710", "C": "-7121280191151763024", "D": "-7121280191151763024"}
SHADER_BY_CONFIG = {"A": "none", "B": SHADER_NAME, "C": "none", "D": SHADER_NAME}


def load_json(path: Path) -> Dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def read_events(path: Path) -> Iterable[Dict[str, Any]]:
    if not path.is_file():
        raise ValueError(f"event log does not exist: {path}")
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid event JSON at {path}:{line_number}: {exc}") from exc
            if isinstance(value, dict):
                yield value


def finite_numbers(values: Iterable[Any]) -> List[float]:
    result: List[float] = []
    for value in values:
        try:
            number = float(value)
        except (TypeError, ValueError):
            continue
        if math.isfinite(number):
            result.append(number)
    return result


def percentile(values: Sequence[float], fraction: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    # Nearest-rank matches the profiler's documented percentile convention.
    index = max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))
    return ordered[index]


def dispersion(values: Sequence[float]) -> Optional[float]:
    return statistics.pstdev(values) if values else None


def scalar_stats(values: Sequence[float], include_tail: bool = True) -> Dict[str, Any]:
    result: Dict[str, Any] = {
        "count": len(values),
        "min": min(values) if values else None,
        "median": statistics.median(values) if values else None,
        "max": max(values) if values else None,
        "mean": statistics.fmean(values) if values else None,
        "stddev": dispersion(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
    }
    # 99.9th percentile is only reported when the combined sample is large
    # enough to contain a meaningful tail rather than one interpolated point.
    result["p99_9"] = percentile(values, 0.999) if include_tail and len(values) >= 1000 else None
    return result


def csv_durations(path: Path) -> List[float]:
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if "duration_ms" not in (reader.fieldnames or []):
            raise ValueError(f"duration_ms column missing: {path}")
        values = finite_numbers(row.get("duration_ms") for row in reader)
    if not values:
        raise ValueError(f"no duration samples: {path}")
    return values


def reject(rejections: List[Dict[str, Any]], event: Dict[str, Any], reason: str) -> None:
    rejections.append(
        {
            "attempt_id": event.get("attempt_id"),
            "run_id": event.get("run_id"),
            "configuration": event.get("configuration"),
            "scenario": event.get("scenario"),
            "result_dir": event.get("result_dir"),
            "reason": reason,
        }
    )


def expected_config(environment: Dict[str, Any]) -> Optional[str]:
    seed = str(environment.get("world", {}).get("seed"))
    shader = str(environment.get("shader", {}).get("name"))
    for config in CONFIGS:
        if seed == SEEDS[config] and shader == SHADER_BY_CONFIG[config]:
            return config
    return None


def validate_export(
    event: Dict[str, Any],
    result_dir: Path,
    commit_sha: str,
) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    required = ("environment.json", "summary.json", "frames.csv", "ticks.csv")
    missing = [name for name in required if not (result_dir / name).is_file()]
    if missing:
        return None, "missing files: " + ", ".join(missing)
    try:
        environment = load_json(result_dir / "environment.json")
        summary = load_json(result_dir / "summary.json")
        frames = csv_durations(result_dir / "frames.csv")
        ticks = csv_durations(result_dir / "ticks.csv")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return None, str(exc)

    run_id = str(event.get("run_id") or environment.get("run_id") or "")
    scenario = str(event.get("scenario") or environment.get("benchmark_id") or "")
    config = str(event.get("configuration") or "")
    errors: List[str] = []
    if not run_id or environment.get("run_id") != run_id:
        errors.append("run_id mismatch")
    if scenario not in SCENARIOS or environment.get("benchmark_id") != scenario:
        errors.append("benchmark_id mismatch")
    if config not in CONFIGS:
        errors.append("configuration is not A/B/C/D")
    if environment.get("reny", {}).get("commit_sha") != commit_sha:
        errors.append("Reny commit mismatch")
    if summary.get("schema_version") != EXPECTED_SCHEMA_VERSION:
        errors.append("benchmark schema version mismatch")
    if expected_config(environment) != config:
        errors.append("environment does not match configuration seed/shader")
    display = environment.get("display", {})
    for key, value in (("width", 1280), ("height", 720), ("render_distance_chunks", 8), ("vsync", False), ("fps_cap", 260)):
        if display.get(key) != value:
            errors.append(f"display.{key} mismatch")
    extras = environment.get("extras", {})
    if extras.get("optimization_patches") != "none":
        errors.append("optimization patches were not none")
    if extras.get("shader_pack_configured") != ("true" if SHADER_BY_CONFIG[config] != "none" else "false"):
        errors.append("shader_pack_configured mismatch")
    if extras.get("optifine_loaded") != "true":
        errors.append("OptiFine was not loaded")
    if extras.get("optifine_version") != OPTIFINE_VERSION:
        errors.append("OptiFine version mismatch")
    if extras.get("game_mode") != "creative":
        errors.append("game mode was not creative")
    if extras.get("workload_descriptor_version") != "baseline-0.0-review-1":
        errors.append("workload descriptor version mismatch")
    if extras.get("workload_procedure_id") != scenario:
        errors.append("workload procedure mismatch")
    capture = summary.get("capture", {})
    if capture.get("complete") is not True or capture.get("truncated") is not False:
        errors.append("formal capture is incomplete or truncated")
    for phase, configured, minimum in (("warmup", 60000, 59000), ("measurement", 120000, 119000)):
        value = summary.get(phase, {})
        try:
            actual = float(value.get("actual_ms", 0))
        except (TypeError, ValueError):
            actual = 0
        if value.get("configured_ms") != configured or actual < minimum:
            errors.append(f"{phase} duration invalid")
    frame_expected = int(summary.get("frame", {}).get("sample_count", 0))
    tick_expected = int(summary.get("tick", {}).get("sample_count", 0))
    if frame_expected < 1000 or frame_expected != len(frames):
        errors.append("frame sample count invalid")
    if tick_expected < 1000 or tick_expected != len(ticks):
        errors.append("tick sample count invalid")
    if capture.get("frame_samples") != frame_expected or capture.get("tick_samples") != tick_expected:
        errors.append("capture sample count mismatch")
    if errors:
        return None, "; ".join(errors)
    return {
        "run_id": run_id,
        "scenario": scenario,
        "configuration": config,
        "result_dir": str(result_dir),
        "environment": environment,
        "summary": summary,
        "frames": frames,
        "ticks": ticks,
    }, None


def run_metric(run: Dict[str, Any], section: str, field: str) -> Optional[float]:
    value = run["summary"].get(section, {}).get(field)
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def cell_summary(config: str, scenario: str, runs: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    frame_values = [value for run in runs for value in run["frames"]]
    tick_values = [value for run in runs for value in run["ticks"]]
    frame = scalar_stats(frame_values)
    tick = scalar_stats(tick_values)
    thresholds: Dict[str, Dict[str, Any]] = {}
    for threshold in (16.67, 33.33, 50.0, 100.0, 250.0):
        count = sum(1 for value in frame_values if value > threshold)
        key = f"gt_{str(threshold).replace('.', '_')}_ms"
        thresholds[key] = {"count": count, "rate": count / len(frame_values) if frame_values else None}
    frame["fps_1_percent_low"] = 1000.0 / frame["p99"] if frame.get("p99") else None
    frame["fps_0_1_percent_low"] = 1000.0 / frame["p99_9"] if frame.get("p99_9") else None
    frame["thresholds"] = thresholds
    frame_run_metrics = {
        field: scalar_stats(
            [value for value in (run_metric(run, "frame", field) for run in runs) if value is not None],
            include_tail=False,
        )
        for field in ("p50_ms", "p95_ms", "p99_ms", "p99_9_ms", "fps_1_percent_low", "fps_0_1_percent_low")
    }
    tick_run_metrics = {
        field: scalar_stats(
            [value for value in (run_metric(run, "tick", field) for run in runs) if value is not None],
            include_tail=False,
        )
        for field in ("p50_ms", "p95_ms", "p99_ms", "p99_9_ms")
    }
    runtime_metrics = {}
    for field in ("gc_count", "gc_time_ms", "heap_delta_bytes"):
        values = []
        for run in runs:
            value = run["summary"].get("runtime_delta", {}).get(field)
            try:
                number = float(value)
            except (TypeError, ValueError):
                continue
            if math.isfinite(number):
                values.append(number)
        runtime_metrics[field] = scalar_stats(values, include_tail=False)
    return {
        "configuration": config,
        "scenario": scenario,
        "expected_runs": RUNS_PER_CELL,
        "valid_runs": len(runs),
        "status": "COMPLETE" if len(runs) >= RUNS_PER_CELL else "PARTIAL",
        "run_ids": sorted(run["run_id"] for run in runs),
        "frame_combined_measurement": frame,
        "frame_run_level": frame_run_metrics,
        "tick_combined_measurement": tick,
        "tick_run_level": tick_run_metrics,
        "runtime_run_level": runtime_metrics,
    }


def flatten_csv(cell: Dict[str, Any]) -> Dict[str, Any]:
    frame = cell["frame_combined_measurement"]
    tick = cell["tick_combined_measurement"]
    runtime = cell["runtime_run_level"]
    row: Dict[str, Any] = {
        "configuration": cell["configuration"],
        "scenario": cell["scenario"],
        "status": cell["status"],
        "expected_runs": cell["expected_runs"],
        "valid_runs": cell["valid_runs"],
        "frame_samples": frame["count"],
        "frame_min_ms": frame["min"],
        "frame_median_ms": frame["median"],
        "frame_max_ms": frame["max"],
        "frame_stddev_ms": frame["stddev"],
        "frame_p50_ms": frame["p50"],
        "frame_p95_ms": frame["p95"],
        "frame_p99_ms": frame["p99"],
        "frame_p99_9_ms": frame["p99_9"],
        "frame_fps_1_percent_low": frame["fps_1_percent_low"],
        "frame_fps_0_1_percent_low": frame["fps_0_1_percent_low"],
        "tick_samples": tick["count"],
        "tick_min_ms": tick["min"],
        "tick_median_ms": tick["median"],
        "tick_max_ms": tick["max"],
        "tick_stddev_ms": tick["stddev"],
        "tick_p50_ms": tick["p50"],
        "tick_p95_ms": tick["p95"],
        "tick_p99_ms": tick["p99"],
        "tick_p99_9_ms": tick["p99_9"],
    }
    for key, values in frame["thresholds"].items():
        row[f"frame_{key}_count"] = values["count"]
        row[f"frame_{key}_rate"] = values["rate"]
    for field, stats in runtime.items():
        row[f"{field}_median"] = stats["median"]
        row[f"{field}_min"] = stats["min"]
        row[f"{field}_max"] = stats["max"]
        row[f"{field}_stddev"] = stats["stddev"]
    return row


def candidate_events(events_path: Path, commit_sha: str) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    selected: Dict[str, Dict[str, Any]] = {}
    rejected: List[Dict[str, Any]] = []
    for event in read_events(events_path):
        if event.get("commit_sha") != commit_sha:
            continue
        if event.get("status") == "VALID":
            run_id = str(event.get("run_id") or "")
            if run_id:
                selected.setdefault(run_id, event)
        elif event.get("status") == "INVALID":
            reject(rejected, event, str(event.get("error") or "invalid attempt"))
    return list(selected.values()), rejected


def scan_candidates(results_root: Path, commit_sha: str) -> List[Dict[str, Any]]:
    events: List[Dict[str, Any]] = []
    for scenario in SCENARIOS:
        for environment_path in sorted((results_root / scenario / commit_sha).glob("run-*/environment.json")):
            environment = load_json(environment_path)
            events.append(
                {
                    "status": "VALID",
                    "run_id": environment.get("run_id"),
                    "scenario": environment.get("benchmark_id"),
                    "configuration": expected_config(environment),
                    "result_dir": str(environment_path.parent),
                    "commit_sha": commit_sha,
                }
            )
    return events


def resolve_result_dir(raw_value: str, results_root: Path) -> Path:
    """Resolve event paths written relative to the benchmark instance.

    Campaign events use paths such as ``benchmarks/results/...`` because the
    runner executes in the CurseForge instance.  The aggregator is commonly
    invoked from the development checkout, so resolving those paths against
    the current working directory silently rejects otherwise valid exports.
    """
    value = Path(raw_value)
    if value.is_absolute():
        return value
    if len(value.parts) >= 2 and value.parts[:2] == ("benchmarks", "results"):
        return results_root.parent.parent / value
    return Path.cwd() / value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-root", type=Path, default=Path("benchmarks/results"))
    parser.add_argument("--events", type=Path, help="campaign-runner.jsonl; default formal input")
    parser.add_argument("--scan", action="store_true", help="scan result directories instead of using terminal VALID events")
    parser.add_argument("--commit", required=True, help="exact Reny commit SHA to aggregate")
    parser.add_argument("--output-dir", type=Path, default=Path("benchmarks/baseline-0.0"))
    parser.add_argument("--strict", action="store_true", help="return nonzero unless all 16 cells have five valid runs")
    args = parser.parse_args()

    results_root = args.results_root.resolve()
    if args.scan:
        events = scan_candidates(results_root, args.commit)
        rejections: List[Dict[str, Any]] = []
    elif args.events:
        events, rejections = candidate_events(args.events, args.commit)
    else:
        parser.error("provide --events or --scan")

    accepted: List[Dict[str, Any]] = []
    seen_run_ids: set[str] = set()
    accepted_by_cell: Dict[Tuple[str, str], int] = {}
    for event in events:
        result_dir = resolve_result_dir(str(event.get("result_dir", "")), results_root)
        run, error = validate_export(event, result_dir, args.commit)
        if error:
            reject(rejections, event, error)
            continue
        assert run is not None
        key = (run["configuration"], run["scenario"])
        if run["run_id"] in seen_run_ids:
            reject(rejections, event, "duplicate valid run_id")
            continue
        if accepted_by_cell.get(key, 0) >= RUNS_PER_CELL:
            reject(rejections, event, f"more than {RUNS_PER_CELL} valid runs for cell")
            continue
        seen_run_ids.add(run["run_id"])
        accepted_by_cell[key] = accepted_by_cell.get(key, 0) + 1
        accepted.append(run)

    cells = [
        cell_summary(config, scenario, sorted(
            [run for run in accepted if run["configuration"] == config and run["scenario"] == scenario],
            key=lambda item: item["run_id"],
        ))
        for config in CONFIGS
        for scenario in SCENARIOS
    ]
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    aggregate = {
        "schema_version": 1,
        "commit_sha": args.commit,
        "expected_runs_per_cell": RUNS_PER_CELL,
        "cell_count": len(cells),
        "accepted_formal_runs": len(accepted),
        "rejected_attempts_or_exports": len(rejections),
        "cells": cells,
        "method": {
            "percentiles": "nearest-rank over concatenated measurement-only CSV samples",
            "p99_9_minimum_samples": 1000,
            "fps_low_definition": "1000 / frame-time tail percentile",
            "formal_source": "terminal VALID campaign events filtered to the exact commit",
        },
    }
    (output_dir / "aggregate.json").write_text(json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output_dir / "rejections.json").write_text(json.dumps(rejections, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    rows = [flatten_csv(cell) for cell in cells]
    fieldnames = list(rows[0].keys())
    with (output_dir / "aggregate.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps({"accepted": len(accepted), "rejected": len(rejections), "complete_cells": sum(cell["status"] == "COMPLETE" for cell in cells)}, sort_keys=True))
    return 0 if not args.strict or all(cell["status"] == "COMPLETE" for cell in cells) else 2


if __name__ == "__main__":
    sys.exit(main())
