"""Sequential independent client runs; archive evidence and reject camera drift."""
import argparse, ctypes, datetime, json, pathlib, shutil, subprocess, sys, time
from ctypes import wintypes

CAMERAS = ('terrain','complex_building','multi_light','glass_water','dynamic_entity')

def properties(path):
    result={}
    for line in path.read_text(encoding='utf-8').splitlines():
        if line and not line.startswith(('#','!')) and '=' in line:
            key,value=line.split('=',1)
            result[key.replace('\\:',':')]=value.replace('\\:',':').replace('\\=', '=')
    return result

def wait_owned(record):
    kernel=ctypes.windll.kernel32
    kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)]
    kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,*([ctypes.POINTER(wintypes.FILETIME)]*4)]
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=None
    for attempt in range(40):
        handle=kernel.OpenProcess(0x1000,False,record['pid'])
        if handle:break
        time.sleep(0.5)
    if not handle:raise RuntimeError('Cannot obtain client process handle; do not infer completion')
    try:
        times=[wintypes.FILETIME() for _ in range(4)]
        if not kernel.GetProcessTimes(handle,*[ctypes.byref(t) for t in times]): raise RuntimeError('Cannot verify process')
        if (times[0].dwHighDateTime<<32)|times[0].dwLowDateTime != record['creationFiletime']: raise RuntimeError('Process identity changed')
        deadline=time.monotonic()+1800
        while True:
            code=wintypes.DWORD()
            if not kernel.GetExitCodeProcess(handle,ctypes.byref(code)):raise RuntimeError('Cannot read process status')
            if code.value!=259:
                if code.value!=0:raise RuntimeError(f'Client exited {code.value}')
                return
            if time.monotonic()>deadline:raise RuntimeError('Client exceeded paired-run time bound')
            time.sleep(5)
    finally:kernel.CloseHandle(handle)

def archive(state,target):
    target.mkdir(parents=True,exist_ok=False)
    for p in state.iterdir():
        if p.is_file() and p.suffix in ('.json','.jsonl','.properties','.csv','.log','.args','.jar'):
            shutil.copy2(p,target/p.name)
    record=json.loads((state/'process.json').read_text(encoding='utf-8'))
    renderer=record['renderer']
    raw=state/'game'/('rt-frame-stats' if renderer=='caustica' else 'benchmark-results/voxellight')
    shutil.copytree(raw,target/'raw')
    errors=[]
    fixture=properties(state/'fixture-verified.properties')
    for camera in CAMERAS:
        start=properties(state/f'{renderer}-{camera}-sample.begin.properties')
        end=properties(state/f'{renderer}-{camera}-sample.end.properties')
        for phase,fields in [('begin',start),('end',end)]:
            observed=datetime.datetime.fromisoformat(fields['utc'].replace('Z','+00:00')).timestamp()
            launched=record['creationFiletime']/10_000_000-11644473600
            if observed<launched:errors.append(f'{camera}:{phase}:stale evidence')
            for axis in ('xRot','yRot'):
                if abs(float(fields['actualCamera.'+axis]))>0.00001:errors.append(f'{camera}:{phase}:{axis} drift')
            if fields.get('throttleReason')!='NONE':errors.append(f'{camera}:{phase}:throttled')
            if fields.get('scene.dayTime')!=fixture.get('scene.dayTime'):errors.append(f'{camera}:{phase}:world clock changed')
            if renderer=='caustica' and fields.get('timingValid')!='true':errors.append(f'{camera}:{phase}:invalid observation')
            if renderer=='voxellight' and fields.get('focused')!='true':errors.append(f'{camera}:{phase}:unfocused')
        for key in ('actualCamera.pos','actualCamera.xRot','actualCamera.yRot'):
            if start[key]!=end[key]:errors.append(f'{camera}:{key} changed')
    (target/'observation-validation.json').write_text(json.dumps({'valid':not errors,'errors':errors},indent=2),encoding='utf-8')
    if errors:raise RuntimeError('Observation rejected: '+str(errors))

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--workspace',type=pathlib.Path,required=True);ap.add_argument('--first-running',action='store_true');ap.add_argument('--start-pair',type=int,default=2);ap.add_argument('--pairs',type=int,default=3);ap.add_argument('--skip-first',action='store_true');ap.add_argument('--windows-mcp-executable',type=pathlib.Path);args=ap.parse_args()
    root=args.workspace.resolve();launcher=pathlib.Path(__file__).with_name('local_debug.py')
    if not 1<=args.pairs<=10:raise ValueError('Expected 1..10 independent pairs')
    sequence=[(args.start_pair+i,renderer) for i in range(args.pairs) for renderer in (('caustica','voxellight') if (args.start_pair+i)%2==0 else ('voxellight','caustica'))]
    if args.skip_first:sequence=sequence[1:]
    for index,(pair,renderer) in enumerate(sequence):
        state=root/f'work/alpha50-{renderer}-formal'
        if not (index==0 and args.first_running):
            subprocess.run([sys.executable,str(launcher),'launch','--renderer',renderer,'--state-dir',str(state),'--suites','production','--cameras',','.join(CAMERAS)],check=True)
        record=json.loads((state/'process.json').read_text(encoding='utf-8'))
        if args.windows_mcp_executable:
            subprocess.run([sys.executable,str(launcher.with_name('windows_mcp_focus.py')),str(state),str(args.windows_mcp_executable)],check=True)
        print(f'Pair {pair}: {renderer} PID {record["pid"]}',flush=True)
        wait_owned(record)
        archive(state,root/f'outputs/alpha.50/正式测量/round{pair}/{renderer}')
        print(f'Pair {pair}: {renderer} archived and observation checks passed',flush=True)

if __name__=='__main__':main()
