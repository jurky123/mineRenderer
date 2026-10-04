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
    static byte[] loadRt() throws IOException {load();return resource("rt_program.ptx");}
    private static byte[] resource(String name) throws IOException {
        try(var input=OptixBridge.class.getResourceAsStream("/voxellight/native/"+name)) {
            if(input==null)throw new IOException("Native OptiX component missing; use the native-enabled client kit");return input.readAllBytes();
        }
    }
    static native long create(byte[] uuid,byte[] ptx,int width,int height);
    static native int trace(long handle,ByteBuffer positions,ByteBuffer normals,ByteBuffer albedo,ByteBuffer voxels,ByteBuffer settings,ByteBuffer output,boolean reset,boolean denoise);
    static native void destroy(long handle);
}
