package com.voxellight.rt;
import java.util.*;
/** Bounded coalesced world-space invalidations, independent of the near-field voxel tracker. */
public final class RtInvalidationQueue {
 public record Region(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) { }
 private static boolean enabled;
 private record Change(Region region,long revision){}
 private static final ArrayDeque<Change> changes=new ArrayDeque<>();
 private static long revision,floor;
 public static synchronized long revision(int x,int y,int z){long value=floor;for(var change:changes){var r=change.region;if(x>=r.minX&&x<=r.maxX&&y>=r.minY&&y<=r.maxY&&z>=r.minZ&&z<=r.maxZ)value=change.revision;}return value;}

 private static final LinkedHashSet<Region> pending=new LinkedHashSet<>();
 private RtInvalidationQueue(){}
 public static synchronized void enabled(boolean value){enabled=value;pending.clear();changes.clear();floor=++revision;}
 public static synchronized void record(int x,int y,int z,int maxX,int maxY,int maxZ){
  if(!enabled)return;var region=new Region(x,y,z,maxX,maxY,maxZ);pending.add(region);changes.addLast(new Change(region,++revision));if(changes.size()>1024)floor=changes.removeFirst().revision;
  if(pending.size()>256){int ax=x,ay=y,az=z,bx=maxX,by=maxY,bz=maxZ;for(var r:pending){ax=Math.min(ax,r.minX);ay=Math.min(ay,r.minY);az=Math.min(az,r.minZ);bx=Math.max(bx,r.maxX);by=Math.max(by,r.maxY);bz=Math.max(bz,r.maxZ);}pending.clear();pending.add(new Region(ax,ay,az,bx,by,bz));}
 }
 public static synchronized List<Region> drain(){var result=List.copyOf(pending);pending.clear();return result;}
}
