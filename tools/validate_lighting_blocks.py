import pathlib,json,collections
import numpy as np
import argparse
ap=argparse.ArgumentParser(description='Validate complete candidate, BSDF and RIS frozen GPU block outputs')
ap.add_argument('--inputs',type=pathlib.Path,required=True,help='Dense input directory from prepare_lighting_candidates.py')
ap.add_argument('--capture',type=pathlib.Path,required=True,help='Original captured outputs, including result-3.bin')
ap.add_argument('--stages',type=pathlib.Path,required=True,help='stages.json from build_lighting_blocks.py')
ap.add_argument('--output',type=pathlib.Path,required=True,help='Result directory from replay_lighting.py')
args=ap.parse_args();o=args.output
f=np.fromfile(args.inputs/'feedback.bin',dtype='<u4')
cam=np.fromfile(args.inputs/'camera.bin',dtype='<u4');base=1648+4*int(cam[20])*int(cam[21])*max(1,int(cam[23]));table=(base+2048*128+2048)*4
gold=f[table:table+16384*64].reshape(-1,64);parents=f[base*4:base*4+2048*512].reshape(-1,512)
names=json.loads(args.stages.read_text())
expected=['geometry','emission','full_bsdf','integrand','io_control','geometry_environment','geometry_emitter','emission_environment','emission_area','bsdf_multiscatter','bsdf_fresnel','bsdf_distribution_smith','geometry_with_sequence','sequence','complete_candidate','full_ris','complete_candidate_loop','caustica_target_area']
if names!=expected:raise SystemExit('Unsupported fixture stage schema; update validator before comparing a different layout')
report={};worst=0;discrete=0;all_finite=True
for k,name in enumerate(names):
 actual=np.fromfile(o/f'stage-{k}-table.bin',dtype='<u4').reshape(-1,64)
 mask=np.ones(len(gold),dtype=bool)
 if k not in (0,5,6,12,13,14,15,16):mask&=gold[:,6]!=0
 if k in (5,7):mask&=gold[:,16]==1
 if k==6:mask&=gold[:,16]!=1
 if k in (8,17):mask&=gold[:,16]==4
 if k in (0,5,6,12):ref=gold[:,8:20];cols=list(range(48,60));ints=[8,9]
 elif k in (1,7,8,14):ref=gold[:,20:29];cols=list(range(48,57));ints=[8]
 elif k==2:ref=gold[:,32:36];cols=list(range(48,52));ints=[]
 elif k==3:ref=gold[:,36:39];cols=list(range(48,51));ints=[]
 elif k==13:ref=gold[:,3:4];cols=[48];ints=[]
 elif k==15:ref=parents[gold[:,1],256:272];cols=list(range(48,64));ints=[8,14,15]
 elif k==16:
  old=np.fromfile(args.capture/'result-3.bin',dtype='<u4').reshape(-1,4)
  original=np.fromfile(args.inputs/'original-table.bin',dtype='<u4').reshape(-1,64)
  seed_by_parent={int(row[1]):int(row[5]) for row in original if row[0]!=0}
  ref=np.column_stack((old[gold[:,1],:2],np.array([seed_by_parent[int(p)] for p in gold[:,1]],dtype='<u4')));cols=[48,49,50];ints=[0,2]
 elif k==17:ref=gold[:,44:48];cols=list(range(48,52));ints=[]
 else:
  end=52 if k in (4,10) else 51
  nonfinite=int((~np.isfinite(actual[mask,48:end].view('<f4'))).sum());all_finite&=nonfinite==0
  report[name]={'activeInvocations':int(mask.sum()),'validation':'component diagnostic only; finite output required','nonfinite':nonfinite};continue
 a=actual[mask][:,cols];b=ref[mask];fl=[i for i in range(len(cols)) if i not in ints]
 diff=np.abs(a[:,fl].view('<f4')-b[:,fl].view('<f4'))/np.maximum(1,np.abs(b[:,fl].view('<f4')))
 maxerr=float(diff.max()) if diff.size else 0;finite=bool(np.isfinite(diff).all());wrong=int((a[:,ints]!=b[:,ints]).sum()) if ints else 0
 if k in (0,5,6,12,14):wrong+=int((actual[mask,60]!=gold[mask,5]).sum())+int((actual[mask,61]!=gold[mask,4]).sum())
 report[name]={'activeInvocations':int(mask.sum()),'maxNormalizedFloatError':maxerr,'nonfinite':not finite,'discreteMismatches':wrong,'floatMismatchesAt1e5':int((diff>1e-5).sum())}
 all_finite&=finite;worst=max(worst,maxerr);discrete+=wrong
report['summary']={'maxNormalizedFloatError':worst,'discreteMismatches':discrete,'allFinite':all_finite,'withinUserTolerance':all_finite and worst<1e-2 and discrete==0,'strict1e5':all_finite and worst<=1e-5 and discrete==0}
(o/'validation.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
if not report['summary']['withinUserTolerance']:raise SystemExit('Replay differs beyond the accepted tolerance or in discrete state')
