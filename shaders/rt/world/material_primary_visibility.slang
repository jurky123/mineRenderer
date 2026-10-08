#include "../common/material_transport.slang"
#include "../common/primary_state.slang"
[shader("raygeneration")]
void material_primary_visibility(){
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    PathState path=primaryPath(index);initializePrimary(path);
    SurfacePayload hit=traceSurface(path.origin.xyz,path.direction.xyz,4096,255);
    uint record=primaryHit(index);pageFeedback[record]=uint4(asuint(hit.distance),asuint(hit.bary),hit.triangle);pageFeedback[record+1]=uint4(hit.instance,1,0,0);
    if(hit.distance>=0&&path.mediumCount==0&&rtPolicy()){
        TerrainSurface surface;if(terrainPrimaryGuide(hit.triangle,hit.bary,hit.instance,surface)){
            bool entering=dot(surface.geometryNormal,path.direction.xyz)<0;writePrimaryGuides(path,surface,hit,entering);
            if(rtSparse(path,surface,hit,path.origin.xyz+path.direction.xyz*hit.distance,entering?surface.frame.n:-surface.frame.n,loadMedia(path))){pageFeedback[record+1].y=0;storePath(index,path);return;}
        }
    }
    // Primary active queue uses the even bank; B0 shading writes B1 into the odd bank.
    if((uint(camera.origin.w)&2097152u)!=0){uint ticket;InterlockedAdd(pageFeedback[520].x,1,ticket);uint capacity=camera.width*camera.height*max(1u,camera.padding);if(ticket<capacity)pageFeedback[1648u+ticket]=uint4(index,0,0,0);}
}
