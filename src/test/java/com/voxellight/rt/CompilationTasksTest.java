package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class CompilationTasksTest {
 @TempDir Path temp;
 @Test void nativePoolExecutesDependentTasksOnceAndJoinsBeforeFailure() throws Exception {
  var exe=temp.resolve("tasks");var log=temp.resolve("build.log");
  var build=new ProcessBuilder("g++","-std=c++17","-O2","-pthread","native/rt/tests/task_pool_test.cpp","-o",exe.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  assertTrue(build.waitFor(30,TimeUnit.SECONDS));assertEquals(0,build.exitValue(),Files.readString(log));
  var run=new ProcessBuilder(exe.toString()).start();try{assertTrue(run.waitFor(10,TimeUnit.SECONDS),"Task pool deadlocked");assertEquals(0,run.exitValue());}finally{run.destroyForcibly();}
 }
}
