"""Separate workload diagnostics; never included in formal timing comparisons."""
import argparse,json,pathlib,subprocess,sys
from paired_runs import CAMERAS,archive,wait_owned

def main(root,renderers=('caustica','voxellight')):
    launcher=pathlib.Path(__file__).with_name('local_debug.py')
    mcp=root/'work/Windows-MCP/.venv/Scripts/windows-mcp.exe'
    for renderer in renderers:
        state=root/f'work/alpha50-{renderer}-counters'
        subprocess.run([sys.executable,str(launcher),'launch','--renderer',renderer,'--state-dir',str(state),'--suites','production','--cameras',','.join(CAMERAS)],check=True,stdout=sys.stdout,stderr=sys.stderr)
        record=json.loads((state/'process.json').read_text(encoding='utf-8'))
        if record['samplingMode']!='counters' or record['formalTimingEligible']:raise RuntimeError('Counter run misclassified')
        focus=subprocess.run([sys.executable,str(launcher.with_name('windows_mcp_focus.py')),str(state),str(mcp)],stdout=sys.stdout,stderr=sys.stderr)
        if focus.returncode:print('Windows-MCP focus helper failed; actual observation validation remains mandatory',flush=True)
        print(f'Counter diagnostic: {renderer} PID {record["pid"]}',flush=True)
        wait_owned(record)
        archive(state,root/f'outputs/alpha.50/计数诊断/{renderer}')
        print(f'Counter diagnostic: {renderer} archived; excluded from formal timing',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--workspace',type=pathlib.Path,required=True);parser.add_argument('--renderer',choices=('caustica','voxellight'));args=parser.parse_args();main(args.workspace.resolve(),(args.renderer,) if args.renderer else ('caustica','voxellight'))
