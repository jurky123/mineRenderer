#include "../common/bringup.slang"
[shader("raygeneration")]
void primary() {
    uint2 pixel = DispatchRaysIndex().xy;
    float2 ndc = (float2(pixel) + 0.5) / float2(camera.width, camera.height) * 2.0 - 1.0;
    float4 farPoint = camera.inverseClip0 * ndc.x + camera.inverseClip1 * ndc.y + camera.inverseClip2 * 0.00001 + camera.inverseClip3;
    RayDesc ray;
    ray.Origin = camera.origin.xyz;
    ray.Direction = normalize(farPoint.xyz / farPoint.w);
    ray.TMin = 0.001; ray.TMax = 4096.0;
    SurfacePayload payload = {float3(0.0), -1.0};
    TraceRay(world, RAY_FLAG_FORCE_OPAQUE, 255, 0, 0, 0, ray, payload);
    output[pixel.y * camera.width + pixel.x] = float4(payload.normal * 0.5 + 0.5, payload.distance >= 0.0 ? 1.0 : 0.0);
}
