"""Read-only, device-wide telemetry while the exact owned debug JVM is alive."""
import ctypes,datetime,json,pathlib,shutil,subprocess,sys,time
from ctypes import wintypes
state=pathlib.Path(sys.argv[1]);record=json.loads((state/'process.json').read_text())
kernel=ctypes.windll.kernel32
kernel.OpenProcess.restype=wintypes.HANDLE
kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,*([ctypes.POINTER(wintypes.FILETIME)]*4)]
kernel.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)]
kernel.CloseHandle.argtypes=[wintypes.HANDLE]
handle=kernel.OpenProcess(0x1000,False,record['pid'])
if not handle:sys.exit(0)
try:
    created,exited,system,user=(wintypes.FILETIME() for _ in range(4))
    if not kernel.GetProcessTimes(handle,ctypes.byref(created),ctypes.byref(exited),ctypes.byref(system),ctypes.byref(user)):raise RuntimeError('GetProcessTimes failed')
    if ((created.dwHighDateTime<<32)|created.dwLowDateTime)!=record['creationFiletime']:raise RuntimeError('Telemetry process identity mismatch')
    tool=shutil.which('nvidia-smi')
    if not tool:raise RuntimeError('nvidia-smi unavailable; telemetry is missing, not zero')
    query='timestamp,index,name,clocks.gr,clocks.mem,power.draw,temperature.gpu,utilization.gpu,memory.used,clocks_event_reasons.sw_thermal_slowdown,clocks_event_reasons.hw_thermal_slowdown,clocks_event_reasons.sw_power_cap'
    append='--append' in sys.argv[2:]
    with (state/'gpu-telemetry.csv').open('a' if append else 'w',encoding='utf-8') as output:
        if not append:output.write(query+'\n');output.flush()
        while True:
            code=wintypes.DWORD()
            if not kernel.GetExitCodeProcess(handle,ctypes.byref(code)) or code.value!=259:break
            try:
                result=subprocess.run([tool,'--query-gpu='+query,'--format=csv,noheader'],capture_output=True,text=True,timeout=5,creationflags=subprocess.CREATE_NO_WINDOW)
            except subprocess.TimeoutExpired:
                with (state/'gpu-telemetry-gaps.jsonl').open('a',encoding='utf-8') as gaps:
                    gaps.write(json.dumps({'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'error':'nvidia-smi timeout; telemetry unavailable for this interval'})+'\n')
                time.sleep(2);continue
            if result.returncode:raise RuntimeError(result.stderr.strip())
            output.write(result.stdout);output.flush();time.sleep(1)
finally:kernel.CloseHandle(handle)
