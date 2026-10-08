# alpha.42 实机验收与 alpha.43 热点削减

来源：rt-suite-1791389072669.zip（SHA-256 `6cdbc1072f6323583701d9a54741c192d099af57dfd1914703446c8627be3575`），24 段全部有效，RTX 4060 Laptop，1 spp，427×240 → 854×480，DLSS RR，固定 298 地形 sections。所有 frame A/B 强制 FULL/FIXED、OMM/SER off，不能用于证明 Cache/Sparse 的效果。VSync=false、frameLimit=260、throttleReason=NONE。

| 对照 | baseline | candidate | 判断 |
|---|---:|---:|---|
| 世界 GPU / Composite → Exclusive | 22.093 ms | 21.659 ms | 约 -1.96%，低于验收最小收益门槛 |
| transport / Exact → Fast | 19.714 ms | 15.178 ms | -23.01%，两轮一致；透明阴影近似，非所有场景默认 |
| transport / Wavefront → Iterative | 15.186 ms | 20.160 ms | +32.76%，保留 Wavefront |

FAST/Wavefront 的 B1/B2 约 5.68/5.94 ms，RR 约0.78 ms，GPU scene commit约0.255 ms。CPU scene commit约4.28 ms、动态捕获约1.22 ms；CPU scopes 存在嵌套，不能相加。早前 alpha.41 的 OptiX/214×120 数据不能当成同分辨率 baseline。

## alpha.43 修改

- 非发光 surface 不再查询 emissive-triangle MIS PDF；发光 surface 只查询一次，保留原 MIS 与训练去重语义。
- RIS emitter proposal 使用 emission-only 材质入口：只读 UV/tint/flags/颜色/发光 atlas，不解码 BSDF、wetness、normal map 或 tangent frame。动态模型依然无发光，原生 flame-body 排除与 LabPBR 规则一致。
- 容量足够的动态更新不构造整张 resident size map，也不遍历、按距离排序淘汰候选。内存不足时保留原完整淘汰规则。
- TLAS 的 terrain/dynamic 稳定排序改为线性分组；opaque TLAS 复用同一份列表，保持 instance 排列与 identity 对齐。
- FAST 保留手动选项，EXACT 默认和 Reference 保持；不因一个场景获益而推定全设备或全场景赢家。Iterative 继续仅 A/B。

这些是减少重复工作，不是 Primary split、B0 cache 或新短路径算法；不宣称已完成 Cache 2.0。CPU/SPIR-V 检查不能证明 RTX 提速。

## 客户端验收

固定窗口、视角与场景；使用 DLSS。执行 `rt_benchmark frame` 验证 FAST 与 Wavefront 的场景选择，再执行 `rt_benchmark realtime` 单独验证缓存策略。自动测试恢复原设置；本场景日常可使用 `rt_shadow fast`、`rt_integrator wavefront`。跨版本比较须相同输入输出尺寸与材质/光照/地形，不能只比较聊天中的 FPS。

重点：B1/B2、transport、CPU scene commit、world GPU/P95、RR；检查 glass/water 阴影，FAST 有画质取舍。新增 emission-only 与完整材质的数值对照，以及容量充足时禁止扫描驻留表的回归。

## 本地验证

`./gradlew build clientKit`：350 项 Java 回归零失败；80 个 Vulkan RT SPIR-V stages 与 descriptor/continuation ABI 校验通过；32 个实际 Slang 材质样例的 emission-only 与完整解码结果一致；57,600 个 BSDF 样例、生产 RIS/MIS、透明 visibility、缓存策略与 DLSS guides 数值回归通过。本地无 RTX 实机，不能据此给出新版 FPS 或提速百分比。
