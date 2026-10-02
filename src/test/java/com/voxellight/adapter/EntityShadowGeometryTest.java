package com.voxellight.adapter;

import com.mojang.blaze3d.vertex.*;
import com.voxellight.world.DynamicCasterSelection;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityShadowGeometryTest {
    private Model<Integer> animatedCube() {
        var cube = new ModelPart.Cube(0, 0, 0, 0, 0, 16, 16, 16, 0, 0, 0, false, 64, 64, EnumSet.allOf(Direction.class));
        var part = new ModelPart(List.of(cube), Map.of());
        return new Model<>(part, ignored -> null) {
            @Override public void setupAnim(Integer state) { root().x = state * 16; }
        };
    }
    @Test void nativeAnimatedModelsProduceThePrivateCasterFormatAndWorldRelativePose() {
        try (var scratch = new ByteBufferBuilder(4096, DynamicCasterSelection.MODEL_BYTES)) {
            var pose = new PoseStack(); pose.translate(4, 5, 6);
            var model = animatedCube();
            try (var mesh = EntityShadows.buildModel(scratch, model, 2, pose, 0, 0, -1, null)) {
                assertNotNull(mesh); assertEquals(24, mesh.drawState().vertexCount()); assertEquals(36, mesh.drawState().indexCount());
                var data = mesh.vertexBuffer(); assertEquals(24 * DefaultVertexFormat.BLOCK.getVertexSize(), data.remaining());
                for (int i=0;i<24;i++) {
                    int offset = i * 28;
                    assertTrue(data.getFloat(offset) >= 6 && data.getFloat(offset) <= 7);
                    assertTrue(data.getFloat(offset+4) >= 5 && data.getFloat(offset+4) <= 6);
                    assertTrue(data.getFloat(offset+8) >= 6 && data.getFloat(offset+8) <= 7);
                }
            }
            scratch.clear();
            try (var mesh = EntityShadows.buildModel(scratch, model, 3, pose, 0, 0, -1, null)) {
                assertNotNull(mesh);
                for (int i=0;i<24;i++) assertTrue(mesh.vertexBuffer().getFloat(i*28) >= 7);
            }
        }
    }
    @Test void oversizedModelCaptureCannotGrowPastItsNativeAllocationLimit() {
        try (var scratch = new ByteBufferBuilder(28, 28)) {
            assertThrows(IllegalArgumentException.class, () -> EntityShadows.buildModel(scratch, animatedCube(), 0, new PoseStack(), 0, 0, -1, null));
        }
    }
}
