"""Collect a separate counter-free trace triggered by a real ready sample window."""
import argparse,datetime,json,pathlib,subprocess,sys,time,os
from paired_runs import properties,wait_owned

NSYS=pathlib.Path('C:/Program Files/NVIDIA Corporation/Nsight Systems 2024.5.1/target-windows-x64/nsys.exe')

def target(state,renderer):
 launcher=pathlib.Path(__file__).with_name('local_debug.py')
 subprocess.run([sys.executable,str(launcher),'launch','--renderer',renderer,'--state-dir',str(state),'--suites','production','--cameras','multi_light'],check=True)
 record=json.loads((state/'process.json').read_text())
 if record['samplingMode']!='nsight' or record['formalTimingEligible']:raise ValueError('Nsight classification incorrect')
 wait_owned(record)

def collect(root,state,renderer,duration=2):
 session='alpha50'+renderer+str(int(time.time()))
 out=root/'outputs/alpha.50/nsight';out.mkdir(exist_ok=True)
 started=datetime.datetime.now(datetime.timezone.utc)
 log=(out/f'{session}-launch.log').open('w',encoding='utf-8')
 env=dict(os.environ,VOXELLIGHT_NSIGHT_SESSION=session)
 # The local Nsight installation has no registered implicit-layer manifest.
 # Restrict layer discovery/activation to this diagnostic process tree.
 env.update(VK_LAYER_PATH=str(NSYS.parent/'vulkan-layers'),VK_INSTANCE_LAYERS='VK_LAYER_NV_nsight-sys',ENABLE_VK_LAYER_NV_nsight_sys='1',VK_LOADER_DEBUG='error,warn,layer')
 env.pop('DISABLE_VK_LAYER_NV_nsight_sys',None)
 process=subprocess.Popen([sys.executable,str(pathlib.Path(__file__).resolve()),'--target','--renderer',renderer,'--state',str(state)],env=env,stdout=log,stderr=subprocess.STDOUT)
 deadline=time.monotonic()+240;record=None
 while time.monotonic()<deadline:
  if process.poll() is not None:raise RuntimeError('Nsight launch exited before readiness')
  p=state/'process.json'
  if p.exists():
   candidate=json.loads(p.read_text())
   created=candidate['creationFiletime']/10000000-11644473600
   if created>=started.timestamp():record=candidate;break
  time.sleep(1)
 if record is None:raise RuntimeError('No fresh owned process')
 focus=subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('windows_mcp_focus.py')),str(state),str(root/'work/Windows-MCP/.venv/Scripts/windows-mcp.exe')])
 begin=state/f'{renderer}-multi_light-sample.begin.properties'
 while time.monotonic()<deadline:
  if begin.exists():
   fields=properties(begin)
   if datetime.datetime.fromisoformat(fields['utc'].replace('Z','+00:00'))>=started:
    if fields.get('throttleReason')!='NONE' or fields.get('focused',fields.get('timingValid'))!='true':raise ValueError('Readiness observation invalid')
    break
  if process.poll() is not None:raise RuntimeError('Client ended before sample')
  time.sleep(1)
 else:raise RuntimeError('No fresh ready sample')
 if renderer=='voxellight':
  while time.monotonic()<deadline:
   events=(state/'agent-events.log').read_text(encoding='utf-8').splitlines()
   if any(' SAMPLE;' in line and datetime.datetime.fromisoformat(line.split(' ',1)[0].replace('Z','+00:00'))>=started for line in events):break
   if process.poll() is not None:raise RuntimeError('Client ended before actual benchmark sampling')
   time.sleep(.5)
  else:raise RuntimeError('No actual benchmark SAMPLE evidence')
 evidence={'session':session,'sampling_mode':'nsight','formal_timing_eligible':False,'process':record,'ready_observation':fields,'focus_helper_exit':focus.returncode,'collection_start_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 with (out/f'{session}-collect.log').open('w',encoding='utf-8') as capture:
  result=subprocess.run([str(NSYS),'start','--session='+session,'--sample=none','--cpuctxsw=none','--output='+str(out/session)],stdout=capture,stderr=subprocess.STDOUT)
  if result.returncode==0:
   time.sleep(duration)
   stop=subprocess.run([str(NSYS),'stop','--session='+session],stdout=capture,stderr=subprocess.STDOUT)
   evidence['stop_exit_code']=stop.returncode
   evidence['collection_stop_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
   if stop.returncode:raise RuntimeError('Nsight stop failed')
 evidence['start_exit_code']=result.returncode
 (out/f'{session}-identity.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
 if result.returncode:raise RuntimeError('Nsight collection failed; inspect evidence')
 process.wait(timeout=900);log.close()
 if process.returncode:raise RuntimeError('Nsight target failed')
 print(session,flush=True)

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('--target',action='store_true');ap.add_argument('--workspace',type=pathlib.Path);ap.add_argument('--state',type=pathlib.Path,required=True);ap.add_argument('--renderer',choices=('caustica','voxellight'),required=True);a=ap.parse_args()
 target(a.state.resolve(),a.renderer) if a.target else collect(a.workspace.resolve(),a.state.resolve(),a.renderer)
