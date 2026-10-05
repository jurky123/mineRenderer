# mineRenderer / VoxelLight

VoxelLight 是 Minecraft 26.2 / Java 25 的纯客户端 Fabric 光照原型，支持原生 Vulkan。当前版本 **0.39.0-alpha.13**：Vulkan Material 3 路径输运与静止累积。全新安装默认不启用效果。

[下载 alpha.13 安装包](https://temp.sh/lqfww/voxellight-client-kit-26.2-0.39.0-alpha.13.zip)（临时链接）。替换旧 jar 后，进入世界执行：

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_accumulate spp 256
/voxellight rt_accumulate on
/voxellight status
```

静止时每帧增加一个样本，默认目标 64 spp，允许 4–4096；达到目标后保留结果，提高目标后继续累积。移动、转视角、FOV/分辨率变化、场景更新、方块编辑和资源重载会重新开始。`rt_accumulate reset` 手动重置，`rt_accumulate off` 恢复每帧 1 spp。累积在色调映射前对线性 HDR 求平均，不是时间重投影或去噪。每次静止快照冻结天空、手持灯参数、动画纹理和水波；需要更新时移动镜头或手动重置。

按用户授权，旧 OptiX/CUDA **追踪**实现、JNI 桥、运行时 PTX/OptiX 编译器、旧动态捕获、旧命令和混合渲染分支已经删除，安装包不包含旧 native DLL/SO/PTX。旧配置中的 RTX preset/backend/sample target 自动迁移，旧路径独有开关丢弃。材质/BSDF/环境/水面的原始数学基准继续用于 Slang 数值校验。独立 OptiX temporal AOV 去噪接口与 GPU export 基础保留，尚未接入 Vulkan 输出。

当前 Vulkan 路径包含原生地形 BLAS/TLAS、Material 3/LabPBR、cutout、介质栈、最多 6 次反弹、共享 HDR 天空、太阳/月亮/环境/手持光 NEE 与 MIS。预编译 Slang/SPIR-V 随 jar 发布，游戏内不编译 PT 程序。`rt_backend vulkan_poc` 为法线调试；`vulkan_transport_test` 为灰色材质输运对照；`raster` 恢复光栅。`preset vulkan_quality` 开启 Vulkan 材质路径和累积；performance/balanced/quality 是光栅预设。

仍有边界：64 MiB/512 section 地形预算、最高 640×360 追踪分辨率，实体尚未进入 RT；发光三角形 NEE、完整环境一致性、AOV 重建/去噪/DLSS RR 和性能验收仍待完成。静止累积版本需要 RTX 实机验收。

## 构建与验证

```sh
./gradlew build clientKit
```

构建工具需要 Slang 和 SPIR-V 验证工具，详见 [Vulkan 迁移记录](docs/VULKAN-RT-MIGRATION.md)。构建执行 Java 回归、真实 Minecraft GLSL pipeline 链接、12 个 RT SPIR-V stage 验证，以及实际 Slang CPU target 对原始 BSDF/材质/环境的数值校验。无需旧 CUDA/OptiX native build。产物位于 `build/libs/` 和 `build/distributions/`；client kit 只含本 mod 和当前操作文档。

## 文档

- [安装与命令](docs/INSTALL.md)
- [设置与静止累积](docs/SETTINGS.md)
- [当前阶段](docs/CURRENT.md)
- [Vulkan 迁移与验收](docs/VULKAN-RT-MIGRATION.md)
- [Material 3](docs/MATERIAL-3.md)
- [输运数学](docs/RT-LIGHT-TRANSPORT.md)
- [环境采样](docs/RT-ENVIRONMENT.md)
- [版本历史](docs/CHANGELOG.md)

旧 REFERENCE、PATH-TRACING 和 RTX 文档保留为历史记录，其中旧后端和旧命令不再适用于 alpha.13。

Alpha.13 validation: build/clientKit passed, 241 tests with zero failures; actual accumulation GLSL links against Minecraft bindings, packaged-artifact regression rejects legacy tracer/native compiler payloads. Material/terrain/environment Slang-native parity remains unchanged (57,600 cases / 32 cases / 300,000 samples). Independent temporal AOV header syntax-check passed against the installed OptiX/CUDA SDK. RTX visual acceptance remains pending.
