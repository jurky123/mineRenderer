package com.voxellight.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Optional native library, loaded only when path tracing is explicitly enabled. */
final class OptixBridge {
    private static boolean loaded;
    static synchronized byte[] load() throws IOException {
        if (!loaded) {
            String os=System.getProperty("os.name").toLowerCase();
            String arch=System.getProperty("os.arch");
            if(!arch.equals("amd64") && !arch.equals("x86_64"))throw new IOException("OptiX prototype requires x86-64");
            String name=os.contains("win")?"voxellight_optix.dll":os.contains("linux")?"libvoxellight_optix.so":null;
            if(name==null)throw new IOException("OptiX prototype supports Windows/Linux only");
            byte[] code=resource(name);Path file=Files.createTempFile("voxellight-optix-",os.contains("win")?".dll":".so");
            Files.write(file,code);file.toFile().deleteOnExit();System.load(file.toAbsolutePath().toString());loaded=true;
        }
        return resource("pathtrace.ptx");
    }
    static long createRt(byte[] uuid,boolean full) throws IOException {
        load();
        String profile=System.getenv().getOrDefault("VOXELLIGHT_RT_PROFILE","REALTIME_RELEASE");
        if(!java.util.Set.of("REALTIME_RELEASE","REFERENCE_STRICT","DEVELOPMENT").contains(profile))throw new IOException("Unknown RT compilation profile: "+profile);
        boolean fast=!full&&!profile.equals("REFERENCE_STRICT")&&System.getProperty("voxellight.rt.math","strict").equals("fast");
        boolean ir=System.getProperty("voxellight.rt.module","ptx").equals("ir");
        String cache=Path.of(System.getProperty("user.home"),".cache","voxellight","optix").toAbsolutePath().toString();
        org.slf4j.LoggerFactory.getLogger("VoxelLight").info("RTX compile profile={} full={} math={} format={} cache={}",full?"REFERENCE_STRICT":profile,full,fast?"fast":"strict",ir?"IR":"PTX",cache);
        // Strict PTX stays the baseline until the user GPU numerical/visual A/B is accepted.
        try{return createModules(uuid,full,fast,ir,cache);}
        catch(IllegalStateException failure){
            if(!ir||!failure.getMessage().contains("OptiX error 7251"))throw failure;
            org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("OptiX-IR 7251; retrying the same math profile in PTX",failure);
            try{return createModules(uuid,full,fast,false,cache);}catch(RuntimeException retry){retry.addSuppressed(failure);throw retry;}
        }
    }
    private static long createModules(byte[] uuid,boolean full,boolean fast,boolean ir,String cache)throws IOException {
        String suffix=(fast?"_fast":"")+(ir?".optixir":".ptx");
        return com.voxellight.nvidia.OptixNative.create(uuid,new byte[][]{resource("rt_hit"+suffix),resource((full?"rt_reference":"rt_realtime")+suffix),full?new byte[0]:resource("rt_caustics"+suffix),resource("rt_utility.ptx")},full,cache);
    }
    private static byte[] uncheckedResource(String name){try{return resource(name);}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}}
    private static byte[] resource(String name) throws IOException {
        try(var input=OptixBridge.class.getResourceAsStream("/voxellight/native/"+name)) {
            if(input==null)throw new IOException("Native OptiX component missing; use the native-enabled client kit");return input.readAllBytes();
        }
    }
    static native long create(byte[] uuid,byte[] ptx,int width,int height);
    static native int trace(long handle,ByteBuffer positions,ByteBuffer normals,ByteBuffer albedo,ByteBuffer voxels,ByteBuffer settings,ByteBuffer output,boolean reset,boolean denoise);
    static native void destroy(long handle);
}
