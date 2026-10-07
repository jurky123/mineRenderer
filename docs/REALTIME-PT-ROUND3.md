# 第三轮：短路径、世界辐射缓存与时域稀疏 PT

## 模式与估计器边界

Reference 不读取缓存与稀疏历史，继续现有六个 surface vertex 上限、MIS、RR 与渐进累积。现有介质估计器的近似不因此变成严格无偏。Realtime 的缓存/历史复用是有偏近似，GPU 加速与画质必须分别验收。

`/voxellight rt_realtime full|cache|sparse|cache_sparse` 切换 realtime 输运策略；reference 强制 FULL。切换策略重建 RT context，清空缓存和历史。旧执行优化 benchmark 强制 FULL，以免第三轮改变其工作量。

Realtime 在 B1/B2 的稳定漫反射命中处查询缓存，成熟后在当前命中的精确直接光之后结束尾部。查询失败继续完整路径，不以天空或常量替代缺失光照。B1 可用即只保留一次精确间接命中；B1 未成熟时允许到 B2，再继续完整回退。镜面、金属、玻璃、水、介质、动态表面、带 clearcoat、法线贴图使 shading normal 偏离几何法线与发光表面保留精确路径。粗糙镜面也保守地继续追踪，在后续漫反射命中处才使用缓存；不直接用 diffuse SH 替代 glossy BRDF。

## 世界空间缓存

16 方块 section 对齐的 2 方块 cells，世界 cell 坐标与法线主轴/正负面组成完整 key，哈希到 65536 个槽。每槽 160B，约 10MiB，存 RGB L0/L1 SH、计数、亮度均值/方差、更新时间、光照 epoch、训练点与法线。冲突不能通过只有 hash 的匹配误用另一 cell；同 cell 内还校验平面距离≤0.08、法线点积>0.97、位置距离≤1.5，降低薄墙串光。这里没有 DDGI 距离矩或神经网络，SH 的角向精度和空间插值精度仍是明确限制。

至少 24 次观察才允许查询；相对方差<0.5；距相机 32 方块内最长寿命 16 帧，其他区域 48 帧。过期槽重新训练时清空旧统计，不能只更新 timestamp 就重新使用旧均值。冷缓存和高方差刷新概率 1/2，成熟近处 1/8，远处 1/16，这些概率再按 path capacity / probe budget 缩放，散布到全屏，避免前几个 GPU waves 抢光预算；每帧最多 1024 条训练后缀。训练仅来自当前实际 camera path 到达的 cells，未访问区域保持冷状态。没有独立全世界探针 sweep。

训练在候选 diffuse hit 上以均匀半球方向继续精确路径。记录本次命中直接光后的 radiance prefix 与采样后的 throughput，在 resolve 用 `(final-prefix)/throughput` 恢复入射尾部，以 `Y_lm(direction)/pdf` 投影 SH。训练路径禁止再次查询缓存，避免缓存训练缓存的反馈漂移。保留深层 RR 的补偿；零 throughput、无效或非有限样本拒绝写入。

当前命中的直接光与缓存尾部互斥分工：终止/训练命中的 NEE 取消与下一次 emitter/environment 命中的竞争 MIS；训练后缀的首个 emitter/environment 命中，已被该 NEE 支持的部分不再累加。后续 surface 的直接光照常计算。这样缓存保存间接尾部而不是重复当前表面已经估计的直接光。SH 查询得到单位反照率 diffuse response，再乘当前 baseColor 与 path throughput；ROUGH_DIFFUSE 的尾部使用此近似，reference 保留完整 BRDF。

世界、资源、算法、尺寸变化使缓存失效；静态 terrain generation、编辑队列 generation、大幅光照变化使 epoch 前进。当前采用保守的全局逻辑失效，不宣称已实现逐 section 精确依赖追踪。缓慢日光变化由 anchor 阈值与 TTL 控制；手持灯移动超过阈值也使全局失效。动态几何不作为训练锚点，仍可能影响远处间接光，TTL 是近似刷新边界。

## 稀疏调度与历史

每帧仍对每个 internal pixel 发 primary ray，获得当前真实表面。跳过的是后续完整 shading/transport，不是首命中遍历。上一帧 camera clip/位置重投影双缓冲历史；校验 triangle/material ID、epoch、世界位置、法线、反照率、材质类型与粗糙度。新暴露、动态表面、介质、锐利材质、无效历史每帧完整采样。

