# alpha.24 架构实施记录

**alpha.27 更新：** 当前执行合同已由 [执行层第一轮](../performance/RT-EXECUTION-ROUND-1.md) 覆盖：三类 geometry ranges、64B PathHot、独立 AOV/radiance、共享 scratch、visibility/queue/OMM/SER A/B。以下是 alpha.24 的历史实施基线，单 geometry、144B hot、65536 阈值与 per-AS scratch 描述已被替换。

基线 alpha.23；本次版本 **0.39.0-alpha.24**。设计入口见 [README](README.md)。本页区分实际实现与目标合同：整体设计中的可选 SDK、完整分信号重建和高级采样没有因基础架构改动而自动完成。构建机没有 NVIDIA GPU，驱动同步、运动画质和性能验收待实机完成。

## 实际帧流程

```mermaid
flowchart LR
    A[地形变化 collect] --> C[一次 scene commit]
    B[原生动态模型 collect] --> C
    C --> D[纹理和光源资产更新]
    D --> E[带 jitter 的 primary + guide]
    E --> F[五次 continuation]
    F --> G[spp resolve]
    G --> H[OptiX 或 Vulkan beauty 降噪]
    H --> I[独立输出分辨率 HDR 时域放大]
    I --> J[现有 HDR display 和原生 UI]
```

Reference 使用同一积分器，关闭 guide/降噪/放大并保持原有渐进累积。光源采样和介质模型的原有近似没有改变；实时输出时域滤波本身有偏。队列仅改变存活路径的调度，不改变 RNG、吞吐、PDF、MIS 和 spp 平均规则。

## 本次实现

| 领域 | 代码行为 | 边界 |
|---|---|---|
| Profiling | `.passes.csv` schema 2：真实 frame、scope/parent、尺寸、spp、scene generation；异步 GPU 结果保留提交时元数据 | 父子 scope 重叠，不可相加；CPU 时间为提交工作 |
| 射线计数 | profile on 时记录六个顶点层的有效路径 dispatch、shadow trace、any-hit invocation；每八帧异步读取 header，导出 `.rays.csv` | any-hit 是调用次数；六层计数包含 miss，不是六层成功命中数；不是全帧逐射线日志 |
| Collect/commit | terrain 和 dynamic 先合并，最多一次场景 commit；无变化不构建 TLAS | dynamic CPU 提取仍由原生 renderer 提供 |
| AS 生命周期 | 同大小动态顶点复用；BLAS/TLAS UPDATE 复用原 AS/storage/scratch；构建签名不匹配则 BUILD 新对象 | 当前单 geometry、无索引三角形；场景全部 opaque 才绕过 any-hit，混合 section 尚未拆 range |
| 稳定 shader arena | first-fit 空闲区间分配，以三角形为单位；删除合并空闲区间，其他 section 不搬家 | 64 MiB current arena；alpha.26 先回收全部删除/尺寸变化范围；碎片时异常路径重排属性缓冲并重置历史，常规更新不搬家 |
| 动态实例 | entity UUID、block position、view-model owner + feature 作为身份；位置从局部顶点中分离 | 平移可仅更新 TLAS；旋转/动画仍修改 CPU 捕获顶点并 refit；未共享重复模型 BLAS |
| 动态运动 | 保留上帧动态顶点和 translation；同 topology 的当前 barycentric hit 映射到上帧位置 | topology 由 UV/tint/material flags 合同校验，不是任意 renderer 的显式顶点身份；粒子保守拒绝历史 |
| 表面身份 | 稳定 arena allocation identity；新建/不兼容 topology 用负号拒绝历史；静态按正身份验证 | identity 用 float 精确整数编码，达到 2^24 时需 reset；还没有完整独立 instance-table ABI |
| 纹理更新 | 追踪原生 Vulkan encoder 的上传、copy、clear、color render attachment；未变的 crop 和地形 albedo 不重传 | 绕过 Minecraft encoder 的第三方底层写入不在此 tracker 合同中；render pass 保守标脏 |
| Emitters | terrain generation 驱动 proposal 重建/上传；动态平移不重建静态 CDF；复用每个 shading point 的最近 flame 搜索 | 空间层级 proposal 和 ReSTIR 尚未接入；最近 flame 选择随 terrain proposal 重建 |
| Path ABI | GPU hot 144 bytes，cold media 288 bytes；无介质路径不读写 cold；SPIR-V reflection 验证布局 | 总容量仍是 432 bytes/path，不宣称显存减少；CPU 输运 fixture 继续使用原 PathState |
| Compaction | 支持 indirect trace 且总路径数 ≥65,536、queue 不超 storage range 时启用；两队列交替，GPU 生成 dispatch 参数 | 65,536 是待测的初始阈值；小 workload/不支持设备保持 fixed batch；没有 shadow queue/材质分桶 |
| 队列合同 | 每条存活路径最多 append 一次，容量等于 width×height×spp；保存原 path index；零路径 dispatch 1 后立即退出 | 除 header 计数外不回读 queue；需要驱动验证写入→indirect read barrier |
| View model | primary mask 255；continuation 与 shadow mask 254 排除 view model；held light 独立 | 未单独采用 HUD 投影 pass；保留原 FOV 补偿；glint 和 hurt/bob 的全部 parity 待完善 |
| Guide | 同一个 jittered primary hit 写 albedo、normal/roughness、world position/ray distance、前一帧位置/identity | 消除额外中心 visibility trace；还不是正式 viewZ/hit-distance/specular motion ABI |
| 输出放大 | 降噪后独立 RGBA32F 双 history；九邻域 guide filter、表面/天空重投影、身份与 disocclusion 校验、最多 16 帧历史；锐利表面拒绝 history | 低分辨率 guide 无法恢复未采到的细线；未提供输出分辨率可见性 guide或独立反射/透射运动 |
| 描述符 | 持久 pool/set ring；提交后 fenced task 释放 busy slot，无每帧 pool 创建/销毁 | 最多 32 in-flight slots，超限安全失败回退 |
| 内存观测 | 记录 VulkanRtBuffer 实际 requirements bytes、retiring bytes、累计 allocations | 不含 Minecraft textures、OptiX helper、CPU 页缓存；不是统一全局显存预算 |

