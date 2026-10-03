package com.voxellight.world;

/** Bounded analytic aerial perspective, not a shadow-marched volumetric integrator. */
public final class Atmosphere {
    public static final int SETTINGS_BYTES=16;
    public static final float DEFAULT_DENSITY=.018f, MAX_DISTANCE=48, HEIGHT_SCALE=48;
    private Atmosphere() { }
    public static float density(float value) {
        if(!Float.isFinite(value)||value<0||value>.08f)throw new IllegalArgumentException("Atmosphere density must be between 0 and .08");
        return value;
    }
    public static float weatherDensity(float base,float clear){return density(base)*(1+1.5f*(1-Math.clamp(clear,0,1)));}
    public static float amount(float distance,float verticalDistance,float cameraAboveSea,float skyAccess,float density) {
        float integral=0;
        for(int i=0;i<4;i++)integral+=(float)Math.exp(-Math.clamp((cameraAboveSea+verticalDistance*(i+.5f)/4)/HEIGHT_SCALE,-1,4));
        float opticalDepth=Math.min(MAX_DISTANCE,Math.max(0,distance))*density*integral*.25f;
        return (1-(float)Math.exp(-Math.min(opticalDepth,2)))*Math.clamp(skyAccess,0,1);
    }
    public static float forward(float dot){return (float)Math.pow(Math.max(0,Math.min(1,dot)),12);}
}
