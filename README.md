# mineRenderer / VoxelLight

VoxelLight 是 Minecraft 26.2 / Java 25 的客户端 Fabric 路径追踪原型，支持原生 Vulkan。当前版本 **0.39.0-alpha.40**：FULL/Realtime、RIS/Legacy 改为独立编译期 shader，生产 HYBRID 的 alive 校准与完整诊断分离并有界冻结；新增固定整轮光照输入的 clean FULL 对照和逐算法队列扫描。保留原 estimator、六顶点 transport、64B PathHot、RIS、优化 BLAS 和重建后端；第三轮默认 FULL。RTX 收益待同场景验收，全新安装默认 effects off。

[下载 alpha.40 安装包](https://temp.sh/JZalv/voxellight-client-kit-26.2-0.39.0-alpha.40.zip)（临时链接，只包含本 mod）。替换旧 jar 后：

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction optix
/voxellight rt_spp 1
/voxellight stats
```

实时模式优先使用独立 OptiX temporal AOV denoiser，失败回退 Vulkan 时空滤波；全部追踪仍是 Vulkan camera primary + 五次 continuation，没有恢复旧 OptiX/CUDA tracer 或运行时 PTX 编译。`rt_mode reference` 使用独立静止渐进累积，可设置 `rt_accumulate spp 256` 和显式 freeze。

多 spp 在同一套帧调度中批处理，相机只上传一次，末尾平均后复制一次 radiance。直接光默认采用统一 RIS，手持源保持独立；`rt_direct legacy` 可回退旧直接光用于对照。场景包含地形与原生实体/方块实体、手持/手臂、自定义 quad、cutout quad 粒子；动态模型目前使用原生纹理/tint 的漫反射材质。

`rt_scale 0` 保留至少 4×、最高 640×360 的自动分辨率；`1..8` 指定线性缩放，实际尺寸受设备 buffer 范围和 1 GiB continuation 预算约束，stats 显示实际尺寸。场景有 256 MiB 压缩 CPU 页缓存、64 MiB GPU working set 和异步 miss 请求优先页；无法重建服务器尚未加载的区块。透明/加法粒子、动态 LabPBR 材质、DLSS RR、OMM/SER 性能验收仍未完成。此版本的 RTX 降噪、动态外观和性能需要实机验证。

本轮先执行 `/voxellight rt_benchmark shader`：8 段同场景 runtime FULL / clean FULL ABBA，约 2–3 分钟。完整 `/voxellight rt_benchmark hot`：按 FULL/SPARSE/CACHE_SPARSE 分别扫描队列，再使用稳定赢家比较算法，支持间接 tracing 时 72 段、约 19 分钟。所有自动测试固定 renderer 光照/天气/水面时钟，结束恢复输入和原执行控制。[实施与验收](docs/performance/HOT-SHADER-CLEANUP.md)。

BLAS 专用验收：执行 `/voxellight rt_benchmark blas`（8 段，默认每段 10 秒）。[实施与指标说明](docs/performance/RT-BLAS-OPTIMIZATION.md)。

第二轮专用验收：执行 `/voxellight rt_benchmark direct`（8 段，默认每段 10 秒）。[设计与指标说明](docs/performance/RT-DIRECT-LIGHTING-ROUND-2.md)。

完整自动验收：进入正常 Vulkan PT 世界站定，执行 `/voxellight rt_benchmark start`。通常约 2–12 分钟，另加驱动管线初始化耗时，结束自动恢复原执行控制并导出 `benchmark-results/voxellight/rt-suite-<时间戳>.zip`。`status` 查看进度，`stop` 中断并导出。收益处于重复段波动范围内时明确报告 `within_variation`。

## 构建与验证

```sh
./gradlew build clientKit
```

构建工具需要 Slang 和 SPIR-V 验证工具，详见 [Vulkan 迁移记录](docs/VULKAN-RT-MIGRATION.md)。构建执行 Java 回归、真实 Minecraft GLSL pipeline 链接、58 个 RT SPIR-V stage 验证，以及实际 Slang CPU target 对原始 BSDF/材质/环境的数值校验。独立降噪 helper 需要 NVIDIA SDK 头文件和 Windows/Linux C++ 工具链，不需要 nvcc 或旧 tracing build。产物位于 `build/libs/` 和 `build/distributions/`；client kit 只含本 mod 和当前操作文档。

## 文档

- [Hot Shader Cleanup、clean FULL 与按算法选队列](docs/performance/HOT-SHADER-CLEANUP.md)
- [BLAS 更新、scratch slices 与专用验收](docs/performance/RT-BLAS-OPTIMIZATION.md)
- [直接光第二轮设计、MIS 与自动验收](docs/performance/RT-DIRECT-LIGHTING-ROUND-2.md)
- [一条命令自动 A/B 与结果判定](docs/performance/RT-AUTOMATIC-BENCHMARK.md)

- [系统架构调整设计：总体、场景、PT、重建与迁移验收](docs/architecture/README.md)（设计基线 alpha.23；[alpha.24 实施与未完成项](docs/architecture/IMPLEMENTATION.md)）
- [安装与命令](docs/INSTALL.md)
- [设置与静止累积](docs/SETTINGS.md)
- [当前阶段](docs/CURRENT.md)
- [alpha.27 执行层实施、A/B 操作与冻结验收](docs/performance/RT-EXECUTION-ROUND-1.md)
- [alpha.21–23 性能分析与下一阶段设计](docs/performance/ALPHA-23-ANALYSIS.md)
- [Vulkan 迁移与验收](docs/VULKAN-RT-MIGRATION.md)
- [Material 3](docs/MATERIAL-3.md)
- [输运数学](docs/RT-LIGHT-TRANSPORT.md)
- [环境采样](docs/RT-ENVIRONMENT.md)
- [版本历史](docs/CHANGELOG.md)

旧 REFERENCE、PATH-TRACING 和 RTX 文档保留为历史记录，其中旧后端和旧命令不再适用于 alpha.21。

本轮 342 项 Java 测试、58 个 SPIR-V stage、Minecraft GLSL 链接与生产 Slang 数值回归通过；GPU 帧时间与实机场景视觉需客户端验收。
