package com.voxellight.adapter;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.*;
import java.nio.*;
import java.util.*;
/** Native posed models are untransformed into local GAS geometry; world transforms become IAS instances. */
public final class RtDynamicStream {
 public record Model(long id,long version,byte[] geometry,GpuTextureView texture,float[] transform){ }
 private static int frameBytes;
 private static Object level;
 private static final Map<Object,java.lang.ref.WeakReference<net.minecraft.world.entity.Entity>> sources=new WeakHashMap<>();
 private static final Map<Object,java.lang.ref.WeakReference<net.minecraft.world.level.block.entity.BlockEntity>> blockSources=new WeakHashMap<>();
 public static void associateBlock(Object state,net.minecraft.world.level.block.entity.BlockEntity block){if(state!=null)blockSources.put(state,new java.lang.ref.WeakReference<>(block));}
 private record Retained(Model model,java.lang.ref.WeakReference<net.minecraft.world.entity.Entity> entity,java.lang.ref.WeakReference<net.minecraft.world.level.block.entity.BlockEntity> block,double x,double y,double z,long seen){}
 private static final Map<Long,Retained> retained=new HashMap<>();
 public static void associate(Object state,net.minecraft.world.entity.Entity entity){sources.put(state,new java.lang.ref.WeakReference<>(entity));}
 public static void clear(){retained.clear();sources.clear();blockSources.clear();frame.clear();level=null;}

 private static final List<Model> frame=new ArrayList<>();
 private static final Map<VertexConsumer,Capture> captures=new IdentityHashMap<>();
 private record Capture(BufferBuilder builder,ByteBufferBuilder memory,MaterialVertexTee tee,ModelFeatureRenderer.Submit<?> submit){ }
 private static float canonical(float value){return (float)(java.lang.Math.rint(value*10000.0)/10000.0);}
 private RtDynamicStream(){}
 public static void begin(){var mc=net.minecraft.client.Minecraft.getInstance();var current=mc==null?null:mc.level;if(level!=current){clear();level=current;}frame.clear();frameBytes=0;}
 public static VertexConsumer consumer(ModelFeatureRenderer.Submit<?> submit,VertexConsumer downstream){
  if(!RtGeometryStream.enabled()||frame.size()+captures.size()>=64||(EntityMaterials.style(submit.renderType().pipeline())<0||EntityMaterials.style(submit.renderType().pipeline())==3)||submit.sheetedDecalPose()!=null)return downstream;
  var memory=new ByteBufferBuilder(4096,256*1024);var builder=new BufferBuilder(memory,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);var tee=new MaterialVertexTee(downstream,submit.sprite()==null?builder:submit.sprite().wrap(builder));captures.put(tee,new Capture(builder,memory,tee,submit));return tee;
 }
 public static void finish(VertexConsumer consumer,boolean success){var capture=captures.remove(consumer);if(capture==null)return;
  try(var memory=capture.memory){if(!success||capture.tee.failed())return;try(var mesh=capture.builder.build()){if(mesh==null)return;var submit=capture.submit;var texture=submit.renderType().prepare().textures().stream().filter(t->t.name().equals("Sampler0")).map(t->t.textureView()).findFirst().orElse(null);if(texture==null)return;
   var data=mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());int count=mesh.drawState().vertexCount();if(count%4!=0||count>16384)return;int stride=DefaultVertexFormat.ENTITY.getVertexSize();var out=ByteBuffer.allocate(count/4*6*40).order(ByteOrder.nativeOrder());var inverse=new Matrix4f(submit.pose().pose()).invert();var normalInverse=new Matrix3f(submit.pose().normal()).invert();var position=new Vector3f();var normal=new Vector3f();
   for(int quad=0;quad<count/4;quad++)for(int index:new int[]{0,1,2,2,3,0}){int offset=data.position()+(quad*4+index)*stride;inverse.transformPosition(data.getFloat(offset),data.getFloat(offset+4),data.getFloat(offset+8),position);out.putFloat(canonical(position.x)).putFloat(canonical(position.y)).putFloat(canonical(position.z)).putFloat(data.getFloat(offset+16)).putFloat(data.getFloat(offset+20));normal.set(data.get(offset+32)/127f,data.get(offset+33)/127f,data.get(offset+34)/127f);normalInverse.transform(normal).normalize();out.putFloat(canonical(normal.x)).putFloat(canonical(normal.y)).putFloat(canonical(normal.z)).putInt(data.getInt(offset+12)).putInt(1|16);}
   long hash=0xcbf29ce484222325L;for(byte value:out.array()){hash^=value&255;hash*=0x100000001b3L;}var transform=new Matrix4f(submit.pose().pose());var camera=net.minecraft.client.Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;transform.m30(transform.m30()+(float)camera.x()).m31(transform.m31()+(float)camera.y()).m32(transform.m32()+(float)camera.z());float[] matrix=new float[12];for(int row=0;row<3;row++)for(int col=0;col<4;col++)matrix[row*4+col]=transform.get(col,row);
   var source=sources.get(submit.state());var entity=source==null?null:source.get();var blockSource=blockSources.get(submit.state());var block=blockSource==null?null:blockSource.get();long owner=entity!=null?entity.getId():block!=null?block.getBlockPos().asLong():System.identityHashCode(submit.state());
   long id=(owner*0x9e3779b97f4a7c15L)^((long)System.identityHashCode(submit.model())<<32)^System.identityHashCode(submit.renderType());if(frameBytes+out.capacity()>2*1024*1024)return;frameBytes+=out.capacity();var model=new Model(id,hash,out.array(),texture,matrix);frame.add(model);
   retained.put(id,new Retained(model,source,blockSource,entity==null?0:entity.getX(),entity==null?0:entity.getY(),entity==null?0:entity.getZ(),System.nanoTime()));
  }}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").debug("Unsupported RT dynamic model retained natively",error);}
 }
 static List<Model> snapshot(){
  var mc=net.minecraft.client.Minecraft.getInstance();if(mc==null||!RtGeometryStream.enabled()||mc.level!=level){clear();return List.of();}
  var camera=mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;long now=System.nanoTime();var result=new ArrayList<Model>();
  var iterator=retained.values().iterator();while(iterator.hasNext()){var item=iterator.next();var entity=item.entity==null?null:item.entity.get();
   var block=item.block==null?null:item.block.get();
   if(item.entity!=null&&(entity==null||entity.isRemoved())||item.block!=null&&(block==null||block.isRemoved()||!mc.level.getChunkSource().hasChunk(block.getBlockPos().getX()>>4,block.getBlockPos().getZ()>>4))||item.entity==null&&item.block==null&&now-item.seen>2_000_000_000L){iterator.remove();continue;}
   var model=item.model;float[] transform=model.transform.clone();if(entity!=null){transform[3]+=entity.getX()-item.x;transform[7]+=entity.getY()-item.y;transform[11]+=entity.getZ()-item.z;}
   double dx=transform[3]-camera.x(),dy=transform[7]-camera.y(),dz=transform[11]-camera.z();if(dx*dx+dy*dy+dz*dz>128*128){iterator.remove();continue;}
   result.add(new Model(model.id,model.version,model.geometry,model.texture,transform));
  }
  result.sort(Comparator.comparingDouble(model->{var t=model.transform;double dx=t[3]-camera.x(),dy=t[7]-camera.y(),dz=t[11]-camera.z();return dx*dx+dy*dy+dz*dz;}));
  var selected=List.copyOf(result.subList(0,java.lang.Math.min(64,result.size())));var keep=new HashSet<Long>();for(var model:selected)keep.add(model.id);retained.keySet().retainAll(keep);return selected;
 }
}
