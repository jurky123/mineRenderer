package com.voxellight.adapter;
import com.mojang.blaze3d.vertex.MeshData;
import com.voxellight.world.SectionKey;
import net.minecraft.client.renderer.chunk.*;
import java.nio.*;
import java.util.*;
/** Compilation-time snapshot: copies the native compiled triangles, never recompiles or re-rasterizes terrain. */
public final class RtGeometryStream {
 public record Section(SectionKey key,long version,byte[] triangles){public int vertices(){return triangles.length/40;}}
 private static boolean enabled;

 private record Snapshot(SectionKey key,long revision,long generation){}
 private static final Map<RenderSectionRegion,Snapshot> snapshots=Collections.synchronizedMap(new WeakHashMap<>());
 public static void snapshot(RenderSectionRegion region,int x,int y,int z){if(region!=null&&enabled())snapshots.put(region,new Snapshot(new SectionKey(x,y,z),com.voxellight.rt.RtInvalidationQueue.revision(x,y,z),epoch()));}
 private static boolean current(RenderSectionRegion region){var snapshot=snapshots.get(region);return snapshot==null||snapshot.generation==epoch&&snapshot.revision==com.voxellight.rt.RtInvalidationQueue.revision(snapshot.key.x(),snapshot.key.y(),snapshot.key.z());}

 private static final ThreadLocal<Long> compileGeneration=new ThreadLocal<>();
 public static long compileEpoch(){var value=compileGeneration.get();return value==null?epoch():value;}
 public static void withGeneration(long generation,Runnable task){compileGeneration.set(generation);try{task.run();}finally{compileGeneration.remove();}}
 private static long epoch,bytes;
 private static final LinkedHashMap<SectionKey,Section> pending=new LinkedHashMap<>();
 private RtGeometryStream(){}
 public static synchronized long epoch(){return epoch;}
 public static synchronized void enable(boolean value){enabled=value;com.voxellight.rt.RtInvalidationQueue.enabled(value);pending.clear();snapshots.clear();bytes=0;epoch++;}
 public static synchronized boolean enabled(){return enabled;}
 public static void compiled(int x,int y,int z,SectionCompiler.Results results,long generation,RenderSectionRegion region){
  synchronized(RtGeometryStream.class){if(!enabled||generation!=epoch||!current(region))return;}
  int count=0;for(var mesh:results.renderedLayers.values())if(mesh.drawState().format()==NativeTerrainAttributes.FORMAT)count+=mesh.vertexBuffer().remaining()/36/4*6;
  if(count>600000)return;
  var out=ByteBuffer.allocate(count*40).order(ByteOrder.nativeOrder());
  for(var entry:results.renderedLayers.entrySet()){
   var mesh=entry.getValue();if(mesh.drawState().format()!=NativeTerrainAttributes.FORMAT)continue;
   var in=mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());int quads=in.remaining()/144;
   for(int q=0;q<quads;q++){
    int thin=0;if(region!=null){float px=0,py=0,pz=0;for(int v=0;v<4;v++){int offset=in.position()+(q*4+v)*36;px+=in.getFloat(offset)*.25f;py+=in.getFloat(offset+4)*.25f;pz+=in.getFloat(offset+8)*.25f;}var block=region.getBlockState(net.minecraft.core.BlockPos.containing(x*16.+px,y*16.+py,z*16.+pz)).getBlock();if(block==net.minecraft.world.level.block.Blocks.GLASS_PANE||block instanceof net.minecraft.world.level.block.StainedGlassPaneBlock)thin=8;}
    for(int vertex:new int[]{0,1,2,2,3,0}){
    int offset=in.position()+(q*4+vertex)*36;for(int f=0;f<3;f++)out.putFloat(in.getFloat(offset+f*4));
    out.putFloat(in.getFloat(offset+16)).putFloat(in.getFloat(offset+20));
    for(int f=0;f<3;f++)out.putFloat(in.get(offset+32+f)/127f);
    int packed=in.getInt(offset+28),tint=((packed>>16)&255)|((packed>>8)&255)<<8|(packed&255)<<16|0xff000000;
    int marker=in.get(offset+35),emission=Math.clamp(marker-16,0,15);
    if(marker<16)tint=in.getInt(offset+12);
    int flags=(entry.getKey()==ChunkSectionLayer.CUTOUT?1:0)|(entry.getKey()==ChunkSectionLayer.TRANSLUCENT?2:0)|(emission<<16);
    int sky=(in.getInt(offset+24)>>>20)&15;out.putInt(tint).putInt(flags|thin|(sky<<8));
   }}
  }
  synchronized(RtGeometryStream.class){if(!enabled||generation!=epoch||!current(region))return;var key=new SectionKey(x,y,z);var old=pending.put(key,new Section(key,geometryVersion(out.array()),out.array()));if(old!=null)bytes-=old.triangles.length;bytes+=out.capacity();
   while(bytes>64L*1024*1024||pending.size()>1024){var iterator=pending.entrySet().iterator();bytes-=iterator.next().getValue().triangles.length;iterator.remove();}
  }
 }
 private static long geometryVersion(byte[] bytes){long hash=0xcbf29ce484222325L;for(byte b:bytes){hash^=b&255;hash*=0x100000001b3L;}return hash;}
 public static synchronized List<Section> drain(int limit){var result=new ArrayList<Section>();var i=pending.values().iterator();while(i.hasNext()&&result.size()<limit){var section=i.next();result.add(section);bytes-=section.triangles.length;i.remove();}return result;}
 public static synchronized int pending(){return pending.size();}
}
