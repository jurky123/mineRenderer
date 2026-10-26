/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.sky;

import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.port.PortLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import java.nio.*;
import java.nio.file.*;
import org.lwjgl.system.MemoryUtil;

/** Official atmosphere, sun/moon extinction, weather dome and ambient-light equations. */
public final class PortSky {
    private AtmosphericSkyGenerator generator;
    private final PortCelestials celestials=new PortCelestials();
    private long texture,overcast;
    private boolean fixed;
    private final float[] mean=new float[3];
    private final PortLight dome=new PortLight(0x26220001),sun=new PortLight(0x26220002),moon=new PortLight(0x26220003),ambient=new PortLight(0x26220004),weather=new PortLight(0x26220005);
    public long uploads;
    public void render(RemixAPI api,SkyRenderState state){
        boolean normal=state.skybox==DimensionType.Skybox.OVERWORLD;
        var world=Minecraft.getInstance().level;
        var lighting=normal?SkyMath.evaluateSkyLightState(state.sunAngle/(float)(2*Math.PI),world.getRainLevel(0),world.getThunderLevel(0)):SkyMath.SkyLightState.empty();
        ByteBuffer pixels=null;
        if(normal){
            try(var ignored=new SkyGlState()){
                if(generator==null)generator=new AtmosphericSkyGenerator();
                pixels=generator.generate(lighting,mean,0.9f,texture!=0&&!fixed);
            }
            fixed=false;
        }else if(texture==0||!fixed){
            mean[0]=state.skybox==DimensionType.Skybox.END?0.0275f:0;
            mean[1]=state.skybox==DimensionType.Skybox.END?0.0210f:0;
            mean[2]=state.skybox==DimensionType.Skybox.END?0.0372f:0;
            pixels=MemoryUtil.memAlloc(AtmosphericSkyGenerator.OUTPUT_BYTES).order(ByteOrder.nativeOrder());
            for(int i=0;i<AtmosphericSkyGenerator.WIDTH*AtmosphericSkyGenerator.HEIGHT;i++)pixels.putShort(Float.floatToFloat16(mean[0])).putShort(Float.floatToFloat16(mean[1])).putShort(Float.floatToFloat16(mean[2])).putShort(Float.floatToFloat16(1));
            pixels.flip();fixed=true;
        }
        if(pixels!=null){
            try{upload(api,pixels);}finally{if(fixed)MemoryUtil.memFree(pixels);}
        }
        if(texture!=0){var ext=new LightInfoDomeEXT();ext.setColorTexture(texture);dome.publish(api,LightInfo.builder().radiance(1,1,1).domeExt(ext).build(),1);}
        if(normal){
            distant(api,sun,lighting.sunPosition(),lighting.sunDirectRadiance(),0.53f);
            distant(api,moon,lighting.moonPosition(),lighting.moonDirectRadiance(),0.52f);
            celestials.render(api,state,lighting);
        }
        float rain=normal?Math.clamp(world.getRainLevel(0),0,1):0,thunder=normal?Math.clamp(world.getThunderLevel(0),0,1):0;
        float opacity=rain+(1-rain)*thunder,celestial=lighting.weatherDomeRadiance();
        float intensity=opacity>0?(rain*Math.max(celestial,0.02f)*(1-thunder)+celestial*0.8f*thunder)/opacity:0;
        if(opacity>0){
            if(overcast==0){Path path=Minecraft.getInstance().gameDirectory.toPath().resolve("textures/overcast_thunderstorm_bc6u.dds");if(Files.isRegularFile(path))overcast=api.createTextureFromFile(path.toAbsolutePath().toString());}
            if(overcast!=0){var ext=new LightInfoDomeEXT();ext.setColorTexture(overcast);ext.setOpacity(opacity);ext.setBlendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA);weather.publish(api,LightInfo.builder().radiance(intensity,intensity,intensity).domeExt(ext).build(),opacity,intensity);}else opacity=0;
        }
        float multiplier=normal?2.5f*(1-SkyMath.celestialVisibility(lighting.sunPosition()[1])):1;
        float r=(mean[0]*(1-opacity)+0.2393801f*intensity*opacity)*multiplier;
        float g=(mean[1]*(1-opacity)+0.26890025f*intensity*opacity)*multiplier;
        float b=(mean[2]*(1-opacity)+0.31064802f*intensity*opacity)*multiplier;
        if(r+g+b>0)ambient.publish(api,LightInfo.builder().radiance(r,g,b).rectExt(LightInfoRectEXT.builder().position(0,325,0).xAxis(1,0,0).xSize(8000).yAxis(0,0,1).ySize(8000).direction(0,-1,0).build()).build(),r,g,b);
    }
    private static void distant(RemixAPI api,PortLight light,float[] p,float[] r,float diameter){
        if(r[0]+r[1]+r[2]==0)return;
        light.publish(api,LightInfo.builder().radiance(r[0],r[1],r[2]).distantExt(LightInfoDistantEXT.builder().direction(-p[0],-p[1],-p[2]).angularDiameterDegrees(diameter).build()).build(),p[0],p[1],p[2],r[0],r[1],r[2]);
    }
    private void upload(RemixAPI api,ByteBuffer pixels){
        if(texture==0)texture=api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(0x26221001).size(AtmosphericSkyGenerator.WIDTH,AtmosphericSkyGenerator.HEIGHT).format(TextureFormat.RGBA16_SFLOAT).pixelData(pixels).build());
        else{var patch=new TexturePatch();patch.width=AtmosphericSkyGenerator.WIDTH;patch.height=AtmosphericSkyGenerator.HEIGHT;patch.rowPitch=patch.width*8;patch.format=TextureFormat.RGBA16_SFLOAT;patch.pixelData=pixels;var entry=new TextureBatchEntry();entry.handle=texture;entry.patches=new TexturePatch[]{patch};var info=new TexturePatchInfo();info.entries=new TextureBatchEntry[]{entry};api.updateTextureFromMemory(info);}
        uploads++;
    }
    public void close(RemixAPI api){
        celestials.close(api);
        dome.close(api);sun.close(api);moon.close(api);weather.close(api);ambient.close(api);
        if(texture!=0)api.destroyTexture(texture);if(overcast!=0)api.destroyTexture(overcast);texture=overcast=0;
        if(generator!=null)try(var ignored=new SkyGlState()){generator.destroy();}generator=null;
    }
}
