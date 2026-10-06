# mineRenderer / VoxelLight

VoxelLight 是 Minecraft 26.2 / Java 25 的客户端 Fabric 路径追踪原型，支持原生 Vulkan。当前版本 **0.39.0-alpha.26**：修复场景网格替换的临时容量冲突，并在几何区间碎片化时重排属性缓冲、重置历史，避免直接退出 RT；包含 alpha.25 历史纹理清空修复。全新安装默认 effects off。

[下载 alpha.26 安装包](https://temp.sh/tEDaw/voxellight-client-kit-26.2-0.39.0-alpha.26.zip)（临时链接，只包含本 mod）。替换旧 jar 后：

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction optix
/voxellight rt_spp 1
/voxellight stats
```

实时模式优先使用独立 OptiX temporal AOV denoiser，失败回退 Vulkan 时空滤波；全部追踪仍是 Vulkan camera primary + 五次 continuation，没有恢复旧 OptiX/CUDA tracer 或运行时 PTX 编译。`rt_mode reference` 使用独立静止渐进累积，可设置 `rt_accumulate spp 256` 和显式 freeze。

多 spp 在同一套帧调度中批处理，相机只上传一次，末尾平均后复制一次 radiance。近火把连接缩减为最近两个直接连接加一个 RIS 采样连接，手持源保持独立。场景包含地形与原生实体/方块实体、手持/手臂、自定义 quad、cutout quad 粒子；动态模型目前使用原生纹理/tint 的漫反射材质。

`rt_scale 0` 保留至少 4×、最高 640×360 的自动分辨率；`1..8` 指定线性缩放，实际尺寸受设备 buffer 范围和 1 GiB continuation 预算约束，stats 显示实际尺寸。场景有 256 MiB 压缩 CPU 页缓存、64 MiB GPU working set 和异步 miss 请求优先页；无法重建服务器尚未加载的区块。透明/加法粒子、动态 LabPBR 材质、DLSS RR、OMM/SER 性能验收仍未完成。此版本的 RTX 降噪、动态外观和性能需要实机验证。

## 构建与验证

```sh
./gradlew build clientKit
```

构建工具需要 Slang 和 SPIR-V 验证工具，详见 [Vulkan 迁移记录](docs/VULKAN-RT-MIGRATION.md)。构建执行 Java 回归、真实 Minecraft GLSL pipeline 链接、14 个 RT SPIR-V stage 验证，以及实际 Slang CPU target 对原始 BSDF/材质/环境的数值校验。独立降噪 helper 需要 NVIDIA SDK 头文件和 Windows/Linux C++ 工具链，不需要 nvcc 或旧 tracing build。产物位于 `build/libs/` 和 `build/distributions/`；client kit 只含本 mod 和当前操作文档。

## 文档

- [系统架构调整设计：总体、场景、PT、重建与迁移验收](docs/architecture/README.md)（设计基线 alpha.23；[alpha.24 实施与未完成项](docs/architecture/IMPLEMENTATION.md)）
- [安装与命令](docs/INSTALL.md)
- [设置与静止累积](docs/SETTINGS.md)
- [当前阶段](docs/CURRENT.md)
- [alpha.21–23 性能分析与下一阶段设计](docs/performance/ALPHA-23-ANALYSIS.md)
- [Vulkan 迁移与验收](docs/VULKAN-RT-MIGRATION.md)
- [Material 3](docs/MATERIAL-3.md)
- [输运数学](docs/RT-LIGHT-TRANSPORT.md)
- [环境采样](docs/RT-ENVIRONMENT.md)
- [版本历史](docs/CHANGELOG.md)

旧 REFERENCE、PATH-TRACING 和 RTX 文档保留为历史记录，其中旧后端和旧命令不再适用于 alpha.21。

本轮验证：268 项 Java 测试通过；14 个 RT SPIR-V stage 验证与 Minecraft GLSL 链接通过；实际 Slang 材质/地形/环境输运、数值边界与光源采样通过，RIS 200,000 次采样的含遮挡 RGB 能量误差低于 0.8%。Windows/Linux 独立去噪桥接已构建，但 NVIDIA GPU 运行验收尚未完成。

手持灯异常可运行 `/voxellight rt_lighting_probe`：拿着光源对准附近不透明墙面，约 30 帧后日志输出入射光、材质响应与遮挡结果。详见 [设置说明](docs/SETTINGS.md)。

alpha.21 验收：固定窗口大小、`rt_spp 1` / `rt_scale 0`，`profile on` 后静止/移动各测试约 20 秒，`export` 导出分阶段 GPU 时间。动态场景同拓扑 refit，shader 几何和纹理只复制变化区段/当前纹理 tile；去噪 guide 来自稳定中心射线，动态历史保守拒绝。OptiX 只降噪实际输出的 beauty 层，成功时不运行 Vulkan 滤波。性能与画质仍需 RTX 实测。
