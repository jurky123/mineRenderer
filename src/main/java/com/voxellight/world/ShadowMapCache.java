package com.voxellight.world;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

/** Bounded tile validity. Dirty tiles are cleared and rebuilt from all current overlapping casters. */
public final class ShadowMapCache {
    public static final int TILE_SIZE = 256;
    public static final int GRID = ShadowVolume.MAP_SIZE / TILE_SIZE;
    public static final int TILE_COUNT = GRID * GRID;
    public record Anchor(int x, int y, int z) { }
    public record Rect(int x, int y, int width, int height) {
        public boolean intersects(Rect other) {
            return x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height;
        }
    }
    public record Update(List<Rect> regions, int pages, String reason) { }

    private final int cascade, mapSize, grid, tileCount;
    public ShadowMapCache() { this(-1); }
    public ShadowMapCache(int cascade) {
        this.cascade = cascade;
        mapSize = cascade < 0 ? ShadowVolume.MAP_SIZE : ShadowCascades.range(cascade).mapSize();
        grid = mapSize / TILE_SIZE; tileCount = grid * grid;
    }
    public int tileCount() { return tileCount; }
    public boolean hasPending(){return !dirty.isEmpty();}

    private final BitSet dirty = new BitSet();
    private Anchor anchor;
    private ShadowLight light = ShadowLight.fixed();
    private long renders, reuses, pageUpdates, pageReuses;
    private int updatedPages, regionCount;
    private String reason = "uninitialized";

    /** Invalidate old footprint on removal and new footprint on addition. Empty geometry contributes no shadow. */
    public void invalidate(CasterBounds bounds) {
        if (bounds != null && anchor != null) mark(project(bounds));
    }

    public Update plan(Anchor current, boolean enabled, Collection<CasterBounds> cutouts) {
        return plan(current, ShadowLight.fixed(), enabled, cutouts);
    }

    public Update plan(Anchor current, ShadowLight currentLight, boolean enabled, Collection<CasterBounds> cutouts) {
        return plan(current,currentLight,enabled,cutouts,tileCount);
    }

    public Update plan(Anchor current, ShadowLight currentLight, boolean enabled, Collection<CasterBounds> cutouts, int pageBudget) {
        if(pageBudget<0)throw new IllegalArgumentException("Negative shadow page budget");
        String cause = dirty.isEmpty() ? "valid tiles reused" : "casters changed";
        if (!enabled) { dirty.set(0, tileCount); cause = "reference redraw"; }
        else if (anchor == null) { dirty.set(0, tileCount); cause = "uninitialized"; }
        else if (!anchor.equals(current)) { dirty.set(0, tileCount); cause = "volume moved"; }
        else if (!light.key().equals(currentLight.key())) { dirty.set(0, tileCount); cause = "celestial light moved"; }
        anchor = current;
        light = currentLight;
        int beforeAnimation = dirty.cardinality();
        for (var bounds : cutouts) mark(project(bounds));
        if (dirty.cardinality() > beforeAnimation) cause = beforeAnimation == 0 ? "cutout animation safety" : cause + "; cutout animation safety";
        var selected=new BitSet();
        for(int index=dirty.nextSetBit(0), count=0;index>=0 && count<pageBudget;index=dirty.nextSetBit(index+1),count++)selected.set(index);
        return new Update(regions(selected), selected.cardinality(), cause);
    }

    /** Four-texel raster/filter precision guard. Subtract world coordinates in double before float projection. */
    public Rect project(CasterBounds bounds) {
        if (anchor == null) throw new IllegalStateException("No shadow volume selected");
        var matrix = cascade < 0 ? ShadowVolume.lightMatrix(anchor.x, anchor.y, anchor.z, light)
                : ShadowCascades.matrix(anchor.x, anchor.y, anchor.z, light, cascade);
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (int corner = 0; corner < 8; corner++) {
            var p = new Vector3f((float)(((corner & 1) == 0 ? bounds.minX() : bounds.maxX()) - anchor.x),
                    (float)(((corner & 2) == 0 ? bounds.minY() : bounds.maxY()) - anchor.y),
                    (float)(((corner & 4) == 0 ? bounds.minZ() : bounds.maxZ()) - anchor.z));
            matrix.transformProject(p);
            double px = (p.x * 0.5 + 0.5) * mapSize, py = (p.y * 0.5 + 0.5) * mapSize;
            minX = Math.min(minX, px); minY = Math.min(minY, py); maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
        }
        int x = clamp(Math.floor(minX) - 4), y = clamp(Math.floor(minY) - 4);
        int right = clamp(Math.ceil(maxX) + 4), top = clamp(Math.ceil(maxY) + 4);
        return right <= x || top <= y ? null : new Rect(x, y, right - x, top - y);
    }

    private int clamp(double value) { return (int)Math.max(0, Math.min(mapSize, value)); }
    private void mark(Rect rect) {
        if (rect == null) return;
        for (int y = rect.y / TILE_SIZE; y <= (rect.y + rect.height - 1) / TILE_SIZE; y++) {
            dirty.set(y * grid + rect.x / TILE_SIZE, y * grid + (rect.x + rect.width - 1) / TILE_SIZE + 1);
        }
    }

    /** Merge horizontal runs vertically; bounded by 64 tiles and never includes clean tiles. */
    private List<Rect> regions(BitSet selected) {
        var result = new ArrayList<Rect>();
        var remaining = (BitSet)selected.clone();
        for (int index = remaining.nextSetBit(0); index >= 0; index = remaining.nextSetBit(0)) {
            int x = index % grid, y = index / grid, width = 1, height = 1;
            while (x + width < grid && remaining.get(index + width)) width++;
            remaining.clear(index, index + width);
            while (y + height < grid) {
                int row = (y + height) * grid + x;
                if (remaining.nextClearBit(row) < row + width) break;
                remaining.clear(row, row + width);
                height++;
            }
            result.add(new Rect(x * TILE_SIZE, y * TILE_SIZE, width * TILE_SIZE, height * TILE_SIZE));
        }
        return List.copyOf(result);
    }

    /** Commit only after recording this region's clear and all overlapping caster draws successfully. */
    public void rendered(Rect region) {
        for (int y = region.y / TILE_SIZE; y < (region.y + region.height) / TILE_SIZE; y++) {
            dirty.clear(y * grid + region.x / TILE_SIZE, y * grid + (region.x + region.width) / TILE_SIZE);
        }
    }

    public void finishFrame(Update update) {
        updatedPages = update.pages; regionCount = update.regions.size(); reason = update.reason;
        if (update.pages == 0) reuses++; else renders++;
        pageUpdates += update.pages; pageReuses += tileCount - update.pages;
    }
    public void suspend() { updatedPages = 0; regionCount = 0; reason = "no celestial shadow; local lighting only"; }
    public long renders() { return renders; }
    public long reuses() { return reuses; }
    public long pageUpdates() { return pageUpdates; }
    public long pageReuses() { return pageReuses; }
    public int updatedPages() { return updatedPages; }
    public int regionCount() { return regionCount; }
    public String reason() { return reason; }
    public void resetValidity(){anchor=null;dirty.clear();reason="uninitialized";updatedPages=regionCount=0;}
    public void clear() {
        anchor = null; light = ShadowLight.fixed(); dirty.clear(); renders = 0; reuses = 0; pageUpdates = 0; pageReuses = 0;
        updatedPages = 0; regionCount = 0; reason = "uninitialized";
    }
}
