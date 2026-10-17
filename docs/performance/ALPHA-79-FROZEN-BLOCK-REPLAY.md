# alpha.79：实际输入与冻结资源的 GPU 工作块回放

2026-10-17。独立诊断版本，无生产性能优化。一个 multi_light 客户端，捕获实际输入/参考输出并复制 camera、assets、geometry、normals、feedback 到独立缓冲区。随后直接调用生产函数，在冻结数据上 GPU 回放；快照同时保存为二进制。客户端正常退出 0。

## 覆盖与正确性

相机 `(123.5,100.62,-2.5)`，524×336、1 SPP，捕获帧 1024。共 1158 条记录，完整 RIS、prepareSurfaceBsdf、sampleBsdf 各 386 条，无截断。RIS 样本 bounce0/1/2/3 为 172/112/98/4，普通 ROUGH_DIFFUSE 365 条、EMISSIVE 21 条；没有金属、透明或体积样本，不外推这些材质的成本。

三个完整函数的 GPU 结果与原渲染调用参考输出均无不匹配：离散字段及 RNG 严格一致，浮点按 `abs(a-b)<=1e-5*max(1,abs(b))` 检查，且要求有限。完整候选准备另行回放 sequence、geometry/PDF、receiver reject、emission，消费其 checksum；它没有逐候选独立 golden，正确性由包含这些工作的完整 RIS 对照提供支持。

## 诊断时间

剔除原计数帧，895 个有效 timestamp 样本；每次 dispatch 实际处理 386 条该类记录，单位 ms。

| 范围 | P50 | P95 |
| --- | ---: | ---: |
| 完整 RIS | 0.068096 | 0.069226 |
| BSDF 准备 | 0.022336 | 0.023104 |
| continuation 采样，含求值 | 0.014496 | 0.015072 |
| 完整候选准备，含序列/几何/PDF/发光读取 | 0.040992 | 0.041760 |

这些是小样本、重复访问冻结数据、独立入口下的耗时，包含回放记录读取和结果校验。各入口编译后的资源占用不同，候选准备属于 RIS 子集；不能相减得到纯 BSDF 时间，不能乘调用频率换算整帧，也不能把四项相加。最大值有约 0.38–0.50ms 尾部波动，原始值保留。

随后完成了独立 Vulkan Compute 执行器：不加载 Minecraft、不创建窗口，直接上传上述冻结资源到 device-local 缓冲区。每项 100 次预热和 100 次测量，保存完整实际结果。源码为 `tools/native/OfflineLightingReplay.java`，入口 `tools/replay_lighting.py --help`；使用 Java 25/LWJGL、capture 目录、四个 replay SPIR-V、classpath 和 native 库目录，输出目录必须为新目录。

独立入口的 BSDF 准备和 sample 对照没有超过 1e-5 的分量；RIS 仅 record859 的 light.pdf 从 17.56693649 变为 17.56715965，归一化差 1.27034e-5，其他分量通过 1e-5，离散字段和 RNG 一致。它未通过原定严格 1e-5 检查，但在用户允许的 1e-2 数值范围内；明确保留失败明细，没有描述成逐位一致。不同入口的浮点代码生成是可能原因，尚未用机器码证明。

独立初次回放的 RIS/prepare/sample/candidate P50 为 0.043008/0.008192/0.004384/0.034816ms；加入逐分量实际输出后的诊断运行分别为 0.043088/0.007424/0.005120/0.034816ms。Compute 和 raygeneration 的分组、入口、校验和缓存条件不同，不把两组时间差视为可直接移植的生产加速。独立结果位于 `outputs/alpha.79/offline`，首次结果保留在 `offline-initial`。

通用入口复验位于 `portable-check-r2`。使用 `tools/validate_lighting_replay.py --capture <capture目录> --schema <schema.json> --output <回放输出目录>` 生成 validation.json；需要 numpy。严格 1e-5 判定保持 false，用户1e-2数值范围判定为 true；这些标记仅说明回放误差，不代表生产优化通过验收。

## 差异与下一实验

Caustica 普通候选是较轻的 Lambert/GGX/Schlick，热光源记录 32B；VoxelLight 保留 FULL、空间发光纹理和环境候选。回放说明完整候选准备值得关注，但没有证明某个 Load 或缓存命中是主要硬件瓶颈。alpha.69–78 的若干局部干预未获得足够净收益，不能将源码差异直接换成节省毫秒。

下一项结构性干预检验前两层 continuation 的中间状态往返：Caustica 在 Indirect 内循环推进，VoxelLight 在阶段间 store/load。仅融合首两层，保留每层有序输出提交、SER、RNG 和八层介质；用正常 A–B–A 检验，不能凭此回放预先认定融合会更快。

诊断来源 `outputs/alpha.79`：schema、capture/*.bin、replay-validation.json、sample-coverage.json、replay-passes.csv；生成脚本 `work/alpha79-build.py`、`alpha79-prepare.py`、`alpha79-analyze.py`。硬件寄存器、spill、cache、occupancy 仍未知。

启动器已修复分类：manifest 标为 diagnosticOnly 的无计数包也明确标为 diagnostic、formalTimingEligible=false，避免把回放客户端的整帧时间混入正式基准。该修改不改变生产 shader 或渲染设置。
