# mineRenderer / VoxelLight

VoxelLight 是 Minecraft 26.2 / Java 25 的客户端 Fabric 路径追踪原型，支持原生 Vulkan。当前版本 **0.39.0-alpha.35**：动态 BLAS 区分位置与 shader 属性更新，独立 AS 更新使用共享 scratch slices，校准 native 计时并新增 BLAS 自动 A/B。保留 alpha.34 统一直接光 RIS（上一场景实测 transport 耗时下降 28.15%）与 64B PathHot。BLAS 新优化的 GPU 收益和动态视觉待客户端实测，全新安装默认 effects off。

[下载 alpha.35 安装包](https://temp.sh/juUOx/voxellight-client-kit-26.2-0.39.0-alpha.35.zip)（临时链接，只包含本 mod）。替换旧 jar 后：

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

BLAS 专用验收：执行 `/voxellight rt_benchmark blas`（8 段，默认每段 10 秒）。[实施与指标说明](docs/performance/RT-BLAS-OPTIMIZATION.md)。

第二轮专用验收：执行 `/voxellight rt_benchmark direct`（8 段，默认每段 10 秒）。[设计与指标说明](docs/performance/RT-DIRECT-LIGHTING-ROUND-2.md)。

完整自动验收：进入正常 Vulkan PT 世界站定，执行 `/voxellight rt_benchmark start`。通常约 2–12 分钟，另加驱动管线初始化耗时，结束自动恢复原执行控制并导出 `benchmark-results/voxellight/rt-suite-<时间戳>.zip`。`status` 查看进度，`stop` 中断并导出。收益处于重复段波动范围内时明确报告 `within_variation`。

## 构建与验证

```sh
./gradlew build clientKit
```

构建工具需要 Slang 和 SPIR-V 验证工具，详见 [Vulkan 迁移记录](docs/VULKAN-RT-MIGRATION.md)。构建执行 Java 回归、真实 Minecraft GLSL pipeline 链接、24 个 RT SPIR-V stage 验证，以及实际 Slang CPU target 对原始 BSDF/材质/环境的数值校验。独立降噪 helper 需要 NVIDIA SDK 头文件和 Windows/Linux C++ 工具链，不需要 nvcc 或旧 tracing build。产物位于 `build/libs/` 和 `build/distributions/`；client kit 只含本 mod 和当前操作文档。

## 文档

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

本轮验证：319 项 Java 测试通过；24 个 RT SPIR-V stage 与 Minecraft GLSL 链接通过；生产 Slang 统一 RIS 对彩色遮挡点光、面积光、环境、自适应 proposal、深层 roulette 与表面 NEE/BSDF MIS 做每 case 200,000 次数值回归，RGB 能量误差在 2% 验收阈值内。NVIDIA GPU 帧时间与实机场景视觉尚待验收。

手持灯异常可运行 `/voxellight rt_lighting_probe`：拿着光源对准附近不透明墙面，约 30 帧后日志输出入射光、材质响应与遮挡结果。详见 [设置说明](docs/SETTINGS.md)。

alpha.21 验收：固定窗口大小、`rt_spp 1` / `rt_scale 0`，`profile on` 后静止/移动各测试约 20 秒，`export` 导出分阶段 GPU 时间。动态场景同拓扑 refit，shader 几何和纹理只复制变化区段/当前纹理 tile；去噪 guide 来自稳定中心射线，动态历史保守拒绝。OptiX 只降噪实际输出的 beauty 层，成功时不运行 Vulkan 滤波。性能与画质仍需 RTX 实测。
