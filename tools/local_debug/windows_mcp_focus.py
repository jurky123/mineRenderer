"""Focus an owned client through the installed Windows-MCP stdio server."""
import argparse,asyncio,json,os,pathlib,time
from mcp import ClientSession,StdioServerParameters
from mcp.client.stdio import stdio_client

async def main(state,executable):
    record=json.loads((state/'process.json').read_text(encoding='utf-8'));pid=int(record['pid'])
    env=dict(os.environ,ANONYMIZED_TELEMETRY='false',POSTHOG_API_KEY='',WINDOWS_MCP_DISABLE_FLASH='true',PYTHONIOENCODING='utf-8')
    server=StdioServerParameters(command=str(executable),args=['serve'],env=env,cwd=str(executable.parents[2]))
    deadline=time.monotonic()+180
    with (state/'windows-mcp-focus-server.log').open('w',encoding='utf-8') as log:
      async with stdio_client(server,errlog=log) as (read,write):
       async with ClientSession(read,write,read_timeout_seconds=60) as session:
        await session.initialize()
        await session.call_tool('Snapshot',{'use_vision':False,'use_ui_tree':True})
        while time.monotonic()<deadline:
            result=await session.call_tool('PowerShell',{'command':f'$taskProcess=Get-Process -Id {pid} -ErrorAction Stop; [pscustomobject]@{{Id=$taskProcess.Id; MainWindowTitle=$taskProcess.MainWindowTitle; CreationFiletime=$taskProcess.StartTime.ToFileTimeUtc()}} | ConvertTo-Json -Compress','timeout':10})
            content='\n'.join(c.text for c in result.content if c.type=='text').replace('\r\n','\n')
            if result.is_error:
                if 'USER_CONTROL' in content:await asyncio.sleep(11);continue
                raise RuntimeError('Owned window query failed: '+content)
            payload=content.partition('Response: ')[2].partition('\n\nStatus Code:')[0].strip()
            window=json.loads(payload)
            if window['Id']!=pid or window['CreationFiletime']!=record['creationFiletime']:raise RuntimeError('Owned window process identity mismatch')
            title=window.get('MainWindowTitle','')
            if not title:await asyncio.sleep(2);continue
            await session.call_tool('Snapshot',{'use_vision':False,'use_ui_tree':True})
            switched=await session.call_tool('App',{'mode':'switch','name':title})
            evidence='\n'.join(c.text for c in switched.content if c.type=='text')
            if 'USER_CONTROL' in evidence:await asyncio.sleep(11);continue
            if switched.is_error or not ('Switched to' in evidence or 'switched to' in evidence):raise RuntimeError('Windows-MCP did not verify foreground: '+evidence)
            (state/'windows-mcp-focus.json').write_text(json.dumps({'pid':pid,'creationFiletime':record['creationFiletime'],'window_title':title,'transport':'installed Windows-MCP stdio','verified_by':'App.switch foreground HWND verification','result':evidence},indent=2),encoding='utf-8')
            print(f'Windows-MCP verified foreground for owned client {pid}',flush=True)
            return
        raise RuntimeError('Windows-MCP could not focus the owned client within 180 seconds')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('state',type=pathlib.Path);parser.add_argument('executable',type=pathlib.Path);args=parser.parse_args()
    asyncio.run(main(args.state.resolve(),args.executable.resolve()))
