package com.voxellight.nvidia;

/** Denoiser-only bridge. The trace pipeline remains entirely Vulkan. */
public final class OptixDenoiserNative {
    private static boolean loaded;
    private OptixDenoiserNative(){}
    public static synchronized void load(){
        if(loaded)return;
        String platform=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")?"windows-x86_64":"linux-x86_64";
        String name=platform.startsWith("windows")?"voxellight_denoiser.dll":"libvoxellight_denoiser.so";
        try(var input=OptixDenoiserNative.class.getResourceAsStream("/voxellight/denoiser/"+platform+"/"+name)){
            if(input==null)throw new IllegalStateException("OptiX denoiser helper is not packaged for "+platform);
            var directory=java.nio.file.Files.createTempDirectory("voxellight-denoiser-");var file=directory.resolve(name);java.nio.file.Files.copy(input,file);file.toFile().deleteOnExit();directory.toFile().deleteOnExit();System.load(file.toAbsolutePath().toString());loaded=true;
        }catch(java.io.IOException|UnsatisfiedLinkError error){throw new IllegalStateException("OptiX denoiser helper unavailable",error);}
    }
    public static native long create(byte[] uuid,long memory,long allocation,long ready,long done,int width,int height);
    public static native void invoke(long handle,boolean previousValid);
    public static native void destroy(long handle);
}
