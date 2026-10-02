#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D BloomInput;
layout(std140) uniform BloomSettings { vec4 BlurDirection; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec2 stepUv=BlurDirection.xy/vec2(textureSize(BloomInput,0));
    vec3 sum=vec3(0.0);float weightSum=0.0;
    for(int i=-6;i<=6;i++) {
        float weight=exp(-float(i*i)/18.0);
        sum+=texture(BloomInput,texCoord+stepUv*float(i)).rgb*weight;weightSum+=weight;
    }
    fragColor=vec4(sum/weightSum,1.0);
}
