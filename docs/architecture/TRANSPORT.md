# 材质、路径追踪、光源与介质

[总设计](README.md)。以 `shaders/rt/common` 的实际积分代码为实现主体，`native/rt` 和 Slang CPU fixture 继续校验。下列新增算法为提案，避免在设计阶段把未验证近似称为物理正确。

## 1. 积分合同与模式

统一线性 RGB scene radiance，世界距离以 block 为单位，介质系数以 block⁻¹ 为单位。颜色解码只发生在材质/纹理入口，曝光与显示变换只发生在输出。原生 block/sky light level 可用于发光源识别、wetness/sky-access 或 fallback；不能把 lightmap 已照明颜色当 baseColor 或在 PT 结果上再乘一次。

现有相机首个 surface 加五次 continuation，共最多六个 surface 顶点；已在第三次及后续 continuation 用 Russian roulette 并以 survival 补偿。迁移初期保留这个精确语义作为 A/B 基线。之后将 `maxSurfaceVertices`、`maxMediumEvents`、`maxAlphaCrossings`、`maxVisibilityInterfaces` 分开配置和统计，不能把所有相交都称一次 bounce。

Reference 初期保留六顶点以核对现有实现；高质量 reference 后续可配置更高顶点限额并报告截断率，仍在 GPU 预算内分批推进。有限深度会截断能量，不能称无偏无限路径真值。Realtime/reference 共用 eval/sample/PDF/MIS，只通过显式 mode 控制重建、缓存和近似。

| 项目 | Realtime | Reference |
|---|---|---|
| 材质/BSDF/PDF/基础 NEE | 共用 | 共用 |
| RR 终止 | survival 补偿 | 相同补偿，可较深路径 |
| 动态时间 | live | 一次 snapshot；freeze 显式 |
| ReSTIR/缓存/贡献限幅 | 独立实验开关与 biased 标记 | 默认关闭 |
| 去噪/放大 | 正式 signal ABI | 无；输出 HDR mean |
| 缺页/NaN | mask、请求、拒绝低置信历史 | 不接受无效样本，计数并标记未完整 |

## 2. 材质合同

