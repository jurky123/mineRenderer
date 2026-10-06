package com.voxellight.adapter;
import com.voxellight.world.SectionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.*;
import java.util.concurrent.*;
/** Builds missing loaded RT sections with the native compiler, independently of the camera frustum.
 * CPU backing pages are restored before compiling; results feed the Vulkan BLAS stream. */
final class RtTerrainWarmup implements AutoCloseable {
 private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"VoxelLight RT terrain admission");t.setDaemon(true);return t;});
 private SectionBufferBuilderPack buffers;private volatile long compileNanos;
 long compileNanos(){return compileNanos;}
 private final Set<SectionKey> requested=new LinkedHashSet<>();
 Set<SectionKey> requestPages(Set<SectionKey> keys){var mc=Minecraft.getInstance();var accepted=new LinkedHashSet<SectionKey>();if(mc.level==null)return accepted;for(var key:keys){var chunk=mc.level.getChunkSource().getChunk(key.x(),key.z(),ChunkStatus.FULL,false);int index=mc.level.getSectionIndexFromSectionY(key.y());if(chunk!=null&&index>=0&&index<chunk.getSections().length&&!chunk.getSections()[index].hasOnlyAir()){accepted.add(key);if(requested.size()<256)requested.add(key);}}return accepted;}
 private Future<?> future;private List<SectionKey> candidates=List.of();private SectionKey center;private long epoch=-1;private final Set<SectionKey> attempted=new HashSet<>();private long refresh;
 void prepare(Set<SectionKey> resident,double x,double y,double z){
  if(future!=null&&!future.isDone())return;var mc=Minecraft.getInstance();if(mc.level==null||!RtGeometryStream.enabled())return;var camera=new SectionKey((int)Math.floor(x/16),(int)Math.floor(y/16),(int)Math.floor(z/16));long generation=RtGeometryStream.epoch();
  if(!camera.equals(center)||epoch!=generation||System.nanoTime()-refresh>2_000_000_000L){epoch=generation;center=camera;refresh=System.nanoTime();attempted.clear();var keys=new ArrayList<SectionKey>();int radius=Math.clamp(mc.options.renderDistance().get(),6,16);for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){var chunk=mc.level.getChunkSource().getChunk(camera.x()+dx,camera.z()+dz,ChunkStatus.FULL,false);if(chunk==null)continue;for(int dy=-6;dy<=6;dy++){int sectionY=camera.y()+dy,index=mc.level.getSectionIndexFromSectionY(sectionY);if(index>=0&&index<chunk.getSections().length&&!chunk.getSections()[index].hasOnlyAir())keys.add(new SectionKey(camera.x()+dx,sectionY,camera.z()+dz));}}keys.sort(Comparator.comparingLong(k->{long ax=k.x()-camera.x(),ay=k.y()-camera.y(),az=k.z()-camera.z();return ax*ax+ay*ay+az*az;}));candidates=List.copyOf(keys.subList(0,Math.min(4096,keys.size())));}
  var work=new ArrayList<SectionKey>(requested);work.addAll(candidates);requested.clear();
  for(var key:work){if(resident.contains(key)||!attempted.add(key))continue;if(RtGeometryStream.restore(key))break;var region=new RenderRegionCache().createRegion(mc.level,SectionPos.asLong(key.x(),key.y(),key.z()));if(region==null)continue;var manager=mc.getModelManager();var compiler=new SectionCompiler(mc.options.ambientOcclusion().get(),mc.options.cutoutLeaves().get(),manager.getBlockStateModelSet(),manager.getFluidStateModelSet(),mc.getBlockColors());
   future=executor.submit(()->{if(RtGeometryStream.epoch()!=generation)return;long start=System.nanoTime();try{if(buffers==null)buffers=new SectionBufferBuilderPack();RtGeometryStream.withGeneration(generation,()->{var result=compiler.compile(SectionPos.of(key.x(),key.y(),key.z()),region,VertexSorting.DISTANCE_TO_ORIGIN,buffers);result.release();});buffers.clearAll();compileNanos=System.nanoTime()-start;}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").debug("RT section warmup deferred",error);} });break;
  }
 }
 public void close(){executor.submit(()->{if(buffers!=null){buffers.close();buffers=null;}});if(future!=null)future.cancel(false);future=null;center=null;candidates=List.of();attempted.clear();requested.clear();}
}
