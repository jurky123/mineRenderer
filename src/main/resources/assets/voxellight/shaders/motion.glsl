layout(std140) uniform MotionSettings {mat4 CurrentClipToWorld;mat4 CurrentWorldToClip;mat4 PreviousWorldToClip;mat4 PreviousClipToWorld;vec4 MotionCameraDelta;vec4 MotionControls;};
vec3 motionPosition(vec2 uv,float depth,mat4 inverseMatrix){vec4 p=inverseMatrix*vec4(uv*2.0-1.0,depth,1);return p.xyz/p.w;}
