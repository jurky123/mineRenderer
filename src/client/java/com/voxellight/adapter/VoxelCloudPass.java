package com.voxellight.adapter;
import com.mojang.blaze3d.*;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;
/** Quarter-resolution voxel silhouettes with density erosion and camera-aware temporal reconstruction. */
final class VoxelCloudPass implements AutoCloseable {
 static final RenderPipeline CLOUD=pipeline();
 private final GpuTexture[] textures=new GpuTexture[2];private final GpuTextureView[] views=new GpuTextureView[2];
 private GpuBuffer controls;private int width,height,read,debug;private boolean enabled=false,active,valid;
 void enabled(boolean value){enabled=value;if(!value)close();}
 void debug(int value){debug=value;valid=false;}
 boolean enabled(){return enabled;}
 void render(CommandEncoder encoder,RenderTarget output,ShadowRenderer shadows,GpuBuffer environment,GpuBuffer lighting,MotionFrame motion){
  active=false;if(!enabled||!motion.ready())return;int w=(output.width+3)/4,h=(output.height+3)/4;
  var device=RenderSystem.getDevice();if((long)w*h*16>16L*1024*1024||!device.precompilePipeline(CLOUD,RenderProbe.SHADERS).isValid()){close();return;}
  if(textures[0]==null||width!=w||height!=h){close();width=w;height=h;for(int i=0;i<2;i++){textures[i]=device.createTexture("VoxelLight voxel cloud history "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,w,h,1,1);views[i]=device.createTextureView(textures[i]);}controls=device.createBuffer(()->"VoxelLight voxel cloud controls",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,16);}
  try(var stack=MemoryStack.stackPush()){encoder.writeToBuffer(controls.slice(),Std140Builder.onStack(stack,16).putVec4(valid?1:0,debug,0,0).get());}
  int write=1-read;var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
  try(var profile=RenderPassProfile.begin(encoder,"voxel_cloud");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight voxel volumetric clouds").withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(views[write],Optional.of(new Vector4f(0))))){
   pass.setPipeline(CLOUD);pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);pass.bindTexture("CloudHistory",views[read],nearest);pass.setUniform("EnvironmentSettings",environment);pass.setUniform("LightingEnvironment",lighting);pass.setUniform("CloudSettings",controls);motion.bindSettings(pass);shadows.bindTransform(pass);pass.draw(3,1,0,0);
  }read=write;active=valid=true;
 }
 void bind(RenderPass pass,GpuTextureView fallback){pass.bindTexture("VoxelCloud",active?views[read]:fallback,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));}
 boolean active(){return active;}
 String status(){return ", voxelClouds="+(active?"quarter-res macro cells + fuzzy density":enabled?"waiting":"off")+", cloudBytes="+(active?(long)width*height*16:0);}
 public void close(){for(int i=0;i<2;i++){if(views[i]!=null){views[i].close();views[i]=null;}if(textures[i]!=null){textures[i].close();textures[i]=null;}}if(controls!=null){controls.close();controls=null;}active=valid=false;read=0;}
 static RenderPipeline pipeline(){return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/voxel_cloud")).withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","voxel_cloud")).withBindGroupLayout(BindGroupLayout.builder().withSampler("SceneDepth").withSampler("CloudHistory").withUniform("EnvironmentSettings",UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment",UniformType.UNIFORM_BUFFER).withUniform("CloudSettings",UniformType.UNIFORM_BUFFER).withUniform("MotionSettings",UniformType.UNIFORM_BUFFER).withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER).build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();}
}