有效历史用亮度在线均值/方差和真实更新次数决定 1/2/4 帧间隔：confidence≥4 且相对方差<0.1 为隔帧，confidence≥8 且方差<0.025 为四帧；其余每帧。复用帧不增长 confidence、不篡改最后真实采样时间，固定像素 hash 错开相位；历史 RGB 与 diffuse/reflection/refraction AOV 一起复用。高方差恢复每帧完整路径与更频繁缓存训练，不额外提高用户设置的 spp。第一版 sparse 只在 1 spp 开启；多 spp 保留精确主路径及 cache 功能。

0.25–0.6 是稳定漫反射区域的完整路径密度目标，不是所有场景的强制比例。天空、镜面、水、运动和高噪声区域可能使实际密度更高。首命中检查和重建耗时也不会随完整路径密度同比降低。

## 存储与同步

PathHot 仍为 64B；冷介质 288B、AOV 48B 与 descriptor ABI 不变。新状态复用 feedback storage buffer，避免增加已经接近设备限制的 storage binding。

设 P=internal pixels，S=spp：1648 个 uint4 固定 header（旧 visibility replay 保留 544–1567，新 policy 为 1568–1574，诊断为 1600–1604）；2PS queue slots；8PS pending probe slots；16P 双缓冲历史 slots；655360 cache slots。总字节为 `16*(1648+10PS+16P+655360)`。每 path 128B pending，每 pixel 256B 历史。host 的分辨率上限包含新增 storage range 与 transport buffers 总量限制（场景 AS/材质资产另计）。首次分配清零，旧帧 RT 写入到新帧 RT 读/写同步，查询期间缓存只读，resolve 阶段使用单次 CAS 锁更新；锁冲突丢弃训练，不自旋。publish 前加设备内存屏障。

## 自动验收

`/voxellight rt_benchmark realtime`（可追加采样秒数 4–30，默认 10）要求 realtime、1 spp 与 compact queue。三个独立比较：FULL→CACHE、FULL→SPARSE、FULL→CACHE_SPARSE，各两轮 ABBA，总 24 blocks，结束/取消恢复原设置。固定 RIS、优化 BLAS、同一 visibility、compact queue，OMM/SER 关闭。

输出原有 zip，schema 8；每 block `.rays.csv` 新增 `rt_*` 十六项：eligible、full_paths、reused、high_variance、cache_queries、cache_hits、cache_trained、cache_rejected、probes、probe_dropped、medium_protected、sharp_protected、new_exposure、history_rejected、dynamic_protected、history_updates。密度=`full_paths/eligible`，缓存命中率=`cache_hits/cache_queries`。每八帧诊断 counters，与原策略相同，从性能聚合排除诊断帧。`warmup.json` 也记录实际策略及缓存 epoch/计数。

第三轮 alive 曲线应变化，比较不再要求 5% 曲线一致；前两轮仍保留该检查。检查 batch、primary、bounce1/2、resolve、重建及整帧成本；不能只统计省掉的后续射线。自动套件只能证明耗时和工作量，不能证明画质。

画质对照至少覆盖：室内间接照明、薄墙两侧、开关/移动手持灯、挖放方块、日夜与天气、水/玻璃/镜面、缓慢旋转和快速转向。使用同曝光的 reference 收敛截图检查能量偏差、漏光、残影及新增曝光表面。10ms→3–5ms 尚无本版本 RTX 实测支持；通过构建/数值测试也不能当作该目标已达成。

## 参考资料

本实现借鉴短精确路径接缓存和分时更新的思路，具体存储与训练按现有 renderer 实现，未直接移植下面算法：

- [NVIDIA Neural Radiance Caching](https://research.nvidia.com/labs/rtr/publication/muller2021nrc/)：在线缓存训练与路径终止的参考。
- [Dynamic Diffuse Global Illumination](https://research.nvidia.com/index.php/publication/2019-05_dynamic-diffuse-global-illumination-ray-traced-irradiance-fields)：世界空间更新与漏光处理的参考；本版没有完整 visibility probe。
- [Q2RTX](https://github.com/NVIDIA/Q2RTX)：实时 PT 与重建实践。
- [SVGF](https://research.nvidia.com/publication/2017-07_spatiotemporal-variance-guided-filtering-real-time-reconstruction-path-traced)：时域方差与稳定性参考。
