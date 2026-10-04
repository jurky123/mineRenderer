import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('report', Path(__file__).parents[1] / 'rt_baseline_report.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class BaselineReportTest(unittest.TestCase):
    def test_missing_gpu_and_placeholder_cpu_are_not_zero_measurements(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'input.csv'
            file.write_text('frame,mode,pass_cpu_submission_ns,pass_gpu_ns\n1,rt_specular,0,1000000\n2,rt_specular,0,\n3,rt_specular,0,3000000\n')
            rows = report.summarize([file])
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]['samples'], 2)
            self.assertEqual(rows[0]['p50_ms'], 2)
            self.assertAlmostEqual(rows[0]['p95_ms'], 2.9)

    def test_percentiles_do_not_depend_on_input_order(self):
        self.assertEqual(report.percentile([3, 1, 2], .5), 2)
        self.assertEqual(report.percentile([7], .95), 7)


if __name__ == '__main__':
    unittest.main()
