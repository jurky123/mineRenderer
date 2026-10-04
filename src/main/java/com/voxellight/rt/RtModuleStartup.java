package com.voxellight.rt;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.function.Consumer;
/** IR driver compilation failures retry PTX once; device/interop failures never retry. */
public final class RtModuleStartup {
    private RtModuleStartup(){}
    public static long start(boolean ir,Supplier<byte[]> irCode,Supplier<byte[]> ptxCode,ToLongFunction<byte[]> create,Consumer<RuntimeException> report){
        if(!ir)return create.applyAsLong(ptxCode.get());
        try{return create.applyAsLong(irCode.get());}
        catch(IllegalStateException failure){
            String message=failure.getMessage();
            if(message==null||!message.contains("OptiX error 7251"))throw failure;
            report.accept(failure);
            try{return create.applyAsLong(ptxCode.get());}
            catch(RuntimeException retry){retry.addSuppressed(failure);throw retry;}
        }
    }
}
