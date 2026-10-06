#!/usr/bin/env python3
"""Summarize bounded RT profile exports without treating scope IDs as frame IDs.

Only timings and an allowlist of workload controls are emitted; raw runtime logs
and account/server metadata are deliberately excluded from the report.
"""
import argparse
import bisect
import collections
import csv
import json
import pathlib
import re
import statistics


def distribution(values):
    values = sorted(values)
    if not values:
        return None
    return {"samples": len(values), "median_ms": statistics.median(values),
            "mean_ms": statistics.mean(values),
            "p95_ms": values[int((len(values) - 1) * .95)]}


def timings(rows):
    grouped = collections.defaultdict(lambda: {"cpu": [], "gpu": []})
    for row in rows:
        for kind, column in (("cpu", "pass_cpu_submission_ns"),
                             ("gpu", "pass_gpu_ns")):
            if row[column]:
                grouped[row["mode"]][kind].append(int(row[column]) / 1e6)
    return {name: {kind: distribution(values) for kind, values in data.items()}
            for name, data in grouped.items()}


def summarize(path):
    with path.open() as stream:
        rows = list(csv.DictReader(stream))
    stem = str(path)[:-len(".passes.csv")]
    status = pathlib.Path(stem + ".txt").read_text()
    controls = {}
    for name in ("VoxelLight", "internalResolution", "requestedSppPerFrame",
                 "sppPerFrame", "renderMode", "rtDynamicModels", "rtDynamicGroups",
                 "rtDynamicTextureTiles", "rtDynamicTextureCopyBytes",
                 "geometryCopyBytes", "sections", "sceneBytes", "emissiveTriangles"):
        match = re.search(r"\b" + name + r"=([^,\n]+)", status)
        if match:
            controls[name] = match.group(1)
    # The passes.csv 'frame' column actually holds scope serial IDs. A scene
    # preparation belongs to the following batch, not the same serial ID.
    batches = sorted(int(row["frame"]) for row in rows
                     if row["mode"] == "vulkan_rt_batch")
    windows = collections.defaultdict(list)
    for row in rows:
        index = bisect.bisect_right(batches, int(row["frame"]))
        if index < len(batches):
            windows[batches[index]].append(row)
    scene_cpu = []
    for window in windows.values():
        scenes = [row for row in window if row["mode"] == "vulkan_rt_scene"]
        captures = [row for row in window if row["mode"] == "vulkan_rt_dynamic_capture"]
        # Reject partial ring-buffer boundaries and nonstandard frame schedules.
        if len(scenes) == 2 and len(captures) == 1:
            scene_cpu.append(sum(int(row["pass_cpu_submission_ns"])
                                 for row in scenes) / 1e6)
    with pathlib.Path(stem + ".world.csv").open() as stream:
        world = list(csv.DictReader(stream))
    return {"final_controls": controls, "passes": timings(rows),
            "world": timings(world),
            "inferred_scene_cpu_per_batch": distribution(scene_cpu),
            "caveat": "Scope timings overlap; do not add parent and child passes. "
                      "Final controls do not prove settings throughout the captured window. "
                      "World and pass rings can cover different time windows."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profiles", type=pathlib.Path, nargs="+")
    args = parser.parse_args()
    print(json.dumps([summarize(path) for path in args.profiles], indent=2,
                     ensure_ascii=False))
