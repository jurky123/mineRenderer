# alpha.73：移动噪声的 RR 历史修复与原版天空颜色

日期：2026-10-15。性能基线仍为已接受的 alpha.67；这是画质修复，不宣称新的 FPS 提升。

用户在实际客户端反馈：移动视角后噪声明显，静止后改善。安装版本为 alpha.67，DLSS RR Performance、SPP=1、FULL/EXACT。实际客户端日志未发现 DLSS 回退。此次不重新测试 Caustica 性能。

## 根因与代码对照

`RtDlssReconstruction.resolve` 原来以 `!valid || epoch != generation` 作为 NGX 全局 reset 条件，同时把 Guide 的 previous-camera-valid 置零。传入的 generation 是 `scene.historyGeneration()`，对应 `VulkanRtScene.terrainGeneration`；地形加入、重建、移除或淘汰都可能改变它。因此移动时加载地形，会让整帧 RR 历史反复丢失，并使运动向量全屏为零。

本地 Caustica 参考提交 `14770591086232e13a6b1d5320b0f2f8ef8731e3` 的 `RtDlssRr.java` 仅在新建 feature 时设置 `resetHistory=true`，evaluate 后清除；没有把每次地形版本变化作为全局 reset。这里学习其历史管理边界，未改动采样或透明度算法。

修复将普通地形更新交由 RR 的逐像素深度、运动和颜色历史判断。保留初始化、尺寸/Guide/重建切换、资源/世界重建和既有显著光照变化的失效；另加超过 64 格的单帧位移或视线反转的 camera-cut reset。增加捕获元数据 `terrainChanged / cameraCut / reset / historyFrames / historyResets`，正常渲染不增加图像回读或 GPU barrier。

这不证明所有移动噪声都来自地形更新。新显露表面仍需要积累；动态遮挡、间接光突变和拖影需要实际画面继续观察。普通转动但没有地形更新时，原来的这项 reset 条件本就不会触发。

## 天空

主世界 PT 环境改用 Minecraft `SkyRenderState.skyColor`，该颜色已包含时间、生物群系与天气变化。将 RGB8 sRGB 转为线性空间一次，不重复乘昼夜/天气系数。上半球采用该颜色，下半球保留 0.30 的缩放和地平线过渡。保留原有太阳/月亮直接光和圆盘路径；移除 PT 环境生成中的程序星点和云层。

这是原版颜色驱动的 PT 环境，不是完整复刻原版天空的渐变、星空或云渲染。非主世界保留既有回退颜色。环境贴图/CDF 尺寸、精确采样分布流程和缓存有效性仍保留。

## 最小验证与结果

`LightingEnvironmentTest` 共 4 项通过，覆盖 RGB8 线性转换、黑白边界、不重复昼夜/天气调制、直接光保留和非主世界回退。Java main/client 编译通过。继续使用 alpha.67 的全部 110 个生产 SPIR-V，44 份 common/world 源文件身份未变。

使用隔离世界，单客户端先后切换旧/新 reset 条件，保持相同天空、着色器、SPP、分辨率、NGX 原生库和设置。诊断开关只存在隔离覆盖类，未打入生产安装包。转动视角时每 8 tick 放置/移除视野内石块，强制发生真实几何更新。

| 阶段 | 地形更新事件 | 全局 reset | 事件中的连续历史帧数 |
| --- | ---: | ---: | ---: |
| 旧逻辑，转动 | 29 | 29 | 全部为 1 |
| 新逻辑，转动 | 23 | 0 | 1095–2248 |
| 新逻辑，停止后提交剩余更新 | 1 | 0 | 2300 |
| 单次向上瞬移 100 格 | 0 | 1 | 1 |

旧逻辑两份更新帧的 motion 全部为零；新逻辑第一份捕获的非零 motion 像素占比 100%，最大约 0.581 输入像素。第二份新逻辑捕获恰逢两个客户端 yaw tick 之间，motion 接近零，不能当作失败或额外降噪证据。

共 8 份捕获，每份 13 个 float32 图层，104 层均为有限值。包含运动、静止、夜晚和 camera cut；午夜高空捕获的背景为黑，与当帧原版 skyColor=黑一致。输出预览检查未见全帧损坏；未建立噪声/拖影定量指标，也未进行户外白天天空的图像比色。两阶段不是同一随机帧/同一姿态的画质评分，不能用均值或高频差异宣称噪声下降百分比。

首次隔离运行在新逻辑阶段没有引发 terrainGeneration 变化，不能证明该条件；保留其记录，第二次改用可见几何增删后完成上表验证。两次均正常退出 code 0。第二次 PID=17864，创建时间核验并通过 Windows-MCP 验证前台；没有操控用户多人世界。

## 安装包与证据

生产版本 `0.39.0-alpha.73` 保留完整 alpha.67 原生库与 110 个生产着色器；293 个生产类中仅 4 个类变化：`LightingEnvironment`、`VulkanRtEnvironmentAssets`、`VulkanRtMaterialAssets`、`RtDlssReconstruction`。未包含 legacy reset 开关、诊断写盘、DebugAgent 或 BenchmarkPlan 覆盖。alpha.71/.72 未通过性能验收的候选仍排除。

工作区证据：

- `outputs/alpha.73/quality-validation.json`：事件统计、逐捕获元数据/哈希、motion 检查。
- `work/alpha73-quality-r2/rr-events.jsonl`、`agent-events.log`、`e2e-exit.json`、`windows-mcp-focus.json`。
- `work/alpha73-quality-r2/game/benchmark-results/voxellight/rr-guides-*.zip`：原始 HDR/Guide/RR 输出和诊断 PNG。
- `outputs/client-install-alpha73/package-validation.json`：生产类、着色器、完整原生库和 ZIP 验证。
- `outputs/client-install-alpha73/installation.json`：实际安装完成后生成的回执及备份路径。

最小复现准备和启动分别为工作区 `work/alpha73-prepare-r2.py`、`work/alpha73-driver-r2.py`；启动和窗口控制使用 Windows-MCP。此轮不使用捕获期间的 GPU 时间作为性能验收数据。
