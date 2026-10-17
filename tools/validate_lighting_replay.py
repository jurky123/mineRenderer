import pathlib,json,numpy as np,csv
import argparse
parser=argparse.ArgumentParser(description='Compare standalone GPU replay outputs to captured production outputs; requires numpy.')
parser.add_argument('--capture',type=pathlib.Path,required=True)
parser.add_argument('--schema',type=pathlib.Path,required=True)
parser.add_argument('--output',type=pathlib.Path,required=True)
args=parser.parse_args();cap=args.capture;out=args.output;types=json.loads(args.schema.read_text())['types']
def leaves(t,n):
 if t in types:return [x for a,b in types[t] for x in leaves(a,n+'.'+b)]
 if t.startswith('float') and len(t)>5:return [('float',n+'.'+c) for c in 'xyzw'[:int(t[-1])]]
 return [(t,n)]
cam=np.fromfile(cap/'camera.bin',dtype='<u4');w,h,f,s=map(int,cam[20:]);fb=np.fromfile(cap/'feedback.bin',dtype='<u4').reshape(-1,4);n=min(2048,int(fb[1634,0]));rec=fb[1648+4*w*h*s:1648+4*w*h*s+n*128].reshape(n,512)
actual=np.fromfile(out/'actual-records.bin',dtype='<u4').reshape(-1,512);rows=list(csv.DictReader((out/'timings.csv').open()));result=[];mismatches=[]
for k in range(4):
 a=np.fromfile(out/f'result-{k}.bin',dtype='<u4').reshape(-1,4);selected=np.flatnonzero(rec[:,0]==(k if k<3 else 0));a=a[selected];assert (a[:,2]==k).all() and (a[:,3]==1).all();assert np.isfinite(a[:,1].copy().view('<f4')).all();errors=int(a[:,0].sum()) if k<3 else None
 if k<3:
  fields=leaves(['DirectReservoir','PreparedBsdf','BsdfSample'][k],'value')+([('uint','seed')] if k!=1 else [])
  for i,(t,name) in enumerate(fields):
   expected=rec[selected,256+i].copy();got=actual[selected,384+i].copy()
   if t=='float':
    expected=expected.view('<f4');got=got.view('<f4');err=np.abs(got-expected)/np.maximum(1,np.abs(expected));bad=np.flatnonzero(~np.isfinite(got)|(err>1e-5))
   else:bad=np.flatnonzero(expected!=got)
   for b in bad:mismatches.append({'kind':k,'record':int(selected[b]),'field':name,'expected':float(expected[b]),'actual':float(got[b]),'normalizedError':float(err[b]) if t=='float' else None})
 vals=np.array([float(row['gpu_ms']) for row in rows if int(row['stage'])==k]);result.append({'stage':k,'records':len(selected),'mismatchesAt1e5':errors,'p50Ms':float(np.percentile(vals,50)),'p95Ms':float(np.percentile(vals,95)),'samples':len(vals)})
report={'standaloneWithoutMinecraft':True,'device':(out/'device.txt').read_text(),'stages':result,'mismatchDetails':mismatches,'strictGoldenPassed':not mismatches,'withinUserNumericTolerance':all(m['normalizedError'] is not None and m['normalizedError']<1e-2 for m in mismatches),'limits':'Compute entry and additional diagnostic output stores; small frozen warm-cache batch, not production frame attribution or cross-renderer benchmark.'};(out/'validation.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
