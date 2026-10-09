package com.voxellight.rt;
import java.nio.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtGgxEnergyTest {
 @Test void uploadedAssetMatchesCanonicalNativeTable() throws Exception {
  String source=Files.readString(Path.of("native/rt/ggx_energy.h"));
  int start=source.indexOf("{",source.indexOf("GgxEnergy[1024]"));
  int end=source.indexOf("};",start);
  assertTrue(start>=0&&end>start);
  var numbers=java.util.regex.Pattern.compile("(?:[0-9]+\\.[0-9]+|[0-9]+)[fF]").matcher(source.substring(start+1,end));
  var data=RtGgxEnergy.data();assertTrue(data.isDirect());assertEquals(ByteOrder.LITTLE_ENDIAN,data.order());
  int count=0;
  while(numbers.find()){assertEquals(Float.floatToRawIntBits(Float.parseFloat(numbers.group())),data.getInt(),"cell "+count);count++;}
  assertEquals(1024,count);assertFalse(data.hasRemaining());
  var fresh=RtGgxEnergy.data();assertEquals(RtGgxEnergy.BYTES,fresh.remaining());assertNotSame(data,fresh);
 }
}