延续 Material 3 的优先级：LabPBR → 显式 override → curated vanilla → family → generic。资源包标准入口参考 [Iris PBR 标准说明](https://shaders.properties/current/how-to/pbr_standards/)；具体通道沿用现有 `Material3` / `PbrMaterials` 解析与测试，本次不重新发明 `_n/_s` 编码。

逻辑 `MaterialRecord`：linear baseColor、emission、microfacet alpha、IOR/F0/conductor 参数、coat weight/IOR/alpha、normal/height map references、opacity/cutout threshold、thin/solid type、sigmaA/sigmaS/phaseG、materialVersion、textureVersion。感知 roughness 与 alpha 分开命名，**alpha = perceptualRoughness²** 的转换只做一次；后端要求 linear roughness 时明确转换，不能重复平方。

| 材质类别 | BSDF/几何语义 | 相交类别 |
|---|---|---|
| stone/wood/cloth 等 | diffuse/rough dielectric，coating 按 Material 3 | OPAQUE |
| metal | conductor GGX，参数由现有材质编码解析 | OPAQUE |
| leaves/grass | 二面薄面散射，alpha coverage；不是水体 | CUTOUT |
| 火焰/发光贴图 | emission + 明确的表面散射/opacity | CUTOUT/EMISSIVE |
| glass pane / 薄 sheet | thin dielectric，无实体介质厚度栈 | TRANSMISSIVE |
| solid glass / ice | dielectric 界面 + 有限厚度介质 | TRANSMISSIVE |
| water | IOR + 吸收/散射/相函数 + fluid boundary | TRANSMISSIVE |
| skin/entity/item | native UV/tint 起步，再补动态 PBR 来源 | 按真实 render type 分类 |

实体未获得 PBR 时保留 generic dielectric，不能套 block `_s` 或靠纹理名字猜 metal。共享纹理需要 blockstate 独立材质时使用 primitive/material binding，解决当前单 texture 不能区分不同 block 的限制。

### BSDF 数学不变量

Eval、sample、PDF 同一 lobe mixture：`pBSDF = Σ pLobe * pLobeDirection`；sample throughput 为 `f * |n·wi| / pBSDF`，delta 的离散权重与连续 PDF 分开。Coat/substrate 权重、GGX 多散射、conductor 能量仍以现有 furnace、reciprocity、sample/eval fixture 验证；后续 rough diffuse/EON 属于可选模型，不能只增 eval 而不改匹配 sampling/PDF。

几何法线决定 front/back、介质进出和 ray offset，shading normal 决定 BSDF；保留 hemisphere 校验与能量安全。法线贴图/水波不能改变真实拓扑或使射线进入不存在的界面。offset 根据位置误差/ULP/尺度计算；连接终点距离扣除实际 origin shift，不能使用固定超大 bias 穿过一格薄墙。

雨膜使用原生 sky-access/biome/weather + orientation/porosity，材质湿度改变提升 material/reactive version，不更新顶点/AS；下雨不能使洞穴内所有表面同时成镜子。画面外观控制限于材质/光源/显示，不能靠增加无来源 ambient 修补 GI 漏光。

## 3. 相交类别与可见性

当前 material geometry 全部 `flags(0)`。目标在 section/model BLAS 内划分 OPAQUE、CUTOUT、TRANSMISSIVE geometry ranges；按类别设置 geometry flags 和 SBT/ray type，避免普通石头触发 alpha any-hit。分类必须对当前资源包保守，混合 opacity 的 primitive 不能强设 opaque。

Primary/continuation：closest hit 返回紧凑 surface payload（t、instanceSlot、geometryIndex、primitiveId、bary、front-face/class）；着色读取 instance→geometry/material table。CUTOUT any-hit 只做 alpha 与 ignore/accept，不做完整 BSDF；透明 surface 被接受交给积分器，不等同 cutout discard。

Visibility 分为两条：

1. Binary visibility：在正确处理 cutout 后，命中确定不透明 blocker 就结束；避免完整介质/材质 eval。
2. RGB visibility：对 thin attenuation、介质 Beer、透明界面执行当前明确近似，必要时继续 trace。记录 interface cap、early out、透射贡献与超限次数。

不能在 any-hit 中无序累积多个透明面的 RGB 衰减，因为 traversal 的候选次序不保证沿 ray 距离排序。复杂透射使用 nearest-hit 的有序推进或其他经证明的合同。最终 opaque blocker 可 early terminate；不能为了 fast shadow 忽略前面的 cutout。

**折射边界**：沿直线连接灯光并穿过 solid refractive interface，不等价于满足 Snell 定律的真实弯曲连接。现有 RGB visibility 是透射 shadow 的实时近似，必须标记；reference 严格模式遇到需要弯曲连接的 solid dielectric 阻断这条直连，由 BSDF 路径承担真实传播。thin sheet 可使用明确的 sheet transmission；需要低方差折射焦散时另加专门估计器。

24 层 interface 上限迁移时保留但加统计；超限保守终止并标 truncated，不返回无条件白色。cutout 连续 alpha rejection 与物理 bounce 分开计数，防止密集树叶轻易耗尽全部 surface bounce。

## 4. 光源表示、增量更新与能量归属

现有 sun/environment、独立 held、最近两火焰 + 余项 RIS、emissive triangle 连接保留作数值基线。优化目标是消除每 vertex 重复线性近灯搜索及无贡献 shadow，不通过暗化灯光换成本。

```text
LightRecord:
  stable lightSlot + generation, type, sourceGeometry/primitive
  current/previous transform, radiance or point intensity
  area/bounds/direction, material/texture/emission version
  selectionPower, region/cell binding, committedSceneVersion
```

太阳/月亮保持有限 cone 与环境 map 分离；map 不包含同一太阳盘能量。环境用 luminance×exact solid angle 分布，cell 内 cos(theta) 均匀采样；黑图 fallback 保持合法 sphere PDF。环境 radiance 与采样分布发布同一版本，可分时更新但不得以新 radiance 配无对应 PDF 的旧表冒充一致重要性采样。

发光源分两级：section/model-local emitter list（拓扑或 emission 变动更新）+ world light instance（transform 变动更新）。稳定 emitter key = geometry identity + primitive identity + emission source；动态 pose 改面积/位置时只更新该对象。静态 emitter 不随其他实体动画重新排序/变换/上传。当前最多 8192 admission 的丢弃部分仍可 BSDF hit，但 NEE 无覆盖；目标层级容纳全部已驻留发光 primitive，预算不足显式统计，保持 sampled 与 BSDF-hit 的 PDF 一致。

同一个火把的 flame proxy、emissive quad 和 held proxy 必须有明确 source owner。迁移先保留现有 fixture 和源分类，再审计重复：采用 proxy 的 flame 区域不再另外作为同一物理源加入 sampled emitter，或者定义它们代表不同能量并做预算。最终 camera hit emission 与 NEE 的重复靠 MIS 处理；两个重复代理的能量翻倍不能由普通 MIS 自动修正。

手持：保留独立确定性 delta connection，主/副手源明确；点源 `I / r²`，单位是 scene intensity，不称未经标定的 SI 流明。相机/手持运动更新 position/lightVersion；源不参加同一 stochastic light selection，因此不会被天空能量饿死。visibility、BSDF 和 hemisphere 仍检查，不能无条件照亮屏幕。若未来改成非零面积灯，增加匹配 geometry-hit 与 PDF。

### 分层 proposal

首期对 flame/local emitters 建立 section/cell 空间索引，近邻列表按 frame/region 或 surface query 得到，surface vertex 内只计算一次候选集。对 area emitter 使用 power hierarchy/alias + spatial importance，光源巨大或特殊分布保留 support：任何非零贡献可选源概率都应 >0，不能仅最近灯而丢失其余灯。

条件概率完整：`p(light, point | x) = pCategory * pLightGivenCategory * pPointGivenLight`。面积转立体角：`pω = pA * distance² / |nLight·(-wi)|`；delta 用离散概率。Emission 贴图/alpha 在采样点求值，proxy 与真实 emission 的 proposal 只改变 PDF，不改变实际发光能量。

当前 nearest-two 与 remaining RIS 应严格排除相同源/各自重复项。改 light hierarchy 时一个 commit 同时更新 sampled PDF、BSDF hit PDF 和 exclusions；禁止只改 sampleEmitter 而留下旧 emitterHitPdf。

## 5. NEE/MIS 与 ReSTIR DI

表面直接光目标贡献 `F = throughput * f * |n·wi| * Le * visibility`。环境/面积灯与非 delta BSDF 的竞争用 power heuristic，PDF 在同一 solid-angle measure，含实际 category/light 选择概率。Delta/无法竞争事件权重为 1；末端顶点没有 BSDF continuation 的直接估计保持 unit MIS。若多个技术各采不同集合，按真实 sampling technique 和 sample count 定义权重，不能把独立 held 算进 environment 的 PDF。

### 普通 RIS

候选 proposal `q_i`，非负 target `t_i`（如未遮挡贡献 luminance），`w_i=t_i/q_i`；reservoir 按 `w_i / Σw` 选中样本 y，估计 `(Σw / (M*t(y))) * F(y)`。零 target、零 PDF 与非 finite 单独处理；最终 visibility 在真实 shading point 重新求值。Target 是重要性，不是输出 radiance；不能直接显示 target。候选各来自不同 q 时使用对应合法混合/多技术估计，不随意用统一 q。

### ReSTIR DI 后置实验

先在 primary rough/opaque surface 上运行，保留 baseline NEE 和独立 held。逻辑 reservoir 含 lightSlot/generation、light sample coordinates、weight sum、candidate count/effective M、target at current point、age、scene/light versions、visibility confidence。只为参与该算法的像素分配，不默认每 bounce 都保存 reservoir。

Temporal 通过 object motion 找上帧 surface，验证 material/topology/pose/normal/depth，重算当前 target；Spatial 检查薄墙、surface side、distance，不能仅同 material 就跨墙合并。源删除、slot 重用、发光/alpha 变动拒绝对应 history。搬运旧 reservoir 必须采用算法规定的权重/偏差修正，**上述独立 RIS 公式不等于完整时空 ReSTIR 合并公式**；接入固定版本 RTXDI bridge 或独立验证论文算法。[RTXDI application bridge](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/RtxdiApplicationBridge.md)

灯或 blocker 移动时重算最终 visibility，不无限复用旧 shadow。候选 target 可不含昂贵遮挡，但无可见性复查不能保证正确。记录 reference bias、shadow ray 数/有效贡献、reservoir 接受率、ghosting 和耗时。只有等画质成本优于基线才开启；ReSTIR GI/PT 在 DI 稳定后另立 gate，不能用名称替代 PDF/shift/Jacobian 设计。

## 6. 路径状态与 GPU 调度

现有 432-byte PathState 包括 8 层介质与三类 radiance，且 primary+五 continuation 使用同尺寸网格。保留旧 dispatch 作为 baseline；分阶段演进如下。

### 6.1 热冷拆分

目标 `PathHot` **初始布局 112 bytes，7×16 bytes**：

| offset | 字段 |
|---|---|
| 0 | float4 origin（w=tMin/约定） |
| 16 | float4 direction（w=lastPdf） |
| 32 | float4 throughput（w=etaScale） |
| 48 | float4 radiance（w 不复用 RNG bit） |
| 64 | uint4 pixelIndex/sampleIndex/rngState/flags |
| 80 | uint4 vertexCount/firstLobe/mediumSlot/pathSlot |
| 96 | uint4 previousEvent/hitOrQueueIndex/reserved/reserved |

这个新布局需 ABI 校验，不直接兼容现有 bank。AOV 累计放 per-path `PathSignals`，最后 resolve 按 pixel/sample 合并；八层介质单独 `MediumState`，普通 air path 使用 sentinel，不读取完整栈。冷池容量不足不能静默降为 air：本批转安全完整状态路径或重试分批。

RNG 用 pixel/sample/frame/snapshot seed，queue compaction 和材质排序不改变样本身份；避免依赖 dispatch index 后导致 A/B 样本完全变掉。每个 radiance invalid flag 独立，不把浮点 NaN 或 packed RNG 当贡献。累计仍以足够精度执行，先测试极亮/长路径再考虑 FP16 throughput。

### 6.2 队列演进

```text
primary visibility
 -> active hit/miss
 -> shading（先共用 kernel，再按复杂度分桶）
 -> shadow requests + continuation requests
 -> visibility（binary / RGB）
 -> contribution resolve
 -> active-index compaction
 -> next intersection
 -> sample/signal resolve
```

第一阶段只增加存活计数并拆冷介质；第二阶段分离 shadow，使复杂连接不压在每个 continuation 程序中；第三阶段 compact active continuation；最后有证据再拆 diffuse/metal/dielectric/medium shading queues。小画面可以继续 fixed grid，阈值由实际 GPU 分析确定。保留 `TraceRay` pipeline，ray query 仅在设备支持及测得收益时做替代实验，不默认重写全部 shader。

append 用 subgroup prefix/batched atomic，queue capacity 明确；shadow queue 按本批最大连接数预测，overflow 保留任务分批处理/重跑，不能直接丢光。多 shadow task 写同 path 时使用独立 contribution slot + reduce，避免浮点非原子写竞争；continuation 只在对应 direct contributions 正确归并后推进，保证随机/能量一致。

GPU 生成 active count 和 indirect dispatch args，不经每 bounce CPU readback。`vkCmdTraceRaysIndirectKHR` 需查询/启用 `rayTracingPipelineTraceRaysIndirect` 并满足 indirect buffer 对齐/usage；compute queue 用实际可用的 dispatch indirect。写 args/counter 后同步 shader write→indirect read，队列写→消费者 shader read；零 active 用合法 zero-work/skip 处理，不能发出越界 dimensions。

多 spp 尽量 tile/sample batch，在像素归并时保持 sum/sample count 一致；避免 `width*height*spp` 永久同时占满八套冷状态。Reference 用分块预算保证 UI 响应，累积不同 tile 的 accepted count，不把未采样 tile 加入全局 spp 平均。

## 7. 水、玻璃与体积

### 边界与 medium identity

水邻接 section 属于连续介质，不能 `mediumId=instance`。统一 medium family + 连通/进入退出策略；现有水的 section-independent identity 保留。玻璃实体介质需要区别不同嵌套 volume；mesh-only 没有真实封闭体身份时明确 thin/solid fallback，不能靠 `materialId` 证明体积连通。

front/back 依据几何法线，IOR 比值从 stack 的两侧介质获得。折射穿越才更新 stack，反射不更新；TIR 保持原 stack。等 IOR straight-through delta、8 层栈上限、缺失 exit/overflow 都有诊断，不能 silently 清空所有介质。

相机 submerged 通过世界 fluid state + 相机实际位置初始化；水体/玻璃界面与透射 shadow 使用同量化参数。透明叠层如 pane/ice/leaves 与 solid media 明确区分。水面 normal waves 保留原生几何轮廓及岸线，water-off 关闭波浪/外观控制不等于让水失去介质属性。

### 介质积分

Realtime 保留当前有限 segment 单散射作基线：Beer `T=exp(-(sigmaA+sigmaS)*distance)`，散射事件对 sun/env/held/emitter 连接，现有估计器没有 phase continuation 时不强加竞争 phase MIS。它是近似，不称 full volume PT。

Reference 严格体积模式另增 free-flight / phase continuation，最先支持均匀介质：可证明的 spectral/channel-mixture distance PDF 或其他合法 RGB estimator、HG sample/eval/PDF、event throughput、absorption 与 escape 概率一致；不能用任意 luminance extinction 抽一次距离后省略 RGB 权重。phase NEE 与 phase continuation 竞争时加 MIS；体积事件深度与表面顶点深度分开。异质云采用 majorant + delta/ratio tracking 前要验证 density 上界和 transmittance；首期不作为默认路线。

当前 unbounded medium miss 的 128 block 截断必须标记为既有策略。目标用真实 medium segment/world range；未知世界边界仍 coverage-limited。水底不再同时叠原生 underwater fog 和 PT Beer；native fog 仅在原生回退路径拥有颜色。

### 焦散

普通单向 PT 能表示部分折射路径，但强 specular 链连接小光源方差极高；提高 diffuse spp 或 denoise 不能保证水下焦散。首期水波 + 正确折射/衰减优先。历史 photon 设计不能标成当前 Vulkan 已实现。

后续可选 sunlight→water→receiver photon/light tracing：随水面/太阳版本更新 world tile，GPU 存 hit/flux，receiver 做有法线/距离/材质拒绝的核估计。其有限半径/temporal merge 有偏，reference 默认关闭。实时附加焦散若未有严格 MIS，应从基础积分器排除被它替代的对应 light-path family，或明确采用仅 debug 独立比较；直接把 photon irradiance 加到含同一贡献的 PT 上会双算。折射多层玻璃、点源焦散和 manifold connection 属于单独研究，不作为首期效果承诺。

## 8. 维度环境、天气与云

由 `LightingEnvironment`/`Atmosphere` 输出一个 `EnvironmentSnapshot`：dimension profile、tick/partialTick、sun/moon direction/radiance/angular radius、sky palette 或 atmosphere 参数、weather/biome/cloud 参数、environmentVersion、lightVersion。参数来自合法客户端世界状态；snapshot 同时服务 PT miss、NEE 和显示天空，不能各 pass 独立读取不同时间。

| Profile | 目标辐射环境 | 禁止的替代 |
|---|---|---|
| Overworld | 原生日夜角度、月相、biome/weather，连续 horizon/天空 | 固定白太阳、雨天屏幕整体无来源变亮 |
| Nether | 维度 palette、局部 emissive 与明确体积/距离雾；无主世界太阳 | 把红色 fog 当各方向均匀强环境光灌进封闭洞穴 |
| End | 维度天空/背景与独立灯光策略 | 残留上一维度 sun/moon 或云参数 |
| 自定义维度 | 适配已知 dimension effects；未识别时完整 native fallback | 自动套 Overworld 并声称支持 |

Overworld 可先复用当前 256×128 HDR map 和分布，太阳小盘仍由独立 finite-cone light 表示。环境更新分为 radiance 与 importance distribution，发布匹配版本；缓慢日夜变化可按量化角度/颜色误差或最大 age 更新，突变天气/时间立即 reactive，禁止为性能永久冻结天空。importance proposal 使用旧分布但新 radiance 的实验必须明确真实旧 PDF 与 support，并另行验证，不能误称同版本分布。

云首期作为当前 environment approximation 与显示云，共享 density/time 参数；不能再把云颜色作为第二份 environment 能量加入 miss。太阳连接需要云阴影时，从 world point 朝太阳采样 cloud transmittance，而不是拿相机上方云遮挡替代所有点；低分辨率 world tile/cache 具有 cloudVersion、age 与运动响应。环境 map 内云辐射与 cloud shadow 的近似边界明确，严格体积云 PT 后置。

天空/太阳/火把/水的 scene intensity 标定用固定曝光的校准场景：晴天 diffuse card、夜晚/月相、火把近墙、洞穴入口和 underwater。保留美术 palette 设置，但环境强度、光源强度与曝光分别记录；不能靠自动曝光隐藏一个版本丢失直接光。雨雪粒子在输出 overlay 合同内，湿度在材质合同内，大气散射在介质 owner 内，三个职责独立。

## 9. 辐亮度缓存、数值与实验边界

辐亮度 cache 在基础 scene/motion/light 版本稳定后考虑。沿用现有 `RadianceCacheLayout` 的空间规划能力，不宣称旧 cache 已接到当前 Vulkan 全 PT。缓存位置为粗糙 diffuse 多 bounce 终止；内容是已定义方向表达的间接 radiance/irradiance，明确是否含 direct/emission，防止读取后再加同一项。

world-space cell + geometry/light version，normal/visibility/depth moments 拒绝跨薄墙；动态手持/对象单独 reactive 更新。按 age/variance 分配 update rays。镜面、透明、发光和 incomplete coverage 不盲目使用 cache。缓存终止通常有偏，与 cache-off reference 对比误差和响应，只有收益明确才默认启用。体素代理/SHARC/DDGI 是可选实现方向，不同时引入多种缓存框架。

全链检查 finite origin/direction/throughput/PDF/radiance；invalid sample 不写进 HDR mean，按像素 accepted count 累积并暴露 invalid rate。Firefly clamp 是实时有偏策略，保留 unclamped reference；不以 clamp 隐藏 PDF bug。极亮天空、小 emitter、grazing/TIR、等 IOR、alpha border、黑 environment、camera underwater 是生产 Slang fixture 必测项。

OMM 需稳定 alpha/mip coverage，资源动画和 reload 正确失效；SER 需硬件/扩展能力及实际分歧证据。两者在上述状态与调度优化之后做 A/B，不改变材质/PDF，也不成为最低运行要求。
