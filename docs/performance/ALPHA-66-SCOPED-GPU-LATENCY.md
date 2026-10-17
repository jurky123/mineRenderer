# alpha.66：Transport 内部调用延迟定位

2026-10-15。一个 multi_light 隔离客户端、一个 12 秒观测窗口，无 Caustica 重跑，无生产算法变更。候选定位从泛化 BSDF 分支转向 EXACT shadow 的最近命中交接；这不是加速验收。

## 测量方法与有效范围

现有 GPU timestamp 能定位 Primary/Transport/B1/B2，不能直接拆分一个 Shader 内的函数。独立诊断源码在实际函数前后读取 `VK_KHR_shader_clock` 的 Device 时钟，将稀疏样本累加到既有 feedback 的保留区。没有在被测函数间增加 GPU barrier；读回复用已有计数帧路径。测量对象包括实际 TraceRay、Query 前的原有 EXACT 路径、实际 FULL BSDF 与空间发光，不是 CPU 循环或替代实现。

设备为 RTX 4060 Laptop GPU，支持 device clock。独立 compute 程序通过 Vulkan timestamp 校准：跨 dispatch 的时钟增量比约为 `1.00001491 ns/tick`，读数约按 1024ns 量化。112 个计数帧中交替选择约 1/1024 和 1/4096 的调用路径，并随帧旋转哈希，避免只看固定像素。全部正常算法仍照常执行；仅抽样路径读取时钟并记录。相邻时钟读取平均间隔约 0.045µs，粗粒度下许多相邻读取相同，不能据此认为每个短函数都测到了亚微秒精度。

下表是**包含等待的单次调用平均延迟**。同一 GPU 上许多 invocation 重叠，不能乘以全帧调用数获得实际毫秒占比，也不能把各行相加。RIS/visibility/sample 等父函数含子函数与其计数写入；诊断版本也改变了代码体积和调度。因此可用于选择干预对象，不替代干净 Shader 的 A/B 帧时间验收。P50/P95 报告的是每帧调用均值的分位数，不是全部单次调用的分位数。

## 实测

| 调用 | 1/1024 平均 µs | 1/4096 平均 µs |
| --- | ---: | ---: |
| proposal geometry／PDF／环境采样 | 4.884 | 4.940 |
| candidate emission，含环境／点光／面积光分支 | 2.351 | 2.407 |
| FULL BSDF eval | 2.919 | 2.965 |
| BSDF sample，含内部求值 | 5.069 | 5.273 |
| 按命中 prepare BSDF | 6.889 | 6.849 |
| RIS 循环，含子调用 | 82.947 | 84.518 |
| EXACT visibility，含边界循环／TraceRay | 56.053 | 57.563 |
| shadow TraceRay 最近命中交接 | 43.992 | 44.791 |
| EXACT shadow 材质解码 | 1.595 | 1.645 |
| surface decode | 4.217 | 4.277 |
| 普通 radiance TraceRay | 5.252 | 5.301 |
| 空间面积光发光读取，candidate emission 子集 | 1.257 | 1.271 |
| receiver hemisphere/type reject | 0.234 | 0.243 |

两种密度的延迟排序接近；较密组 shadow trace 采样 28720 次、BSDF eval 122285 次、proposal 124388 次。它们不是同等调用数量，不能只比较单次时间决定所有优化优先级。稀疏采样也不等于无扰动；未测硬件 occupancy/cache/spill counters。

完整观测通过 benchmark 有效性检查，FULL/EXACT/LATE、524×336、SPP 1，geometry signature `1417246207890208711`、40 sections、5453040 bytes。客户端正常退出 0，未重复质量捕获。三份 Java 类及两个活跃 raygen 只在隔离 overlay 中修改，生产源码、编译 classes 与默认 Shader bank 未替换。

## 与 Caustica 对照后的具体干预

Caustica `shaders/pipelines/world/trace.slang:visibility` 使用 `ACCEPT_FIRST_HIT_AND_END_SEARCH | SKIP_CLOSEST_HIT_SHADER`，cutout／透明／水在 any-hit 处理，不执行普通 shadow closest-hit。VoxelLight 的 EXACT 则为每个有序边界执行普通 TraceRay，closest-hit 返回 distance/bary/triangle/instance，然后在 raygen 中处理介质。这种交接与语义差异真实存在，44µs 延迟让它成为值得验证的对象；尚不能证明全部差距就是 closest-hit 或寄存器 spill。

历史 Caustica 只有 pass 级同口径 GPU timestamp，没有这些函数的相同抽样时钟数据，不能编造 Caustica 每次函数耗时，也不能把普通 radiance 的 5.3µs 当作其 shadow 时间。已有总 PT 差距与源码对照提供方向，本轮的自身测量进一步缩小到具体调用。

下一项最小实验是 shadow 最近命中改用 inline RayQuery，逐候选复用同一 cutout 判定与 instance/geometry/primitive 映射，保持全部 ordered-boundary、Fresnel、RGB extinction、八层介质逻辑。非 shadow 的 radiance TraceRay 保持原实现，不直接照搬 Caustica 的平均透明度模型。GPU hit parity 与独立干净 A–B–A 帧时间将决定是否保留。

其他范围仍有实际成本：Caustica 的 `lightRadiance` 解码打包辐射值，VL 按点读取发光图集；其 BSDF 为 Lambert/GGX/Schlick，VL FULL 包括更完整的数学与 PDF/MIS。单看函数调用延迟不能确认这些差异各自占了多少跨 Renderer 毫秒。alpha.65 的颜色转换查表未获收益，继续保留其否定证据。

## 运行环境发现与证据

独立 compute 校准第一次在 `RTSSVkLayer64.dll` 的设备创建路径崩溃，栈还包含其他 implicit Vulkan layer。仅对子进程设置 `VK_LOADER_LAYERS_DISABLE=~implicit~` 和 RTSS disable 环境变量后，校准及本轮客户端正常结束；没有改变系统配置。该事实不能证明 alpha.65 的 `vkDestroyPipeline` 卡顿由同一原因造成。后续 A/B 必须使用相同 layer 环境，不能把与历史运行的环境差异当成优化收益。

工作区 `outputs/alpha.66/` 保存 `scope-latencies.json`（全部计数、帧均值 P50/P95、最大值）、时钟支持／校准、原始 suite、72-word work CSV、passes/rays/resources、退出与焦点记录、日志和诊断源码／脚本。生产仍为 alpha.61。
