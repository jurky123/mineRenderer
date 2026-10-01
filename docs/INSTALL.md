# VoxelLight 26.2 接入原型

版本：0.1.1。仅客户端，不安装到 Paper 服务端。此版本用于接入诊断，尚无太阳阴影或 GI；默认关闭，功能开关不跨游戏启动保存。

## 安装

1. 创建 Minecraft Java **26.2** 的 Fabric 客户端，使用 Java **25**、Fabric Loader **0.19.5** 或兼容的新版本。
2. 安装 [Fabric API 0.160.0+26.2](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.160.0+26.2/fabric-api-0.160.0+26.2.jar)。已有兼容的 Fabric API 时无需重复安装。
3. 将安装包 `mods/voxellight-client-26.2-0.1.1.jar` 放进该客户端的 `mods/`，替换旧版 VoxelLight，保留其他前置。
4. 视频设置中选择 Vulkan，然后进入测试世界。首次验证使用 vanilla 材质和不含其他 renderer mod 的独立测试配置。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/voxellight` 或 `/voxellight status` | backend、设备/驱动、pass 状态、timestamp、scratch 字节和样本数。 |
| `/voxellight mode color` | 世界颜色复制并重新绘制；预期视觉上与 off 一致，用于发现翻转、采样或色彩差异。 |
| `/voxellight mode depth` | 世界 reversed-Z 的对数灰度诊断，非线性距离；天空预期为黑色。透明物体可能不写深度。 |
| `/voxellight mode off` | 关闭 pass 并释放自有纹理/query pool。 |
| `/voxellight export` | 在游戏目录 `benchmark-results/voxellight/` 写 CSV 和设备状态说明。 |

CSV 的 CPU 字段只表示该 pass 的命令准备时间，GPU 字段表示 color copy + draw 或 depth draw，不是整帧耗时。GPU 结果延迟读取，未完成/不支持时留空；导出前让场景继续渲染几帧。模式切换、窗口尺寸变化、世界切换清空样本；先导出再切换。最多保留 14,400 条，导出发生在命令执行时，不在每帧写文件。

OpenGL/未知 backend 或不支持的 scene format 保留原生画面，status 会显示原因。shader/pass 出错时自动关闭并写日志，下一帧恢复原生渲染；可用 mode 命令重试。已有 vanilla spectator/post effects 会继续处理诊断结果。

## 实机 smoke checklist（当前待执行）

- Vulkan 启动/退出世界无 crash，color 与 off 的同场景截图无翻转/亮度差异。
- depth 在主手渲染前读取真实世界深度；手和 2D HUD 不被改成深度灰度。
- 改变窗口尺寸、fullscreen、F3+T resource reload 后画面正确，无陈旧纹理。
- 切换维度、teleport、退出并重进世界，状态/样本与资源数量可解释。
- 连续切换 off/color/depth；off 的 scratchBytes 为 0，画面立即回到 vanilla。
- OpenGL 下请求 color/depth 无诊断 draw、无 Vulkan 对象访问，原画面可用。
- export 中 CPU/GPU 数值与 profiler 对比，空值不伪报为 0；记录 GPU、驱动、视距、分辨率和测试路线。

以上通过前不进入阴影/GI 阶段。此构建尚未在真实 GPU/图形环境运行。
