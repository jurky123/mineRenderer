package com.voxellight.adapter;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;

/** Entity and block-entity streams share dynamic depth, independently of static terrain caches. */
final class DynamicCasterSystem implements AutoCloseable {
    private final EntityShadows entities=new EntityShadows();
    private final BlockEntityShadows blocks=new BlockEntityShadows();
    void prepare(){entities.prepare();blocks.prepare();}
    void upload(CommandEncoder encoder){entities.upload(encoder);blocks.upload(encoder);}
    void prepareIndices(){
        // Terrain draws may grow the shared sequential buffer between cascades. Borrow the current
        // buffer once for both streams, after terrain and before opening the dynamic render pass.
        var sequence=com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(com.mojang.blaze3d.PrimitiveTopology.QUADS);
        var indices=sequence.getBuffer(Math.max(entities.maxIndices(),blocks.maxIndices()));
        entities.borrowIndices(indices,sequence.type());blocks.borrowIndices(indices,sequence.type());
    }
    boolean hasModels(){return entities.hasModels() || blocks.hasModels();}
    void draw(RenderPass pass){entities.draw(pass);blocks.draw(pass);}
    void setEntitiesEnabled(boolean enabled){entities.setEnabled(enabled);}
    void setBlocksEnabled(boolean enabled){blocks.setEnabled(enabled);}
    String status(){return entities.status()+blocks.status();}
    @Override public void close(){entities.close();blocks.close();}
}
