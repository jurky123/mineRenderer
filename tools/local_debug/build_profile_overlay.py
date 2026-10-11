"""Package already compiled classes/shaders into an isolated diagnostic jar."""
import argparse, hashlib, json, pathlib, zipfile

def sha(data): return hashlib.sha256(data).hexdigest()

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--base",type=pathlib.Path,required=True)
    ap.add_argument("--state-dir",type=pathlib.Path,required=True)
    ap.add_argument("--shader-bank",type=pathlib.Path,required=True)
    args=ap.parse_args()
    repo=pathlib.Path(__file__).resolve().parents[2]
    state=args.state_dir.resolve()
    identity=state/"renderer.json"
    info=json.loads(identity.read_text(encoding="utf-8"))
    if info["renderer"]!="voxellight": raise ValueError("Wrong renderer")
    target=pathlib.Path(info["artifact"])
    if target.resolve()==args.base.resolve(): raise ValueError("Base must stay untouched")
    replacement={}
    for variant in ("java/main","java/client"):
        classes=repo/"build/classes"/variant
        for p in classes.rglob("*.class"):
            replacement[p.relative_to(classes).as_posix()]=p.read_bytes()
    if not replacement: raise ValueError("No compiled classes")
    resources=repo/"src/main/resources/assets/voxellight"
    for p in resources.rglob("*"):
        if p.is_file() and not p.name.endswith((".dll",".so")):
            replacement["assets/voxellight/"+p.relative_to(resources).as_posix()]=p.read_bytes()
    manifest=json.loads((args.shader_bank/"manifest.json").read_text(encoding="utf-8"))
    for name, record in manifest["shaders"].items():
        data=(args.shader_bank/(name+".spv")).read_bytes()
        if sha(data)!=record["sha256"]: raise ValueError("Shader hash mismatch: "+name)
        replacement["assets/voxellight/rt/vulkan/"+name+".spv"]=data
    replacement["assets/voxellight/rt/vulkan/manifest.json"]=(args.shader_bank/"manifest.json").read_bytes()
    changed={name:sha(data) for name,data in replacement.items()}
    with zipfile.ZipFile(args.base) as source, zipfile.ZipFile(target,"w",zipfile.ZIP_DEFLATED) as output:
        for entry in source.infolist():
            output.writestr(entry,replacement.pop(entry.filename,source.read(entry.filename)))
        for name,data in replacement.items(): output.writestr(name,data)
    with zipfile.ZipFile(args.base) as source, zipfile.ZipFile(target) as patched:
        native={name:sha(source.read(name)) for name in source.namelist() if name.endswith((".dll",".so"))}
        for name,digest in native.items():
            if sha(patched.read(name))!=digest: raise ValueError("Native artifact changed")
    info["sha256"]=sha(target.read_bytes())
    identity.write_text(json.dumps(info,indent=2),encoding="utf-8")
    provenance={"scope":"Local diagnostic overlay; no fresh native SDK build", "baseSha256":sha(args.base.read_bytes()),"patchedSha256":info["sha256"],"profileWorkload":manifest.get("profileWorkload",False),"formalTimingEligible":not manifest.get("profileWorkload",False),"entries":changed,"nativeEntries":native}
    source_hashes={str(p.relative_to(repo)).replace("\\","/"):sha(p.read_bytes()) for folder in ("src","shaders","tools") for p in (repo/folder).rglob("*") if p.is_file() and p.suffix in (".java",".slang",".py",".json",".bin") and "__pycache__" not in p.parts}
    provenance["sourceSha256"]=source_hashes
    (state/"patch-build.json").write_text(json.dumps(provenance,indent=2),encoding="utf-8")
    print(json.dumps({key:provenance[key] for key in ("patchedSha256","profileWorkload","formalTimingEligible")},indent=2))

if __name__=="__main__": main()
