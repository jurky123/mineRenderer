#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D DepthSource;
layout(location=0) out float nearestDepth;
void main() {
    ivec2 sourceSize=textureSize(DepthSource,0),cell=ivec2(gl_FragCoord.xy);
    ivec2 start=cell*2,end=start+ivec2(2);
#ifndef FIRST_DEPTH
    // Floor-sized native mip levels must retain the unmatched odd row/column.
    ivec2 destinationSize=max(ivec2(1),sourceSize/2);
    if(cell.x==destinationSize.x-1)end.x=sourceSize.x;
    if(cell.y==destinationSize.y-1)end.y=sourceSize.y;
#endif
    nearestDepth=0.0;
    for(int y=0;y<3;y++)for(int x=0;x<3;x++) {
        ivec2 pixel=start+ivec2(x,y);
        if(any(greaterThanEqual(pixel,min(end,sourceSize))))continue;
        nearestDepth=max(nearestDepth,texelFetch(DepthSource,pixel,0).r);
    }
}
