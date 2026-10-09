"""Launch existing Minecraft files in an isolated local copy, without saved account tokens."""
import argparse, hashlib, json, pathlib, shutil, subprocess, sys, uuid
ROOT=pathlib.Path(__file__).resolve().parents[2]
CODE=pathlib.Path(__file__).resolve().parent
HERE=pathlib.Path(__file__).resolve().parent
SOURCE=pathlib.Path(r'E:\MC\.minecraft\versions\26.2-Fabric 0.19.5')
MC=pathlib.Path(r'E:\MC\.minecraft')
JAVA=pathlib.Path.home()/'AppData/Roaming/.minecraft/runtime/java-runtime-epsilon/bin/java.exe'
GAME=HERE/'game'
WORLD='新的世界'
RENDERER='voxellight'
RESOURCE_PACK=None

def allowed(lib):
    if 'rules' not in lib:return True
    result=False
    for rule in lib['rules']:
        os=rule.get('os',{})
        if os.get('name','windows')!='windows':continue
        if os.get('arch','x86_64') not in ('x86_64','amd64'):continue
        result=rule['action']=='allow'
    return result

def prepare():
    ensure_idle()
    identity=HERE/'renderer.json'
    if identity.exists() and json.loads(identity.read_text())['renderer']!=RENDERER:raise RuntimeError('Use a separate state directory for each renderer')
    version=json.loads((SOURCE/(SOURCE.name+'.json')).read_text(encoding='utf-8'))
    cp=[];missing=[]
    for lib in version['libraries']:
        if not allowed(lib):continue
        artifact=lib.get('downloads',{}).get('artifact')
        if artifact:path=MC/'libraries'/artifact['path']
        else:
            parts=lib['name'].split(':');group,name,ver=parts[:3]
            path=MC/'libraries'/group.replace('.','/')/name/ver/(name+'-'+ver+('.jar' if len(parts)==3 else '-'+parts[3]+'.jar'))
        if path.exists():cp.append(str(path))
        else:missing.append(str(path))
    if missing:raise RuntimeError('Missing installed libraries: '+str(missing))
    cp.append(str(SOURCE/(SOURCE.name+'.jar')))
    GAME.mkdir(exist_ok=True)
    for name in ('mods','config'):
        if not (GAME/name).exists():shutil.copytree(SOURCE/name,GAME/name)
    artifact=GAME/'mods/voxellight-client-26.2-0.39.0-alpha.49.jar'
    if RENDERER=='caustica':
        artifact.unlink(missing_ok=True)
        artifact=GAME/'mods/caustica-0.1.1.jar'
        shutil.copy2(SOURCE/'mods/caustica-0.1.1.jar.disabled',artifact)
    world=GAME/'saves/LocalDebug49'
    if not world.exists():shutil.copytree(SOURCE/'saves'/WORLD,world,ignore=shutil.ignore_patterns('session.lock'))
    options=(SOURCE/'options.txt').read_text(encoding='utf-8')
    replacements={'pauseOnLostFocus':'false','overrideWidth':'1048','overrideHeight':'672','fullscreen':'false','enableVsync':'false','maxFps':'260','resourcePacks':'[]'}
    if RESOURCE_PACK:
        packs=GAME/'resourcepacks';packs.mkdir(exist_ok=True);shutil.copy2(RESOURCE_PACK,packs/RESOURCE_PACK.name)
        replacements['resourcePacks']=json.dumps(['file/'+RESOURCE_PACK.name],ensure_ascii=False)
    lines=[]
    for line in options.splitlines():
        key=line.split(':',1)[0];lines.append(key+':'+replacements[key] if key in replacements else line)
    (GAME/'options.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    classes=HERE/'classes';classes.mkdir(exist_ok=True)
    subprocess.run([str(JAVA.with_name('javac.exe')),'-d',str(classes),str(CODE/'DebugAgent.java')],check=True)
    manifest=HERE/'MANIFEST.MF';manifest.write_text('Manifest-Version: 1.0\nPremain-Class: DebugAgent\n\n')
    subprocess.run([str(JAVA.with_name('jar.exe')),'cfm',str(HERE/'debug-agent.jar'),str(manifest),'-C',str(classes),'.'],check=True)
    fixture_tool=CODE/'alpha49.py'
    if not fixture_tool.exists():
        fixture_tool=ROOT/'tools/acceptance/alpha49.py'
        if not fixture_tool.exists():fixture_tool=ROOT/'work/mineRenderer/tools/acceptance/alpha49.py'
    subprocess.run(['python',str(fixture_tool),'fixture',str(HERE/'fixture')],check=True)
    natives=SOURCE/'26.2-Fabric 0.19.5-natives'
    args=['-Xms512M','-Xmx4G','--sun-misc-unsafe-memory-access=allow','--enable-native-access=ALL-UNNAMED',f'-Djava.library.path={natives}/java',f'-Djna.tmpdir={natives}/jna',f'-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}/lwjgl',f'-Dio.netty.native.workdir={natives}/netty','-Dminecraft.launcher.brand=LocalVoxelLightDebug','-Dminecraft.launcher.version=1','-cp',';'.join(cp),version['mainClass'],'--username','LocalDebug49','--uuid',uuid.uuid3(uuid.NAMESPACE_DNS,'voxellight.localdebug49').hex,'--accessToken','0','--version','26.2-Fabric 0.19.5','--gameDir',str(GAME),'--assetsDir',str(MC/'assets'),'--assetIndex',version['assetIndex']['id'],'--versionType','release','--width','1048','--height','672','--quickPlaySingleplayer','LocalDebug49']
    if RENDERER=='caustica':args=['-Dcaustica.rt.frameStats=true','-Dcaustica.rt.dlssRr=true','-Dcaustica.rt.dlssRr.quality=0','-Dcaustica.rt.fg=false','-Dcaustica.rt.spp=1','-Dcaustica.rt.maxBounces=4']+args
    (HERE/'base-args.json').write_text(json.dumps(args,indent=2),encoding='utf-8')
    identity.write_text(json.dumps({'renderer':RENDERER,'artifact':str(artifact),'sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'resourcePack':{'name':RESOURCE_PACK.name,'sha256':hashlib.sha256(RESOURCE_PACK.read_bytes()).hexdigest()} if RESOURCE_PACK else None},indent=2))
    print(json.dumps({'java':str(JAVA),'isolatedGame':str(GAME),'renderer':RENDERER,'classpathEntries':len(cp),'modSha256':hashlib.sha256(artifact.read_bytes()).hexdigest()},indent=2))

def ensure_idle():
    if (HERE/'process.json').exists():
        pid=json.loads((HERE/'process.json').read_text())['pid']
        import ctypes
        kernel=ctypes.windll.kernel32
        kernel.OpenProcess.restype=ctypes.c_void_p
        kernel.GetExitCodeProcess.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_ulong)]
        kernel.CloseHandle.argtypes=[ctypes.c_void_p]
        handle=kernel.OpenProcess(0x1000,False,pid)
        if handle:
            code=ctypes.c_ulong();kernel.GetExitCodeProcess(handle,ctypes.byref(code));kernel.CloseHandle(handle)
            if code.value==259:raise RuntimeError('Previous dedicated client is still running: '+str(pid))

def launch(suites,cameras):
    ensure_idle()
    identity=HERE/'renderer.json'
    renderer=json.loads(identity.read_text())['renderer'] if identity.exists() else 'voxellight'
    allowed_suites={'production','guides','transport','scene','capture','integrator'}
    if not set(suites.split(','))<=allowed_suites:raise ValueError('Unsupported suite')
    if 'capture' in suites.split(',') and suites!='capture':raise ValueError('Quality-only capture must run separately from performance suites')
    if not set(cameras.split(','))<={'terrain','foliage','mirror','glass_water','entity'}:raise ValueError('Unsupported fixture camera')
    args=json.loads((HERE/'base-args.json').read_text())
    args=[f'-javaagent:{HERE}/debug-agent.jar={HERE}',f'-Dvoxellight.debug.suites={suites}',f'-Dvoxellight.debug.cameras={cameras}',f'-Dvoxellight.debug.renderer={renderer}']+args
    # Java argument file avoids Windows command length/quoting and contains no saved credentials.
    def quote(s):return '"'+s.replace('\\','/').replace('"','\\"')+'"'
    argfile=HERE/'launch.args';argfile.write_text('\n'.join(quote(s) for s in args),encoding='utf-8')
    log=(HERE/'launch-output.log').open('w',encoding='utf-8')
    process=subprocess.Popen([str(JAVA),'@'+str(argfile)],cwd=GAME,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    import ctypes
    from ctypes import wintypes
    kernel=ctypes.windll.kernel32;kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME)]
    created,exited,system,user=(wintypes.FILETIME() for _ in range(4));kernel.GetProcessTimes(int(process._handle),ctypes.byref(created),ctypes.byref(exited),ctypes.byref(system),ctypes.byref(user))
    creation=(created.dwHighDateTime<<32)|created.dwLowDateTime
    (HERE/'process.json').write_text(json.dumps({'pid':process.pid,'creationFiletime':creation,'java':str(JAVA),'suites':suites,'gameDir':str(GAME),'renderer':renderer,'cameras':cameras,'jvmProperties':[a for a in args if a.startswith('-D')]},indent=2))
    with (HERE/'watchdog-output.log').open('w',encoding='utf-8') as watchlog:
        subprocess.Popen([sys.executable,str(CODE/'watchdog.py'),str(HERE)],stdout=watchlog,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    with (HERE/'telemetry-output.log').open('w',encoding='utf-8') as telemetrylog:
        subprocess.Popen([sys.executable,str(CODE/'gpu_telemetry.py'),str(HERE)],stdout=telemetrylog,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    print('Started isolated client PID',process.pid,'suites',suites)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','launch']);parser.add_argument('--renderer',choices=['voxellight','caustica'],default='voxellight');parser.add_argument('--resource-pack',type=pathlib.Path);parser.add_argument('--suites',default='production');parser.add_argument('--cameras',default='terrain');parser.add_argument('--state-dir',type=pathlib.Path,default=ROOT/'work/automation');parser.add_argument('--source-version-dir',type=pathlib.Path,default=SOURCE);parser.add_argument('--java',type=pathlib.Path,default=JAVA);parser.add_argument('--world',default=WORLD);opts=parser.parse_args()
    SOURCE=opts.source_version_dir.resolve();MC=SOURCE.parents[1];JAVA=opts.java.resolve();WORLD=opts.world;RENDERER=opts.renderer;RESOURCE_PACK=opts.resource_pack.resolve() if opts.resource_pack else None
    HERE=opts.state_dir.resolve();HERE.mkdir(parents=True,exist_ok=True);GAME=HERE/'game'
    prepare() if opts.action=='prepare' else launch(opts.suites,opts.cameras)
