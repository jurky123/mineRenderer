package com.voxellight.rt;
/** World-anchored toroidal cascades; orientation is deliberately absent from admission. */
public final class RadianceCacheLayout {
 public static final int SIDE=8,CASCADES=3,PROBES=SIDE*SIDE*SIDE*CASCADES,BYTES=PROBES*128;
 private RadianceCacheLayout(){}
 public static int spacing(int cascade){if(cascade<0||cascade>=CASCADES)throw new IllegalArgumentException();return 4<<cascade;}
 public static int slot(int cascade,int x,int y,int z){return cascade*512+(x&7)+((y&7)<<3)+((z&7)<<6);}
 public static int coordinate(double world,int cascade){return (int)Math.floor(world/spacing(cascade));}
 public static boolean affected(double x,double y,double z,int cascade,int sectionX,int sectionY,int sectionZ){
  double margin=spacing(cascade)*2.;return x>=sectionX*16-margin&&x<=(sectionX+1)*16+margin&&y>=sectionY*16-margin&&y<=(sectionY+1)*16+margin&&z>=sectionZ*16-margin&&z<=(sectionZ+1)*16+margin;
 }
}
