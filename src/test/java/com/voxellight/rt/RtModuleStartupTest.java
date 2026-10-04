package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
class RtModuleStartupTest {
 @Test void defaultPtxDoesNotLoadIr(){assertEquals(7,RtModuleStartup.start(false,()->{throw new AssertionError();},()->new byte[]{2},code->7,error->{throw new AssertionError();}));}
 @Test void compilerFailureRetriesPtxOnceAndReportsFullError(){var calls=new ArrayList<Byte>();var errors=new ArrayList<RuntimeException>();assertEquals(9,RtModuleStartup.start(true,()->new byte[]{1},()->new byte[]{2},code->{calls.add(code[0]);if(code[0]==1)throw new IllegalStateException("RT module tasks: OptiX error 7251; decisive compiler error");return 9;},errors::add));assertEquals(java.util.List.of((byte)1,(byte)2),calls);assertEquals(1,errors.size());assertTrue(errors.get(0).getMessage().contains("decisive compiler error"));}
 @Test void deviceFailureDoesNotRetry(){var failure=new IllegalStateException("CUDA device mismatch");assertSame(failure,assertThrows(IllegalStateException.class,()->RtModuleStartup.start(true,()->new byte[]{1},()->{throw new AssertionError();},code->{throw failure;},error->{throw new AssertionError();})));}
 @Test void failedRetryPreservesIrCause(){var retry=new IllegalStateException("PTX compilation failed");var error=assertThrows(IllegalStateException.class,()->RtModuleStartup.start(true,()->new byte[]{1},()->new byte[]{2},code->{if(code[0]==1)throw new IllegalStateException("OptiX error 7251");throw retry;},failure->{}));assertSame(retry,error);assertEquals(1,error.getSuppressed().length);}
}
