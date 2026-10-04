package com.voxellight.adapter;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.lwjgl.system.MemoryStack;
/** One shared world-space weather contract for sky, surface lights, volume and water. */
final class EnvironmentPass implements AutoCloseable  {
    private GpuBuffer settings;
    private boolean sky=true,clouds=true,shadows=true,underwater=true,caustics=true,ripples=true,composed,active;
    private boolean submerged;
    private float rtRain,rtCloud,rtRipples;
    float[] rtSettings(){return new float[]{(float)wind,192,rtRain,rtCloud,rtRipples};}
    boolean submerged() {
        return submerged;
    }
    boolean caustics() {
        return caustics;
    }
    private long clock=System.nanoTime();
    private double wind;
    void setSky(boolean value) {
        sky=value;
        composed=false;
    }
    void setClouds(boolean value) {
        clouds=value;
        composed=false;
    }
    void setCloudShadows(boolean value) {
        shadows=value;
    }
    void setUnderwater(boolean value) {
        underwater=value;
    }
    void setCaustics(boolean value) {
        caustics=value;
    }
    void setRipples(boolean value) {
        ripples=value;
    }
    void prepare(CommandEncoder encoder,boolean polished) {
        var mc=Minecraft.getInstance();
        var state=mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        var camera=mc.gameRenderer.mainCamera();
        var pos=camera.position();
        boolean overworld=state.skybox==DimensionType.Skybox.OVERWORLD;
        active=polished&&overworld&&camera.getFluidInCamera()==FogType.NONE;
        boolean inWater=polished&&underwater&&overworld&&camera.getFluidInCamera()==FogType.WATER;
        submerged=inWater;
        float waterTop=(float)pos.y;
        if(inWater&&mc.level!=null) {
            var block=net.minecraft.core.BlockPos.containing(pos);
            for(int i=0;i<32;i++) {
                if(!mc.level.getFluidState(block).is(net.minecraft.tags.FluidTags.WATER))break;
                waterTop=block.getY()+1;
                block=block.above();
            }
        }
        boolean cloud=clouds&&mc.options.cloudStatus().get()!=CloudStatus.OFF;
        rtRain=1-Math.clamp(state.rainBrightness,0,1);rtCloud=polished&&overworld&&shadows&&cloud?1:0;rtRipples=ripples?1:0;
        long now=System.nanoTime();
        wind=(wind+Math.clamp((now-clock)/1e9,0,.25)*.35)%16384;
        clock=now;
        if(settings==null)settings=RenderSystem.getDevice().createBuffer(()->"VoxelLight shared environment",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,64);
        try(var stack=MemoryStack.stackPush()) {
            encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,64)                 .putVec4((float)(pos.x%16384),(float)(pos.z%16384),(float)pos.y,(float)wind)                 .putVec4(active&&sky?1:0,active&&sky&&cloud?1:0,polished&&overworld&&shadows&&cloud?1:0,1-Math.clamp(state.rainBrightness,0,1))                 .putVec4(state.sunAngle,state.moonPhase==null?0:state.moonPhase.ordinal(),192,.48f)                 .putVec4(inWater?1:0,caustics&&polished&&overworld?1:0,ripples?1:0,waterTop).get());
        }
    }
    void bind(RenderPass pass) {
        pass.setUniform("EnvironmentSettings",settings);
    }
    GpuBuffer settings() {
        return settings;
    }
    void composed() {
        composed=active&&sky&&clouds;
    }
    boolean replacesClouds() {
        return composed;
    }
    void endFrame() {
        composed=false;
    }
    String status() {
        return ", customSky="+sky+", clouds="+clouds+", cloudShadows="+shadows+", underwater="+underwater+", caustics="+caustics+", rainRipples="+ripples;
    }
    @Override public void close() {
        if(settings!=null) {
            settings.close();
            settings=null;
        }
        composed=active=false;
        clock=System.nanoTime();
    }
}
