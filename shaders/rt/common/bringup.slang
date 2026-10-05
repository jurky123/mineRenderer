// Bringup ABI only; Material 3 transport remains owned by existing renderer until parity migration.
struct SurfacePayload { float3 normal; float distance; };
[[vk::binding(0, 0)]] RaytracingAccelerationStructure world;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;
[[vk::binding(2, 0)]] StructuredBuffer<float4> normals;
struct Camera { float4 inverseClip0; float4 inverseClip1; float4 inverseClip2; float4 inverseClip3; float4 origin; uint width; uint height; uint2 padding; };
[[vk::binding(3, 0)]] ConstantBuffer<Camera> camera;
