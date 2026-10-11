import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("cross_renderer_profile", Path(__file__).parents[1]/"cross_renderer_profile.py")
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


def block(renderer, pair=1, elapsed=2_000_000):
    return {"schema": 1, "renderer": renderer, "metadata": {
        "run_id": f"{renderer}-{pair}", "pair_id": pair, "scene_id": "terrain",
        "scene_signature": "world-sha", "camera_signature": "camera-sha",
        "artifact_sha256": "artifact", "source_commit": "commit", "sampling_mode": "timing", "valid": True, "counter_frames_excluded": True,
        "source_proof":"source-sha","process_identity":{"pid":pair+1,"creation_filetime":pair+100},
        "controls": {name: False if name in ("fg", "vsync") else 1 for name in profile.REQUIRED_CONTROLS}},
        "samples": [{"frame": i, "scope_id": i, "parent_scope_id": 0, "stage": "primary",
            "domain": "gpu", "queue": "graphics", "elapsed_ns": elapsed} for i in range(40)]}


class CrossRendererTests(unittest.TestCase):
    def test_explicit_invalid_gpu_sample_counts_as_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"passes.csv"
            path.write_text("frame,stage,scope_id,parent_scope_id,cpu_submission_ns,gpu_ns,valid\n1,world,1,0,500,1000,false\n",encoding="utf-8")
            result=profile.normalize("caustica",path,{"frame_start":1,"frame_end":2,"sampling_mode":"timing"})
            summary=profile.summarize(result)
            self.assertIsNone(summary["gpu|graphics|world"]["timing"])
            self.assertEqual(summary["gpu|graphics|world"]["coverage"],0)
            self.assertIsNotNone(summary["cpu|graphics|world"]["timing"])

    def test_missing_gpu_is_never_cpu_substitute(self):
        a, b = block("voxellight"), block("caustica")
        for row in a["samples"]:
            row["elapsed_ns"] = None
        a["samples"] += [dict(row, domain="cpu", elapsed_ns=4_000_000) for row in a["samples"]]
        self.assertEqual(profile.compare([a, b])["comparisons"], [])

    def test_reject_diagnostics_invalid_and_mismatched_actual_controls(self):
        for change in (lambda m: m.update(valid=False), lambda m: m.update(sampling_mode="counters"),
                       lambda m: m["controls"].update(render_size=2), lambda m: m.pop("artifact_sha256")):
            a, b = block("voxellight"), block("caustica")
            change(a["metadata"])
            result=profile.compare([a, b])
            self.assertFalse(result["comparisons"])
            self.assertTrue(result["rejected_pairs"])

    def test_independent_pairs_not_frame_count(self):
        bundles=[]
        for pair in range(3):
            bundles += [block("voxellight", pair, 3_000_000), block("caustica", pair, 2_000_000)]
        result=profile.compare(bundles)["comparisons"][0]
        self.assertEqual(result["independent_pairs"], 3)
        self.assertEqual(result["median_delta_ms"], 1)
        self.assertTrue(result["repeated"])
        single=profile.compare(bundles[:2])["comparisons"][0]
        self.assertFalse(single["repeated"])

    def test_nested_times_remain_separate(self):
        a=block("voxellight")
        a["samples"] += [dict(row, stage="world", elapsed_ns=5_000_000) for row in a["samples"]]
        summary=profile.summarize(a)
        self.assertEqual(summary["gpu|graphics|primary"]["timing"]["p50_ms"], 2)
        self.assertEqual(summary["gpu|graphics|world"]["timing"]["p50_ms"], 5)

    def test_same_process_cannot_fake_independent_repetitions(self):
        bundles=[]
        for pair in range(3):
            for renderer in ('voxellight','caustica'):
                data=block(renderer,pair)
                data['metadata']['process_identity']={'pid':100,'creation_filetime':12345}
                bundles.append(data)
        result=profile.compare(bundles)
        self.assertEqual(result['comparisons'][0]['independent_pairs'],1)
        self.assertEqual(len(result['rejected_pairs']),2)

    def test_fused_input_total_requires_complete_disjoint_producers(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"passes.csv"
            names=["vulkan_rt_primary", "vulkan_rt_deterministic_guides", "vulkan_rt_sample_resolve", "vulkan_rt_dlss_guides"]+[f"vulkan_rt_bounce_{i}" for i in range(1,6)]
            header="frame,mode,scope_id,parent_scope_id,pass_cpu_submission_ns,pass_gpu_ns\n"
            body="".join(f"1,{name},{i+1},0,50,{(i+1)*1000}\n" for i,name in enumerate(names))
            metadata={"frame_start":1,"frame_end":2,"sampling_mode":"timing"}
            path.write_text(header+body,encoding="utf-8")
            rows=profile.normalize("voxellight",path,metadata)["samples"]
            total=[row for row in rows if row["stage"]=="pt.ready_rr_inputs"]
            self.assertEqual(len(total),1)
            self.assertEqual(total[0]["elapsed_ns"],45000)
            path.write_text(header+body+"1,vulkan_rt_primary,20,0,50,1000\n",encoding="utf-8")
            self.assertIsNone(next(row["elapsed_ns"] for row in profile.normalize("voxellight",path,metadata)["samples"] if row["stage"]=="pt.ready_rr_inputs"))

    def test_counter_exclusion_is_mandatory_for_voxellight_timing(self):
        a,b=block("voxellight"),block("caustica")
        a["metadata"].pop("counter_frames_excluded")
        self.assertTrue(profile.compare([a,b])["rejected_pairs"])

    def test_counter_frame_and_half_open_window_exclusion(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"passes.csv"
            path.write_text("frame,mode,scope_id,parent_scope_id,pass_cpu_submission_ns,pass_gpu_ns\n"
                            "1,vulkan_rt_primary,3,0,500,1000\n"
                            "2,vulkan_rt_primary,4,0,500,1000\n"
                            "3,vulkan_rt_primary,5,0,500,1000\n", encoding="utf-8")
            result=profile.normalize("voxellight", path, {"frame_start":1,"frame_end":3,"sampling_mode":"timing"}, [2])
            self.assertEqual({row["frame"] for row in result["samples"]}, {1})


if __name__ == "__main__":
    unittest.main()
