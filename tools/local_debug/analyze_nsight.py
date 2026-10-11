"""Analyze GPU marker scopes, preserving coverage and tool-loss diagnostics."""
import argparse,collections,json,pathlib,sqlite3,statistics

def stats(values):
 values=sorted(values)
 if not values:return None
 position=(len(values)-1)*.95;i=int(position);p95=values[i]+(values[min(i+1,len(values)-1)]-values[i])*(position-i)
 return {'samples':len(values),'p50_ms':statistics.median(values),'p95_ms':p95,'min_ms':values[0],'max_ms':values[-1]}

def main(path,output):
 c=sqlite3.connect(path)
 tables={r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
 if not {'VULKAN_WORKLOAD','VULKAN_DEBUG_API'}<=tables:raise ValueError('No Vulkan GPU marker data')
 diagnostics=[r[0] for r in c.execute('SELECT text FROM DIAGNOSTIC_EVENT')]
 rows=c.execute('SELECT w.start,w.end,s.value,w.contextId FROM VULKAN_WORKLOAD w JOIN StringIds s ON s.id=w.textId WHERE length(s.value)<100 ORDER BY w.start').fetchall()
 rows=[r for r in rows if r[2].startswith('vulkan_rt_')]
 expected=dict(c.execute('SELECT s.value,count(*) FROM VULKAN_DEBUG_API w JOIN StringIds s ON s.id=w.textId WHERE length(s.value)<100 GROUP BY w.textId'))
 scopes=collections.defaultdict(list)
 for start,end,name,context in rows:
  if end<=start:raise ValueError('Nonpositive GPU scope interval')
  scopes[name].append((end-start)/1e6)
 result={'schema':2,'sampling_mode':'nsight','formal_timing_eligible':False,'diagnostics':diagnostics,'stages':{name:{'marker_event_duration':stats(values),'shader_execution_time':None,'gpu_markers':len(values),'cpu_markers':expected.get(name),'coverage':len(values)/expected[name] if expected.get(name) else None} for name,values in scopes.items()},'limitations':['The installed tracer exports queue-submit and debug-begin events, not individual TraceRaysKHR workloads.','Debug-begin event duration is not shader execution time.','Intervals between ordered GPU begin markers include execution and dependency scheduling; they are not idle gaps.','CPU marker durations are not GPU execution time.','Dropped unlabelled workload events prevent a complete critical-path or idle-time calculation.','Capture-end in-flight scopes can lower coverage.','Nsight layer changes overhead; these times do not replace formal timestamps.']}
 primary=[r for r in rows if r[2]=='vulkan_rt_primary'];complete=[];counter_excluded=0;incomplete=0
 names=['vulkan_rt_primary']+['vulkan_rt_bounce_'+str(i) for i in range(1,6)]+['vulkan_rt_sample_resolve','vulkan_rt_deterministic_guides','vulkan_rt_dlss_rr']
 for current,following in zip(primary,primary[1:]):
  inside=[r for r in rows if current[0]<=r[0]<following[0]]
  if any(r[2].startswith('vulkan_rt_visibility_') for r in inside):counter_excluded+=1;continue
  selected={name:[r for r in inside if r[2]==name] for name in names}
  if any(len(values)!=1 for values in selected.values()):incomplete+=1;continue
  frame={names[i]+'_begin_to_next_begin':(selected[names[i+1]][0][0]-selected[names[i]][0][0])/1e6 for i in range(len(names)-1)}
  frame['transport_begin_to_resolve_begin']=(selected['vulkan_rt_sample_resolve'][0][0]-selected['vulkan_rt_bounce_1'][0][0])/1e6
  frame['primary_begin_to_rr_begin']=(selected['vulkan_rt_dlss_rr'][0][0]-selected['vulkan_rt_primary'][0][0])/1e6
  complete.append(frame)
 result['complete_non_replay_sequences']={'samples':len(complete),'visibility_replay_sequences_excluded':counter_excluded,'incomplete_sequences':incomplete,'begin_boundary_intervals':{name:stats([f[name] for f in complete]) for name in complete[0]} if complete else {},'semantics':'sequence between successive GPU Primary labels; full stage presence required; replay labels excluded; beginning-boundary intervals include execution and dependency scheduling, not standalone kernel times or idle gaps'}
 output.write_text(json.dumps(result,indent=2),encoding='utf-8')
 print(json.dumps(result['complete_non_replay_sequences'],indent=2))

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('sqlite',type=pathlib.Path);ap.add_argument('--output',type=pathlib.Path,required=True);a=ap.parse_args();main(a.sqlite,a.output)
