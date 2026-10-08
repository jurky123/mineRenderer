// Execute production load/store and AOV accumulation through Slang's CPU target.
#ifdef VOXELLIGHT_COMPACT_TEST
#include "path_storage_compact.cpp"
using HotStorage=PackedPath48_0;
#else
#include "path_storage.cpp"
using HotStorage=PathHot_0;
#endif
#include <cstdio>
#include <cstring>
#include <cmath>
#include <vector>
int main(){
 #ifdef VOXELLIGHT_COMPACT_TEST
 static_assert(sizeof(HotStorage)==48);
#else
 static_assert(sizeof(HotStorage)==64);
#endif
static_assert(sizeof(MediumStorage_0)==288);static_assert(sizeof(PathAov_0)==48);
 Camera_0 camera{};camera.width_0=4;camera.height_0=1;camera.padding_1=2;
 Vector<float,4> output[8]{};HotStorage hot[4]{},hot1[4]{};MediumStorage_0 media[8]{};PathAov_0 aovs[8]{};
 std::vector<Vector<float,4>> normals(559243);unsigned bases[4]={0,10,30,0};std::memcpy(&normals[559242],bases,16);
 GlobalParams_0 globals{};globals.normals_0={normals.data(),normals.size()};globals.camera_0=&camera;globals.output_0={output,8};globals.paths_0={hot,4};globals.paths1_0={hot1,4};globals.pathMedia_0={media,8};globals.pathAovs_0={aovs,8};
 ComputeVaryingInput varying{};varying.endGroupID={1,1,1};path_storage(&varying,nullptr,&globals);
 unsigned seed;std::memcpy(&seed,&output[0].w,4);
 if(output[0].x!=3||output[0].y!=5||output[0].z!=7||seed!=0x87654321u||output[1].x!=1||output[1].y!=2||output[1].z!=3||std::abs(output[1].w-1.2f)>1e-6f||output[2].x!=7||output[2].w!=49||output[3].x!=1||output[3].w!=8||hot[1].seed_0!=0||hot1[1].seed_0!=seed||output[4].x!=1||output[4].y!=1||output[4].z!=1||output[4].w!=0||output[6].x!=19||output[6].y!=29||output[6].z!=49||output[5].x!=405)return 1;
 if(output[7].x>.0001||std::abs(output[7].y-.4)>1e-6||output[7].z!=2||output[7].w!=1)return 2;
#ifdef VOXELLIGHT_COMPACT_TEST
 std::puts("Production PackedPath48: PDF, depth, validity, RNG, proposal identity, AOV and eight-media roundtrip passed");
#else
 std::puts("Production Slang PathHot 64B: split radiance/AOV, second sample bank, RNG, previous proposal cell and eight nested media roundtrip passed");
#endif
}
