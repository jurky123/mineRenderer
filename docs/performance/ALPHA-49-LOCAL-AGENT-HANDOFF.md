# VoxelLight alpha.49：本地 RTX 调试交接

交接日期：2026-10-09。仓库：`jurky123/mineRenderer`。实现基线：`f83ce25`，版本 `0.39.0-alpha.49`。本文件之后的文档提交不改变该安装包的代码。

## 1. 本次交接要完成的事

在本地 RTX 客户端验证 alpha.49 的三个问题：

1. `endpoint_fast` 是否减少 Guide 的重复 Camera Ray / 材质解码，并真正降低 GPU World 与客户端帧时间。
2. FULL / SIMPLE / TWO_PASS 在相同实际 PT 分辨率与工作量下的性能、HDR/AOV 和最终画质差异。
3. 受控 Scene 更新下，ASYNC_PREP 是否实际参与工作，以及 CPU Scene P95 / Client P95 是否改善。

先取得可比数据，再定位和修改。保留 Vulkan RT / Runtime 2.0 / Material 3；不同时增加新的缓存算法、ReSTIR、Neural Cache 或独立 GPU AS executor。一次实验只改变一个因素。

优先阅读：

- [ALPHA-49-ANALYSIS.md](ALPHA-49-ANALYSIS.md)：本轮实现、上传数据分析、统计语义和验收边界。
- [RECONSTRUCTION-TRANSPORT-BASELINE.md](RECONSTRUCTION-TRANSPORT-BASELINE.md)：alpha.48 的 RR 契约与三种 Transport 设计。
- 当前源码。较旧版本文档是历史记录，不能据其“尚未实现”描述判断当前功能。

## 2. 当前产物与已验证范围

