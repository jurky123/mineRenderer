/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.sky;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.MeshData;
import com.nvidia.rtxremix.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;
/** Official SkyRenderer's sky-category quads/materials and transforms, with 26.2 texture paths. */
public final class PortCelestials {
    private static float[] starPositions=new float[0];
    private static final class Body {long texture,material,mesh;float opacity=-1;final float radiance;final long hash;Body(float radiance,long hash){this.radiance=radiance;this.hash=hash;}}
    private final Body sun=new Body(8,0x26260001),stars=new Body(1,0x26260002);
    private final Map<String,Body> moons=new HashMap<>();
    private InstanceInfoBlendEXT blend;
    public static void captureStars(MeshData data){
        var format=data.drawState().format();var position=format.getElement("Position");if(position==null)throw new IllegalStateException("Missing canonical star positions");
        var bytes=data.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());int count=data.drawState().vertexCount(),stride=format.getVertexSize();float[] result=new float[count*3];
        for(int i=0;i<count;i++)for(int axis=0;axis<3;axis++)result[i*3+axis]=bytes.getFloat(bytes.position()+i*stride+position.offset()+axis*4);starPositions=result;
    }
    public void render(RemixAPI api,SkyRenderState state,SkyMath.SkyLightState lighting){
        if(blend==null){blend=InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(1);blend.alphaBlendEnabled=1;blend.srcColorBlendFactor=InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;blend.dstColorBlendFactor=InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;blend.srcAlphaBlendFactor=InstanceInfoBlendEXT.VK_BLEND_FACTOR_ZERO;blend.dstAlphaBlendFactor=InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;blend.allocNative(0);}
        var position=Minecraft.getInstance().gameRenderer.mainCamera().position();float visibility=state.rainBrightness;
        draw(api,sun,"sun",lighting.sunPosition(),30,visibility,position);
        if(state.moonPhase!=null){String phase=state.moonPhase.getSerializedName();var body=moons.computeIfAbsent(phase,k->new Body(.2f,0x26260100L+state.moonPhase.index()));draw(api,body,"moon/"+phase,lighting.moonPosition(),20,visibility,position);}
        float opacity=Math.clamp(state.starBrightness,0,1)*visibility;
        if(opacity>0 && starPositions.length>0){
            updateMaterial(api,stars,opacity);
            if(stars.mesh==0){HardcodedVertex[] vertices=new HardcodedVertex[starPositions.length/3];for(int i=0;i<vertices.length;i++)vertices[i]=HardcodedVertex.create(starPositions[i*3],starPositions[i*3+1],starPositions[i*3+2]);stars.mesh=createMesh(api,vertices,stars.material,stars.hash);}
            var rotation=new Quaternionf().rotateX((float)Math.toRadians(30)).rotateY(-(float)Math.PI/2).rotateX(state.starAngle);
            var transform=Transform.fromTRS((float)position.x,(float)position.y,(float)position.z,rotation,1.1f);
            api.drawInstance(stars.mesh,transform.matrix,InstanceCategoryBit.SKY,1,blend.nativePtr);
        }
    }
    private void draw(RemixAPI api,Body body,String path,float[] direction,float size,float visibility,Vec3 camera){
        float edge=-(float)Math.sin(Math.toRadians(4)),t=Math.clamp((direction[1]-edge)/-edge,0,1),opacity=t*t*(3-2*t)*visibility;if(opacity<=0)return;
        if(body.texture==0)body.texture=load(api,path,body.hash);
        updateMaterial(api,body,opacity);
        if(body.mesh==0)body.mesh=createMesh(api,new HardcodedVertex[]{HardcodedVertex.create(-1,-1,0,0,0,-1,0,1,-1),HardcodedVertex.create(1,-1,0,0,0,-1,1,1,-1),HardcodedVertex.create(1,1,0,0,0,-1,1,0,-1),HardcodedVertex.create(-1,1,0,0,0,-1,0,0,-1)},body.material,body.hash);
        api.drawInstance(body.mesh,transform(camera,direction,size),InstanceCategoryBit.SKY,1,blend.nativePtr);
    }
    private static float[] transform(Vec3 camera,float[] d,float size){
        float dx=d[0],dy=d[1],dz=d[2],inverse=1/(float)Math.sqrt(dx*dx+dy*dy+dz*dz);dx*=inverse;dy*=inverse;dz*=inverse;
        float rx=dz,rz=-dx,ir=1/(float)Math.sqrt(rx*rx+rz*rz);rx*=ir;rz*=ir;
        float ux=dy*rz,uy=dz*rx-dx*rz,uz=-dy*rx;
        return new float[]{rx*size,ux*size,dx,(float)camera.x+dx*100,0,uy*size,dy,(float)camera.y+dy*100,rz*size,uz*size,dz,(float)camera.z+dz*100};
    }
    private static void updateMaterial(RemixAPI api,Body body,float opacity){
        if(body.material!=0&&Math.abs(opacity-body.opacity)<=1f/255)return;
        var opaque=MaterialInfoOpaqueEXT.builder().albedoConstant(opacity,opacity,opacity).opacityConstant(opacity).roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build();
        var builder=MaterialInfo.builder().hash(body.hash).emissiveIntensity(body.radiance*opacity).opaqueExt(opaque);
        if(body.texture!=0)builder.albedoTexture(body.texture).emissiveTexture(body.texture).filterMode(0).wrapMode(MaterialInfo.WRAP_MODE_CLAMP,MaterialInfo.WRAP_MODE_CLAMP);else builder.emissiveColorConstant(1,1,1);
        long previous=body.material;body.material=api.createMaterial(builder.build());body.opacity=opacity;if(previous!=0&&previous!=body.material)api.destroyMaterial(previous);
    }
    private static long load(RemixAPI api,String path,long hash){
        var id=Identifier.withDefaultNamespace("textures/environment/celestial/"+path+".png");
        try(var stream=Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open();var image=NativeImage.read(stream)){
            var data=MemoryUtil.memAlloc(image.getWidth()*image.getHeight()*4);
            try{for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int argb=image.getPixel(x,y);data.put((byte)(argb>>>16)).put((byte)(argb>>>8)).put((byte)argb).put((byte)(argb>>>24));}data.flip();return api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(hash).size(image.getWidth(),image.getHeight()).format(TextureFormat.RGBA8_UNORM).pixelData(data).build());}finally{MemoryUtil.memFree(data);}
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    private static long createMesh(RemixAPI api,HardcodedVertex[] vertices,long material,long hash){
        var vb=MemoryUtil.memAlloc(vertices.length*64).order(ByteOrder.nativeOrder());var ib=MemoryUtil.memAlloc(vertices.length/4*24).order(ByteOrder.nativeOrder());
        try{for(var vertex:vertices)vertex.writeTo(vb);for(int q=0;q<vertices.length;q+=4)ib.putInt(q).putInt(q+1).putInt(q+2).putInt(q).putInt(q+2).putInt(q+3);vb.flip();ib.flip();var surface=new MeshInfoSurfaceTriangles();surface.vertexBuffer=vb;surface.vertexCount=vertices.length;surface.indexBuffer=ib;surface.indexCount=ib.remaining()/4;surface.material=material;return api.createMesh(MeshInfo.builder().hash(hash).surfaces(new MeshInfoSurfaceTriangles[]{surface}).build());}finally{MemoryUtil.memFree(vb);MemoryUtil.memFree(ib);}
    }
    public void close(RemixAPI api){List<Body> bodies=new ArrayList<>(moons.values());bodies.add(sun);bodies.add(stars);for(var body:bodies){if(body.mesh!=0)api.destroyMesh(body.mesh);if(body.material!=0)api.destroyMaterial(body.material);if(body.texture!=0)api.destroyTexture(body.texture);}if(blend!=null)blend.freeNative();}
}
