import array, json, pathlib, tempfile, unittest, zipfile
from alpha49 import compare,fixture
class AcceptanceTest(unittest.TestCase):
 def test_capture_contract_and_errors(self):
  with tempfile.TemporaryDirectory() as directory:
   d=pathlib.Path(directory)
   for i,camera in enumerate(('0,0,0','0,0,0','1,0,0')):
    with zipfile.ZipFile(d/f'{i}.zip','w') as z:
     z.writestr('capture.txt',f'camera={camera} workingColor=linear-sRGB preExposure=1 depth=reverse-Z motion=previous-current-input-pixels jitter=0,0 clip=identity\nRaw files: float32\ninput-hdr 1x1 channels=4\n')
     z.writestr('input-hdr.f32',array.array('f',[float(i),0,0,1]).tobytes())
   r=compare(d/'0.zip',d/'1.zip');self.assertEqual(.25,r['channels']['input-hdr']['mae']);self.assertTrue(r['jitterMatched'])
   with self.assertRaises(ValueError):compare(d/'0.zip',d/'2.zip')
 def test_five_scene_fixture(self):
  with tempfile.TemporaryDirectory() as directory:
   d=pathlib.Path(directory);fixture(d);self.assertEqual(5,len(json.loads((d/'scenes.json').read_text())['camera']));self.assertIn('water',(d/'setup.mcfunction').read_text());self.assertIn('tp @e[tag=vl49_mirror] 36 99 0',(d/'pose-1.mcfunction').read_text());self.assertIn('tp @e[tag=vl49_entity] 68 99 3',(d/'pose-1.mcfunction').read_text())
if __name__=='__main__':unittest.main()
