# alpha.21–23 性能分析与下一阶段设计

2026-10-06。依据用户上传的三组 `.passes.csv`、`.world.csv`、最终状态和当前代码；没有在构建主机进行 NVIDIA GPU 测试。本轮完成分析与可复现统计工具，没有修改渲染路径或发布新 mod。

## 结论

alpha.23 修复了 alpha.22 的动态 BLAS 分组回退，几何搬移优化也保留下来了。然而目前在 **214×120 内部 PT 分辨率**下，世界渲染 CPU/GPU 中位耗时仍为 **16.32/15.44 ms**，GPU P95 **21.80 ms**。这离能在更高分辨率、复杂动态场景下稳定运行还有结构性差距。世界渲染区间不是整帧，不能直接当作游戏 FPS。

当前优先级应是：补齐可靠测量 → 场景更新资源持久化 → 追踪/材质/可见性工作分离 → 完整重建信号与时域放大。继续改分组数、提高 spp 或调去噪强度不能同时解决这些问题。

## 数据与测量边界

以下是各自导出窗口内的中位数，单位 ms。三次最终状态都显示 214×120、实时、1 spp；alpha.21 保留的诊断仍来自之前的 8-spp 帧，文件没有每次 dispatch 的 spp。场景、采集时长和 GPU 时钟也没有受控，因此表格用于观察趋势，不能作为严格版本 A/B 结论。

| 区间 | alpha.21 | alpha.22 | alpha.23 |
|---|---:|---:|---:|
| 世界渲染 CPU | 18.336 | 33.684 | 16.320 |
| 世界渲染 GPU | 16.710 | 33.117 | 15.443 |
| 世界渲染 GPU P95 | 19.798 | 37.159 | 21.803 |
| RT batch GPU | 10.717 | 23.718 | 10.637 |
| RT batch GPU P95 | 13.986 | 27.208 | 17.588 |
| primary GPU | 1.867 | 4.925 | 1.755 |
| bounce 1 GPU | 3.104 | 7.566 | 3.129 |
| bounce 2 GPU | 3.291 | 7.660 | 3.429 |
| bounce 3 GPU | 0.884 | 1.591 | 0.986 |
| bounce 4 GPU | 0.719 | 0.849 | 0.860 |
| bounce 5 GPU | 0.490 | 0.859 | 0.535 |
| geometry copy GPU | 2.017 | 0.034 | 0.018 |
| BLAS scope CPU | 1.296 | 8.874 | 1.663 |
| BLAS scope GPU | 0.207 | 0.389 | 0.157 |
| TLAS scope CPU | 6.695 | 5.011 | 4.195 |
| TLAS scope GPU | 2.139 | 0.337 | 0.140 |
| dynamic capture CPU | 1.529 | 1.541 | 1.450 |
| dynamic textures GPU | 0.178 | 1.593 | 0.874 |
| material assets GPU（含纹理/天空等） | 0.886 | 3.151 | 1.625 |
| OptiX exchange GPU | 2.135 | 3.958 | 1.828 |

alpha.23 RT batch 有 612 个完成 GPU 样本；世界区间有 831 个完成 GPU 样本。两个 ring buffer 长度不同，覆盖的时间段不同。BLAS/TLAS 一帧也可能发生多次，不能把这些 scope 的中位数当作每帧开销。

`RenderPassProfile` 的 `.passes.csv` 中 `frame` 实际是 **scope serial ID**，`width/height` 都写 0，不是真实帧号或 dispatch 尺寸。`scene` 包含 BLAS、TLAS；`tlas` 包含几何复制；`batch` 包含 primary/bounces；`material_assets` 包含动态纹理和天空。不能把父子时间相加，也不能把 CPU 和 GPU 相加算 FPS。

按相邻 batch serial 窗口重建，保留恰好两个 scene scope 和一个 dynamic capture 的完整窗口，alpha.23 有 613 个窗口，**两个 scene CPU scope 的合计中位为 11.870 ms、P95 17.079 ms**。这是基于现有调用顺序的推导，未来需要真实 frame ID 验证。可复现工具：

