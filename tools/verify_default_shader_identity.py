"""Compare analysis-only canonical SPIR-V; never package transformed binaries."""
import argparse,hashlib,json,pathlib,subprocess,zipfile

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--baseline',type=pathlib.Path,required=True);parser.add_argument('--current',type=pathlib.Path,required=True);parser.add_argument('--tools',type=pathlib.Path,required=True);parser.add_argument('--output',type=pathlib.Path,required=True);args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True);rows=[]
    with zipfile.ZipFile(args.baseline) as jar:
        for name in jar.namelist():
            if not name.startswith('caustica/shaders/') or not name.endswith('.spv'):continue
            relative=pathlib.Path(name).relative_to('caustica/shaders');current=args.current/relative
            analysis=args.output/relative.parent;analysis.mkdir(parents=True,exist_ok=True)
            base=analysis/(relative.name+'.baseline');base.write_bytes(jar.read(name))
            canonical=[]
            for label,source in [('baseline',base),('current',current)]:
                target=analysis/(relative.name+'.'+label+'.canonical.spv')
                subprocess.run([str(args.tools/'spirv-opt.exe'),'--strip-nonsemantic','--strip-debug','--eliminate-dead-const','--eliminate-dead-functions','--compact-ids',str(source),'-o',str(target)],check=True)
                subprocess.run([str(args.tools/'spirv-val.exe'),str(target)],check=True)
                canonical.append(hashlib.sha256(target.read_bytes()).hexdigest())
            rows.append({'shader':name,'canonical_identical':canonical[0]==canonical[1],'baseline_sha256':canonical[0],'current_sha256':canonical[1]})
    result={'analysis_only':True,'method':'strip debug/nonsemantic information, dead constants/functions, compact IDs; validate; transformed binaries never packaged','baseline_jar_sha256':hashlib.sha256(args.baseline.read_bytes()).hexdigest(),'shaders':rows,'all_identical':bool(rows) and all(row['canonical_identical'] for row in rows)}
    (args.output/'validation.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(len(rows),'shaders; canonical identical:',result['all_identical'],flush=True)
    if not result['all_identical']:raise SystemExit('Default executable shader difference requires review')

if __name__=='__main__':main()
