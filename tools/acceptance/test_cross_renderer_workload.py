import importlib.util,json,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('workload',Path(__file__).parents[1]/'cross_renderer_workload.py')
workload=importlib.util.module_from_spec(spec);spec.loader.exec_module(workload)

class WorkloadTests(unittest.TestCase):
    def test_missing_counter_is_unavailable_not_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'work.csv';path.write_text('frame,camera_path_traces,scatter_rays\n1,100,20\n',encoding='utf-8')
            result=workload.normalize('caustica',path)
            self.assertEqual(result['counters']['radiance_traces']['median_per_frame'],120)
            self.assertIsNone(result['counters']['material_surface_decodes']['median_per_frame'])
            self.assertFalse(result['formal_timing_eligible'])

    def test_negative_component_cannot_cancel_positive_component(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'work.csv';path.write_text('frame,camera_path_traces,scatter_rays\n1,-1,20\n',encoding='utf-8')
            with self.assertRaises(ValueError):workload.normalize('caustica',path)

    def test_duplicate_auxiliary_frames_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'work.csv';path.write_text('frame,radiance_rays\n1,10\n',encoding='utf-8')
            extra=Path(directory)/'rays.csv';extra.write_text('frame,shadow_rays\n1,2\n1,3\n',encoding='utf-8')
            with self.assertRaises(ValueError):workload.normalize('voxellight',path,extra=extra)

    def test_sparse_as_counts_require_lossless_instrumentation_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'as.csv';path.write_text('frame,blas_builds_recorded,blas_updates_recorded,tlas_builds_recorded,tlas_updates_recorded\n2,0,6,1,0\n3,100,0,0,0\n',encoding='utf-8')
            status=Path(str(path)+'.status.json');status.write_text(json.dumps({'enabled':True,'dropped_events':0}),encoding='utf-8')
            result=workload.normalize_as(path,1,3)
            self.assertEqual(result['per_recording_frame']['blas_updates_recorded'],3)
            self.assertEqual(result['totals']['blas_builds_recorded'],0)
            status.write_text(json.dumps({'enabled':True,'dropped_events':1}),encoding='utf-8')
            with self.assertRaises(ValueError):workload.normalize_as(path,1,3)

if __name__=='__main__':unittest.main()