[alpha.49 安装包](https://temp.sh/fRTQH/voxellight-client-kit-26.2-0.39.0-alpha.49.zip)，SHA-256：

```text
387b69af9388f6895119b4aa28a0f1defbafa51aaf6670f8a3f4ec98a3631dfe
```

包内只有本 mod、随附 runtime、文档和 `acceptance/*.py`；前置 Fabric API 需要客户端已有。temp.sh 链接可能过期，源码及构建步骤以仓库为准。

已完成 Linux 构建机验证：371 项 Java 测试零失败/零跳过、2 项 Python 测试、110 个 SPIR-V 校验、59 个 Slang 源码哈希，以及原生 Slang/GLSL 数值回归。最终收紧透射历史有效性后，两个 Guide 变体重新编译并校验，重新打包通过。

**未完成 alpha.49 RTX 性能及图像验收。** 不把本地编译/数值通过解释为无拖影、无漏光或 FPS 提升。

默认仍 FULL / Wavefront / Monolithic / OPTIMIZED / 原始 ENDPOINT。ENDPOINT_FAST、SIMPLE、TWO_PASS、ASYNC_PREP 均为实验开关，不能因配置生效就启用为默认。

## 3. alpha.49 改了什么，去哪里看

| 内容 | 主要文件 | 调试重点 |
|---|---|---|
| 当前帧 32B Guide Hit Record | `shaders/rt/common/reconstruction_math.slang`、`material_transport.slang` | 精确整数 ID、bary/distance、frame/eligibility；仅第一份 Primary sample 写入 |
| 原始与 Fast endpoint Guide | `shaders/rt/world/material_guides.slang` | 普通表面/sky 早返回；反射、透射继续必要射线；记录无效回退 |
| 同一 Camera Ray 方向 | `shaders/rt/common/primary_state.slang` | `primaryDirection()` 与 RR jitter 一致；Guide 不再初始化/写 medium storage |
| Guide 管线、反馈和资源生命周期 | `src/client/java/com/voxellight/rt/vulkan/VulkanRtContext.java` | pipeline 选择、barrier、frame、反馈读回、资源重建 |
| RR motion / 原始导出 | `src/main/resources/assets/voxellight/shaders/rt_dlss_guides.fsh`、`RtDlssReconstruction.java`、`RtGuideCapture.java` | 无效 previous position、RR reset、SDK 输入契约 |
| Guide ABBA / Scene 负载 | `RtBenchmarkPlan.java`、`RtBenchmarkRunner.java`、`RtSceneLoad.java`、`VulkanPathTracer.java` | 实际执行控制、采样归属、实际接受/发布量 |
| Scene GPU 更新 | `VulkanRtScene.java`、`VulkanRtScratch.java` | ASYNC_PREP 与 OPTIMIZED 都使用分片 scratch；LEGACY 单独保留 |
| 固定质量场景 / 离线差异 | `tools/acceptance/alpha49.py` | HDR 和各 Guide/AOV 契约；单帧噪声不等于质量变化 |

Java 文件完整路径可用 `rg --files src | rg '类名'` 定位。

Hit Record **复用 output 的第 8/9 页**，不是新 Buffer：Primary 写入 → resolve 保留 → Fast Guide 读取并清空 → 改写为反射 endpoint → RR 消费。两个 uint4 共 32B，uint/float bitcast 不经过纹理过滤。不增加每像素历史或 descriptor。

同帧且 valid 才复用；miss 为负 distance。普通表面不重复追踪或完整解码。反射复用首次 position/normal/previous position；透射仍需接口几何法线和 IOR，因此会解码接口。sky/miss 不产生有效 endpoint。动态历史不可信时拒绝 motion；透射历史同时要求前景接口和后方终点有效。

切换 Guide 模式使 RR history 失效；尺寸/资源重载沿用 context 重建，不跨资源生命周期复用 triangle ID。保留 FIRST_HIT、原始 ENDPOINT 和 ENDPOINT_FAST 三条路径。

## 4. 本地环境与构建

客户端基线：Minecraft 26.2、Java 25、Fabric Loader 0.19.5、Fabric API 0.160.0+26.2。之前实测设备为 RTX 4060 Laptop / NVIDIA 591.74，但本次须记录实际机器和驱动，不强制改驱动。

先确认 checkout 和工作区；有本地改动时保留，使用独立分支，避免覆盖：

```sh
git status --short
git fetch origin
git log -1 --oneline origin/main
git switch -c debug/alpha49-local origin/main
```

先用已验证安装包跑基线，之后再构建修改版。客户端退出后替换 jar，保证 `mods/` 中只有一份 VoxelLight；不要把旧 alpha.48/49 同时留在目录中。视频设置选原生 Vulkan 并重启，日志确认实际加载版本与 GPU。

完整构建入口：

```sh
./gradlew build clientKit
python3 -m unittest discover -s tools/acceptance
```

当前 native 构建脚本面向 **Linux 构建 Windows/Linux 双平台 runtime**；本地 Windows agent 推荐在 WSL/Linux 构建、Windows 客户端运行。不能假定 `gradlew.bat build` 已具备同等 native 工具链。

需要 Java 25、Python 3、Slang 2026.19、`spirv-val`、C++ 编译器，以及脚本要求的 SDK/交叉编译依赖。Linux x86_64 的 Slang 可用 `python3 tools/bootstrap_vulkan_rt.py` 安装；它不会安装全部依赖。

| 环境变量 | 含义 |
|---|---|
| `SLANGC` / `SPIRV_VAL` | 对应工具可执行文件；也可放入 PATH |
| `JAVA_HOME` | Linux Java 25 根目录 |
| `DLSS_SDK` | NVIDIA/DLSS checkout，固定 commit `374959484e79a640feaba44c93ac8cfb0a03f5b5` |
| `VULKAN_HEADERS` | Vulkan-Headers 根目录，包含 `include/` |
| `MSVC_SDK` | 交叉构建所需 CRT / Windows SDK 根目录 |
| `MINGW_ROOT` | 脚本所用 LLVM-MinGW 工具链根目录 |
| `OPTIX_INCLUDE` / `CUDA_INCLUDE` | OptiX 去噪 bridge 所需头文件目录 |
| `VOXELLIGHT_RT_DEPS` | 本地 native 依赖缓存根目录 |

以 `tools/build_dlss.py`、`tools/build_optix_denoiser.py` 的实际检查为准。交接构建机的 `/tmp`、`/home/ubuntu/.cache` 路径不在 Git 中，也不会自动出现在本地。运行安装包不需要这些 SDK。

产物：`build/distributions/voxellight-client-kit-26.2-0.39.0-alpha.49.zip`。Shader 修改必须重新生成 SPIR-V 和 manifest；不要只改 `.slang` 后复制旧 jar，也不要用跳过编译的包冒充已验证修改版。修改版另记 commit、jar SHA、构建日志和版本标识。

## 5. 启动及固定条件

使用专用本地 Creative 世界，避免联网掉线和真实世界修改干扰。笔记本插电，保持相同电源模式/窗口尺寸/资源包/视距；记录 VSync、帧率上限、温度和后台负载。无 FG。

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction dlss
/voxellight rt_spp 1
/voxellight rt_transport full
/voxellight rt_integrator wavefront
/voxellight rt_shadow exact
/voxellight rt_realtime full
/voxellight rt_world exclusive
/voxellight rt_primary monolithic
/voxellight rt_guides endpoint
/voxellight rt_roughness linear
/voxellight rt_scene_update optimized
/voxellight stats
```

必须从 stats/日志确认 **实际 DLSS RR 活跃**、实际 PT 输入/输出尺寸和实际执行控制，不能只看 requested=DLSS。OptiX 回退不能作为 endpoint Guide 成功验收。相同 Performance 菜单档不代表相同实际像素数。

基准期间站定、关菜单、保持窗口焦点；不要截取 Guide/readback、调设置、编辑方块或切换维度。质量测试另做。

## 6. 三组性能测试与判读

以下三组单独执行，完成/导出后再运行下一组。默认每段采样 12 秒，可指定 4..30 秒；先保持默认，不因失败盲目放宽门禁。

```text
/voxellight rt_benchmark guides
/voxellight rt_benchmark transport
/voxellight rt_benchmark scene
/voxellight rt_benchmark status
```

需要中止时用 `/voxellight rt_benchmark stop`。结束/中断恢复原执行控制。结果在**实际 gameDir** 的 `benchmark-results/voxellight/rt-suite-*.zip`，最新日志在 `logs/latest.log`；启动器的版本隔离目录可能不同于 `.minecraft` 根目录。

### Guides：32 段

两轮 ABBA，Monolithic / Split 各比较 first_hit → endpoint、endpoint → endpoint_fast。结论按 `vulkan_world_total`，不能仅按 Guide pass 的下降：Primary 写 record 的成本也必须算入。

检查 `.passes.csv` 和摘要中的 Guide / Transport / RR / GPU World，以及 CPU 与 Client P50/P95。`.guides.json` 是每八帧诊断采样的累计计数，**不是全段 ray 总数**；计数帧不进入正常 GPU 时间摘要。

- 无 invalid record fallback 时，FAST 的 `cameraRays` 应为 0。
- 普通表面应增加 `ordinarySkipped`，而不是逐像素执行 material decode；反射/透射和次级终点有合法 decode，不能要求全段 `materialDecodes=0`。
- `reflectionEndpoints` / `transmissionEndpoints` 是本帧找到有效终点的计数，不等于该终点的历史 motion 必然有效。
- 检查 sky、endpointMiss、TIR、roughDielectricFallback、unresolvedInterfaces 和 invalidRecordFallback。
- ENDPOINT 段没有 counter 样本会被判无效；FIRST_HIT 没有额外 pass/射线是正常现象。

若 Guide 变快而 GPU World/Client 变慢，判为退化；不把缺失 GPU timestamp、未收敛或 `not_comparable` 当作收益。

### Transport：16 段

FULL 分别与 SIMPLE / TWO_PASS 两轮 ABBA。控制实际尺寸、1 spp、六次 surface advances、EXACT、RIS、FIXED、FULL，OMM/SER 关闭。

SIMPLE 改变普通 ROUGH_DIFFUSE 的 BSDF，是有偏简化，不是同画质优化。TWO_PASS 将 B0–B5 放在融合 Shade 中；不能把它缺少逐 bounce dispatch 时间解释为 bounce 免费。定位使用总 Transport、GPU World、AOV 和 Nsight。

### Scene：8 段

OPTIMIZED 对 ASYNC_PREP，主要指标 **CPU `vulkan_rt_scene_commit` P95**，同时看 GPU World、Client P50/P95、GPU Scene、资源/复制流量。

每帧 16 个 RT-only Section × 128 三角形，245,760B 固定拓扑变形；用真实捕获的材质/UV/tint，放在相机外 8192 blocks。受控更新先入队，自然对象仍保留。它走真实 upload / BLAS refit / TLAS，但不代表 Minecraft section 编译或完整动画捕获成本。

检查 `.scene_load.json` 的实际 acceptedSections/acceptedBytes、asyncPreparedSections/synchronousSections。少于预期一半更新无效；ASYNC 全部同步回退也无效。`.resources.json` 每秒快照包含 AS、scratch、内存和准备队列状态；累计计数取段内差值，last-frame/gauge 字段不能机械相减。

ASYNC_PREP 只是有界 CPU 分类/重排，可能等待 worker；GPU 上传/AS 提交仍在渲染线程。它不是独立异步 GPU executor。

## 7. 固定画质场景与回归

```sh
python3 tools/acceptance/alpha49.py fixture alpha49-fixture
```

安装包路径为 `acceptance/alpha49.py`。生成 `setup.mcfunction`、`pose-0.mcfunction`、`pose-1.mcfunction`、`scenes.json`。这些是手动本地世界输入，不会自动连接客户端。**setup 会清理指定区域，仅在专用测试世界执行。** 本地 agent 可将函数接入测试 datapack；按 Minecraft 26.2 的实际 datapack 格式制作，不把输出目录直接当作完整 datapack。

五个相机位：普通地形、persistent cutout 树叶、金属反射、玻璃/水、动态 armor stand。镜面对象在反射面前，动态对象在实体场景墙前；pose 文件固定移动位置。原版 gold 不保证光滑镜面，使用同一已验证 LabPBR 包并记录 hash/实际 roughness。

先 FULL + 原始 ENDPOINT，固定位置/朝向、时间、天气、曝光、输出尺寸和 PT 输入像素，等历史稳定再捕获：

```text
/voxellight rt_transport full
/voxellight rt_guides endpoint
/voxellight rt_capture_guides
/voxellight rt_guides endpoint_fast
/voxellight rt_capture_guides
```

再独立对比 FIRST_HIT 和 SIMPLE/TWO_PASS；不要同时切多个因素。输出 `rr-guides-*.zip` 后重命名成带场景、模式、commit 的文件。

```sh
python3 tools/acceptance/alpha49.py compare reference.zip candidate.zip --output difference.json
```

工具检查通道/尺寸、camera 位置和 clip/view 矩阵、颜色/曝光/depth/motion 契约，报告 MAE/RMSE/P95/max absolute、非有限值和 jitter 是否匹配。单帧 1 spp 的噪声/不同 jitter 会产生差异，先重复捕获 FULL 估计噪声基线。该工具没有“画质等价”自动阈值。

检查 input HDR、Diffuse / Reflection / Transmission AOV、normal/roughness/depth、ordinary/specular motion、endpoint validity 和 RR HDR output。Reflection AOV 是现有分支归属，不是完整 BSDF lobe decomposition。诊断 PNG 不是最终 tone-mapped 屏幕；最终显示另保存 F2 截图/录像。

静止外还需同一轨迹/pose 的运动、转动镜头、动态对象生成/删除、玻璃后方对象移动、水面、方块/光源编辑、F3+T、窗口缩放、维度切换。性能 suite 中禁止这些动作，但质量回归必须覆盖。

当前保留的近似：previous reflector normal 使用当前 normal、最多六层 Snell、虚拟折射深度/运动、单层 RR Guide 无法同时表示两套完整反射/透射层。旋转/变形镜面、动态水和复杂嵌套介质必须实机看，不能声称 alpha.49 已全部精确解决。

## 8. 已上传 alpha.48 数据，供定位参考

不是 alpha.49 成绩：RTX 4060 Laptop，PT 524×336 → 输出 1048×671，1 spp、RR Performance、295 固定地形 sections。

- SIMPLE 配对：FULL Transport 约 28.564ms，SIMPLE 22.261ms；改善 22.06%，但材质改变，不是同画质收益。
- TWO_PASS 配对：FULL 28.021ms，TWO_PASS 61.256ms；耗时增加约 118.61%，融合 Primary Shade 约 60.72ms。
- Scene 配对为另一个相机/298 sections；CPU P95 因重复波动 13.61% 判不可比。GPU Scene 约 0.251ms vs 3.802ms；源码确认 ASYNC_PREP 错走 LEGACY scratch，alpha.49 修了条件，耗时是否恢复仍须重测。

原始 ZIP 名称：`rt-suite-1791528975644.zip`、`rt-suite-1791529691918.zip`。原包是用户上传数据，未提交 Git；如本地没有，需要取得原文件。分析与 SHA 在 ALPHA-49-ANALYSIS.md。原包没有图像，不可据其确认清晰度。

## 9. 失败时怎样定位

- 长停顿：保留 `warmup.json`、latest.log 和原始逐帧 CSV，区分 pipeline 创建、首个 GPU 样本等待、真正的时长波动、fence/提交/退休资源。不要直接改 timeout 让结果“通过”。
- 原始 endpoint 正常而 fast 异常：优先查 record frame/flags、8/9 页消费顺序、barrier、miss 清空、同 jitter、动态 ID/previous geometry、重载后的 context。
- Sky 黑点/非有限值：检查 miss tuple、normal/depth/endpoint validity 和 NaN，比较 FIRST_HIT 与 ENDPOINT。不要先用 sharpening 掩盖。
- 玻璃/镜面拖影：检查前景与终点历史、specular motion 的 input-pixel 单位、RR reset，以及已知 previous-normal/refraction 近似。
- Scene ASYNC 无收益：先核对实际发布量、同步回退、worker wait、复制量和 scratch barriers/slices；不能只凭开关值认定后台工作已生效。
- TWO_PASS 仍慢：用 RTX Nsight 对融合 Shade 观察 registers/spills、occupancy、ALU/memory、材质/NEE，而不是再次直接合并 dispatch 或降低 bounce 来冒充同工作量优化。

手动恢复：FULL、Wavefront、Monolithic、ENDPOINT、OPTIMIZED、linear roughness；重建历史后再开始新测试。保持原始回归路径。

## 10. 本地 agent 最终返回什么

每个结果保留：源码 commit、jar SHA、构建/测试结果、GPU/驱动、资源包 hash、世界/相机配置、实际 PT/输出尺寸、spp、执行控制、VSync/帧率限制、电源模式，以及未经删减的 suite ZIP/latest.log。

报告至少写出：每组 valid/incomplete/not_comparable 原因、GPU World/Transport/Guide/RR P50/P95、CPU Scene/Client P50/P95、Guide rays/endpoint/fallback、实际更新与异步发布量、AS/资源差值、HDR/AOV/Guide/最终输出对照。逐项标明“源码确认”“数值验证”“RTX 实测”“仍不确定”。

若修改代码，交付独立 commit、修改前后配对结果与图像、保留回退，并说明是否具备默认推广证据。没有同画质端到端证据时维持实验开关。

可直接给本地 agent 的启动指令：

```text
请完整阅读 docs/performance/ALPHA-49-LOCAL-AGENT-HANDOFF.md、
ALPHA-49-ANALYSIS.md 和 RECONSTRUCTION-TRANSPORT-BASELINE.md，检查当前源码。
以 f83ce25 / alpha.49 为实现基线，先安装已验证包并在本地 RTX 取得
guides、transport、scene 三组可比数据与固定场景图像，再针对证据定位修正。
保留已有架构和实验回退；一次只改一个因素，不改门禁来制造通过结果。
不要把编译/数值通过、ray 减少或某个 pass 下降称为 FPS/画质验收成功。
请返回原始 ZIP/日志/图像、运行配置、commit/jar SHA、配对分析及未解决项。
```
