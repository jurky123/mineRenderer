package com.voxellight.world;

import java.util.regex.Pattern;

/** 26.2 native color/fog/filtering plus material output, sharing one texture sample and raster. */
public final class NativeMaterialShaders {
    private static final Pattern MAIN=Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private NativeMaterialShaders(){}
    public static String wrap(String nativeSource,boolean vertex,String materialSource) {
        if(nativeSource==null || !MAIN.matcher(nativeSource).find())throw new IllegalArgumentException("Native terrain main unavailable");
        String source=MAIN.matcher(nativeSource).replaceFirst("void vlNativeMain(){");
        if(vertex){
            int start=materialSource.indexOf("in vec3 Position;");
            if(start<0)throw new IllegalArgumentException("Material vertex contract unavailable");
            String extra=materialSource.substring(start);
            for(String declaration:new String[]{"in vec3 Position;","in vec4 Color;","in vec2 UV0;","in ivec2 UV2;"})extra=extra.replace(declaration,"");
            extra=extra.replaceAll("layout\\(location\\s*=\\s*\\d+\\)\\s*","");
            extra=MAIN.matcher(extra).replaceFirst("void vlMaterialMain(){");
            extra=extra.replace("gl_Position=ProjMat*ModelViewMat*vec4(pos,1);","");
            return source+'\n'+extra+"\nvoid main(){vlNativeMain();vlMaterialMain();}\n";
        }
        String color="vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;";
        if(!source.contains(color))throw new IllegalArgumentException("Unsupported native terrain sampling contract");
        source=source.replace("void vlNativeMain(){","vec4 vlTexel;\nvoid vlNativeMain(){").replace(color,"vec4 color = vlTexel * vertexColor;");
        String extra=materialSource.substring(materialSource.indexOf("layout(location = 0) in vec2 texCoord;"));
        int a=extra.indexOf("vec4 sampleNearest"),b=extra.indexOf("vec2 octEncode");
        if(a<0||b<a)throw new IllegalArgumentException("Material sampling contract unavailable");
        extra=extra.substring(0,a)+extra.substring(b);
        extra=extra.replaceAll("layout\\(location\\s*=\\s*\\d+\\)\\s*","");
        extra=MAIN.matcher(extra).replaceFirst("void vlMaterialMain(){");
        extra=extra.replace("vec4 texel = UseRgss == 1 ? sampleRGSS(Sampler0, texCoord, 1.0 / vec2(TextureSize))\n            : sampleNearest(Sampler0, texCoord, 1.0 / vec2(TextureSize));","vec4 texel=vlTexel;");
        extra=extra.replace("if(emissionFlags.x<0)discard;","if(emissionFlags.x<0){outAlbedo=vec4(0);outNormal=vec4(0);outEmission=vec4(0);outMaterialPbr=vec4(0);return;}");
        extra=extra.replace("if ((flags & 1) != 0 && texel.a * unlitTint.a < 0.5) discard;", "// Native alpha cutoff owns coverage.");
        return source+"\nuniform sampler2D PbrIdsAtlas;\nuniform sampler2D PbrNormalAtlas;\n"+extra+"\nvoid main(){fragColor=vec4(0);vlTexel=UseRgss==1?sampleRGSS(Sampler0,texCoord0,1.0/vec2(TextureSize)):sampleNearest(Sampler0,texCoord0,1.0/vec2(TextureSize));vlMaterialMain();vlNativeMain();}\n";
    }
}
