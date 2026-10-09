"""Serialize the canonical GGX energy floats; no resampling or model changes."""
import argparse,pathlib,re,struct
ROOT=pathlib.Path(__file__).resolve().parents[1]
def data():
    text=(ROOT/'shaders/rt/common/ggx_energy_data.slang').read_text()
    body=text[text.index('={')+2:text.index('};')]
    values=[float(x) for x in re.findall(r'([0-9]+\.[0-9]+)f',body)]
    if len(values)!=1024 or any(not 0<=v<=1 for v in values):raise ValueError('Invalid canonical GGX energy data')
    return struct.pack('<1024f',*values)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');a=p.parse_args()
    target=ROOT/'src/main/resources/assets/voxellight/rt/ggx-energy.bin'
    expected=data()
    if a.check:
        if target.read_bytes()!=expected:raise SystemExit('GGX asset differs from canonical table')
    else:target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(expected)
    print('Verified 1024 canonical float32 GGX energy cells (4096 bytes)')
