# 运动信号、重建、放大与输出

[总设计](README.md)。目标是 1 spp 的可用动态画面；去噪、反锯齿、时域放大及显示变换各自有合同，不能以 blur + LINEAR 放大宣称已实现完整重建。

## 1. 输入 surface 的一致性

alpha.21 独立中心 guide 稳定但可能与 jittered noisy sample 命中不同物体。目标主要 reconstruction guide 与**生成该像素 noisy signal 的同一 primary sample**一致；sample jitter 由相机合同管理。保留中心 guide 作为 debug/输出边缘辅助，它不能替换 noisy sample 的 surface ID。

多 spp 时选固定的 representative sample surface，其他样本若属于不同物体/深度，记录 coverage variance/reactive 或分层 resolve，不能简单平均 surface position/ID。Sky 有独立类型：viewZ sentinel、rotation-only motion，不能伪造远平面位置参与 surface plane 检查。

Primary surface 总体稳定与采样变化之间由 jitter-aware reprojection、ID 验证及 coverage policy 协调。输出分辨率高精度 guide 可由额外 ray 或原生几何 raster 提供，但需先证明成本/覆盖：同一 model/instance/material、cutout、view-model projection、一致相机与深度；不恢复 raster lighting/shadow 等被 PT 替代的整套 pass。默认先做内部尺寸一致 guide，不强制双 primary。

## 2. 目标 signal ABI v1

以下为内部逻辑合同；SDK adapter 转成各后端要求的具体格式/空间，SDK packed 布局不能反向成为物理积分器的材质定义。内部图像除明确标注外均为 internal resolution，使用显式 `validMask`，不复用 alpha 隐藏 unrelated metadata。

| 字段 | 初始存储建议 | 精确定义 |
|---|---|---|
| NoisyBeauty | RGBA16F，HDR 极值用 RGBA32F fallback | scene-linear 总 radiance，预曝光约定见下文；alpha 为有效覆盖 |
| DiffuseRadiance | RGBA16F/32F | 按第一非 delta 分支归属的 diffuse 信号；adapter 根据需要 demodulate |
| SpecularRadiance | RGBA16F/32F | 相应 glossy/reflection 分支，含 specular-chain 传播 |
| TransmissionRadiance | RGBA16F/32F | 首个 transmission 分支的背景/透射贡献，独立处理 |
| VolumeRadiance | RGBA16F/32F，可按模式省略 | camera/segment 介质散射，与表面信号不重复 |
| EmissionResidual | RGB HDR，可复用独立输出 | 直接可见 emission/sky 与其他未滤波分量的明确归属 |
| DiffuseAlbedo / SpecularAlbedo | RGB16F（或满足范围的 UNORM） | linear reflectance；specular 使用约定的方向/模型，不统一当 baseColor |
| NormalRoughness | RGBA16F 起步 | world/render-space shading normal + perceptual roughness；后端转换 |
| GeometricNormal | packed 或由几何重建 | front/back、plane/法线贴图边界校验 |
| ViewZ | R32F | 正的 linear camera-space 深度；不是 device depth 或 ray t |
| Motion | RG16F 起步 | **current→previous，未 jitter 的 internal pixel 位移** |
| SurfaceIdentity | RGBA32UI 逻辑 16 B | instance slot、generation、primitive ID、surface/topology version |
| HitDistanceDiffuse/Specular | RG16F/32F | 由 signal 路径实际得到的距离及独立 valid 标志，不用 primary viewZ 替代 |
| Reactive / Confidence | RG8 或等价格式 | 材质动画/灯光/透明响应与可用历史程度，不是强行平滑权重 |
| Valid/Layer/Event flags | uint mask | sky、cutout、dynamic、thin、transmission、volume、incomplete、invalid |

三类 AOV 当前已有累计字段，但本设计要求验证每项贡献的归属，而不只是写出三张图。Emission/sky/volume 的归属与 `Beauty = Σ owned signals` 必须 fixture 可还原；可见直接 emission 不参与 diffuse albedo demodulation。Direct eval 若 diffuse/coated/specular 同时有非零 lobe，应分别累计 lobe 贡献，不能把整项按一次随机 lobe 标签粗分。