```sh
python3 tools/analyze_rt_profile.py logs/benchmark-results/voxellight/*.passes.csv
```

工具只输出耗时和白名单工作量字段，不复制运行日志中的账户或服务器信息。

最新最终状态还显示：219 个捕获模型、20 个几何分组、89 个裁切纹理、326 个地形 section，动态纹理上传 5,832,704 bytes；最近一次几何复制 380,160 bytes。后两个值是快照，不是整段平均流量。BLAS/refit 累计计数也不能跨不同运行时长直接比较。

## CPU：refit 省了 GPU 构建，但没有消除资源更新成本

当前 `VulkanRtScene.update` 为每个改变的组新建顶点 buffer、重新上传；`VulkanRtAccel.build` 即便选择 UPDATE，也重新查询尺寸、新建目标 AS storage、新建 scratch，更新后再延迟销毁旧资源。`VulkanRtBuffer.allocate` 每次执行 `vkCreateBuffer`、内存类型查询、`vkAllocateMemory`、绑定和设备地址查询。

因此现在是 **out-of-place refit + 高频分配**。有 UPDATE 标记，不等于拥有成熟的实时更新路径。TLAS scope GPU 仅 0.140 ms、CPU 4.195 ms，BLAS scope GPU 0.157 ms、CPU 1.663 ms，说明这些 scope 的主要成本发生在 CPU/驱动录制与资源维护中；现有数据不能进一步证明其中多少是显存分配、CPU emitter 工作或驱动等待。

还有两个独立的浪费来源：

- 地形 update 与动态 update 分别立即重建 TLAS。一帧 terrain 有变动时，可能更新两次全局 TLAS，而追踪只需要最后一次结果。
- 每次 TLAS rebuild 都重新遍历静态 emissive triangles、变换坐标、排序/生成光源 CDF；动态模型变化推动 `scene.generation`，也会让静态 emitter table 重新上传。骨骼动画不应该使地形发光面数据每帧重建。

目标设计是持久场景资源：静态模型缓存、动态变形 buffer、实例变换数据、frame staging ring、AS storage 池和 scratch arena；帧开始收集变动，帧末统一 commit。复用已有 AS，只有容量不足或拓扑变更才新建/扩容。同一队列上原地更新可以通过正确同步复用；需要并行帧时采用受提交完成信息保护的多缓冲，不能直接覆盖在途资源。批量并行 BLAS 构建使用不重叠 scratch 区域，或者明确串行同步。

进一步让模型坐标与实例变换分离。纯平移/旋转只更新 instance；骨骼变形更新对应模型 BLAS；方块实体未变形时无需每帧捕获和 refit。按纹理分组仍会让一个移动对象推动整个组更新，也容易因为 camera-relative 到 world-space 的浮点变化改变几何 hash。需要稳定 object/model ID、当前/上一帧 transform 和顶点布局，不能用“更大的分组”代替实例系统。

地形/动态/emitter/纹理/天空应具有独立 dirty generation。geometry shader 索引也应从紧凑全局顺序改为稳定 allocation + instance metadata，避免 section 进出造成地址搬移、光源重编号和材质身份变化。

