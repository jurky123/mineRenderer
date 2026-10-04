import importlib.util
import unittest
from pathlib import Path
spec = importlib.util.spec_from_file_location('report', Path(__file__).parents[1] / 'rt_compile_report.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

class CompileReportTest(unittest.TestCase):
    def test_stalled_task_and_warm_cache_zero_are_distinguished(self):
        value = report.summarize('''VoxelLight compile start module=rt_hit bytes=42
VoxelLight compile finish module=rt_hit elapsed_ms=0 timeout=0
VoxelLight compile start module=rt_realtime bytes=123
VoxelLight task start module=rt_realtime id=1 key=abc
VoxelLight task start module=rt_realtime id=2 key=def
VoxelLight task finish module=rt_realtime id=1 active_ms=20 children=1
''')
        self.assertEqual(0, value['modules'][0]['elapsed_ms'])
        self.assertIsNone(value['modules'][1]['elapsed_ms'])
        self.assertEqual(2, value['unfinished_tasks'][0]['id'])
        self.assertEqual(1, len(value['unfinished_tasks']))

if __name__ == '__main__':
    unittest.main()
