package com.voxellight.rt;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Recyclable dynamic model addresses. Generation is part of the scene key. */
public final class RtModelSlots {
    public record Handle(int slot,int generation){public int keyZ(boolean hand){return (generation<<1)|(hand?1:0);}}
    private final ArrayList<Integer> generations=new ArrayList<>();
    private final ArrayList<Boolean> active=new ArrayList<>();
    private final ArrayDeque<Integer> free=new ArrayDeque<>();
    public Handle acquire(){
        int slot;if(free.isEmpty()){slot=generations.size();generations.add(0);active.add(true);}else{slot=free.removeFirst();active.set(slot,true);}
        return new Handle(slot,generations.get(slot));
    }
    public void release(Handle handle){
        int slot=handle.slot();if(slot<0||slot>=generations.size()||!active.get(slot)||generations.get(slot)!=handle.generation())throw new IllegalArgumentException("Stale dynamic model handle");
        active.set(slot,false);if(handle.generation()<Integer.MAX_VALUE>>>1){generations.set(slot,handle.generation()+1);free.addLast(slot);}
        // An exhausted generation is retired rather than wrapping into an old identity.
    }
    public int capacity(){return generations.size();}
    public void clear(){generations.clear();active.clear();free.clear();}
}
