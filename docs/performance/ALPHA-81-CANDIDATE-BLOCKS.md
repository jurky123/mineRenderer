# alpha.81–85：完整候选与 FULL BSDF 的离线定位

2026-10-17。生产基线仍为用户验证的 alpha.73 画质代码和 alpha.67 shader。没有重启 Caustica。新增的是可复用诊断工具和一个有效游戏客户端；没有新增通过既定门槛的生产性能改动。

## 输入与验证

复用 alpha.79 保存的 multi_light 实际资源和 386 个完整 RIS 调用。用生产函数展开全部提议，得到 2217 个候选：1754 个面积光、458 个环境候选、5 个无效候选；1817 个通过接收面筛选，其中面积光 1577、环境 240。383 个父调用产生候选，另三个在 survival 中结束。通过筛选的材质为 ROUGH_DIFFUSE 1774、EMISSIVE 43，没有金属、玻璃、水或体积覆盖。

保存完整 RuntimeCandidate、发光结果、BSDF f/PDF、MIS 后 integrand、RNG、bucket，以及 alias 选择后的精确状态。固定输入重复到 16384 个 slot；每项写回实际组件，校验与计时使用相同结果存储，不能被当作无输出的空计算消除。完整 RIS 回放按这些 slot 的父调用重复执行，父调用权重受原候选数影响，不是均匀的 386 个表面或生产像素分组。

独立几何、发光、FULL BSDF、integrand、采样序列均与生成参考逐位一致；离散结果和 RNG 零不匹配。完整 RIS 对原捕获 golden 的最大归一化浮点差为 `1.27034e-5`，完整候选循环 checksum 为 `1.09704e-4`，都未通过严格 `1e-5`，但低于用户认可的 `1e-2`。这些是新入口与原 raygeneration 捕获之间的差异；本轮 A/B 候选之间完整 RIS 输出逐位一致。没有将原 golden 差异隐瞒为严格通过。

## 分拆回放

首个完整分拆各 300 次预热和 300 次测量；GPU P50/P95，单位 ms/dispatch。active 为实际进入该工作块的 slot 数。

| 工作块 | active | P50 | P95 |
| --- | ---: | ---: | ---: |
| 候选几何/PDF，复用已生成 sequence | 16384 | 0.024576 | 0.025600 |
| 候选几何/PDF，含原 sequence | 16384 | 0.025600 | 0.025634 |
| 发光读取 | 13388 | 0.015360 | 0.016384 |
| FULL BSDF 求值，消费 f 与 PDF | 13388 | 0.010544 | 0.011264 |
| FULL integrand，含 MIS | 13388 | 0.012288 | 0.013312 |
| 输入/输出控制 | 13388 | 0.007168 | 0.007392 |
| 环境几何/PDF，复用 sequence | 3383 | 0.008192 | 0.008386 |
| 发光物几何/PDF，复用 sequence | 13001 | 0.018432 | 0.018720 |
| 面积发光纹理路径 | 11598 | 0.014336 | 0.015360 |
| 完整候选循环 | 16384 父调用重复 | 0.046080 | 0.047104 |
| 完整 RIS | 16384 父调用重复 | 0.082944 | 0.085162 |

这些项目包含不同记录读取和输出，线程掩码、代码生成及缓存不同；不能相减得到纯算术、相加得到帧时间，或按调用次数换算整帧。候选和 RIS 是包含关系。时间约有 1024ns 量化，细小分量的差异尤其不能过度解读。

## 在完整 RIS 内做单项替代

第二组干预保持完整 RIS 的 reservoir/MIS/输出，分别从冻结表取回某个工作块的结果；只用于诊断，绝不作为生产实现。各 2000 次预热/测量，所有替代对未替代完整 RIS 的 16 个输出组件及 RNG/选择状态逐位一致。

| 完整 RIS 中的诊断替代 | P50 ms | P95 ms |
| --- | ---: | ---: |
| 原实现，前端控制 | 0.078848 | 0.081920 |
| 全部候选几何/PDF/sequence 用参考值 | 0.049152 | 0.050434 |
| 仅 BSDF eval 用参考值 | 0.075776 | 0.080896 |
| 同时替代几何与 BSDF | 0.044144 | 0.046080 |
| 整个候选准备含 emission 用参考值 | 0.039936 | 0.040960 |
| 仅发光物几何用参考值，环境仍正常计算 | 0.070720 | 0.075776 |
| 仅环境几何用参考值，发光物仍正常计算 | 0.063488 | 0.066560 |
| 仅 emitter alias 选择用参考值，几何继续计算 | 0.073728 | 0.078850 |
| 保留 alias 选择，替代 emitter 几何算术 | 0.080096 | 0.084992 |
| 原实现，后端控制 | 0.078848 | 0.093440 |

alias 保留项通过实际选中 record、PMF、选择后 seed 与参考值的检查消费输出，避免整个选择链被编译器删除。后控制 P95 有尾部变化；不把小幅差异或诊断替代的时间差当成严格成本上界。代替工作也会改变编译资源、依赖链和调度。

证据支持的排序是：完整候选准备值得优先调查，环境分支尤其值得干预；不能把剩余差距笼统归因于 FULL BSDF 或只归因于 alias→node。也没有证明 emitter 的距离/法线数学本身是主因。环境候选虽只约占提议的 20%，它的两级 CDF 各有串行二分读取；实际 PT 中是否进一步受 wave 分歧影响，仍缺生产 lane/wave 数据，不能由这组重新分组的回放证明。

## 与 Caustica 的具体区别

固定对照提交 `14770591086232e13a6b1d5320b0f2f8ef8731e3`。检查 `lighting.slang::risInitial/evalSampleContrib`、`math.slang` 和 `world_common.slang::Light`：

