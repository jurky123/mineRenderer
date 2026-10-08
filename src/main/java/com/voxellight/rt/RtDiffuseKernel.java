package com.voxellight.rt;

import java.io.IOException;
import java.nio.*;

/** Versioned preintegrated BSDF asset, uploaded once per material-resource lifetime. */
public final class RtDiffuseKernel {
    public static final int BYTES=33*8+33*65*12;
    private static final byte[] DATA=load();
    private RtDiffuseKernel(){}
    private static byte[] load(){
        try(var input=RtDiffuseKernel.class.getResourceAsStream("/assets/voxellight/rt/diffuse-kernel.bin")){
            if(input==null)throw new IOException("missing diffuse kernel asset");
            byte[] bytes=input.readAllBytes();
            if(bytes.length!=16+BYTES)throw new IOException("diffuse kernel size mismatch");
            var header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(header.getInt()!=0x314b4456||header.getInt()!=33||header.getInt()!=65||header.getInt()!=4096)throw new IOException("diffuse kernel ABI mismatch");
            return java.util.Arrays.copyOfRange(bytes,16,bytes.length);
        }catch(IOException e){throw new IllegalStateException("Invalid preintegrated diffuse kernel",e);}
    }
    public static ByteBuffer data(){return ByteBuffer.allocateDirect(BYTES).order(ByteOrder.LITTLE_ENDIAN).put(DATA).flip();}
}
