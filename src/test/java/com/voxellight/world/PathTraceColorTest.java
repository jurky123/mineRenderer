package com.voxellight.world;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PathTraceColorTest {
 @Test void encodedReflectanceDoesNotEnterThroughputAsLinear(){
  assertEquals(.21404114048223255,PathTraceColor.linear(.5),1.e-12);
  assertEquals(0,PathTraceColor.linear(0));assertEquals(1,PathTraceColor.linear(1));
  assertEquals(.02/12.92,PathTraceColor.linear(.02),1.e-12);
  assertTrue(Math.pow(PathTraceColor.linear(.5),3)<.01,"Three diffuse bounces must attenuate linear energy");
 }
}
