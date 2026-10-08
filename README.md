# mineRenderer / VoxelLight

VoxelLight 是 Minecraft 26.2 / Java 25 的客户端 Fabric 路径追踪原型，支持原生 Vulkan。当前版本 **0.39.0-alpha.47**：ROUGH_DIFFUSE cache 使用经数值验证的预积分核，非线性截断保持 64 点回退；Primary Shade 复用 Visibility 已验证的分类与 guide。新增 `rt_benchmark cost` 隔离核成本，并配对 FULL/MONOLITHIC 与 SPLIT/PRIMARY/CACHE_SPARSE 的 GPU 世界帧时间。默认仍 FULL/MONOLITHIC/EXACT/Wavefront，RTX 整体收益与同画质尚待验收。[实现与验收](docs/performance/CACHE-COST-REDUCTION.md)。

[下载 alpha.47 安装包](https://temp.sh/ouqkP/voxellight-client-kit-26.2-0.39.0-alpha.47.zip)（只含本 mod；集成 RR runtime）。alpha.42 已实测 DLSS RR 与世界接管运行；该场景 FAST 输运耗时降低 23.01%，Iterative 增加 32.76%。默认仍为 EXACT + Wavefront，FAST 按场景选择。[实测与本轮修改](docs/performance/ALPHA-42-FRAME-ANALYSIS.md)。替换旧 jar 后：

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction dlss
/voxellight rt_spp 1
/voxellight stats
/voxellight rt_benchmark cost
```

实时默认 exclusive world / exact shadow / wavefront indirect，RR 失败回退 OptiX/Vulkan，stats 显示实际后端与输入尺寸。Reference 保留精确透明阴影和渐进累积；没有帧生成。[实现、限制与验收](docs/performance/CAUSTICA-FRAME-PIPELINE.md)。

多 spp 在同一套帧调度中批处理，相机只上传一次，末尾平均后复制一次 radiance。直接光默认采用统一 RIS，手持源保持独立；`rt_direct legacy` 可回退旧直接光用于对照。场景包含地形与原生实体/方块实体、手持/手臂、自定义 quad、cutout quad 粒子；动态模型目前使用原生纹理/tint 的漫反射材质。

RR 活跃时使用 SDK 输入尺寸；OptiX/Vulkan fallback 的 `rt_scale 0` 保留至少 4×、最高 640×360 的自动分辨率；`1..8` 指定线性缩放，实际尺寸受设备 buffer 范围和 1 GiB continuation 预算约束，stats 显示实际尺寸。场景有 256 MiB 压缩 CPU 页缓存、64 MiB GPU working set 和异步 miss 请求优先页；无法重建服务器尚未加载的区块。透明/加法粒子、动态 LabPBR 材质、RR 的镜面/透射 guide 覆盖、OMM/SER 性能验收仍未完成。此版本的 RTX 降噪、动态外观和性能需要实机验证。

本轮先执行 `/voxellight rt_benchmark shader`：8 段同场景 runtime FULL / clean FULL ABBA，约 2–3 分钟。完整 `/voxellight rt_benchmark hot`：按 FULL/SPARSE/CACHE_SPARSE 分别扫描队列，再使用稳定赢家比较算法，支持间接 tracing 时 72 段、约 19 分钟。受控 A/B 测试固定 renderer 光照/天气/水面时钟，结束恢复输入和原执行控制。[实施与验收](docs/performance/HOT-SHADER-CLEANUP.md)。

BLAS 专用验收：执行 `/voxellight rt_benchmark blas`（8 段，默认每段 10 秒）。[实施与指标说明](docs/performance/RT-BLAS-OPTIMIZATION.md)。

第二轮专用验收：执行 `/voxellight rt_benchmark direct`（8 段，默认每段 10 秒）。[设计与指标说明](docs/performance/RT-DIRECT-LIGHTING-ROUND-2.md)。

完整自动验收：进入正常 Vulkan PT 世界站定，执行 `/voxellight rt_benchmark start`。通常约 2–12 分钟，另加驱动管线初始化耗时，结束自动恢复原执行控制并导出 `benchmark-results/voxellight/rt-suite-<时间戳>.zip`。`status` 查看进度，`stop` 中断并导出。收益处于重复段波动范围内时明确报告 `within_variation`。

alpha.44 新增 `/voxellight rt_benchmark material`（24 段专项 ABBA）与 `/voxellight rt_benchmark production`（4 段当前日常配置 baseline，保留 AUTO/实时/OMM/SER 和 live 场景）。可切换 `rt_primary monolithic|split`、`rt_cache tail|primary`、`rt_sampling owen|shift`，默认保留 monolithic/tail/owen；B0 cache 需开启实时 cache 策略。[实现、内存、测试与尚未完成项](docs/performance/PRIMARY-CACHE-2.md)。

## 构建与验证

```sh
./gradlew build clientKit
```

构建工具需要 Slang 和 SPIR-V 验证工具，详见 [Vulkan 迁移记录](docs/VULKAN-RT-MIGRATION.md)。构建执行 Java 回归、真实 Minecraft GLSL pipeline 链接、103 个 RT SPIR-V stage 验证，以及实际 Slang CPU target 对原始 BSDF/材质/环境的数值校验。RR 构建依赖详见 [帧管线文档](docs/performance/CAUSTICA-FRAME-PIPELINE.md)。独立降噪 helper 需要 NVIDIA SDK 头文件和 Windows/Linux C++ 工具链，不需要 nvcc 或旧 tracing build。产物位于 `build/libs/` 和 `build/distributions/`；client kit 只含本 mod 和当前操作文档。

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
