# alpha.49：Guide Fast Path、Transport 与 Scene 验收

## 范围与结论

基于最新 main `8a31f07`（alpha.48）及 `RECONSTRUCTION-TRANSPORT-BASELINE.md` 实现。保留 Vulkan RT / Runtime 2.0 / Material 3、FULL / SIMPLE / TWO_PASS、原有缓存和精确阴影。没有引入新缓存算法、ReSTIR、Neural Cache 或 GPU AS executor。

alpha.49 完成的是帧内命中复用、Guide 诊断和可重复负载/图像验收能力。**没有 alpha.49 RTX 数据，不宣称 FPS 提升或重建画质通过。** 默认仍 FULL / Wavefront / Monolithic / OPTIMIZED / 原始 ENDPOINT；ENDPOINT_FAST、SIMPLE、TWO_PASS、ASYNC_PREP 均须独立验收。

## alpha.48 上传数据：不是 alpha.49 的性能成绩

设备 RTX 4060 Laptop、NVIDIA 591.74；DLSS RR Performance，实际 PT 524×336、输出 1048×671、1 spp。TraceRay / FIXED / EXACT / RIS / FULL，OMM/SER 关闭。两份 ZIP 完整结束，分别 16/16、8/8 段有效。下面数字是同一对照各组四个段的分位数均值，不是合并逐帧后的全局分位数，也不能把两个 suite 当作同场景。

| alpha.48 同组对照 | Transport GPU ms | GPU World ms | Client 帧间隔 P50 / P95 ms |
|---|---:|---:|---:|
| FULL（SIMPLE 配对基线） | 28.564 | 31.203 | 31.065 / 33.696 |
| SIMPLE | 22.261 | 24.747 | 25.130 / 27.381 |
| FULL（TWO_PASS 配对基线） | 28.021 | 30.647 | 30.533 / 33.139 |
| TWO_PASS | 61.256 | 64.035 | 64.275 / 65.991 |

SIMPLE 输运耗时改善 22.06%，重复波动 3.05%；它改变 Oren–Nayar/多重散射，**不是同画质收益**。TWO_PASS 耗时增加 118.61%，其中融合 Primary Shade 约 60.72 ms；不能因为 48B 状态或少调度而启用为默认。B1/B2 仍是 FULL 热点；寄存器/溢出归因须 Nsight，不能从时间表推断。

Scene suite 的 CPU Scene P95 对照因 13.61% 重复波动判为 `not_comparable`。OPTIMIZED / ASYNC_PREP 的 GPU Scene commit 约 0.251 / 3.802 ms。源码确认 scratch 只把 OPTIMIZED 识别为分片模式，ASYNC_PREP 错落入逐 build 屏障路径；上传状态也显示 barrier/slice 从低比例变为约 1:1。alpha.49 将分片条件修为所有非 LEGACY 模式。**这解决分支遗漏，GPU 耗时是否恢复仍须重测。**

原始数据标识：

- `rt-suite-1791528975644.zip`：295 固定地形 sections；SHA256 `76ac6d29cbb775d1c0128f7795138c01d082fd952b60f9daf9e40b49ee1bfb5d`。
- `rt-suite-1791529691918.zip`：298 sections；SHA256 `c64455240f622faeac2a71212c23f9d1f263bbaead299a1386bc3e15657ca7be`。

原始包无图像，不能验收 RR 清晰度，也不能与此前 427×240 的结果直接比较。

## A：帧内 32B 命中复用

Primary 已经生成首次命中及 normal/albedo/motion Guide。ENDPOINT_FAST 在这一步写入精确的 `distance/barycentrics/triangle/instance/frame/eligibility`，复用现有 output 的 endpoint 两页（8/9），**不增加 descriptor、Buffer、每像素常驻内存或历史资源**。记录包含两个 uint4；通过 bitcast 写入 storage buffer，不经过纹理过滤。现有 Split visibility 分类及完整 shade 都能写入，Monolithic 直接在首次材质解码处写入。

```
Primary（Monolithic 或 Split）
  → 当前帧 Hit Record + 第一层 Guide
  → resolve（保留 8/9 页）
  → Fast Guide 读取、清空记录
       普通表面/sky：返回
       反射：复用首次 normal / position / previous position，再追踪反射终点
       平滑透射：复用首次 hit，解码接口并继续 Snell 链
  → 8/9 页改为反射终点
  → DLSS Guide raster → NGX
```

普通表面不会重发 Camera Ray，也不会在 Guide pass 再执行 `terrainSurface`。反射接口复用已写入的第一层 frame；透射确需几何法线/IOR，因此仅透射接口再次解码。次级终点仍使用原材质/动态几何流程。记录缺失或 frame 不匹配回退原 Camera TraceRay；miss 显式写入负 distance，消费后保持 sky tuple。所有记录和 endpoint 每帧初始化，资源重载/尺寸变化沿用既有 context 重建，不跨资源世代复用 triangle ID。

