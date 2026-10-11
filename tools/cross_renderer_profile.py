#!/usr/bin/env python3
"""Normalize queue-aware timings and compare independent paired profiling blocks.

No CPU-to-GPU substitution, nested-time summation or historical baseline inference.
Run manifests supply verified provenance and sampling windows, never inferred from FPS.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics

SCHEMA = 1
MAP = {
    "voxellight": {
        "vulkan_world_total": "world", "vulkan_rt_scene_commit": "scene.update",
        "vulkan_rt_primary": "primary", "vulkan_rt_deterministic_guides": "guides",
        "vulkan_rt_dlss_guides": "rr.input_conversion",
        "vulkan_rt_sample_resolve": "resolve", "vulkan_rt_indirect_iterative": "transport",
        "dlss_rr": "rr", "vulkan_rt_dlss_rr": "rr",
        "client_frame_interval": "client_frame_interval",
    },
    "caustica": {
        "world": "world", "entity.blasRecord": "scene.entity_blas",
        "frame.recordTlas": "scene.tlas", "frame.skyLut": "environment",
        "frame.tracePrimary": "primary_guides", "frame.traceIndirect": "transport",
        "frame.dlssRr": "rr", "frame.upscale": "fallback_upscale",
        "frame.exposure": "exposure", "frame.bloom": "bloom",
        "frame.displayMap": "display", "frame.copyOutput": "output",
        "scene.async_build_batch": "scene.async_build_batch",
    },
}
REQUIRED_CONTROLS = ("display_size", "render_size", "spp", "rr_mode", "fg", "vsync", "frame_cap", "path_budget", "world_clock_phase_ticks", "warmup_seconds")


def percentile(values, fraction):
    values = sorted(values)
    position = (len(values)-1)*fraction
    lower = int(position)
    return values[lower] + (values[min(lower+1, len(values)-1)]-values[lower])*(position-lower)


def distribution(values):
    return None if not values else {"samples": len(values), "p50_ms": statistics.median(values),
        "p95_ms": percentile(values, .95), "min_ms": min(values), "max_ms": max(values)}


def canonical(renderer, stage):
    if renderer == "voxellight" and stage.startswith("vulkan_rt_bounce_"):
        return "transport.bounce." + stage.removeprefix("vulkan_rt_bounce_")
    return MAP[renderer].get(stage, "unmapped." + stage)


def normalize(renderer, source, metadata, excluded_frames=()):
    rows = []
    excluded = set(map(int, excluded_frames))
    with Path(source).open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            frame = int(row["frame"])
            if not metadata["frame_start"] <= frame < metadata["frame_end"] or frame in excluded:
                continue
            stage = row["mode"] if renderer == "voxellight" else row["stage"]
            cpu = row.get("pass_cpu_submission_ns", row.get("cpu_submission_ns", ""))
            gpu = row.get("pass_gpu_ns", row.get("gpu_ns", ""))
            if row.get("valid", "true").strip().lower() in ("false", "0"):
                gpu = ""
            for domain, value in (("cpu", cpu), ("gpu", gpu)):
                number = float(value) if value else None
                if number is not None and (not math.isfinite(number) or number < 0):
                    raise ValueError("invalid elapsed time")
                rows.append({"frame": frame, "scope_id": int(row["scope_id"]),
                    "parent_scope_id": int(row["parent_scope_id"]), "raw_stage": stage,
                    "stage": canonical(renderer, stage), "domain": domain,
                    "queue": row.get("queue", "graphics"), "elapsed_ns": number,
                    "semantics": "inclusive", "sampling_mode": metadata["sampling_mode"]})
    grouped=collections.defaultdict(list)
    for row in rows:
        if row["domain"]=="gpu":grouped[(row["frame"],row["queue"])].append(row)
    definitions=([("primary_guides", {"primary","guides"}),
        ("transport", {f"transport.bounce.{i}" for i in range(1,6)}),
        ("pt.ready_rr_inputs", {"primary","guides","resolve","rr.input_conversion"}|{f"transport.bounce.{i}" for i in range(1,6)})]
        if renderer=="voxellight" else [("pt.ready_rr_inputs", {"primary_guides","transport"})])
    for values in grouped.values():
        if values[0]["queue"]!="graphics":continue
        for index,(stage,wanted) in enumerate(definitions):
            selected=[row for row in values if row["stage"] in wanted]
            complete=len(selected)==len(wanted) and {row["stage"] for row in selected}==wanted
            rows.append(dict(values[0],scope_id=-index-1,parent_scope_id=0,stage=stage,
                raw_stage="derived_disjoint_producers",semantics="sum of logically disjoint producer scopes; approximate GPU scope time, not calibrated critical path; excludes inter-scope gaps",
                elapsed_ns=sum(row["elapsed_ns"] for row in selected) if complete and all(row["elapsed_ns"] is not None for row in selected) else None))
    return {"schema": SCHEMA, "renderer": renderer, "metadata": metadata,
        "source_sha256": hashlib.sha256(Path(source).read_bytes()).hexdigest(),
        "excluded_counter_frames": sorted(excluded), "samples": rows}


def summarize(bundle):
    if "stage_summary" in bundle:return bundle["stage_summary"]
    groups = collections.defaultdict(list)
    missing = collections.Counter()
    totals = collections.Counter()
    valid_frames=collections.defaultdict(set)
    metadata=bundle.get("metadata",{})
    start,end=metadata.get("frame_start"),metadata.get("frame_end")
    expected=(end-start-sum(start<=frame<end for frame in bundle.get("excluded_counter_frames",[]))) if start is not None and end is not None else None
    for row in bundle["samples"]:
        key = (row["domain"], row["queue"], row["stage"])
        totals[key] += 1
        if row["elapsed_ns"] is None:
            missing[key] += 1
        else:
            groups[key].append(row["elapsed_ns"]/1e6)
            valid_frames[key].add(row["frame"])
    return {"|".join(key): {"timing": distribution(groups[key]),
        "coverage": len(valid_frames[key])/expected if expected and key[0]=="gpu" and key[1]=="graphics" else 1-missing[key]/total, "missing": missing[key], "total": total, "expected_frames":expected}
        for key, total in totals.items()}


def validate_pair(left, right):
    errors = []
    for bundle in (left, right):
        if bundle.get("schema") != SCHEMA:
            errors.append("unsupported schema")
        m = bundle["metadata"]
        for field in ("run_id", "pair_id", "scene_id", "scene_signature", "camera_signature",
                      "artifact_sha256", "source_commit", "source_proof", "process_identity", "controls", "sampling_mode", "valid"):
            if field not in m or m[field] in (None, "", {}):
                errors.append("missing " + field)
        if m.get("valid") is not True or m.get("sampling_mode") != "timing":
            errors.append("invalid block or instrumented diagnostic mode")
        for name in REQUIRED_CONTROLS:
            if name not in m.get("controls", {}):
                errors.append("missing actual control " + name)
        if bundle["renderer"]=="voxellight" and m.get("counter_frames_excluded") is not True:
            errors.append("VoxelLight counter frames were not explicitly excluded")
        if not bundle.get("samples") and not bundle.get("sample_count"):
            errors.append("empty sample window")
    a, b = left["metadata"], right["metadata"]
    for name in ("pair_id", "scene_id", "scene_signature", "camera_signature"):
        if a.get(name) != b.get(name):
            errors.append("mismatch " + name)
    for name in REQUIRED_CONTROLS:
        if a.get("controls", {}).get(name) != b.get("controls", {}).get(name):
            errors.append("mismatch actual control " + name)
    if {left["renderer"], right["renderer"]} != {"voxellight", "caustica"}:
        errors.append("pair must contain both renderers")
    return sorted(set(errors))


def compare(bundles):
    pairs = collections.defaultdict(list)
    for bundle in bundles:
        pairs[(bundle["metadata"].get("scene_id"), bundle["metadata"].get("pair_id"))].append(bundle)
    differences = collections.defaultdict(list)
    rejected = []
    seen_runs = set()
    seen_processes = {}
    for identity, blocks in pairs.items():
        if len(blocks) != 2:
            rejected.append({"pair": identity, "errors": ["expected exactly two blocks"]})
            continue
        errors = validate_pair(*blocks)
        for block in blocks:
            run = block["metadata"].get("run_id")
            if run in seen_runs:
                errors.append("duplicate run identity")
            seen_runs.add(run)
            process=block['metadata'].get('process_identity',{})
            process_key=(identity[0],block['renderer'],process.get('pid'),process.get('creation_filetime'))
            if process_key in seen_processes and seen_processes[process_key]!=identity[1]:
                errors.append('same client process reused across independent pairs')
            seen_processes[process_key]=identity[1]
        if errors:
            rejected.append({"pair": identity, "errors": sorted(set(errors))})
            continue
        ordered = {block["renderer"]: summarize(block) for block in blocks}
        v, c = ordered["voxellight"], ordered["caustica"]
        for stage in sorted(v.keys() & c.keys()):
            if stage.startswith("gpu|") and (v[stage]["coverage"] < .8 or c[stage]["coverage"] < .8):
                continue
            vt, ct = v[stage]["timing"], c[stage]["timing"]
            if not vt or not ct or min(vt["samples"], ct["samples"]) < 30:
                continue
            differences[(identity[0], stage)].append({"pair_id": identity[1],
                "voxellight": vt, "caustica": ct, "delta_p50_ms": vt["p50_ms"]-ct["p50_ms"],
                "delta_p95_ms": vt["p95_ms"]-ct["p95_ms"], "stage_fusion_sensitive": stage.split("|")[-1] in {"primary_guides", "transport"}})
    comparisons = []
    for (scene, stage), values in differences.items():
        deltas = [value["delta_p50_ms"] for value in values]
        comparisons.append({"scene_id": scene, "stage": stage, "independent_pairs": len(values),
            "median_delta_ms": statistics.median(deltas), "delta_range_ms": [min(deltas), max(deltas)],
            "repeated": len(values) >= 3, "pairs": values})
    comparisons.sort(key=lambda row: row["median_delta_ms"], reverse=True)
    return {"schema": SCHEMA, "comparisons": comparisons, "rejected_pairs": rejected,
        "limitations": ["Inclusive scopes overlap: ranking is not an additive cost breakdown.",
            "Compute and graphics queues overlap; their execution and host waits must not be added.",
            "Stage fusion and semantic differences require source review before causal inference.",
            "Pair differences are descriptive; frames are not independent experiment repetitions.",
            "Workload counter comparisons and quality evidence are separate diagnostic inputs."]}


def markdown(result):
    lines = ["# VOXELLIGHT-VS-CAUSTICA-PROFILING", "",
        "测量结果。正差值表示 VoxelLight 耗时更高；父子范围不能相加。", "",
        "| 场景 | 阶段 | 配对数 | P50 差值 ms | 跨轮差值范围 ms |", "| --- | --- | ---: | ---: | --- |"]
    for row in result["comparisons"]:
        lines.append(f'| {row["scene_id"]} | {row["stage"]} | {row["independent_pairs"]} | {row["median_delta_ms"]:.4f} | {row["delta_range_ms"]} |')
    if not result["comparisons"]:
        lines += ["", "尚无满足身份、参数、采样模式与时间戳覆盖检查的可比数据。"]
    lines += ["", "## 未通过比较检查的配对", "", "```json", json.dumps(result["rejected_pairs"], ensure_ascii=False, indent=2), "```", "",
        "## 原因闭环", "", "每个主要差异必须另附原因假设、支持证据及反证、最小验证实验和建议优化。阶段排行榜自身不能证明原因或优化收益。", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    norm = sub.add_parser("normalize")
    norm.add_argument("--renderer", choices=MAP, required=True)
    norm.add_argument("--input", type=Path, required=True)
    norm.add_argument("--metadata", type=Path, required=True)
    norm.add_argument("--counter-frames", type=Path)
    norm.add_argument("--output", type=Path, required=True)
    norm.add_argument("--summary-only", action="store_true")
    comp = sub.add_parser("compare")
    comp.add_argument("inputs", nargs="+", type=Path)
    comp.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "normalize":
        excluded = json.loads(args.counter_frames.read_text(encoding="utf-8")) if args.counter_frames else []
        result = normalize(args.renderer, args.input, json.loads(args.metadata.read_text(encoding="utf-8")), excluded)
        if args.summary_only:
            result["stage_summary"]=summarize(result)
            result["sample_count"]=len(result.pop("samples"))
            result["source_file"]=str(args.input.resolve())
    else:
        result = compare([json.loads(path.read_text(encoding="utf-8")) for path in args.inputs])
        args.output.with_suffix(".md").write_text(markdown(result), encoding="utf-8")
        with args.output.with_suffix(".csv").open("w", encoding="utf-8", newline="") as stream:
            fields = ["scene_id", "stage", "independent_pairs", "median_delta_ms", "repeated"]
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows({key: row[key] for key in fields} for row in result["comparisons"])
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