[NVIDIA RT 最佳实践](https://developer.nvidia.com/blog/best-practices-for-using-nvidia-rtx-ray-tracing-updated/)建议复用未改变的 BLAS、管理 scratch 与小 AS 的池化。这里的改造仍须独立测量验证，不能引用文档里的收益百分比作为本项目承诺。

## GPU：现在是固定网格的分阶段 transport，还没有存活队列

`VulkanRtContext` 始终 dispatch primary 和五次相同尺寸的 continuation；`material_indirect.slang` 对每个 pixel/sample 加载 PathState、调用 advance、存回状态并写 radiance。`advance` 会跳过死亡路径，但线程和外围状态处理仍发生。当前没有 active-index compaction、按存活数间接 dispatch 或按材质分开的 shading queue。

432-byte PathState 混合了热字段、八层介质栈以及 diffuse/reflection/refraction 累计数据。完整结构跨 pass 传递有潜在的寄存器、访存和 divergence 成本；编译器可能消除部分访问，所以不能仅凭 432 bytes 就宣布带宽受限。按完整读写估算，214×120、1 spp、五次 continuation 的逻辑流量约 111 MB，但这不是实测 DRAM 流量。需要看 registers、spill、occupancy、L2 和实际存活数。

尤其不能把一次 bounce 当作一次 ray。每个合适的表面可能连接：一个 sky/sun 候选、一个 held source、两个最近火焰、一个其余火焰 RIS 候选、一个 emissive 候选；这些是否发射 shadow rays 取决于 BSDF、半球和候选有效性。`visibility` 还允许最多 24 层透射接口。最近火焰搜索会在多次 connection 内重复执行。实际光线数远大于“6 bounces”这一参数能表达的工作量。

CPU 调度修好以后，RT batch 约 10.64 ms 仍是最大的 GPU 区间。bounce 1/2 是最重的两个阶段，优先应查：可见性射线比例、any-hit 次数、介质/材料分布、寄存器溢出和长路径，而不是先删后面三个便宜 bounce。latest batch P95 17.59 ms 表明还要关注复杂视角下的长尾。

还有一个可验证的设计问题：material BLAS 目前统一 `.flags(0)`，所以 opaque 物体也会进入 any-hit，再由 shader 判断不需要 alpha test。应把真正 opaque 与 cutout/transmission 几何按正确语义分开，让硬件跳过不必要的 any-hit。不能对全部地形强制 opaque，否则会破坏树叶、玻璃、水和火把。NVIDIA 同一份最佳实践也指出 any-hit 会打断硬件相交搜索。

目标调度可以逐步成为：相交队列 → 按材质 shading → 可见性队列 → continuation compaction。先测存活率，再决定哪些阶段值得压缩；小分辨率下 compaction 本身也有固定开销。介质栈用冷数据/按需路径类别管理，opaque 直接连接走更轻的可见性处理，复杂透射保留现有正确性路径。光源 proposal/hierarchy 应降低无效 shadow rays 与方差，保留独立 held light 与正确 PDF/MIS。

## 纹理、天空和场景分页：缺少独立更新生命周期

20 个动态 BLAS 与 89 个纹理 tile 已正确分开，但每帧仍裁切/上传所有活动 tile。本次约 5.83 MB/帧，对应约 0.874 ms GPU。静态 skin、未改变的物品/箱子纹理应复用；animated atlas 或自定义动态纹理需要明确内容版本，不能只按 texture view 判断没变化。更长期的方案是 native texture descriptor/atlas metadata 与材质表，避免不断复制整个 native 图集到 byte-address buffer。直接引用必须处理原版资源生命周期、资源包重载、格式和 shader descriptor 限制。

天空、环境分布也每帧生成。它们当前只有约 0.24 ms，优先级低于追踪和 CPU scene；后面可按环境变化与采样质量更新，但不能冻结太阳或动画来换性能。

64 MiB `sceneBytes` 只是当前顶点资源用量，不包含全部 BLAS/TLAS、scratch、retiring allocations、材质资产、continuation、denoiser 和复制出来的 shader geometry。显存预算需要全局分类核算。miss-page 累计请求也不是 miss 率，不能据此断言 paging 是主瓶颈。应测 admission/eviction/缺页驻留时间，并让页预算与相机可见、反射需求、更新预算共同工作；单纯增大 section 上限会加大维护成本。

## 画质与重建：降噪和时域放大是两项不同工作

854×480 输出对应 214×120 PT，只有 25,680 个路径起点。代码把内部结果用 LINEAR sampler 放大，并未做真正 temporal upsampling。1080p 自动内部 480×270 是现在像素数的约 5.05 倍，不能靠当前低分辨率耗时外推成高分辨率性能承诺。

当前独立 OptiX beauty denoiser 交换约 1.828 ms，包含 Vulkan/CUDA 队列交接、输入/输出复制和等待，不能说 OptiX kernel 本身就是 1.828 ms。低分辨率下这些固定成本值得测，但不是 GPU 的最大区间。

更关键的是信号约定：只对合成 beauty 去噪；动态 geometry 在 guide 中被标成负类型而拒绝历史；反射/折射依赖主表面相机重投影，缺少独立 specular/transmission motion；低分辨率 guide 不能提供高分辨率边缘信息。primary noisy sample 与 stable-center guide 的 visibility 也不总相同，必须验证边缘/alpha coverage 的输入一致性。继续提高滤波强度容易变糊，增加 spp 则提高 transport 成本。

建议把 diffuse/specular demodulated radiance、viewZ、normal/roughness、hit distance 和 motion 作为正式 reconstruction ABI；动态实例系统同时提供 object motion。透明与介质保留专门处理，不能假定 opaque denoiser 对它们同样适用。之后比较 Vulkan-native NRD 与现有 OptiX，同分辨率同光照做质量/耗时对照，再接 temporal upscale 或 DLSS RR。NRD 是去噪库，并不自动把 214×120 恢复成清晰的 854×480。

[NRD 官方集成契约](https://github.com/NVIDIA-RTX/NRD)和[NVIDIA Vulkan 集成示例](https://github.com/nvpro-samples/vk_denoise_nrd/blob/main/README.md)说明了这些输入，包括 demodulated radiance、viewZ、normal/roughness、motion 和 hit distance。接入顺序必须先保证信号正确，再比较模型输出。

## 实施顺序与验收门槛

| 阶段 | 具体产出 | 验收方式 |
|---|---|---|
| 0：测量基线 | 真 frame ID、每帧实际 spp/内部尺寸、场景版本；AS build/refit/分配次数和 bytes；存活路径、shadow/any-hit、历史接受率；分离 CUDA kernel 与 exchange | 固定机位静止、移动、手持、实体密集、植被/玻璃/水、区块编辑各录相同设置窗口，剔除 warmup；profiling on/off 比较扰动 |
| 1：场景生命周期 | 持久 buffer/AS、staging ring/scratch arena、稳定实例、独立 dirty generations、一帧一次 scene commit | 稳定容量下每帧 Vulkan memory allocation 接近零；纯 transform 不 refit；动画不重建 emitter；检查放置/破坏、资源重载和在途同步 |
| 2：transport 调度 | opaque/cutout 正确分类；shadow ray 专门路径；测量驱动的 active queue/hot-cold state；light proposal | 同质量比较 shadow rays/有效贡献、存活率、register/spill、RT batch median/P95；保持六顶点 reference 与 MIS 能量一致 |
| 3：重建与输出 | 正式 diffuse/specular/motion/hit-distance ABI；Vulkan-native denoiser 对照；真实时域放大 | 固定 spp/尺寸比较运动稳定、薄边缘、cutout、镜面、玻璃；输出质量和耗时一起记录，禁止通过偷偷降内部尺寸证明提速 |

阶段 1 可以先控制 CPU 帧时间和长尾；阶段 2 才直接针对 10.64 ms 的 tracing 主成本；阶段 3 决定 1 spp 是否能成为可用画面。它们彼此关联，但每阶段要保留独立 reference/ablation 开关，避免一次更改多个变量后无法判断收益。

不预先承诺“翻倍 FPS”或某个固定毫秒目标。当前证据能确认 alpha.23 的回退恢复、几何复制收益和剩余结构成本；还不能确认 shader 是 RT-core、ALU、缓存还是寄存器受限，也不能从最终状态证明整个采集窗口的 spp/时钟一致。OMM/SER 应在上述测量后决定，不能替代基本资源与信号设计。
