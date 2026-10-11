"""Counter diagnostics with explicit availability and semantic contracts."""
import argparse,csv,json,statistics
from pathlib import Path

CONTRACTS={
 "radiance_traces":"actual radiance tracing invocation; includes repeated camera-path traversal",
 "visibility_traces":"visibility tracing invocation; not RIS candidate count",
 "opaque_visibility_traversals":"VoxelLight opaque blocker pretest invocation (RayQuery or TraceRay by runtime mode); included in visibility_traces; Caustica field unavailable",
 "ris_candidates":"number of candidates offered to initial RIS evaluation",
 "ris_selected":"reservoirs reaching final visibility/contribution evaluation",
 "closest_hit_invocations":"shader entry count; not equivalent to material decoding",
 "material_surface_decodes":"surface-parameter evaluation calls: VoxelLight terrainSurface (including dynamic-texture branch), Caustica evaluateMaterial in terrain/glass/entity closest hit; bypass paths remain different",
 "ris_positive_candidates":"initial RIS proposals with finite positive contribution weight",
 "bsdf_evals":"explicit evalBsdf/evalGlossy calls, including evalGlossy nested in sampleGlossy",
 "emitter_front_brdf_evals":"Caustica emitter front-facing BRDF branch; excludes sun and manual BSDF paths; not equivalent to bsdf_evals",
}

def normalize(renderer,path,start=None,end=None,extra=None):
 with Path(path).open(encoding="utf-8-sig",newline="") as stream:
  rows=list(csv.DictReader(stream))
 if extra:
  auxiliary={}
  with Path(extra).open(encoding="utf-8-sig",newline="") as stream:
   for row in csv.DictReader(stream):
    frame=int(row["frame"])
    if frame in auxiliary:raise ValueError("Duplicate auxiliary counter frame")
    auxiliary[frame]=row
  for row in rows:
   other=auxiliary.get(int(row["frame"]),{})
   for key,value in other.items():
    if key!="frame" and key in row and row[key]!=value:raise ValueError("Conflicting counter column: "+key)
    row[key]=value
 values={}; frames=set()
 for row in rows:
  frame=int(row["frame"])
  if start is not None and frame<start or end is not None and frame>=end:continue
  if frame in frames:raise ValueError("Duplicate main counter frame")
  frames.add(frame)
  if renderer=="caustica":
   mapping={"radiance_traces":["camera_path_traces","scatter_rays"],"visibility_traces":["visibility_rays"],"ris_candidates":["ris_candidates"],"ris_selected":["ris_selected"],"ris_positive_candidates":["ris_positive_candidates"],"closest_hit_invocations":["closest_hit_invocations"],"emitter_front_brdf_evals":["emitter_front_brdf_evals"],"material_surface_decodes":["material_surface_decodes"]}
  else:
   mapping={"radiance_traces":["radiance_rays"],"visibility_traces":["shadow_rays"],"opaque_visibility_traversals":["opaque_visibility"],"ris_candidates":["ris_candidates_"+str(i) for i in range(6)],"ris_selected":["ris_selected_"+str(i) for i in range(6)],"ris_positive_candidates":["ris_positive_candidates"],"material_surface_decodes":["material_surface_decodes"],"bsdf_evals":["bsdf_evals"]}
  for name,columns in mapping.items():
   if all(column in row and row[column]!="" for column in columns):
    parts=[int(row[column]) for column in columns]
    if any(value<0 for value in parts):raise ValueError("Negative counter")
    sample=sum(parts)
    values.setdefault(name,[]).append(sample)
 return {"schema":1,"renderer":renderer,"sampling_mode":"counters","formal_timing_eligible":False,"frames":len(set(frames)),"counters":{name:{"available":name in values,"contract":contract,"samples":len(values.get(name,[])),"median_per_frame":statistics.median(values[name]) if name in values else None,"range_per_frame":[min(values[name]),max(values[name])] if name in values else None} for name,contract in CONTRACTS.items()},"limitations":["These mappings require shader call-site verification before causal comparisons.","Counter runs are separate from formal timing runs.","Unavailable fields are not zero.","32-bit per-frame atomic counters can wrap; aggregate range alone cannot prove absence of overflow."]}

def normalize_as(path,start,end):
 status=json.loads(Path(str(path)+'.status.json').read_text(encoding='utf-8'))
 if status.get('enabled') is not True or status.get('dropped_events')!=0:raise ValueError('AS instrumentation unavailable or dropped events')
 if start is None or end is None or end<=start:raise ValueError('AS counts require a nonempty half-open recording-frame window')
 columns=('blas_builds_recorded','blas_updates_recorded','tlas_builds_recorded','tlas_updates_recorded')
 totals={column:0 for column in columns};seen=set()
 with Path(path).open(encoding='utf-8-sig',newline='') as stream:
  for row in csv.DictReader(stream):
   frame=int(row['frame'])
   if not start<=frame<end:continue
   if frame in seen:raise ValueError('Duplicate AS recording frame')
   seen.add(frame)
   for column in columns:
    value=int(row[column])
    if value<0:raise ValueError('Negative AS count')
    totals[column]+=value
 return {'available':True,'recording_frames':end-start,'frames_with_commands':len(seen),'semantics':'CPU-recorded AS build-info entries; missing rows are zero only because enabled, lossless sparse export is verified; not GPU completion or elapsed cost','totals':totals,'per_recording_frame':{column:value/(end-start) for column,value in totals.items()}}

def main():
 ap=argparse.ArgumentParser();ap.add_argument("--renderer",choices=["voxellight","caustica"],required=True);ap.add_argument("--input",type=Path,required=True);ap.add_argument("--output",type=Path,required=True);ap.add_argument("--extra",type=Path);ap.add_argument("--as-input",type=Path);ap.add_argument("--frame-start",type=int);ap.add_argument("--frame-end",type=int);a=ap.parse_args()
 result=normalize(a.renderer,a.input,a.frame_start,a.frame_end,a.extra)
 if a.as_input:result['as_counts']=normalize_as(a.as_input,a.frame_start,a.frame_end)
 a.output.write_text(json.dumps(result,indent=2),encoding="utf-8")

if __name__=="__main__":main()
