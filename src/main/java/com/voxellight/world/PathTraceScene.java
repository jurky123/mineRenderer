package com.voxellight.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Owned 80^3 secondary-ray proxy; missing sections are unknown, never air. */
public final class PathTraceScene {
    public static final int SIDE=80, BYTES=SIDE*SIDE*SIDE*4, UNKNOWN=0xff000000;
    public static int index(int x,int y,int z){return (y*SIDE+z)*SIDE+x;}
    public static int packed(SectionSnapshot.Material m,int color) {
        if((m.flags()&SectionSnapshot.NON_AIR)==0 || (m.flags()&SectionSnapshot.FLUID)!=0)return 0;
        // Only full opaque cubes and emissive proxies: foliage/thin models must not become solid cubes.
        if((m.flags()&SectionSnapshot.FULL_OCCLUDER)==0 && m.emission()==0)return 0;
        return ((m.emission()+1)<<24) | ((color>>16)&255) | (color&0xff00) | ((color&255)<<16);
    }
    public static ByteBuffer encode(List<SectionSnapshot> sections,SectionKey center,IntUnaryOperator color) {
        ByteBuffer data=ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());
        for(int i=0;i<SIDE*SIDE*SIDE;i++)data.putInt(UNKNOWN);
        for(var section:sections) {
            var key=section.request().key();int ox=(key.x()-center.x()+2)*16,oy=(key.y()-center.y()+2)*16,oz=(key.z()-center.z()+2)*16;
            if(ox<0||oy<0||oz<0||ox>=SIDE||oy>=SIDE||oz>=SIDE)continue;
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                var m=section.material(SectionKey.blockIndex(x,y,z));
                data.putInt(index(ox+x,oy+y,oz+z)*4,packed(m,color.applyAsInt(m.stateId())));
            }
        }
        return data.clear();
    }
    private PathTraceScene(){}
}
