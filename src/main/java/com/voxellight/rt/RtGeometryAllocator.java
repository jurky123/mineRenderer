package com.voxellight.rt;

import java.util.TreeMap;

/** Stable triangle ranges; reuse is ordered after previous GPU readers by the scene owner. */
public final class RtGeometryAllocator {
    private final int capacity;
    private final TreeMap<Integer,Integer> free=new TreeMap<>();
    public RtGeometryAllocator(int capacity){if(capacity<=0)throw new IllegalArgumentException("capacity");this.capacity=capacity;clear();}
    public int allocate(int count){
        if(count<=0)throw new IllegalArgumentException("count");
        for(var entry:free.entrySet())if(entry.getValue()>=count){
            int base=entry.getKey(),size=entry.getValue();free.remove(base);if(size>count)free.put(base+count,size-count);return base;
        }
        return -1;
    }
    public void release(int base,int count){
        if(base<0||count<=0||(long)base+count>capacity)throw new IllegalArgumentException("range");
        var before=free.floorEntry(base);var after=free.ceilingEntry(base);
        if(before!=null&&(long)before.getKey()+before.getValue()>base||after!=null&&(long)base+count>after.getKey())throw new IllegalStateException("overlapping release");
        if(before!=null&&before.getKey()+before.getValue()==base){count+=before.getValue();base=before.getKey();free.remove(before.getKey());}
        after=free.ceilingEntry(base);
        if(after!=null&&base+count==after.getKey()){count+=after.getValue();free.remove(after.getKey());}
        free.put(base,count);
    }
    public void clear(){free.clear();free.put(0,capacity);}
}
