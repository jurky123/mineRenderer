> alpha.45 已实现默认 ROUGH_DIFFUSE 的 lobe-aware diffuse cache、精确镜面 continuation、diffuse history 和 bounded indirect training；以下为 alpha.44 的历史设计记录。当前实现与限制见 [Material-Aware Cache 2.1](MATERIAL-AWARE-CACHE-21.md)。

# alpha.44：Material/Light 热路径与 Primary Split / Cache 2.0

本轮落实 alpha.43 评审的两项主线，保留 section BLAS、Wavefront、Light Runtime/RIS、CLEAN 变体与 Vulkan DLSS RR。默认仍 FULL / MONOLITHIC / TAIL / OWEN / EXACT / Wavefront；新算法先用专项 A/B 和日常帧时间验收。没有新增 integrator，也没有将 FAST 宣称为 EXACT 的无损替代。

## 已实现的执行链

```
Primary visibility
  → 32 B HitRecord（distance / barycentric / triangle / instance / active）
  → 仅干燥、平面、非发光 pure diffuse 地形的轻量分类和 history 检查
      → 已验证 history：更新输出/guide，路径终止
      → active shading queue：使用首命中记录，不再追踪首命中
          → 精确 Material 3 或已验证的 pure diffuse guide
          → 精确直接光 + 可选 B0 diffuse irradiance cache
          → Wavefront B1…B5
  → 最多 1024 条 compact training requests
  → 独立 cache train dispatch
  → resolve / reconstruction
```

`rt_primary split` 只在 realtime 生效；Reference 保持 MONOLITHIC/FULL。支持间接 tracing 的设备使用 GPU 计数驱动活跃队列，不支持时固定 dispatch 并检查 HitRecord 的 active 标记。visibility 自身也分为 CLEAN FULL / Realtime 变体。visibility→shade、各 bounce、train→resolve 的读写与 indirect 参数均有 Vulkan barrier。冷路径失败沿用已有重建/原版回退。

历史检查已提前到完整材质解码与光照之前，但生成正确 RR guide 仍需 UV 法线框架。受保护的金属、coat、rough specular、玻璃、水、SSS、发光、雨湿、非平面和动态模型走完整 Material 3。此版没有把任意材质强行简化为 diffuse。

`rt_cache primary` 配合 `rt_realtime cache` 或 `cache_sparse` 开启 B0 查询/训练；`tail` 保持仅 B1/B2。所有 cache 入口仅接受 pure diffuse、平面法线、无介质、无 coat/transmission/emission 的路径；ROUGH_DIFFUSE 现在也不以 diffuse cache 代替间接镜面。直接光继续精确求值；缓存替代的是 diffuse 间接入射辐射。现有平面键、四路槽、SH 系数、成熟度/均值误差/TTL/epoch 与完整 suffix 训练继续使用。

缓存仍为低阶 SH 空间近似，不是 FULL 的无偏或逐像素等价结果；同画质约束下的阴影边界、漏光、运动、遮挡和动态光回归必须实机检查。未成熟、未匹配和训练容量不足均保留完整路径。Primary 后的分类并非取消所有完整 shading：有效 cache 命中才能省掉后续路径。

## 紧凑状态与内存

每条路径的 policy metadata 从 8 个 uint4 减为 3 个；32 B HitRecord 和两套 continuation 队列保留独立范围。训练 payload 改为最多 1024×96 B，包含 cache 地址、path id、prefix、throughput、方向/PDF、位置和法线；训练 dispatch 只访问已预订请求。没有把每像素的 64 B hot continuation、288 B 嵌套介质或全部 history 重写。

Realtime policy 的旧变量部分为 `160×capacity + 256×pixels`；新变量部分为 `112×capacity + 256×pixels`，另增固定 98,304 B 请求。427×240、1 spp 时净减少 **4,820,736 B（约 4.60 MiB）**。FULL/MONOLITHIC 不分配 HitRecord 或 policy/cache 状态；FULL/SPLIT 额外分配 32 B/path。设备 storage range 和 1 GiB 总预算已按新布局计算。

## Material / Light 热路径

- `terrainShadow()` 读取透明阴影实际需要的 type、RGB absorption、IOR/transmission/thickness、geometry normal 与 medium identity，不构造完整 Material 3 或 normal-map/tangent frame。cutout 仍用原 alpha 判定；opaque 原样终止；FAST 的近似透明厚度规则不变。
- local alias 返回被选中 section 的真实 PMF，直接形成 `0.2×global + 0.8×local`。global proposal 和 BSDF-hit PDF 保留局部支持查询，不删混合分布的概率项。
- `rt_sampling shift` 用 bit reverse + digital shift 替代每候选 24 次 Owen hash；`owen` 保持默认。两者使用相同候选预算、随机流接口、roulette 和 MIS。
- 实际 Slang CPU fixture 同时测试两种序列的能量、4096-bin 分层和 sampled/hit PDF，逐场景/深度导出 RGB 方差，不能只根据少 hash 宣称提速或质量等价。200,000 samples/case 的 surface 测试中，SHIFT 相对 OWEN 的 primary RGB 方差变化：points `−3.63%/+0.75%/+8.12%`，mixed `+13.92%/+0.49%/+4.88%`；结果有改善也有退化，支持保留 Owen 默认。

