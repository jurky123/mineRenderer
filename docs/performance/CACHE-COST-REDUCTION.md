# alpha.47 — Cache Cost Reduction

alpha.46 RTX 验收证明 ROUGH_DIFFUSE cache 查询、训练与命中有效，PRIMARY 相对 TAIL transport 改善 8.62%；这不能解释为相对 FULL 的整体收益。alpha.47 优化缓存响应计算，并提供同场景配对门禁。默认仍 FULL / MONOLITHIC / EXACT / Wavefront，不自动选择缓存。

## 预积分响应

旧 `rtDiffuseResponseQuadrature()` 保留 64 点求值。新响应先把半球方向矩还原成 `Li = a + bn*z + bt·tangent`。各向同性 Oren–Nayar 的 A/B 系数仍按实际粗糙度计算；Fresnel 与方向积分预计算成三个核，运行时为 `baseColor * (a*K0 + bn*Kn + bt_out*K_t)`。这不是把 ROUGH_DIFFUSE 当作 Lambert，也没有改动精确镜面 continuation、直接光或训练 estimator。

`tools/generate_diffuse_kernel.py` 生成 33 个 sqrt(F0) × 65 个出射余弦节点，4096 个径向 midpoint 样本、解析方位角矩；表约 26 KiB，带版本头打包，材质资源创建时只上传一次，通过 assets buffer 标量读取；不在每条路径创建局部常量数组。生产 SPIR-V 门禁拒绝新 LUT 的局部数组表示。覆盖 F0 0.01–0.25。出射角和 Fresnel 采用双线性插值；粗糙度无需 LUT 维度。

线性核仅适用于半球内非负的仿射入射光。逐 RGB 检查精确半球最小值；需要 `max(Li,0)` 截断、超出 F0 范围或其他材质时沿用旧求值。不能把有截断的 SH 当作线性函数积分，否则会改变能量。回退率高时收益可能有限，需实测。

数值门禁运行真实 Slang 生成的 native 代码及 SPIR-V 校验；包括既有 40 组 lobe 对照、256 组 F0/粗糙度/掠射角/非均匀入射光与 65536 点 native BSDF 积分，以及截断回退逐值一致。256 组扩展测试使用真实二进制资产表，最大相对误差 0.406%；同组旧 64 点最大误差约 2.04%，两者最大差异约 2.045%。既有 40 组最大相对误差 0.023%。门禁不替代 FULL 世界图像、AOV 和 RTX 性能验收。

## Primary 已验证分类复用

Visibility 的 32 B HitRecord 分类位仅在轻量材质验证和 guide 写入成功后设置。Shade 使用独立 `guideClassified` 参数复用该验证结果，跳过重复 emission/normal atlas、平面与材质准入检查以及 guide 写入。精确 shading 所需颜色/UV/frame 仍读取；未分类、保护材质与 monolithic 路径保留原验证。`sparseChecked` 不作为材质分类证据。

## 验收命令

替换旧 jar，进入有普通地形的单人世界，固定窗口大小和相机，关闭菜单：

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction dlss
/voxellight rt_spp 1
/voxellight rt_benchmark cost
```

16 段、两轮 ABBA：

1. `realtime_kernel`：相同 SPLIT/PRIMARY/CACHE_SPARSE，64 点 QUADRATURE 对 PREINTEGRATED；比较 transport GPU。
2. `realtime_end_to_end`：FULL/MONOLITHIC 对 SPLIT/PRIMARY/CACHE_SPARSE；比较完整 GPU world，包含 scene commit、transport、RR 和显示。

固定 FIXED/OWEN/EXACT/Wavefront，OMM/SER off、同分辨率、1 spp、同地形快照。默认每段采样 12 秒，另加预热、drain 与驱动初始化。结束/中断恢复原设置（包括 kernel）。导出 `benchmark-results/voxellight/rt-suite-*.zip`。这是受控 GPU 世界对照；不能将 verdict 直接叫作客户端 FPS 或同画质通过。

新增 60 列 GPU 诊断，兼容旧 56 列报告。四列为 `kernel_preintegrated`、`kernel_clipped_fallback`、`kernel_material_fallback`、`kernel_forced_quadrature`，统计响应求值次数，不冒充成功 cache hit。应同时检查查询/命中/训练、B1/B2 alive、CPU submit、client P50/P95 和资源数据。

手动隔离：`/voxellight rt_kernel quadrature` 或 `preintegrated`；stats 显示实际 `rtKernel`。旧 `rt_benchmark material` 与保留日常设置的 `rt_benchmark production` 仍可用。

## 尚待 RTX 验收

室外、室内多灯、玻璃/水分别跑 cost；再用两方案的 production 报告比较 client P50/P95 与 CPU scene。静止、相机运动、方块/光源编辑、切换维度分别对照 FULL 的 Beauty/Diffuse/Specular AOV，重点检查薄墙漏光、镜面贡献与时域失效。无同画质结果前不启用 Adaptive Execution。

本轮没有宣称定位或解决驱动首次执行停顿；alpha.46 首样本等待门禁保留。没有新增 ReSTIR、Neural Cache、integrator，跨实体共享 BLAS 与 specular/refraction motion guide 仍为后续工作。
