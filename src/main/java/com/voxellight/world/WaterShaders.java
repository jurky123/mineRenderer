package com.voxellight.world;

import java.util.regex.Pattern;

/** Extends the active processed native terrain shader; never vendors its sampler/fog implementation. */
public final class WaterShaders {
    private static final Pattern MAIN=Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private WaterShaders() { }
    public static String wrap(String nativeSource,boolean vertex,String waterSource,String visualSource) {
        if(nativeSource==null)throw new IllegalArgumentException("Missing native terrain shader");
        var main=MAIN.matcher(nativeSource);
        if(!main.find())throw new IllegalArgumentException("Unsupported native terrain shader main");
        String renamed=main.replaceFirst("void voxellightNativeMain() {");
        if(vertex)return renamed+"\nout float waterSkyAccess;\nvoid main(){voxellightNativeMain();waterSkyAccess=clamp(float(UV2.y)/240.0,0.0,1.0);}\n";
        return renamed+'\n'+waterSource.replace("// VOXELLIGHT_VISUAL_FUNCTIONS",functions(visualSource));
    }
    public static String functions(String visualSource) {
        int begin=visualSource.indexOf("vec3 linearToSrgb"),end=visualSource.indexOf("void main()");
        if(begin<0 || end<begin)throw new IllegalArgumentException("Missing shared HDR display functions");
        return visualSource.substring(begin,end);
    }
}