## CPU 场景与世界维护

动态模型按 owner/feature/hand 缓存上一帧原始 quad 和转换后的三角形/hash。比较 owner-local position、tint、UV 和 crop/slot 后，一致时复用三角形数组/hash；镜头或对象平移只改变 scene pose。`rtModels()` 同帧只构造一次列表。stats 增加 `rtDynamicReusedMeshes` / `rtDynamicRepackedMeshes` / `rtDynamicRetainedArrayBytes`，缓存随离场和 reset 释放。

这减少 quad→triangle、数组构造和完整顶点 hash；原生模型捕获与相关字段比较仍在 CPU，变形/旋转或拓扑变化仍重新转换。尚未实现跨实体共享 rigid BLAS、GPU skinning 或 GPU deformation buffer，不将本轮缓存称为它们的完整替代。

世界维护保留原顺序与调用，新增 CPU scope：`rt_world_reposition`、`rt_world_compile`、`rt_world_upload`（包括锁）、`rt_world_occlusion`、`rt_world_callback`。总维护 scope 与子项嵌套，不能直接相加。未激进删除 native section 更新，也未声称已经排除高速移动/加载/编辑的一帧生命周期风险。

## 两种验收入口

先替换旧 jar，保持相同世界、设备、窗口/输出尺寸、光照与 RR：

```
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction dlss
/voxellight rt_spp 1
/voxellight rt_benchmark material
```

`material [4..30]` 默认 12 秒采样，24 段（3 组、每组双轮 ABBA）：

1. FULL 下 MONOLITHIC / SPLIT：检查首命中拆分本身的成本。
2. SPLIT/CACHE_SPARSE 下 TAIL / PRIMARY：检查 B0 cache 的增量收益与查询覆盖。
3. FULL 下 OWEN / SHIFT：检查轻量采样；方差与图像质量另测，不能根据 timing 胜负直接启用。

控制测试固定 renderer 输入与地形快照；OMM/SER off、FIXED、Wavefront、EXACT。结束/中断/异常恢复全部新旧执行控制。

日常配置门禁另运行：

```
/voxellight rt_primary split
/voxellight rt_cache primary
/voxellight rt_realtime cache_sparse
/voxellight rt_sampling owen
/voxellight rt_benchmark production
```

`production [4..30]` 默认每段 20 秒、4 个连续 baseline 段，保留用户全部执行配置（包括 AUTO、OMM/SER），地形与 sun/sky/weather/held/water 输入保持 live。保持镜头不动、窗口前台、关闭菜单；不强制改变 FPS limiter/VSync。配置实际未生效、分辨率改变、查询覆盖不足或窗口失焦会明确记录/中断。它是日常配置的固定视角门禁，不是自动行走负载。

每段导出：客户端帧间隔 P50/P95（含呈现/限帧等待）、CPU submit/scene commit/dynamic capture/世界维护、GPU world/transport/B1/B2/RR、每秒资源状态与内存字段、实际与请求配置。CPU wall time 与 GPU timestamp 分开；诊断 counter 帧及其后一帧从帧间隔统计中排除。JSON schema 13；production 不对相同配置伪造 A/B winner。

每次 ZIP 仍在 `benchmark-results/voxellight/rt-suite-*.zip`；每段 production JSON 包含在 ZIP 中，同时追加到 `benchmark-results/voxellight/production-history.jsonl`，可跨版本保存。保留本版及后续版本相同日常配置下的 baseline，再对比 P50/P95、总帧时间和显存；alpha.43 的已有 FULL/FIXED frame 报告作为受控基线，不直接与 production 混比，不能只看 Ray 数。

## 本地验证与尚需实机完成

构建入口：`./gradlew build clientKit`。验证真实 Slang/native 数值 parity、shadow/primary 快速解码、known-hit transport/RNG/continuation、B0 保护与紧凑请求溢出、两套采样的能量/方差、Java 动态复用/内存布局/配置恢复，以及所有打包 SPIR-V/源码哈希。

本机没有 RTX；Nsight 的 B1/B2 register/ALU/cache miss/any-hit 指标、真实世界 P50/P95 与同画质验收尚未完成，没有给本版宣称 GPU 提速百分比。DLSS specular motion、折射后 endpoint 与完整 BSDF lobe guide 仍待后续正确性工作。保留 Legacy/RUNTIME 回归入口；Reference 保留完整路径与精确透明阴影。
