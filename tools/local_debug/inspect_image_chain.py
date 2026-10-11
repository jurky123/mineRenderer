"""Normalize capture color and row direction; previews are not blur ground truth."""
import argparse,json,pathlib,zipfile,hashlib,shutil
import numpy as np
from PIL import Image,ImageDraw
from paired_runs import properties

def main(root):
 folder=root/'outputs/alpha.50/首次图像链验证';caustica=folder/'terrain'
 out=folder/'normalized';out.mkdir(exist_ok=True)
 meta=properties(caustica/'capture.properties');exposure=float(meta['preExposure'])
 matrix=np.array([[1.70505095,-.62179214,-.08325887],[-.13025641,1.14080477,-.01054832],[-.02400336,-.12896897,1.15297234]])
 results={};images={}
 with zipfile.ZipFile(folder/'voxellight-terrain-endpoint.zip') as z:
  for name,vname,h,w in [('pre_rr_hdr','input-hdr',336,524),('post_rr_hdr','rr-output',672,1048),('normal_roughness','normal-roughness',336,524),('diffuse_albedo','diffuse-albedo',336,524)]:
   c=np.fromfile(caustica/(name+'.f32'),dtype='<f4').reshape(h,w,4)[...,:3]
   v=np.frombuffer(z.read(vname+'.f32'),dtype='<f4').reshape(h,w,4)[...,:3]
   if name in ('pre_rr_hdr','post_rr_hdr'):c=c/exposure@matrix.T
   elif name=='diffuse_albedo':c=c@matrix.T
   for renderer,array in [('caustica',c),('voxellight',v)]:
    if not np.isfinite(array).all():raise ValueError('Nonfinite capture')
    np.save(out/f'{renderer}-{name}-linear-709.npy',array[::-1])
    results[renderer+'-'+name]={'nonfinite':0,'min':float(array.min()),'max':float(array.max()),'negative_components':int((array<0).sum())}
    images[renderer+'-'+name]=array
 final=np.fromfile(caustica/'final_display.u8',dtype='u1').reshape(672,1048,4)[...,:3]/255
 hdr=images['caustica-post_rr_hdr'];luma=np.array([.2126,.7152,.0722])
 final_luma=final@luma;hdr_luma=np.log1p(np.maximum(hdr@luma,0))
 corr=lambda a,b:float(np.corrcoef(a.ravel(),b.ravel())[0,1])
 results['orientation_evidence']={'caustica_hdr_vs_final_luminance_correlation_raw':corr(hdr_luma,final_luma),'caustica_hdr_vs_final_luminance_correlation_flipped':corr(hdr_luma[::-1],final_luma),'normal_cross_renderer_mean_absolute_error_raw':float(np.abs(images['caustica-normal_roughness']-images['voxellight-normal_roughness']).mean()),'normal_cross_renderer_mean_absolute_error_flipped':float(np.abs(images['caustica-normal_roughness']-images['voxellight-normal_roughness'][::-1]).mean())}
 # Native radiance units differ. Use one exposure within each renderer's chain, not across renderers.
 exposure_preview={r:1/float(np.percentile(np.maximum(images[r+'-post_rr_hdr'],0),90)) for r in ('caustica','voxellight')}
 canvas=Image.new('RGB',(1048,740),'#202020');draw=ImageDraw.Draw(canvas)
 for row,name in enumerate(('pre_rr_hdr','post_rr_hdr')):
  for column,renderer in enumerate(('caustica','voxellight')):
   array=np.maximum(images[renderer+'-'+name][::-1]*exposure_preview[renderer],0);array=array/(1+array)
   encoded=np.where(array<=.0031308,12.92*array,1.055*array**(1/2.4)-.055)
   preview=Image.fromarray((np.clip(encoded,0,1)*255).astype('uint8')).resize((524,336))
   y=row*370;canvas.paste(preview,(column*524,y+25));draw.text((column*524+8,y+6),renderer+' '+name,fill='white')
 canvas.save(out/'hdr-chain-preview.png')
 Image.fromarray((final[::-1]*255).astype('uint8')).save(out/'caustica-final-display.png')
 screenshot=root/'work/alpha50-voxellight-capture/game/screenshots/2026-10-10_20.42.49.png'
 if screenshot.exists():
  shutil.copy2(screenshot,out/'voxellight-endpoint-native-final.png')
  results['voxellight_native_final']={'source':str(screenshot),'sha256':hashlib.sha256(screenshot.read_bytes()).hexdigest(),'requested_utc':'2026-10-10T12:42:49.648891500Z','same_frame_as_hdr_guaranteed':False,'evidence':'capture agent requests guide ZIP and native screenshot in the same client callback; actual readback/presentation scheduling can differ'}
 results['method']={'working_color':'linear Rec.709/D65; native radiance units retained','caustica_deexposure':exposure,'matrix_source':'work/Caustica/shaders/pipelines/debug_present/main.comp.slang','preview':'independent per-renderer exposure, shared within pre/post chain + Reinhard + sRGB encoding; cannot compare absolute brightness','preview_exposures':exposure_preview,'row_transform':'vertical flip for both raw HDR/guide chains and Caustica raw final readback; native VoxelLight PNG already top-down','limitations':['Native lighting and radiance scale differ; color conversion does not equate physical units.','VoxelLight endpoint archive lacks guaranteed same-frame final display readback; native screenshot is separately archived.','Temporal single-frame PT noise, different materials and RR jitter prevent a causal cross-renderer blur score.','Previous floor-shadow ROI derived from unnormalized rows is excluded.']}
 (out/'validation.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
 print(json.dumps(results['orientation_evidence']))

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('workspace',type=pathlib.Path);main(ap.parse_args().workspace.resolve())
