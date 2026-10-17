"""Exercise advanceHit with the pre-decode boundary; hardware reorder is a CPU no-op."""
import argparse,json,subprocess
from build_vulkan_rt import ROOT,tool

def run(args):return subprocess.check_output([str(a) for a in args],text=True)
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--baseline-dir',required=True);ap.add_argument('--output',default='build/ser-boundary-parity');args=ap.parse_args();out=ROOT/args.output;out.mkdir(parents=True,exist_ok=True)
 slang,cxx=tool('slangc','SLANGC'),tool('c++','CXX');results=[]
 for label,common in [('baseline',(ROOT/args.baseline_dir/'common').resolve()),('candidate',ROOT/'shaders/rt/common')]:
  folder=out/label;folder.mkdir(exist_ok=True)
  fixture=(ROOT/'shaders/rt/tests/visibility_transport.slang').read_text(encoding='utf-8').replace('"../common/','"'+common.as_posix()+'/');(folder/'fixture.slang').write_text(fixture,encoding='utf-8')
  run([slang,folder/'fixture.slang','-DRT_SER_BOUNDARY_FIXTURE=1','-target','cpp','-entry','visibility_transport','-stage','compute','-o',folder/'visibility_transport.cpp'])
  run([cxx,'-std=c++17','-O2','-I'+str(folder),ROOT/'tools/native/vulkan_visibility_transport.cpp','-o',folder/'runner.exe'])
  text=run([folder/'runner.exe']);results.append(text);print(label,text.strip(),flush=True)
 assert results[0]==results[1]
 (out/'result.json').write_text(json.dumps({'fixturesPassed':True,'baseline':results[0],'candidate':results[1],'scope':'CPU order/transport checks, not hardware SER scheduling equivalence'},indent=2))
if __name__=='__main__':main()
