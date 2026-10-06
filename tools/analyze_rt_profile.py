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
    statusPath=pathlib.Path(stem + ".txt")
    status=statusPath.read_text() if statusPath.exists() else ""
    controls = {}
    for name in ("VoxelLight", "internalResolution", "requestedSppPerFrame",
                 "sppPerFrame", "renderMode", "rtDynamicModels", "rtDynamicGroups",
                 "rtDynamicTextureTiles", "rtDynamicTextureCopyBytes",
                 "geometryCopyBytes", "sections", "sceneBytes", "emissiveTriangles",
                 "pathHotBytes", "visibility", "optionalEnabled", "queuePolicy",
                 "queueCalibration", "geometryRanges", "asScratchBytes"):
        match = re.search(r"\b" + name + r"=([^,\n]+)", status)
        if match:
            controls[name] = match.group(1)
    schema2=bool(rows and "scope_id" in rows[0])
    scene_cpu=[]
    if schema2:
        windows=collections.defaultdict(list)
        for row in rows:windows[int(row["frame"])].append(row)
        for window in windows.values():
            if any(row["mode"].startswith("vulkan_rt_batch") for row in window):
                scenes=[row for row in window if row["mode"]=="vulkan_rt_scene_commit"]
                if len(scenes)==1:scene_cpu.append(int(scenes[0]["pass_cpu_submission_ns"])/1e6)
    else:
        batches=sorted(int(row["frame"]) for row in rows if row["mode"].startswith("vulkan_rt_batch"))
        windows=collections.defaultdict(list)
        for row in rows:
            index=bisect.bisect_right(batches,int(row["frame"]))
            if index<len(batches):windows[batches[index]].append(row)
        for window in windows.values():
            scenes=[row for row in window if row["mode"]=="vulkan_rt_scene"]
            captures=[row for row in window if row["mode"]=="vulkan_rt_dynamic_capture"]
            if len(scenes)==2 and len(captures)==1:scene_cpu.append(sum(int(row["pass_cpu_submission_ns"]) for row in scenes)/1e6)
    worldPath=pathlib.Path(stem + ".world.csv")
    world=list(csv.DictReader(worldPath.open())) if worldPath.exists() else []
    raysPath=pathlib.Path(stem + ".rays.csv")
    ray_workloads=[]
    if raysPath.exists():
        with raysPath.open() as stream:
            ray_workloads=[{name:int(value) for name,value in row.items()} for row in csv.DictReader(stream)]
    primary=sum(row["active_0"] for row in ray_workloads)
    any_hit=sum(row["any_hit"] for row in ray_workloads)
    alive=[sum(row["active_"+str(i)] for row in ray_workloads)/primary if primary else None for i in range(6)]
    mismatch=sum(row.get("visibility_mismatches",0) for row in ray_workloads)
    pipeline_path=pathlib.Path(str(path)+".pipelines.csv")
    compiler_stats=[]
    if pipeline_path.exists():
        with pipeline_path.open() as stream:compiler_stats=list(csv.DictReader(stream))
    queue_timings=collections.defaultdict(list)
    for row in rows:
        if row["mode"] in ("vulkan_rt_batch_fixed","vulkan_rt_batch_compact") and row["pass_gpu_ns"]:
            queue_timings[(row["width"],row["height"],row.get("spp"),row["mode"])].append(int(row["pass_gpu_ns"])/1e6)
    return {"final_controls": controls,
            "any_hit_per_primary":any_hit/primary if primary else None,
            "alive_fraction_by_bounce":alive,"visibility_replay_mismatches":mismatch,
            "queue_ab_by_resolution_spp":[{"width":key[0],"height":key[1],"spp":key[2],"mode":key[3],"gpu":distribution(values)} for key,values in queue_timings.items()],
            "driver_compiler_statistics":compiler_stats, "passes": timings(rows),
            "ray_workloads":ray_workloads,
            "world": timings(world),
            "scene_cpu_per_batch" if schema2 else "inferred_scene_cpu_per_batch": distribution(scene_cpu),
            "workloads":sorted({(row["width"],row["height"],row.get("spp","unknown")) for row in rows if row["mode"].startswith("vulkan_rt_batch")}),
            "caveat": "Scope timings overlap; do not add parent and child passes. "
                      "Final controls do not prove settings throughout the captured window. "
                      "World and pass rings can cover different time windows. "
                      "Visibility replay is a 256-ray microbenchmark, not total inline visibility cost. "
                      "L1/L2 traffic and runtime register spills require an external GPU profiler; "
                      "queue summaries require matched alive curves/scenes before accepting a speedup."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profiles", type=pathlib.Path, nargs="+")
    args = parser.parse_args()
    print(json.dumps([summarize(path) for path in args.profiles], indent=2,
                     ensure_ascii=False))
