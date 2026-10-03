package com.voxellight.adapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.util.zip.ZipFile;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class OptixBridgeTest {
 @Test void shippedNativeComponentExportsCallableJniAndRejectsInvalidInput() throws Exception {
  Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
  try(var zip=new ZipFile(Path.of(System.getProperty("voxellight.modJar")).toFile())){
   Assumptions.assumeTrue(zip.getEntry("voxellight/native/libvoxellight_optix.so")!=null);
   assertNotNull(zip.getEntry("voxellight/native/voxellight_optix.dll"));
   assertNotNull(zip.getEntry("voxellight/native/licenses/optix_stubs.h.txt"));
  }
  byte[] ptx=OptixBridge.load();assertTrue(new String(ptx,java.nio.charset.StandardCharsets.UTF_8).contains(".entry paths"));
  var ex=assertThrows(IllegalStateException.class,()->OptixBridge.create(new byte[15],ptx,1,1));assertTrue(ex.getMessage().contains("16 bytes"));
  assertThrows(IllegalStateException.class,()->OptixBridge.create(new byte[16],new byte[0],1,1));
  // A nonexistent UUID must fail safely on both GPU-less and NVIDIA build hosts.
  var missing=assertThrows(IllegalStateException.class,()->OptixBridge.create(new byte[16],ptx,1,1));
  assertTrue(missing.getMessage().contains("CUDA"),missing.getMessage());
 }
}
