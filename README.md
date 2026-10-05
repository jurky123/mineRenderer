# mineRenderer / VoxelLight

VoxelLight 是 Minecraft 26.2 / Java 25 的纯客户端 Fabric 光照原型，支持原生 Vulkan。当前版本 **0.39.0-alpha.16**：火把/灯笼独立光源、接收面自遮挡保护、手持照明 GPU 诊断与动态累积。全新安装默认不启用效果。

[下载 alpha.16 安装包](https://temp.sh/fpcTm/voxellight-client-kit-26.2-0.39.0-alpha.16.zip)（临时链接）。替换旧 jar 后，进入世界执行：

```text
/voxellight rt_backend vulkan_pt
/voxellight held_lights on
/voxellight rt_accumulate freeze off
/voxellight rt_accumulate spp 256
/voxellight rt_accumulate on
/voxellight status
```

静止时每帧增加一个样本，默认目标 64 spp，允许 4–4096。默认保持动态光照：目标前求线性 HDR 运行平均，目标后以 1/目标的权重持续更新。太阳/月亮、手持光、动画纹理和水波持续更新，手持灯切换或明显光照变化会重置历史。移动、视角/FOV/分辨率变化、地形编辑和资源重载也会重置。`rt_accumulate reset` 手动重置，`off` 恢复每帧 1 spp。可用 `rt_accumulate freeze on` 显式冻结快照并在目标达到后停止追踪，`freeze off` 返回动态模式。坏样本逐像素拒绝，不会污染累积；这不是时间重投影或去噪。

按用户授权，旧 OptiX/CUDA **追踪**实现、JNI 桥、运行时 PTX/OptiX 编译器、旧动态捕获、旧命令和混合渲染分支已经删除，安装包不包含旧 native DLL/SO/PTX。旧配置中的 RTX preset/backend/sample target 自动迁移，旧路径独有开关丢弃。材质/BSDF/环境/水面的原始数学基准继续用于 Slang 数值校验。独立 OptiX temporal AOV 去噪接口与 GPU export 基础保留，尚未接入 Vulkan 输出。

当前 Vulkan 路径包含原生地形 BLAS/TLAS、Material 3/LabPBR、cutout、介质栈、最多 6 次反弹、共享 HDR 天空、太阳/月亮/环境/手持光与原生发光地形 NEE/MIS。手持点光每次有效表面/介质着色独立连接，不再与天空争用随机光源选择，连续 BSDF MIS 不作用于该离散点光。预编译 Slang/SPIR-V 随 jar 发布，游戏内不编译 PT 程序。`rt_backend vulkan_poc` 为法线调试；`vulkan_transport_test` 为灰色材质输运对照；`raster` 恢复光栅。`preset vulkan_quality` 开启 Vulkan 材质路径和累积；performance/balanced/quality 是光栅预设。

仍有边界：64 MiB/512 section 地形预算、最高 640×360 追踪分辨率，实体尚未进入 RT；仅 LabPBR 发光且原生发光等级为零的材质 NEE、完整环境一致性、AOV 重建/去噪/DLSS RR 和性能验收仍待完成。静止累积版本需要 RTX 实机验收。

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

旧 REFERENCE、PATH-TRACING 和 RTX 文档保留为历史记录，其中旧后端和旧命令不再适用于 alpha.16。

Alpha.15 validation: 246 Java tests passed; 12 shipped SPIR-V stages validated; actual Minecraft GLSL pipelines linked. Material/terrain/environment parity passed (57,600 cases / 32 cases / 400,000 samples), along with actual shader numerical boundary checks and 100,000 emitter CDF/solid-angle PDF samples. RTX visual and performance acceptance remains pending.

手持灯异常可运行 `/voxellight rt_lighting_probe`：拿着光源对准附近不透明墙面，约 30 帧后日志输出入射光、材质响应与遮挡结果。详见 [设置说明](docs/SETTINGS.md)。alpha.16 已通过 CPU 实际表面输运和遮挡测试，RTX 游戏画面尚待实机验收。
