package com.voxellight.rt;

/** Exact environment-map dependency cache; no approximate time/rain thresholds. */
public final class RtEnvironmentCache {
 private Object destination;private int offset;private float[] weather,palette;
 public boolean matches(Object target,int targetOffset,float[] currentWeather,float[] currentPalette){
  if(target!=destination||offset!=targetOffset||!valid(currentWeather)||!valid(currentPalette)||weather==null)return false;
  boolean clouds=currentWeather[5]>.5f||weather[5]>.5f;
  for(int i=0;i<16;i++){
   if((clouds||i>=4)&&Float.floatToRawIntBits(weather[i])!=Float.floatToRawIntBits(currentWeather[i]))return false;
   if(Float.floatToRawIntBits(palette[i])!=Float.floatToRawIntBits(currentPalette[i]))return false;
  }
  return true;
 }
 public void updated(Object target,int targetOffset,float[] currentWeather,float[] currentPalette){
  clear();if(!valid(currentWeather)||!valid(currentPalette))return;
  destination=target;offset=targetOffset;weather=currentWeather.clone();palette=currentPalette.clone();
 }
 private static boolean valid(float[] values){if(values==null||values.length!=16)return false;for(float v:values)if(!Float.isFinite(v))return false;return true;}
 public void clear(){destination=null;weather=palette=null;}
}
