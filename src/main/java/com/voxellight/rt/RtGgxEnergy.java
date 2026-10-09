package com.voxellight.rt;

import java.io.IOException;
import java.nio.*;

/** Canonical GGX energy table; one upload per material-resource lifetime. */
public final class RtGgxEnergy {
    public static final int BYTES=1024*Float.BYTES;
    public static final int HEADER_OFFSET=248;
    private static final byte[] DATA=load();
    private RtGgxEnergy(){}
    private static byte[] load(){
        try(var in=RtGgxEnergy.class.getResourceAsStream("/assets/voxellight/rt/ggx-energy.bin")){
            if(in==null)throw new IOException("missing GGX energy asset");
            byte[] bytes=in.readAllBytes();if(bytes.length!=BYTES)throw new IOException("invalid GGX energy asset size");
            var values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            while(values.hasRemaining()){float v=values.getFloat();if(!Float.isFinite(v)||v<0||v>1)throw new IOException("invalid GGX energy value");}
            return bytes;
        }catch(IOException e){throw new IllegalStateException("GGX energy asset unavailable",e);}
    }
    public static ByteBuffer data(){return ByteBuffer.allocateDirect(BYTES).order(ByteOrder.LITTLE_ENDIAN).put(DATA).flip();}
}
