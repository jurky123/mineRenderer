package com.voxellight.rt;
import java.util.*;
/** Section coordinates and half-open long bounds, including whole-height chunk columns.
 * Snapshot revisions include every reason; rendering history follows committed RT content.
 */
public final class RtInvalidationQueue {
 public record Region(long minX,long minY,long minZ,long maxX,long maxY,long maxZ) {
  public Region {if(minX>=maxX||minY>=maxY||minZ>=maxZ)throw new IllegalArgumentException("nonempty half-open section bounds");}
  public boolean contains(int x,int y,int z){return x>=minX&&x<maxX&&y>=minY&&y<maxY&&z>=minZ&&z<maxZ;}
 }
 public record Change(Region region,long revision,int reasons){}
 public record Updates(long generation,boolean overflow,List<Change> changes){}
 private static boolean enabled;
 private static final ArrayDeque<Change> changes=new ArrayDeque<>();
 private static long revision,floor;
 private static final LinkedHashSet<Region> pending=new LinkedHashSet<>();
 private RtInvalidationQueue(){}
 public static synchronized long generation(){return revision;}
 public static synchronized long revision(int x,int y,int z){long value=floor;for(var change:changes)if(change.region.contains(x,y,z))value=change.revision;return value;}
 /** Independent cursors: drain cannot consume another reader's event history. */
 public static synchronized Updates changesSince(long cursor){return new Updates(revision,cursor<floor,changes.stream().filter(change->change.revision>cursor).toList());}
 public static synchronized void enabled(boolean value){enabled=value;pending.clear();changes.clear();floor=++revision;}
 public static void record(long x,long y,long z,long maxX,long maxY,long maxZ){record(x,y,z,maxX,maxY,maxZ,0);}
 public static synchronized void record(long x,long y,long z,long maxX,long maxY,long maxZ,int reasons){
  if(!enabled)return;var region=new Region(x,y,z,maxX,maxY,maxZ);pending.add(region);changes.addLast(new Change(region,++revision,reasons));if(changes.size()>1024)floor=changes.removeFirst().revision;
  if(pending.size()>256){long ax=x,ay=y,az=z,bx=maxX,by=maxY,bz=maxZ;for(var r:pending){ax=Math.min(ax,r.minX);ay=Math.min(ay,r.minY);az=Math.min(az,r.minZ);bx=Math.max(bx,r.maxX);by=Math.max(by,r.maxY);bz=Math.max(bz,r.maxZ);}pending.clear();pending.add(new Region(ax,ay,az,bx,by,bz));}
 }
 public static synchronized List<Region> drain(){var result=List.copyOf(pending);pending.clear();return result;}
}
