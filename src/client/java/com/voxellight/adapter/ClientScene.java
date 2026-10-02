package com.voxellight.adapter;

import com.voxellight.world.SectionKey;
import com.voxellight.world.CasterVolume;
import com.voxellight.world.ShadowLight;
import net.minecraft.world.phys.Vec3;
import com.voxellight.world.SectionSnapshot;
import com.voxellight.world.WorldSceneBridge;
import com.voxellight.world.LightUpdateScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Copies palettes on the client thread; the worker never reads the live world. */
public final class ClientScene implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoxelLight");
    private static final int MAX_JOBS = 2;
    private static final long COPY_BUDGET_NS = 2_000_000;
    private final WorldSceneBridge bridge = new WorldSceneBridge(CasterVolume.MAX_SECTIONS);
    private final LightUpdateScope lightUpdates = new LightUpdateScope();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_JOBS), runnable -> {
                var thread = new Thread(runnable, "VoxelLight-section-encode");
                thread.setDaemon(true);
                return thread;
            });
    private record Result(SectionSnapshot snapshot, long encodeNanos) { }
    private record Job(WorldSceneBridge.Request request, Future<Result> future) { }
    private final List<Job> jobs = new ArrayList<>();
    private volatile boolean enabled;
    private volatile ClientLevel level;
    private long copyNanos;
    private long encodeNanos;
    private long copied;
    private String state = "off";
    private boolean lightAware = true;
    private ShadowLight casterLight = ShadowLight.none();
    private Vec3 renderCamera;
    private int casterCandidates, casterEligible, casterDeferred;
    private float casterExtrusion;
    private long casterSelectionNanos;

    public void setLightAware(boolean value) { lightAware = value; }

    public void updateCasterVolume(Vec3 camera, ShadowLight light) {
        renderCamera = camera;
        casterLight = light;
        if (!enabled || level == null) return;
        long start = System.nanoTime();
        var center = SectionKey.fromBlock((int)Math.floor(camera.x()), (int)Math.floor(camera.y()), (int)Math.floor(camera.z()));
        var window = CasterVolume.select(center, light, lightAware,
                camera.x() - (center.x() * 16.0 + 8), camera.y() - (center.y() * 16.0 + 8), camera.z() - (center.z() * 16.0 + 8));
        var loaded = new HashMap<Long, Boolean>();
        var admission = CasterVolume.admit(window, level.getMinSectionY(), level.getMaxSectionY(), key -> {
            long column = ((long)key.x() << 32) ^ (key.z() & 0xffffffffL);
            return loaded.computeIfAbsent(column, ignored ->
                    level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false) != null);
        });
        bridge.reconcile(admission.sections());
        casterCandidates = window.candidates().size();
        casterEligible = admission.eligible();
        casterDeferred = admission.deferred();
        casterExtrusion = window.extrusion();
        casterSelectionNanos = System.nanoTime() - start;
    }

    public void setEnabled(boolean enabled) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Scene switch must run on client thread");
        this.enabled = enabled;
        changeLevel(Minecraft.getInstance().level);
        state = enabled ? "waiting for loaded sections" : "off";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public WorldSceneBridge bridge() {
        return bridge;
    }

    public void changeLevel(ClientLevel level) {
        this.level = level;
        bridge.changeWorld();
        renderCamera = null;
        casterLight = ShadowLight.none();
        casterCandidates = casterEligible = casterDeferred = 0;
        casterExtrusion = 0;
        cancelJobs();
        copyNanos = 0;
        encodeNanos = 0;
    }

    public void reloadResources() {
        bridge.reloadResources();
    }

    public void blockChanged(ClientLevel eventLevel, int x, int y, int z) {
        if (enabled && level == eventLevel) {
            // Boundary edits change the exposed model faces in adjacent sections too.
            var min = SectionKey.fromBlock(x - 1, y - 1, z - 1);
            var max = SectionKey.fromBlock(x + 1, y + 1, z + 1);
            bridge.markRangeDirty(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), WorldSceneBridge.GEOMETRY);
        }
    }

    public void sectionChanged(ClientLevel eventLevel, int x, int y, int z, int reason) {
        if (enabled && level == eventLevel) {
            reason = lightUpdates.sectionReason(reason);
            if (reason == WorldSceneBridge.GEOMETRY) {
                bridge.markRangeDirty(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1, reason);
            } else bridge.markDirty(new SectionKey(x, y, z), reason);
        }
    }

    public void lightSectionUpdate(Runnable update) {
        lightUpdates.run(update);
    }

    public void chunkChanged(ClientLevel eventLevel, int x, int z, boolean unload) {
        if (!enabled || level != eventLevel) return;
        if (unload) bridge.unloadChunk(x, z);
        // Loading/unloading a neighbor also changes exposed faces at chunk boundaries.
        bridge.markRangeDirty(x - 1, Integer.MIN_VALUE, z - 1, x + 1, Integer.MAX_VALUE, z + 1,
                unload ? WorldSceneBridge.GEOMETRY : WorldSceneBridge.LOAD | WorldSceneBridge.GEOMETRY);
    }

    public void rangeChanged(ClientLevel eventLevel, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (enabled && level == eventLevel) {
            bridge.markRangeDirty(minX, minY, minZ, maxX, maxY, maxZ, WorldSceneBridge.GEOMETRY);
        }
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.level != level) {
            changeLevel(minecraft.level);
        }
        if (!enabled || level == null || minecraft.getCameraEntity() == null) return;
        try {
            var body = minecraft.getCameraEntity().position();
            // Reuse the previous frame's actual camera (including third person) until a teleport.
            var previous = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
            var camera = renderCamera != null && previous.distanceToSqr(body) < 256 ? previous : body;
            updateCasterVolume(camera, casterLight);
            for (var iterator = jobs.iterator(); iterator.hasNext();) {
                Job job = iterator.next();
                if (!job.future().isDone()) continue;
                Result result = job.future().get(); // isDone checked: no wait on a running job.
                bridge.complete(job.request(), result.snapshot());
                encodeNanos = result.encodeNanos();
                iterator.remove();
            }
            long start = System.nanoTime();
            while (jobs.size() < MAX_JOBS && System.nanoTime() - start < COPY_BUDGET_NS) {
                var request = bridge.acquire();
                if (request == null) break;
                var key = request.key();
                var chunk = level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false);
                if (chunk == null) {
                    bridge.unload(key);
                    continue;
                }
                // PalettedContainer.copy owns its storage; BlockState values are immutable.
                var copy = chunk.getSection(level.getSectionIndexFromSectionY(key.y())).getStates().copy();
                jobs.add(new Job(request, worker.submit(() -> encode(request, copy))));
                copied++;
            }
            copyNanos = System.nanoTime() - start;
            state = lightAware && casterLight.source() != ShadowLight.Source.NONE ? "tracking light-aware caster volume" : "tracking local 7x7x7 section window";
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.error("Scene tracking disabled after failure", e);
            enabled = false;
            bridge.changeWorld();
            cancelJobs();
            state = "failed; see client log";
        }
    }

    public String status() {
        var stats = bridge.stats();
        return "scene=" + (enabled ? "on" : "off") + ", state=" + state
                + ", worldGen=" + stats.worldGeneration() + ", resourceGen=" + stats.resourceGeneration()
                + ", resident=" + stats.resident() + "/" + stats.tracked() + ", dirty=" + stats.dirty()
                + ", inFlight=" + stats.inFlight() + ", payloadBytes=" + stats.payloadBytes()
                + ", accepted=" + stats.accepted() + ", stale=" + stats.stale() + ", merged=" + stats.coalesced()
                + ", copyNs=" + copyNanos + ", lastEncodeNs=" + encodeNanos + ", copied=" + copied
                + ", queuedJobs=" + jobs.size()
                + ", casterVolume=" + (lightAware ? "light" : "cube") + ", casterCandidates=" + casterCandidates
                + ", casterEligible=" + casterEligible + ", casterWindowDeferred=" + casterDeferred
                + ", casterExtrusion=" + casterExtrusion + ", casterSelectionNs=" + casterSelectionNanos;
    }

    public String inspect(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit)) return "VoxelLight: aim at a block";
        var pos = hit.getBlockPos();
        var key = SectionKey.fromBlock(pos.getX(), pos.getY(), pos.getZ());
        var snapshot = bridge.snapshot(key);
        if (snapshot == null) return "VoxelLight: section not ready; use /voxellight scene on and wait within the local window";
        var material = snapshot.material(SectionKey.blockIndex(pos.getX(), pos.getY(), pos.getZ()));
        return "section=" + key + ", version=" + snapshot.request().version() + ", material=" + material
                + ", nonAir=" + snapshot.nonAirCount() + ", fullOccluders=" + snapshot.fullCount()
                + ", emissive=" + snapshot.emissiveCount();
    }

    public void export(Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("section_x,section_y,section_z,world_generation,resource_generation,version,reasons,palette_size,non_air,full_occluders,emissive,payload_bytes\n");
            for (var snapshot : bridge.snapshots()) {
                var request = snapshot.request();
                var key = request.key();
                writer.write(key.x() + "," + key.y() + "," + key.z() + "," + request.worldGeneration() + ","
                        + request.resourceGeneration() + "," + request.version() + "," + request.reasons() + ","
                        + snapshot.paletteSize() + "," + snapshot.nonAirCount() + "," + snapshot.fullCount() + ","
                        + snapshot.emissiveCount() + "," + snapshot.payloadBytes() + "\n");
            }
        }
    }

    private static Result encode(WorldSceneBridge.Request request, PalettedContainer<BlockState> copy) {
        long start = System.nanoTime();
        var indices = new short[SectionSnapshot.BLOCKS];
        var ids = new int[SectionSnapshot.BLOCKS];
        var flags = new byte[SectionSnapshot.BLOCKS];
        var emissions = new byte[SectionSnapshot.BLOCKS];
        var palette = new HashMap<BlockState, Integer>();
        for (int y = 0; y < 16; y++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = copy.get(x, y, z);
                    Integer index = palette.get(state);
                    if (index == null) {
                        index = palette.size();
                        palette.put(state, index);
                        ids[index] = Block.getId(state);
                        int classification = state.isAir() ? 0 : SectionSnapshot.NON_AIR;
                        if (state.isSolidRender()) classification |= SectionSnapshot.FULL_OCCLUDER;
                        if (!state.getFluidState().isEmpty()) classification |= SectionSnapshot.FLUID;
                        if (!state.isAir() && state.getRenderShape() != RenderShape.MODEL) classification |= SectionSnapshot.NON_MODEL;
                        flags[index] = (byte) classification;
                        emissions[index] = (byte) state.getLightEmission();
                    }
                    indices[SectionKey.blockIndex(x, y, z)] = index.shortValue();
                }
            }
        }
        int size = palette.size();
        var snapshot = new SectionSnapshot(request, indices, Arrays.copyOf(ids, size),
                Arrays.copyOf(flags, size), Arrays.copyOf(emissions, size));
        return new Result(snapshot, System.nanoTime() - start);
    }

    private void cancelJobs() {
        for (Job job : jobs) job.future().cancel(true);
        jobs.clear();
        worker.purge();
    }

    @Override
    public void close() {
        enabled = false;
        bridge.changeWorld();
        cancelJobs();
        worker.shutdownNow();
    }
}