Guide 使用共享的 `primaryDirection()`，与 radiance 的 RR jitter 相同；不再调用会写八层 medium storage 的 `primaryPath()`。Guide 本身不改变辐射、NEE、RNG continuation 或缓存。历史定位不可信的动态表面/终点不标记 previous endpoint 有效；透射历史同时要求前景接口与后方终点都有效；普通 motion shader 同时检查 previous-position validity，避免新实体使用无效姿态。模式切换继续使 RR history 失效。

三条路径：

```text
/voxellight rt_guides first_hit
/voxellight rt_guides endpoint
/voxellight rt_guides endpoint_fast
```

ENDPOINT 保留独立 Camera TraceRay + 首次材质解码，作为回归路径。两种 endpoint 共享当前的平面反射、最多六层 Snell、虚拟深度近似。旋转/变形反射面的 previous normal、动态水面、多介质嵌套折射和双层重建能力仍非最终形态。本轮不把这些近似包装为已解决。

## B：GPU 时间、计数与独立负载

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction dlss
/voxellight rt_spp 1
/voxellight rt_benchmark guides
/voxellight rt_benchmark transport
/voxellight rt_benchmark scene
```

分别运行，期间站定、关闭菜单、窗口保持焦点。可加 `4..30` 秒采样参数；不要在性能测量时请求图像 readback。

- `guides`：32 段，两轮 ABBA，分别在 Monolithic / Split 中比较 first_hit → endpoint 和 endpoint → endpoint_fast。**结论使用 GPU World**，包含 Primary 记录写入成本，不能只看 Guide pass 变便宜。另报 `vulkan_rt_deterministic_guides`、Transport、RR、CPU 与 Client P50/P95。若非 first_hit 段没有 endpoint counter samples，则无效，防止没有 DLSS 执行时出现虚假收益。
- `transport`：保留三种模式的 16 段受控配对，FULL 分别对 SIMPLE / TWO_PASS；相同实际尺寸、路径长度、spp、光照、地形、阴影/候选设置。SIMPLE 是画质取舍，TWO_PASS 是 alpha.48 已退化的实验。
- `scene`：仍为 CPU Scene commit **P95** 的 8 段 ABBA，但两侧都注入受控几何变形，并使用相同 scratch 分片策略。它不改变服务器/本地世界方块。

每个段新增 `.guides.json`：sampledFrames、16 列计数；复用已有反馈头 1617..1620，只有已有每八帧 counter 采样帧执行 atomics。列包括 pixels、recordReused、invalidRecordFallback、sky、ordinarySkipped、Camera/Reflection/Transmission rays、有效反射/透射 endpoint、endpointMiss、TIR、roughDielectricFallback、materialDecodes、unresolvedInterfaces。统计是被采样帧的累计值，不能当作整段全部 ray。first_hit 没有额外 Guide pass，额外 Guide ray 为零。`.passes.csv`、`.rays.csv` 和 counter frame 排除规则保留，性能时间由无 counter 帧形成。

每个段另有 `.resources.json` 每秒 renderer/state 快照，包含分配/退休 Buffer、复制字节、BLAS refit/build、scratch 屏障和异步队列统计；这些多为累计值，计算段内流量必须取差值，不能把累计分配当作峰值常驻内存。

### Scene 为什么必须有受控负载

alpha.48 状态表已有大量动态 refit 和 accepted preparation，不能说它完全没有更新。但自然实体会改变数量、动画、位置；原计划不能证明两侧更新工作量相等。alpha.49 每帧生成 **16 个 RT-only Section × 128 三角形 = 245,760 字节**，保留真实捕获的材质/UV/tint 和固定拓扑，顶点位置按 120 帧周期确定性变形。模型放在相机外 8192 blocks，走实际 collect → classify → upload → BLAS refit → TLAS 流程，不重编译世界、不创建真实实体。负 key 命名空间与原动态 slot 分离，结束后删除；世界/资源变动仍中断测试。

`.scene_load.json` 记录实际 acceptedSections/acceptedBytes、参数与 emittedFrames，以及受控更新的 asyncPreparedSections / synchronousSections。ASYNC_PREP 全部同步回退、没有真实后台结果发布的段也判无效。采样期间每帧期望接受 16 个更新，少于期望的一半则该段无效。接受量统计位于实际 Scene commit 接受列表，而不是仅统计负载生成。受控更新先于自然动态对象入队，防止有界 32-ticket 队列被自然对象占满而使全部受控更新回退。readiness 阶段不计入样本；采样开始重置变形序列。自然动态对象仍存在，报告须同时比较实际 AS/资源计数。

这是受控 renderer 更新负载，**不是 Minecraft section 编译/骨骼蒙皮的完整基准，也不是独立 GPU 提交**。CPU 准备存在复制和等待成本，只有 CPU P95、Client P95、GPU World 均可接受时才能考虑推广。

## C：固定场景和图像验收

只在专用本地 Creative 测试世界（建议 flat、seed 49）执行 fixture，不能在已有世界/公开服务器执行清理 fill。离线生成工具不会连接 MC：

```sh
python3 tools/acceptance/alpha49.py fixture alpha49-fixture
```

输出 `setup.mcfunction`、两个动态对象 pose 与 `scenes.json`。它固定五个相机位：普通石质地形、cutout 树叶、金属反射、玻璃/水、动态 armor stand。按文件在本地测试世界载入/执行函数；两个 pose 用于可重复的对象运动。普通原版 gold 不保证足够光滑，镜面场景必须使用双方相同的已验证 LabPBR 资源包，并从 normal/roughness Guide 确认实际材质；记录资源包版本/哈希。

每个相机位以 FULL 为基线，分别切换 Guide 三路径、SIMPLE、TWO_PASS。使用相同曝光、时间、天气、输出尺寸和实际 PT 像素数。等待历史稳定后：

```text
/voxellight rt_transport full
/voxellight rt_guides endpoint
/voxellight rt_capture_guides
/voxellight rt_guides endpoint_fast
/voxellight rt_capture_guides
```

不要同时改变 Transport 与 Guide。再按同样机位独立切 `rt_transport simple` / `two_pass`。既比较静止 HDR Beauty、Diffuse/Reflection/Transmission AOV、normal/roughness/depth、普通/镜面 motion、endpoint 和 RR 输出，也比较受控移动相机/pose 改变、光源/方块编辑、资源重载与维度切换后的历史收敛。Reflection AOV 是现有镜面分支归属，不等于完整离线 BSDF lobe 分解。没有 AOV 图像不能宣称不存在漏光/能量变化。

```sh
python3 tools/acceptance/alpha49.py compare rr-reference.zip rr-candidate.zip --output difference.json
```

工具检查尺寸/通道、camera 位置与 clip/view 矩阵、颜色/曝光/depth/motion 契约，逐通道输出 MAE、RMSE、P95/max absolute error、非有限值数量，并记录 jitter 是否匹配。单帧 1 spp 噪声和不同 jitter 会导致差异；先对重复捕获与 FULL 高采样参考检查，不设自动“画质等价”阈值。动态图像序列还须人工检查拖影、闪烁与细节稳定，不能用一张 PNG 代替。`rr-output.f32` 是 RR 重建 HDR 输出；最终 tone-mapped 屏幕效果另用 F2 截图/同帧录像检查，固定曝光和显示处理，不将诊断 PNG 当作最终显示。安装包中的脚本路径是 `acceptance/alpha49.py`，上述 `tools/acceptance/` 是仓库路径。

## 本地自动化验收结果

`MSVC_SDK=/home/ubuntu/.cache/voxellight/msvc-sdk ./gradlew build clientKit` 完整通过；371 项 Java 测试零失败、零跳过，2 项 Python 验收工具测试通过，110 个 SPIR-V 校验和 59 个 Slang 源码哈希通过。实际 Slang CPU target 的输运/材质/光照/缓存数值回归通过，32B Guide record 的身份/帧/分类断言通过；1024 组 packed direction 最大向量误差 `6.05405e-05`。最终透射历史边界收紧后，重新编译、SPIR-V/ABI 校验两个 Guide 变体，再运行构建门禁并打包。

以上是在 Linux 构建机的编译、ABI 和数值验证，不是 RTX 客户端测试。复现完整构建仍用上述命令；验收工具独立运行：`python3 -m unittest discover -s tools/acceptance`。

## 验证层次与推广门禁

1. **源码/ABI**：复用 10 页 output 和现有 header，不增加 per-pixel history；camera、continuation、descriptor ABI 保持。scratch 分片修复；模式/资源生命周期和恢复路径复查。
2. **自动化/数值**：Java 验证 Guide 配对覆盖和变形负载的拓扑/材质稳定；实际 Slang CPU target 验证 32B record 的高位整数身份、浮点 bary/distance、frame 失效与材质分类；现有 1024 方向、GGX、反射、折射/TIR、八层介质、输运回归继续执行。Python 验证 capture 契约和 fixture。
3. **RTX 仍需执行**：确认 fast cameraRays 为零（无 invalid record fallback 时）、普通像素无二次解码；有效 endpoint 与原版相符；输入/输出/AOV 差异受控；同组 GPU World / Client P50/P95 改善超过重复波动。Nsight 寄存器/缓存/溢出计数尚无本轮实测。

本轮真正修正：重复 primary trace 的可绕过路径、普通材质二次解码、无效动态 history 使用、ASYNC_PREP scratch 分支遗漏，以及 Scene 对照缺少受控提交量验证。**具备测试能力但未完成实机验收**：fast Guide 净性能、三路径重建质量、异步 CPU 净收益。**不能启用默认**：ENDPOINT_FAST、SIMPLE、TWO_PASS、ASYNC_PREP。没有独立 GPU AS、纹理 mip、ACEScg 的新改造。
