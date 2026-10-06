package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.Test;
import org.joml.Vector4f;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RtTemporalUpscaleTest {
    private GpuTexture texture(int usage) {
        return new GpuTexture(usage,"HDR history",GpuFormat.RGBA32_FLOAT,32,16,1,1) {
            public void close() {} public boolean isClosed() {return false;}
        };
    }
    @Test void historyClearPassesMinecraftValidationBeforeReachingBackend() {
        var clears=new AtomicInteger();
        var backend=(CommandEncoderBackend)Proxy.newProxyInstance(CommandEncoderBackend.class.getClassLoader(),new Class<?>[]{CommandEncoderBackend.class},(proxy,method,args)->{
            if(method.getName().equals("clearColorTexture")) {clears.incrementAndGet();return null;}
            throw new AssertionError("Unexpected backend call: "+method.getName());
        });
        var encoder=new CommandEncoder(null,null,backend);
        try(var history=texture(RtTemporalUpscale.HISTORY_USAGE)) {
            assertDoesNotThrow(()->encoder.clearColorTexture(history,new Vector4f(0)));
            assertEquals(1,clears.get());
        }
        try(var oldHistory=texture(RtTemporalUpscale.HISTORY_USAGE & ~GpuTexture.USAGE_COPY_DST)) {
            var error=assertThrows(IllegalStateException.class,()->encoder.clearColorTexture(oldHistory,new Vector4f(0)));
            assertEquals("Color texture must have USAGE_COPY_DST",error.getMessage());assertEquals(1,clears.get());
        }
    }
}
