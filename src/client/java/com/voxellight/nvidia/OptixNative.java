package com.voxellight.nvidia;
import java.nio.ByteBuffer;
/** Batched scene updates and one GPU-only lighting launch per frame; no per-triangle JNI. */
public final class OptixNative {
 private OptixNative(){}
 public static native int initializationStage();
 public static native long initializationTasks();
 public static native long create(byte[] uuid,byte[] ptx);
 public static native void importBuffer(long context,long externalHandle,long allocationSize,long bufferSize);
 public static native void importSemaphore(long context,long externalHandle);
 public static native void updateInstances(long context,ByteBuffer batch);
 public static native void updateSections(long context,ByteBuffer batch);
 public static native void invalidate(long context,ByteBuffer regions);
 public static native void render(long context,ByteBuffer settings,int width,int height,int atlasWidth,int atlasHeight,int idsWidth,int idsHeight,int options,int debug);
 public static native void reference(long context,int spp,boolean reset,float clamp);
 public static native void benchmark(long context);
 public static native long[] stats(long context);
 public static native long[] timings(long context);
 public static native void destroy(long context);
}
