"""Bounded recovery for the owned debug JVM; never treats a hung run as a benchmark pass."""
import ctypes, datetime, json, pathlib, subprocess, sys, time, zipfile
from ctypes import wintypes
state=pathlib.Path(sys.argv[1]);record=json.loads((state/'process.json').read_text());pid=int(record['pid'])
kernel=ctypes.windll.kernel32
kernel.OpenProcess.restype=wintypes.HANDLE
kernel.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)]
kernel.TerminateProcess.argtypes=[wintypes.HANDLE,wintypes.UINT]
kernel.CloseHandle.argtypes=[wintypes.HANDLE]
kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME)]
handle=kernel.OpenProcess(0x1001,False,pid)
if not handle:sys.exit(0)
created,exited,system,user=(wintypes.FILETIME() for _ in range(4))
kernel.GetProcessTimes(handle,ctypes.byref(created),ctypes.byref(exited),ctypes.byref(system),ctypes.byref(user))
creation=(created.dwHighDateTime<<32)|created.dwLowDateTime
if creation!=record.get('creationFiletime'):
    kernel.CloseHandle(handle);raise SystemExit('Watchdog process identity mismatch')
eventfile=state/'agent-events.log';deadline=300;last=time.monotonic();mtime=eventfile.stat().st_mtime_ns if eventfile.exists() else 0;captured=set()
try:
    while True:
        exitcode=wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle,ctypes.byref(exitcode)) or exitcode.value!=259:break
        current=eventfile.stat().st_mtime_ns if eventfile.exists() else 0
        if current!=mtime:mtime=current;last=time.monotonic();captured.clear()
        silent=time.monotonic()-last
        for threshold in (240,270):
            if silent>=threshold and threshold not in captured:
                captured.add(threshold)
                with (state/f'watchdog-{pid}-{threshold}-threads.txt').open('w',encoding='utf-8') as output:
                    try:subprocess.run([str(pathlib.Path(record['java']).with_name('jcmd.exe')),str(pid),'Thread.print'],stdout=output,stderr=subprocess.STDOUT,timeout=15)
                    except Exception as error:output.write(str(error))
        if silent>=deadline:
            evidence={'pid':pid,'completed':False,'reason':'No driver progress for 300 seconds; owned dedicated JVM terminated. External liveness bound, not a relaxed benchmark gate.','utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'suites':record['suites']}
            events=eventfile.read_text(encoding='utf-8',errors='replace') if eventfile.exists() else ''
            evidence['lastEvents']=events.splitlines()[-12:]
            (state/f'watchdog-{pid}.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
            kernel.TerminateProcess(handle,124)
            break
        time.sleep(5)
finally:kernel.CloseHandle(handle)
