package com.voxellight.world;
/** Order-independent local content signature; lighting/task revisions are deliberately excluded. */
public final class PathTraceAdmission {
    public static long sectionHash(SectionKey key,long material){
        long x=material ^ ((long)key.x()*0x9e3779b97f4a7c15L) ^ ((long)key.y()*0xbf58476d1ce4e5b9L) ^ ((long)key.z()*0x94d049bb133111ebL);
        x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;return x^(x>>>31);
    }
    private PathTraceAdmission(){}
}
