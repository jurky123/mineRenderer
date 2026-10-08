# alpha.45：Material-Aware Cache 2.1

alpha.44 的实机数据发现默认 ROUGH_DIFFUSE 在缓存入口被拒绝：CACHE_SPARSE 段查询、训练和命中全零。alpha.45 修复材质覆盖，同时保留 FULL / MONOLITHIC / EXACT / Wavefront 默认。本文记录实现与数值验证；尚无 alpha.45 RTX 实机性能或画质验收。

## 材质与输运

`evalDiffuseLobe()` 保留 Oren–Nayar 的入射/出射方向依赖、双向 Fresnel 衰减和 base color；`evalSpecularLobe()` 包括原有 GGX 与 multiple-scattering。对无 coat/transmission 的 ROUGH_DIFFUSE，二者之和与完整 Material 3 BSDF 一致。条件 PDF 与完整混合 PDF 分开使用。

B0（`rt_cache primary`）、B1/B2 的默认粗糙地形现在可查询、训练和命中缓存。缓存仅替代漫反射间接光。镜面按 FULL 的 specular branch probability 继续精确采样：存入 continuation 的 PDF 是 `p_spec * pdf_spec`，throughput 除以 `p_spec`，漫反射分支不再发射 continuation。直接光分为无竞争 continuation 的 diffuse NEE 和与镜面 continuation 配对的 specular MIS；RIS 的候选数和 survival probability 仍进入混合 PDF。缓存命中不能把完整 BSDF 的 MIS 简单设成 1。

金属、coat、玻璃、水、介质、发光、动态几何、明显法线贴图和低粗糙度继续保护。缓存失效、未成熟或没有训练预算时保留完整路径。Primary Split 的轻量分类与完整 shading 使用相同材质准入；无法轻量分类时 Shade 执行完整分类，不再无条件跳过 Sparse 检查。

## 训练估计器与求值

紧凑请求记录世界位置、法线、源 bounce、采样方向/PDF、prefix 和采样后的 throughput。训练继续用完整精确 suffix；待训练路径不得递归查询缓存。通过 `(output - prefix) / beta` 估计 incident radiance，再以 `Y(direction) / pdf` 更新方向矩。首个 suffix 的环境/已采样发光源不重复计入训练，source NEE 保留完整权重。

缓存按空间 cell、表面 plane 与源 bounce 分开，避免用 B2 较短 suffix 训练结果替代 B0 的剩余输运。保留 4-way / 65536-slot / 10 MiB 存储、epoch、TTL、geometry 和均值误差检查。ROUGH_DIFFUSE 至少需要 64 个有效样本；纯 Lambert 保留原成熟度。

ROUGH_DIFFUSE 求值先反演半球矩阵，重建半球上的 affine incident-light approximation，再用 64 点 cosine quadrature 积分真实 diffuse kernel。这避免将粗糙漫反射误写成 `albedo * irradiance / pi`，并能重建恒定/affine 入射光。负辐射截断、有限空间/角度表示和有限样本仍使缓存成为有偏近似；它不是 FULL 的无损替代。复杂遮挡和高频间接光必须做实机画质对照。

训练先对未成熟可见 cells 去重并争取预算，成熟 cell 保留随机 refresh。预算最多 1024；独立 accepted counter 控制 GPU indirect training dispatch，零请求不执行 raygen。设备不支持 indirect tracing 时保留有边界检查的固定调度。shader-write 到 indirect-read 的 Vulkan barrier 已加入。

## 历史与诊断

纯 DIFFUSE 保留整像素历史复用。ROUGH_DIFFUSE 不复用旧 beauty/specular：历史只携带一个已验证 cache-cell token，当前命中按当前位置、法线、epoch、材质和视角重新积分 diffuse response，直接光与镜面仍计算。token 使用 opaque rough 材质不可能产生的 refraction history 槽，无额外常驻分配；保护材质不采用该解释。

新增 12 列 GPU counter，并兼容旧 16/28/44 列验收文件：

- `diffuse_eligible_b0/b1/b2`、`rough_diffuse_eligible`：当前 cache 策略实际允许查询的命中；分母不是所有材质的像素。
- `cache_mature_queries`、`diffuse_cached_b0/tail`：满足成熟条件的查询与实际采用 diffuse 缓存；当前 B0 子计数限定 1 spp ROUGH_DIFFUSE history 路径，其他材质/spp 的采用情况看总 `cache_hits`，报告 `diffuseCacheUsed` 使用总命中数。
- `exact_specular_continuations`：缓存采用后仍需采样的精确镜面分支；实际发射/存活量看 `active_1/2`。
- `diffuse_history_reused`、`diffuse_paths_terminated`：区分 diffuse cell 的历史定位复用与纯 diffuse 整条路径终止。
- `cache_cells_matured`、`cache_cell_observations`：本次采样帧中新成熟的 cells 与成功训练观察，不冒充持久缓存中全部 live cells 的数量。

报告新增 eligible/visible、queries/eligible、diffuse-cache-used、exact-specular-continuations 和 diffuse-history-reused。不能以 cache hit 直接宣称整条路径已终止。

## 动态模型生命周期

动态模型槽位回收并带 generation；generation 进入 SectionKey，旧实例删除与新实例创建拥有不同场景键，禁止 stale/double release，generation 耗尽时退役而不绕回旧身份。槽位存储随峰值同时存活模型增长，不再随累计创建次数线性增长。

原始顶点 Mesh Cache 保留。跨实体共享 BLAS、GPU skinning 和 deformation buffer 未在这次实现，不能把 generation 修复称为完整 rigid-instance 优化。

## 验证与 RTX 门禁

真实 Slang CPU/SPIR-V 数值门禁新增 40 组 ROUGH_DIFFUSE 原生对照，覆盖粗糙度、anisotropy、F0 与 grazing view：完整 BSDF 与 diffuse + specular 以及混合 PDF 一致；conditional specular throughput、边际 PDF、NEE/BSDF MIS 和随机分支无偏性回归。独立 dense native integral 对 affine 入射光的 cache diffuse kernel 最大相对误差约 1.03%（2% 门限，仅这些测试场景）。生产 policy fixture 实际执行训练后 B0 rough query/hit，保护材质、预算、plane/epoch/TTL 和 source-depth 隔离仍回归。Java 356 项测试通过。安装包最终必须同时通过完整 shader 构建与打包源码哈希核对。

实机先复跑 `/voxellight rt_benchmark material`：在 default vanilla 材质中确认 query/training/hit 非零、eligible 覆盖和 B1/B2 alive count，不能只看配置。再分别在室外、室内多光源、玻璃/水场景运行 `/voxellight rt_benchmark production`，保留相同分辨率、DLSS RR、spp、帧率限制和执行配置，比较 FULL Reference 的能量、GI、镜面、运动质量以及 client P50/P95、CPU submit/capture、GPU world/transport/B1/B2/RR 和内存。

在这些结果出现前，不将 Split/Cache 设成默认，不宣称 FPS 收益。第二轮 Adaptive Execution 的自动选择与跨实体实例复用必须基于覆盖与画质结果继续实现；当前静态 Monolithic/Split 入口均保留。
