package com.voxellight.adapter;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.voxellight.world.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialSurfaceStoreTest {
    private static final SectionKey A=new SectionKey(0,0,0), B=new SectionKey(1,0,0);
    private static final class Buffer extends GpuBuffer {
        int closes;
        Buffer(int bytes){super(USAGE_VERTEX,bytes);}
        @Override public boolean isClosed(){return closes>0;}
        @Override public void close(){closes++;}
        @Override public GpuBufferSlice.MappedView map(long offset,long length,boolean read,boolean write){throw new UnsupportedOperationException();}
    }
    private static MaterialSurfaceStore.Mesh mesh(WorldSceneBridge.SurfaceToken token,int bytes) {
        return new MaterialSurfaceStore.Mesh(token,new Buffer(bytes),4,bytes);
    }
    private static MaterialSurfaceStore.Mesh get(MaterialSurfaceStore store,SectionKey key) {
        return store.entries().stream().filter(e->e.getKey().equals(key)).findFirst().orElseThrow().getValue();
    }
    @Test void lightPropagationKeepsAllOldCoverageAndSwapsOneVerifiedReplacementPerFrame() {
        var bridge=new WorldSceneBridge(2);bridge.reconcile(List.of(A,B));
        try(var store=new MaterialSurfaceStore()) {
            for(int i=0;i<2;i++)store.prepare(bridge,A,k->true,(k,t,r)->mesh(t,128));
            var a=get(store,A);var b=get(store,B);
            bridge.markRangeDirty(0,0,0,1,0,0,WorldSceneBridge.LIGHT);
            store.prepare(bridge,A,k->true,(k,t,r)->{
                assertEquals(2,store.entries().size());assertSame(a,get(store,A));assertSame(b,get(store,B));
                assertFalse(a.vertices().isClosed());assertFalse(b.vertices().isClosed());assertEquals(128,r);
                return mesh(t,192);
            });
            assertEquals(1,((Buffer)a.vertices()).closes);assertSame(b,get(store,B));assertFalse(b.vertices().isClosed());
            assertTrue(bridge.isCurrent(get(store,A).token()));assertFalse(bridge.isCurrent(b.token()));
            assertTrue(store.status().contains("materialStale=1"));
            store.prepare(bridge,A,k->true,(k,t,r)->mesh(t,128));
            assertEquals(1,((Buffer)b.vertices()).closes);assertTrue(store.status().contains("materialStale=0"));
            assertTrue(store.status().contains("materialGeometryBytes=320"));
        }
    }
    @Test void supersededReplacementClosesOnlyNewBufferAndKeepsOldUntilAValidSwap() {
        var bridge=new WorldSceneBridge(1);bridge.reconcile(List.of(A));
        try(var store=new MaterialSurfaceStore()) {
            store.prepare(bridge,A,k->true,(k,t,r)->mesh(t,128));var old=get(store,A);
            bridge.markDirty(A,WorldSceneBridge.GEOMETRY);
            var built=new ArrayList<MaterialSurfaceStore.Mesh>();
            store.prepare(bridge,A,k->true,(k,t,r)->{var next=mesh(t,192);built.add(next);bridge.markDirty(A,WorldSceneBridge.LIGHT);return next;});
            assertSame(old,get(store,A));assertFalse(old.vertices().isClosed());assertEquals(1,((Buffer)built.getFirst().vertices()).closes);
            store.prepare(bridge,A,k->true,(k,t,r)->mesh(t,192));assertEquals(1,((Buffer)old.vertices()).closes);
        }
    }
    @Test void oversizedAndUnavailableReplacementsKeepOldWithoutRetryingUntilTheTokenChanges() {
        var bridge=new WorldSceneBridge(1);bridge.reconcile(List.of(A));
        try(var store=new MaterialSurfaceStore()) {
            store.prepare(bridge,A,k->true,(k,t,r)->mesh(t,128));var old=get(store,A);
            bridge.markDirty(A,WorldSceneBridge.LIGHT);
            store.prepare(bridge,A,k->false,(k,t,r)->{fail("Unavailable neighbours cannot build");return null;});
            assertSame(old,get(store,A));
            store.prepare(bridge,A,k->true,(k,t,r)->{throw new MaterialSurfaceStore.BudgetExceeded(MaterialEncoding.SECTION_LIMIT+1);});
            store.prepare(bridge,A,k->true,(k,t,r)->{fail("Over-budget token must remain deferred");return null;});
            assertSame(old,get(store,A));assertFalse(old.vertices().isClosed());
            bridge.markDirty(A,WorldSceneBridge.LIGHT);store.prepare(bridge,A,k->true,(k,t,r)->mesh(t,128));
            assertTrue(old.vertices().isClosed());
        }
    }
    @Test void unloadReloadAndWindowExitRetireOldSurfacesInsteadOfRetainingAcrossLifetimes() {
        for(int cause=0;cause<4;cause++) {
            var bridge=new WorldSceneBridge(1);bridge.reconcile(List.of(A));
            try(var store=new MaterialSurfaceStore()) {
                store.prepare(bridge,A,k->true,(k,t,r)->mesh(t,128));var old=get(store,A);
                switch(cause){case 0->{bridge.unload(A);bridge.reconcile(List.of(A));}case 1->bridge.reloadResources();case 2->bridge.changeWorld();case 3->{} }
                var center=cause==3?new SectionKey(5,0,0):A;
                store.prepare(bridge,center,k->false,(k,t,r)->{fail("Unexpected build");return null;});
                assertTrue(store.entries().isEmpty());assertEquals(1,((Buffer)old.vertices()).closes);
            }
        }
    }
    @Test void aReplacementAtTheResidentCapReclaimsItsOldBytesBeforeBudgetAdmission() {
        var keys=new ArrayList<SectionKey>();
        for(int y=-1;y<=0;y++)for(int z=-1;z<=0;z++)for(int x=-2;x<=1;x++)keys.add(new SectionKey(x,y,z));
        var bridge=new WorldSceneBridge(16);bridge.reconcile(keys);
        try(var store=new MaterialSurfaceStore()) {
            for(int i=0;i<16;i++)store.prepare(bridge,A,k->true,(k,t,r)->mesh(t,MaterialEncoding.SECTION_LIMIT));
            assertEquals(16,store.entries().size());var old=get(store,A);
            bridge.markDirty(A,WorldSceneBridge.LIGHT);
            store.prepare(bridge,A,k->true,(k,t,r)->{assertEquals(MaterialEncoding.SECTION_LIMIT,r);assertEquals(16,store.entries().size());return mesh(t,MaterialEncoding.SECTION_LIMIT);});
            assertEquals(16,store.entries().size());assertTrue(old.vertices().isClosed());
            assertTrue(store.status().contains("materialGeometryBytes="+MaterialEncoding.RESIDENT_LIMIT));
        }
    }
}
