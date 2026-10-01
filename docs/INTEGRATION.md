# Minecraft 26.2 接入记录

## 已锁定的基线

Minecraft `com.mojang:minecraft:26.2`，Java 25，Fabric Loader `0.19.5`，Fabric API `0.160.0+26.2`，Loom `1.17.21`，Gradle `9.5.1`。依赖实际解析与构建成功，26.x 使用官方未混淆类名。入口为 `com.voxellight.VoxelLightClient`，mod ID 为 `voxellight`；元数据只允许客户端和游戏 26.2。

源码通过 `./gradlew genSources` 生成并检查，没有将 Mojang 源码提交到仓库。26.3 的 RenderPearl 包名与 26.2 不同，当前代码统一使用 26.2 Blaze3D。

## 能力与使用位置

| 能力 | 26.2 符号/证据 | 当前状态 |
| --- | --- | --- |
| 世界 pass 之后、手之前 | `GameRenderer.renderLevel(DeltaTracker)` 内 `LevelRenderer.render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc, GpuBufferSlice, Vector4f, boolean)` 返回后注入 | 版本内部 hook；字节码检查唯一调用且位于 `clearDepthTexture` 前；实机待验证。 |
| 世界颜色与深度 | `GameRenderer.mainRenderTarget()`，`RenderTarget.getColorTexture/View()`、`getDepthTextureView()` | 编译通过；只在 hook 内使用当帧 vanilla 引用，不跨帧保留。 |
| 自有 pass | `GpuDevice.precompilePipeline(RenderPipeline, ShaderSource)`、`CommandEncoder.createRenderPass`、`RenderPass.bindTexture/draw` | 两个独立诊断 pipeline。由游戏编译缓存持有；关闭 mod 不销毁游戏缓存。 |
| read/write alias 避免 | `CommandEncoder.copyTextureToTexture` | color 模式先复制到同尺寸/格式 scratch，再输出 main color；depth 直接采样 main depth，pass 无 depth attachment。 |
| Backend/深度约定 | `GpuDevice.getDeviceInfo().backendName/isZZeroToOne` | 仅 backendName=Vulkan、RGBA8_UNORM scene color 执行；raw depth 诊断采用 vanilla 清零的 reversed-Z。 |
| GPU timestamp | `createTimestampQueryPool`、`writeTimestamp`、`getValues`、`DeviceInfo.timestampPeriod` | 4 个独立 query pair，至少延迟两帧且结果可用后才复用；满则跳过计时，继续 draw。API 未暴露 valid bits，负差值拒绝。 |
| query readback 行为 | 生成的 `VulkanQueryPool.getValues` 使用 64-bit + availability，未设置 WAIT；`VulkanCommandEncoder.writeTimestamp` 先 host reset | 源码检查，不直接调用这些内部 Vulkan 类。GPU 实测仍待验证。 |
| 销毁/同步 | Vulkan texture/view/query pool 的 `close` 交由 backend 延迟回收 | mode 切换、`GameRenderer.resize/resetData/setLevel/close` 释放自有资源；正常帧不 submit、不 waitIdle、不等待 query。 |
| reload | Minecraft 清理 pipeline cache 后，下一次 enabled pass 再 precompile；每帧重新取主目标 | shader 来自 mod 内置资源，不支持材质包覆盖；没有 GBuffer/history，resource reload smoke 待验证。 |
| MRT | `RenderPipeline.Builder.withColorTargetState(int, ...)`、`RenderPassDescriptor` | API 存在，尚未扩展 terrain 输出或验证 MRT 性能/画质。 |
| compute/storage、caster 重绘、albedo/normal/velocity | 尚未完成完整能力实验 | 不宣称支持，不引入 native extension；P0b/P1 阻断项。 |

## 当前验证

`./gradlew build clientKit`：编译 main/client、生成客户端 JAR、运行相关测试并打包。测试先检查最终 JAR 的 mixin package 只含已声明的 mixin 类，再覆盖 bounded 样本在 delayed GPU 结果下的回收/清空、缺失与无效 timestamp 的 CSV 空值、26.2 hook 的字节码位置，以及使用 Minecraft 自带 Vulkan GLSL 编译器将三份 shader 编译为 SPIR-V。shader 测试无需创建 Vulkan device，不能代替 graphics pipeline 实测。

当前环境没有 `/dev/dri` GPU 设备和图形 display；未启动 Minecraft，也未生成帧率/显存/画质跑分。P0a 实机门槛仍未通过；安装验证步骤见 [INSTALL.md](INSTALL.md)。采样导出仅覆盖诊断 pass，不是原计划完整 benchmark 系统。

下一步先在真实 26.2 Vulkan 客户端完成 smoke、depth/颜色校验与 timestamp 对比，再建立 WorldSceneBridge、最小材质/normal 接入和完整资源预算。通过这些门槛之后才实现 P1 参考阴影与缓存。

## 0.1.1 启动崩溃修复

用户实机确认 0.1.0 在 client entrypoint 阶段发生 `IllegalClassLoadError`：mixins JSON 将整个 `com.voxellight.adapter` 声明为保留包，普通 `RenderProbe` 因此无法加载。0.1.1 将 mixin 单独放在 `com.voxellight.mixin.client`，适配器保持原包；不修改其他 mod。新增最终 JAR 包布局回归测试，在 0.1.0 上重现失败后验证修复。benchmark 导出版本改为从 Fabric mod 元数据读取。此修复针对已报告的启动错误，尚未替代后续实机渲染验证。

## 0.1.2 诊断画面修复

用户实机确认 Vulkan 下 `diagnostic active`、GPU timestamp 可用且样本增长，但 depth 画面仍与 vanilla 相同。根因是误用了 `RenderPass.draw` 参数顺序：26.2 实际签名为 `(vertexCount, instanceCount, firstVertex, firstInstance)`，原调用 `(0, 3, 0, 1)` 提交零顶点。0.1.2 改为 `(3, 1, 0, 0)`，提交一个 fullscreen triangle。新增最终 JAR draw 参数检查，在 0.1.1 上重现失败，再验证修复。活动状态/采样只能证明命令路径执行，不能单独证明最终像素正确；修复后的实机画面仍待确认。