## 内存与设备合同

Material 路径要求 `maxStorageBufferRange ≥128 MiB`、至少 14 storage-buffer bindings。current + previous 属性 arena 合计 128 MiB；normal 前缀加 1024 个实例的双 translation/identity 记录。地形沿用最多 512 resident sections，场景总实例不超过 1024。入场前限制总实例数量，避免写越界。所有 buffer 仍由 Minecraft submission-index destruction queue 延迟销毁。

Continuation 容量按 432×width×height×spp 保留原 1 GiB 上限；cold 单 buffer 受 storage range 约束。compact queue 增加 32 bytes/path + header。七个 guide plane 为 112 bytes/internal pixel；新增输出 history 为 **32 bytes/output pixel**（1080p 约 63.3 MiB）。这些都不等于全局显存预算，不能用 64 MiB scene working set 推断全部占用。

上帧动态属性复制→current 覆写→BLAS/TLAS 更新→trace、queue 写→indirect 参数读取均使用同一原生 graphics queue 的显式 memory dependency。Descriptor 在 trace command buffer execute 后挂 fenced task，防止 GPU 仍使用时改写。

## 仍未实现的设计项

- render-origin rebasing、大坐标完整精度合同；loaded/stale/unloaded/out-of-coverage 显式逐命中分类；统一 budget manager 和完整 FrameStats。
- 完整 mesh/instance/material 表、共享 rigid mesh、旋转纯实例更新、拆分 opaque/cutout ranges、动态 LabPBR、透明/加法粒子与 glint。
- 分 diffuse/specular/transmission 的重建历史、demodulation/hit distance、反射/透射运动、NRD 与 DLSS RR adapter、独立 shadow queue。
- ReSTIR DI、空间层级 emitter proposal、远景代理/radiance cache、OMM/SER、焦散及完整 free-flight 体积积分。

这些功能保持设计中的独立能力门控；本版本不暴露未实现的设置，也不把 SDK 支持枚举当作运行能力。

## 验证与实机验收

`./gradlew build clientKit --offline` 验证 Java 回归、真实 Minecraft GLSL pipeline 链接、14 个 Vulkan RT stage 和 hot/cold/camera ABI、实际 Slang/GLSL CPU target 的 BSDF/环境/光源/运动 helper 数值合同。CPU 校验不能验证原地 UPDATE 的驱动执行、GPU 队列并发和 temporal upscaler 画质。

实机用相同窗口、资源包、世界/视角与 1 spp：分别记录静止、实体平移/动画、手持切换、挖放方块、动画纹理；开 `profile on` 后导出。保持同一实际 internal resolution 才与 alpha.23 比较；另测较高内部分辨率、多 spp 触发 compact，用 stats 的 scheduling 确认路径。比较 `rt_reconstruction optix` / `vulkan`，观察动态拖影、遮挡露出、天空旋转、细线及玻璃反射。检查 geometry/texture copy、AS refit/build、retiring bytes，以及 `.rays.csv` 的活跃率；不要由 CPU build 成功宣称 GPU 提速。

## alpha.26 allocator 修正

本帧最终布局先检查总三角形容量，再统一释放删除与尺寸变化的旧范围，最后分配新范围。若总量在预算内但无足够连续空间，清空 allocator 并按最终 section 顺序重排；所有 packed version 标为需重传，保留 surviving identity，清空 previous pose、增加 terrain generation。后续同帧 TLAS UPDATE/BUILD 使用新 custom index；emitter proposal 和实时/reference 历史随 generation 更新。重排不会搬 BLAS 顶点 buffer，仅搬 shader 属性及 normal 索引。stats 的 geometryCompactions 记录这一异常恢复路径。
