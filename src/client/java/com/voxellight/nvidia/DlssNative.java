package com.voxellight.nvidia;

import java.nio.file.*;
import java.util.Locale;

/** JNI calls record NGX work on Minecraft's existing Vulkan command buffer. */
public final class DlssNative {
    private static Path directory;
    private DlssNative(){}
    public static synchronized Path load(){
        if(directory!=null)return directory;
        String platform=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"windows-x86_64":"linux-x86_64";
        String helper=platform.startsWith("windows")?"voxellight_dlss.dll":"libvoxellight_dlss.so";
        String runtime=platform.startsWith("windows")?"nvngx_dlssd.dll":"libnvidia-ngx-dlssd.so.310.9.1";
        try{
            var folder=Files.createTempDirectory("voxellight-dlss-");
            for(String name:new String[]{helper,runtime})try(var input=DlssNative.class.getResourceAsStream("/voxellight/dlss/"+platform+"/"+name)){
                if(input==null)throw new IllegalStateException("DLSS RR runtime not packaged for "+platform);
                Files.copy(input,folder.resolve(name));folder.resolve(name).toFile().deleteOnExit();
            }
            if(platform.startsWith("linux")){Files.createSymbolicLink(folder.resolve("libnvidia-ngx-dlssd.so"),Path.of(runtime));folder.resolve("libnvidia-ngx-dlssd.so").toFile().deleteOnExit();}
            System.load(folder.resolve(helper).toAbsolutePath().toString());folder.toFile().deleteOnExit();directory=folder;return directory;
        }catch(java.io.IOException|UnsatisfiedLinkError error){throw new IllegalStateException("DLSS RR helper unavailable",error);}
    }
    public static native long create(long instance,long physical,long device,long getInstanceProc,long getDeviceProc,String directory);
    public static native String[] extensions(boolean device);
    public static native int[] optimal(long session,int width,int height,int quality);
    public static native void evaluate(long session,long command,long[] images,int width,int height,int outputWidth,int outputHeight,int quality,float jitterX,float jitterY,boolean reset,float frameMs,float[] matrices);
    public static native void destroy(long session);
}
