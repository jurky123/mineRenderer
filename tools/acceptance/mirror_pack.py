"""Generate constant LabPBR gold maps for a deterministic mirror fixture; no vanilla art copied."""
import argparse,hashlib,json,pathlib,struct,zipfile,zlib

def png(rgba):
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    pixels=b''.join(b'\0'+bytes(rgba)*16 for _ in range(16))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')

def build(path):
    metadata={'pack':{'description':'Local LabPBR gold mirror fixture','pack_format':88,'min_format':88,'max_format':88}}
    entries={'pack.mcmeta':json.dumps(metadata).encode(),'assets/minecraft/textures/block/gold_block_s.png':png((255,231,0,255)),'assets/minecraft/textures/block/gold_block_n.png':png((128,128,255,255))}
    path.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path,'w') as output:
        for name,data in entries.items():
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;output.writestr(info,data)
    return {'file':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'specularRgba':[255,231,0,255],'normalRgba':[128,128,255,255],'meaning':'smooth gold ID231, no authored emission, unoccluded flat normal; actual renderer roughness must still be verified','standard':'https://shaderlabs.org/wiki/LabPBR_Material_Standard'}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('output',type=pathlib.Path);args=parser.parse_args();print(json.dumps(build(args.output),indent=2))