- Caustica emitter RIS 没有同口径的环境 CDF 候选，天空由 continuation miss 处理。其 local/global 提议以候选索引安排；VL 的随机环境/发光物类别与之不同。
- Caustica 32B 光源包含打包辐射颜色和矩形几何；VL 64B 三角记录后，还按采样位置读取 UV、材质贴图、albedo/cutout。平均颜色不能替代 VL 的空间发光纹理。
- 普通材质 target 使用 Lambert、各向同性 GGX、Schlick Fresnel；VL FULL 还有精确 Fresnel、各向异性、粗糙漫反射、多重散射、BSDF PDF 和 MIS。

生成器从本地 Caustica 源码抽取原函数，只替换 profileCount 为空诊断函数、补齐其原 math helpers。在相同面积候选上消费贡献/phat，约 0.009216ms。但法线单面规则、输入物理意义、输出和加载量仍与 FULL eval 不同；此数值不是 Caustica 完整 RIS，也不能据此宣称两边等工作量或只差某个 BSDF 系数。

## 有证据后的候选与处置

1. **alpha.82，共享反射 D。** SPIR-V 确认同一 h/ax/ay 的分布项在 PDF 与 BRDF 的两个分支各计算一次，编译器在 Slang 输出层没有消除。仅显式共享 D，文件缩小 408B。完整 RIS A–B–A 为 0.082944/0.082944/0.077824ms，FULL eval 0.010240/0.010240/0.010272ms；没有可确认收益，不进游戏、不进生产。不能把 Slang 指令减少等同于驱动执行收益。
2. **alpha.83，完整发光热记录。** 保留原 64B、附加逐位相同的 float32 UV/tint/flags，形成 96B 记录，当前 330 个光源多 31680B，缩短 record→triangle→geometry 属性读取链。相同扩展资源分别跑旧/新 Shader；完整候选循环均 0.043008ms，完整 RIS 0.082944/0.082944/0.077984ms。没有净收益，不进游戏、不实现生产发布器。冻结温缓存下失败不能外推为真实场景的带宽或 cache 命中结论。
3. **alpha.84/85，精确 CDF 搜索索引。** 基于完整 RIS 的环境干预证据，不再做单个 texel 复用或预读两个二分层。256 个粗分桶提供保守 lo/hi，仍用原 `prefix<=value` 二分，并以端点检查回退，保持支持域。packed 离线前导之后，alpha.85 用生产可生成的 RGBA32F 格式；离线完整 RIS A–B–A 0.077920/0.075008/0.078848ms，输出逐位一致。新增同帧 GPU 索引构建和复制后，仅运行一次游戏 A–B–A；PT 改善 2.13%，低于 3% 保留门槛，仍不纳入生产。见 [完整结果](ALPHA-85-CDF-GUIDE.md)。

## 工具与复现

所有新工具都只操作隔离数据，默认不创建窗口。依次使用：

1. `tools/build_lighting_blocks.py --rt-source shaders/rt --schema <capture-schema> --caustica <local-checkout> --slang <compiler> --spirv-val <validator> --output <new-directory>`，复制并记录源码身份，生成 18 个分拆入口和一个生成入口。
2. `tools/prepare_lighting_candidates.py --capture <alpha79-capture> --output <new-input>`，为生成阶段扩展反馈缓冲区。
3. `tools/replay_lighting.py` 指向生成入口，`--stages 1 --groups 32 --trials 1 --table-bytes 4194304`；其余 Java/classpath/natives 参数与 alpha.79 相同。
4. 再运行 prepare，传入生成输出的 `--generated-table <stage-0-table.bin>` 和新的输出目录，形成 16384-slot 重复输入。
5. replay 指向 bench，`--stages 18 --groups 256 --trials 100 --table-bytes 4194304`；用 `tools/validate_lighting_blocks.py --inputs <dense-input> --capture <original-capture> --stages <stages.json> --output <replay-result>` 检查实际组件。

便携生成器重新编译的生产函数输出与实验生成表逐位一致；便携 18-stage 执行和校验已实际运行。旧四阶段入口保持默认参数。结果文件保存在工作区 `outputs/alpha.81` 至 `outputs/alpha.85`，包括 raw tables、SPIR-V、patch、资源/源码身份、遥测、原始套件和撤回理由。缺少 capture 的源码 checkout 不能凭文档再生真实输入，必须提供保存的捕获数据。

## 当前边界

没有取得 Nsight 寄存器/spill/cache/occupancy 权限，也没有把离线结果推广为整个五场景的硬件根因。只启动一个有效 multi_light 客户端，正常退出 0；两个后测捕获 26 个浮点层有限，这是健全性检查，不是转动恢复或噪声等价的最终验收。没有候选进入生产，因此不追加全场景/移动画质测试或完整发布构建。

61 个默认 manifest 源码（含测试/支持源码）、293 个生产 class、110 个默认阶段均与本轮开始一致。安装包 SHA-256 仍为 `43fc01de70d255021ea1246b86b11a00e714447247c5fc1f84baffc57e6dbdff`。本轮没有安装、提交或推送。

## 后续 alpha.86

以上是 alpha.81–85 的历史处置。后续 [alpha.86](ALPHA-86-RNG-CDF-GUIDE.md) 根据行/列拆分证据改为原 RNG 高 8 位精确索引，以单调性保证替代端点检查。离线完整 RIS 改善10.23%，一次实际 A–B–A 的 PT 改善3.89%，通过门槛后保留生产源码及完整110-stage bank。匹配场景的移动/恢复捕获及8项模块检查完成；安装alpha.73仍不变。原共享D、96B热记录及alpha.85未通过的结论不变，没有把历史失败候选叠加进本次生产。
