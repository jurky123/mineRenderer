package com.voxellight.rt;

import com.voxellight.world.SectionKey;
import java.util.*;
import java.util.zip.*;
import java.io.ByteArrayOutputStream;

/** Compressed CPU backing pages for a bounded GPU working set; revisions prevent stale edits. */
public final class RtPageCache {
    private record Page(long revision,long version,int length,byte[] compressed){}
    public record Restored(long version,byte[] triangles){}
    private final long limit;
    private final LinkedHashMap<SectionKey,Page> pages=new LinkedHashMap<>(16,.75f,true);
    private long bytes,hits,misses,evictions;
    public RtPageCache(long limit){if(limit<1)throw new IllegalArgumentException("Positive cache budget required");this.limit=limit;}
    public synchronized void put(SectionKey key,long revision,long version,byte[] triangles){
        var compressor=new Deflater(Deflater.BEST_SPEED);var output=new ByteArrayOutputStream();byte[] block=new byte[8192];
        try{compressor.setInput(triangles);compressor.finish();while(!compressor.finished()){int n=compressor.deflate(block);output.write(block,0,n);}}finally{compressor.end();}
        byte[] packed=output.toByteArray();var old=pages.remove(key);if(old!=null)bytes-=old.compressed.length;
        if(packed.length>limit)return;pages.put(key,new Page(revision,version,triangles.length,packed));bytes+=packed.length;
        while(bytes>limit){var iterator=pages.values().iterator();bytes-=iterator.next().compressed.length;iterator.remove();evictions++;}
    }
    public synchronized Restored get(SectionKey key,long revision){
        var page=pages.get(key);if(page==null){misses++;return null;}
        if(page.revision!=revision){pages.remove(key);bytes-=page.compressed.length;misses++;return null;}
        if(page.length==0){hits++;return new Restored(page.version,new byte[0]);}
        var inflater=new Inflater();byte[] restored=new byte[page.length];
        try{inflater.setInput(page.compressed);int offset=0;while(!inflater.finished()&&offset<restored.length){int n=inflater.inflate(restored,offset,restored.length-offset);if(n==0)break;offset+=n;}if(offset!=restored.length||!inflater.finished())throw new IllegalStateException("Corrupt RT page");hits++;return new Restored(page.version,restored);}
        catch(DataFormatException error){throw new IllegalStateException("Corrupt RT page",error);}finally{inflater.end();}
    }
    public synchronized void clear(){pages.clear();bytes=hits=misses=evictions=0;}
    public synchronized String status(){return ", rtBackingPages="+pages.size()+", rtBackingBytes="+bytes+", rtPageHits="+hits+", rtPageMisses="+misses+", rtPageEvictions="+evictions;}
}