`firstLobe` 定义：经过相机可见 delta chain 时保存链 metadata，到首个可滤波 surface 选择 signal owner；PSR 若开启，用其 replacement surface 的 guide 与 normal/reflectance 一起发布。混合材质 stochastic lobe selection 需要概率补偿；未采到某 lobe 不等于该 lobe 真实贡献为零，其 hit distance 有效位要通知后端重建。

Demodulation 是各 denoiser adapter 的工作：使用与所选 BSDF 匹配的 reflectance；接近零 reflectance 有 bounded/zero 分支，禁止除以很小 albedo 生成巨大噪声。Remodulation 在输出恢复且仅一次。NRD 的 hit-distance normalization/packing 调用其 SDK helper 或固定版本等价实现，不能用自定义数值冒充 SDK expected range。[NRD 合同](https://github.com/NVIDIA-RTX/NRD)

## 3. 相机与对象运动

所有 motion 以当前 surface 到上帧同一点定义：

```text
pLocal = 当前 hit barycentric 下的 local surface point
pCurrent = currentPose/currentObjectToWorld(pLocal) - currentOrigin
pPrevious = previousPose/previousObjectToWorld(同一 primitive、barycentric) - previousOrigin
uCurrent = project(current unjittered viewProjection, pCurrent)
uPrevious = project(previous unjittered viewProjection, pPrevious)
motionPx = (uPrevious - uCurrent) * internalResolution
historyUV = currentUV + motionPx / internalResolution
```

皮肤/骨骼变形用 previous posed vertices 同 barycentric，刚体只用 transform。Topology 变动、previous pose 不可用、对象未出现过、transient geometry 不伪造 motion：confidence=0、标 new surface。view-model 使用 current/previous HUD-FOV compensation 和 native hand pose，不能拿世界物体的 transform 代替。

投影矩阵保存 FOV、aspect、near/far、镜头特殊变化；与 device depth/reversed-Z 的互转由 adapter 明确。Jitter 作为独立 NDC/pixel 参数，历史 UV 采样显式处理 previous/current jitter；motion 本身不含 jitter，不在后端再扣一次。内部/输出尺寸不同由每个 adapter 的 motion scale 换算。

当前 OptiX 的 previous→current pixel flow 与本合同方向相反，需 adapter 做完整映射/实际 SDK 要求转换，不能只换字段名。转换 fixture 使用平移、旋转、刚体、骨骼、FOV 变动、origin shift 和空运动，验证最后像素落点。

Sky 根据 direction 用上帧旋转投影，忽略相机 translation；世界边界/未知页不认为稳定 sky。镜面 reflection/transmission 的可见内容并不按 primary motion 移动：使用 specular hit distance、virtual hit/reprojection 或经验证 PSR；折射需额外链/界面 metadata。首期无法正确处理时降低历史长度与 confidence，不能把玻璃后运动物体的拖影归因于材质太透明。

## 4. 历史验证和失效

接受 history 必须先完成 screen bounds、valid/current→previous motion、surface identity/generation/material/topology、viewZ/plane、geometric/shading normal/roughness 与 coverage 校验。距离阈值依据 pixel footprint 和深度误差，拒绝薄墙后另一个相近点；连续生物表面不因不同 primitive 就全部 reject，但允许跨 primitive 需要同 instance/topology + pose 对应及表面一致检查。只比较颜色容易把同色墙后旧光拖过来。

| 事件 | history 策略 |
|---|---|
| 世界/维度/resource epoch、backend 切换 | 清全部 reconstruction、reservoir、reference；资源按 GPU completion 退休 |
| 传送、明显 camera cut、投影类型切换 | 清 temporal；不清合法静态 geometry |
| resize/internal scale 改变 | v1 清尺寸相关 history；后续可支持已验证 history rescale |
| 方块编辑/模型拓扑/移除 | 受影响 surface 和 disocclusion 拒绝；相邻 lighting 响应范围 reactive |
| 对象仅刚体/pose 变化 | 正确 object motion，不清全局 scene history |
| 贴图 animation/RGB change | 局部 reactive/短历史；alpha change 增 coverage reject |
| held/sun/weather/emitter 参数变化 | lightingVersion + 影响区域 reactive；巨大变化 reset |
| 缺页/陈旧 pose/invalid sample | confidence 降低或 reject，reference 不累计 incomplete |
| exposure/display curve 改变 | 线性 history 保留；pre-exposure 需要 rescale，纯 display 不碰物理累积 |

分离 versions：world、resource、geometry/topology、instance transform/pose、material、texture content、lighting、environment、coverage。`scene.generation` 表示提交序列，不直接等于“全屏历史必须清除”。全局 reset 只用于确实无法兼容的变化；灯光局部反应使用 age/variance 限制，不能让 static history 把手持灯留成残影。

Temporal blend 使用 validated history length、variance/moments 和 reactive，保证新表面快速响应；spatial filter 只在同 layer 与 surface constraint 下进行。Luma clamp 的信号域与 exposure 一致；confidence 是事实输入，不能靠拉高它掩盖错误 motion。

## 5. 后端选择与放大

只保留以下可审计路径；每帧只有一条输出链拥有 radiance：

| 路径 | 顺序 | 用途 |
|---|---|---|
| Vulkan baseline | 分信号 temporal/spatial → remodulate → temporal upscale/TAA | 可运行基线与故障回退 |
| Vulkan NRD | SDK-required signals/guides → NRD → remodulate → temporal upscale | opaque diffuse/specular 对照 |
| OptiX | ABI 转换 → GPU interop → beauty/AOV 合同去噪 → 对应 upscale | 保留现有真实实现作对照 |
| DLSS RR | 所需 noisy/material/depth/motion → RR 的联合重建/放大 | NVIDIA 可选增强 |
| Reference | accepted sample HDR mean → 同一显示路径 | 数值/画面 oracle，默认无重建 |

NRD 不自动提供高分辨率恢复，透明/体积不直接当 opaque 输入；OptiX beauty 模型不宣称分离 AOV 已正确降噪；RR 不默认串接 NRD/OptiX 或额外 DLSS SR。能力选择基于实际 SDK/设备/驱动/OS 查询和启动自检，不仅看 GPU 品牌。RR 所需 Vulkan/Streamline 初始化可能早于当前 PT 初始化，要在 `VulkanRtxExtensionsMixin` / device creation 边界核对扩展和 lifecycle，不临时再创一台 device。[RR 集成指南](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_RR.md)

Vulkan-native NRD 初期以本项目已有 shader/resource API 录制其 passes，只有证明直接集成成本不可控才引入 NRI；shader/toolchain 版本固定，不同时新增第二套资源 owner。Interop OptiX 的 buffer/semaphore 有 clear ownership：export input 完成→外部消费→output ready→Vulkan display read；超时/失败清该后端 history，当帧走有效 baseline，不阻塞等下一帧结果。测 export copy、wait、kernel、import 与端到端，而不把 exchange 时间称 kernel 时间。

### 时域放大基线

先实现独立 TAAU 合同：output pixel 在 jittered internal grid 中 gather，depth/normal/ID 引导选择当前样本，按输出空间 motion 找 history；disocclusion/reactive 处依赖当前帧边缘重建，variance/neighborhood 限制旧 history。最终锐化 bounded、以输出材质边缘为依据，不把高频噪声锐化成细节。

方案可通过实现成熟 temporal upscaler（如 FSR 对应 SDK）的正式输入合同替代，但必须查实际 Vulkan/平台/许可证并固定版本后接入。额外输出分辨率 guide 仅在显著改善 thin foliage/hand 边缘且成本可接受时开启。现有 4×线性缩小即 1/16 pixel，不能保证放大器恢复丢失的信息；新质量档的 internal/output ratio 由验收而不是性能截图决定。

DRS 使用真实 GPU completed frame 时间、迟滞和缓慢调整，设置最低画质尺寸和 device memory 上限；基准固定尺寸关闭 DRS，stats 同时报告请求/实际尺寸/spp 与调整原因。Reference 不自动 DRS。输入尺寸变化先 reset v1 history，不能为减少 reset 把错误尺寸 history 继续采样。

## 6. 透明、体积与 overlay

Transmission 先保持独立信号、低历史/专门 confidence；只有 replacement surface 或多层 metadata 合同正确才使用 opaque denoiser。半透明界面 alpha 不代表 diffuse radiance blending。多层折射信息无法由单个 primary depth 完整描述，因此首期限制并公开透明 reconstruction 质量边界。

volume 用 segment depth/medium identity + low-frequency filter，不能用水面 normal filter 把空气散射推到实体上。若 realtime 单散射采用屏幕后合成，则相应贡献在 PT 积分中关闭，HDR 合成位置固定；若已在路径中积分则不运行旧 VolumetricPass。远景 distance fog 属于覆盖边界近似，与介质散射区分。

首期 blend/additive 粒子保持原生几何提交，但进入 PT 的 HDR overlay island：同相机/深度、正确 premultiplied alpha/additive 状态，LDR material shader 不能直接当线性 HDR。需要由成功显示的 primary depth 生成 native-compatible depth（明确 device/reversed-Z），若无法生成安全深度，不随意画穿墙粒子，而是原生完整回退或明确 unsupported 状态。

| 特征 | Owner 与颜色域 |
|---|---|
| cutout particle | PT 材质/visibility，反射/阴影是否纳入由 instance mask 明确 |
| blend/additive particle、weather | HDR overlay，受 world depth；首期不进入全部 secondary |
| glint/伤害闪烁/破坏纹理 | 优先 surface material；未支持时专用 overlay，不能两处同时绘制 |
| entity outline/标签/选择框 | native/display overlay；深度合同保持原版语义 |
| 手持/手臂 | PT view-model 或 native hand 二选一；随 frame 成功结果切换 |
| menu/准星/聊天/UI | 最终 native UI，以 display 域处理；不进入 PT history |

`FrameCoverage` / draw suppression ledger 记录具体 feature 是否 captured、committed、displayed；只有三者成功才 suppress native。异常后同帧恢复 owner。成熟全 PT 成功帧只保留必要 extraction/overlay/native UI，不为 fallback 在每个成功帧完整重复 raster world；warming/failure 路径预先执行原生世界，选择在明确 frame boundary 切换。

## 7. HDR、曝光与显示

`Trace HDR → reconstruction → HDR overlay → bloom → exposure → tone mapping/gamut/output transfer → UI`。所有水反射、fog/volume、emission、cloud radiance 都有一个 owner；不在已 tonemap color 上再跑物理材质、bloom 或水吸收。

初期 scene-linear 累积使用 FP32 mean；signal 可 FP16，但检测超范围/非 finite。若采用 pre-exposure，frame 明确 `Ecurrent/Eprevious`，history 转为当前约定比例，光源/PDF 不受影响；曝光估计来自指定 HDR luminance 域，UI/天空极端高亮的权重可独立规定。曝光自动变化不重置 reference 的物理 sample sum，manual exposure 不改物理场景。

SDR 首期沿用项目现有已验证 display curve，明确工作色域与 output sRGB transfer；不把多个 filmic 曲线叠加。HDR 后续只有原生 swapchain、OS/color space、格式、metadata 可控时开启，HDR→PQ/其他 transfer 与 SDR 分开；shader 输出 >1 不能独自证明 HDR 显示。Caustica 的 HDR 路线作为系统边界参考，不复制其配置为本项目平台承诺。

FG/MFG 在基础 motion、UI 分层、swapchain 和稳定帧率验收之后单独设计。当前不为了 FG 改 Minecraft tick、不将插值帧计为渲染吞吐、不承诺降低真实输入延迟。
