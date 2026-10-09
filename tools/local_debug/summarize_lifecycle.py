"""Summarize native destruction boundaries, retaining unmatched starts as incomplete."""
import argparse,datetime,json,pathlib,re
parser=argparse.ArgumentParser();parser.add_argument('log',type=pathlib.Path);parser.add_argument('--output',required=True,type=pathlib.Path);args=parser.parse_args()
pattern=re.compile(r'^\[(\d\d:\d\d:\d\d)\].*RT lifecycle id=(\d+) stage=(\S+) operation=(pipeline.begin|pipeline.end)')
pending={};spans=[]
for line in args.log.read_text(encoding='utf-8',errors='replace').splitlines():
    match=pattern.search(line)
    if not match:continue
    stamp,identity,stage,operation=match.groups();clock=datetime.datetime.strptime(stamp,'%H:%M:%S')
    if operation=='pipeline.begin':pending[identity]=(clock,stage)
    elif identity in pending:
        start,original=pending.pop(identity)
        elapsed=(clock-start).total_seconds()%86400
        spans.append({'id':identity,'stage':original,'durationSecondsRounded':elapsed})
report={'scope':'Native pipeline destroy only; log timestamps have one-second precision. Missing starts/ends are not zero latency.','completed':spans,'unmatchedBegins':{identity:{'start':str(value[0].time()),'stage':value[1]} for identity,value in pending.items()}}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps({'completed':len(spans),'unmatched':len(pending),'longest':sorted(spans,key=lambda r:r['durationSecondsRounded'],reverse=True)[:5]},indent=2))
