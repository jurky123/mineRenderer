# 场景、实例、资源与 Minecraft 数据合同

[总设计](README.md)。本文均为 alpha.23 后的目标设计；保留现有 section stream / warmup / page cache，不新建独立世界模拟。

## 1. 身份分层

当前纹理 source view 分组解决了 alpha.22 回退，但无法区分同贴图的多个运动物体。目标把身份拆成四层：

| 身份 | CPU key | GPU 引用 | 何时改变 |
|---|---|---|---|
| Geometry | 地形：world epoch + SectionKey + 编译版本；模型：资源 epoch + mesh/拓扑标识 | `geometrySlot + slotGeneration` | 拓扑/顶点内容变化 |
| Instance | 地形 section；实体 world epoch + UUID/生成序号 + feature；方块实体维度/位置 + renderer feature | 稳定 `instanceSlot + generation` | 对象移除后新建 |
| Material | namespace/resource + selector + override 版本 | `materialSlot + version` | 参数、来源或分类变化 |
| Texture | resource epoch + native resource identity + atlas/sprite/subregion | `textureSlot + contentVersion` | 实际像素内容变化 |

CPU 可用 UUID/长 key；GPU 用有界 slot 查表，槽回收递增 generation，防止历史、reservoir 或 page request 误认新对象。TLAS `instanceCustomIndex` 的 24-bit 字段只存 instance-table slot，不再存全局 triangle base；检查上限。SBT offset 表示相交程序类别，不承担对象身份。

同 texture 的不同 skin/model 不能仅因贴图相同就共享 BLAS；共享需要完全相同 local mesh、topology、material-index layout 和 pose。不同姿态可共享 immutable base mesh，但各自拥有变形输出/BLAS。材质变更若不影响 opacity/geometry flags，仅更新材质表；opaque→cutout 等变更需要重建相应 AS geometry 分类。

### 目标记录与 ABI 原则

以下是逻辑字段，不是对当前 ABI 的兼容承诺。GPU 表使用显式 uint/float4、固定 offset、无 Java/C++ bool、无隐式指针；新 layout 加版本并执行 Java↔Slang offset/stride 校验。

```text
GeometryRecord:
  geometrySlot, slotGeneration, topologyVersion, vertexVersion
  vertexBase, indexBase, attributeBase, materialRangeBase
  primitiveCount, geometryClass, localBounds, blasAddress
InstanceRecord:
  instanceSlot, slotGeneration, geometrySlot, geometryGeneration
  currentObjectToRender[3x4], previousObjectToRender[3x4]
  materialOverride, flags, poseVersion, previousPoseVersion
  stableSurfaceVersion, bounds, lastCapturedFrame
MaterialRecord / TextureRecord:
  参见 transport 材质合同；texture record 包含描述符/UV/mip/版本
```

slot 与几何分配地址分开；搬移只更新 table，不重编号所有对象。primitive ID 在一次 topologyVersion 内稳定，改变拓扑时拒绝对应历史。法线应用 inverse transpose，负缩放修正绕序/朝向，非均匀缩放不直接用位置矩阵变换法线。

## 2. 坐标与时间

CPU 世界位置用 double / section 整数；静态顶点保持 16³ section-local，模型保持 object-local。GPU render origin 是相机附近的 section 对齐原点，不把世界大坐标直接写入 float 顶点。

`pRender = pLocal * objectToWorld - renderOrigin`。上帧重投影使用上帧 origin 和 transform，或显式补 `currentOrigin - previousOrigin`。origin 变化只更新实例/相机，不改变 geometry hash、material ID 或 poseVersion。正常 origin shift 可保留历史；传送/维度切换属于 camera cut。

一个 frame snapshot 保存 game tick、partialTick、monotonic render time、pause 状态。人物、手持、粒子和光源必须用同一次 partialTick。默认视觉动画随游戏暂停冻结；若设置允许菜单背景动画，则单独计时并提升 texture/environment version。Reference snapshot 完全固定时间。

## 3. 地形快照与页面状态机

现有 `RtGeometryStream` 的 immutable triangle snapshot 继续作为精确场景来源。工作线程只使用合法捕获的编译结果/不可变输入，不直接遍历正在变化的 level、访问渲染线程 texture view 或执行 Vulkan 调用。

```text
UNLOADED
  -> LOADED_UNCOMPILED
  -> SNAPSHOT_READY(revision, resourceEpoch)
  -> CPU_CACHED
  -> UPLOAD_PENDING
  -> AS_PENDING
  -> RESIDENT(committed revision)
RESIDENT -> EDIT_PENDING -> 新版本提交 -> 旧版本 RETIRING
RESIDENT -> EVICT_PENDING -> GPU 槽退休 -> CPU_CACHED（仍加载）
任意状态 -> 卸载/epoch 变化 -> 禁止重新 admission，丢弃陈旧任务
```

每条任务携带 world/resource epoch、SectionKey、section revision、snapshot version。合并队列保留最新版本，commit 前再次校验。空 section 也是合法新版本，要移除旧 BLAS；不能因 triangle bytes=0 继续保留旧墙。

编辑发生后，新几何未就绪时旧几何标记 stale、对应屏幕历史立即失效。相机 primary 涉及 stale 页时触发受控回退/覆盖补偿；不能把延迟提交的旧方块宣传为最新世界。一次爆炸/活塞更新采用按 section 合并、相邻边界 section 更新与预算背压，队列有长度和 bytes 上限。

### 调度与覆盖

优先级依次为：相机所在/近场编辑与手持 → primary 可见 section → 潜在遮挡/近场反射 section → GPU miss 请求 → 运动方向预取 → 远场。优先级还受 page age 和服务饥饿保护影响；稳定驻留设置最短保留窗口，避免边界相机抖动反复 eviction。

GPU miss feedback 只传 section key/重要性/帧号，使用有界去重 ring，隔若干帧非阻塞回读；不读取整帧图像。请求只针对客户端合法已加载页；未加载页不会凭空重建。反馈计数包含 overflow 与丢弃，不把累计 miss 次数当 miss rate。

区分 `KnownEmpty`、`Resident`、`LoadedMissing`、`UnloadedUnknown`。ray miss 仅表示 TLAS 没击中，不能独自证明天空可见。通过稀疏 section coverage/occupancy 表对 ray 穿越范围检查，必要时做低频 section DDA；这是覆盖辅助，不代替精确三角形相交。

- 已知完整范围内 miss：正常 environment。
- 穿越已加载但未驻留页：提交 page request，标记 incomplete sample，不进 reference 累积，也不积累高置信历史。
- 未加载边界：场景范围终点，显示环境/距离雾的明确近似，不能声称无漏光物理正确。Reference 标记 coverage-limited。
- primary 缺页：warmup 阶段整帧原生回退。局部混合须有同投影 depth、覆盖 mask 和单次颜色合同后才能开启，不能直接拼 LDR 原版颜色与 HDR PT。
- secondary 缺页：实时可低置信环境终止；debug 显示 mask 与比例，优先补页。若质量不能接受，增加合法覆盖或回退，不能把未知页当 opaque 假墙。

远景代理后续独立版本：从已加载/已缓存合法数据生成，仅指定远场范围/光线类别使用，精确与代理不重复表示同一空间。代理失效、透明/发光保留与接缝需另行验收；不接入未经明确适配的远景 mod 数据。

## 4. 动态模型与对象生命周期

扩展 `DynamicModelBuffer.RtModel` 的逻辑结果，携带 owner key、feature key、local geometry、material binding、current/previous pose 和 object transform。原生 capture 接口优先在 entity/block entity render-state 或 submit-model 边界保留身份；无法恢复身份的 custom quad 标为 transient，拒绝历史。

| 变化 | vertex/attribute upload | BLAS | TLAS instance | 历史 |
|---|---|---|---|---|
| 平移/旋转 | 无 | 无 | 更新 transform | 对象运动重投影 |
| 骨骼/顶点变形，同拓扑 | 变化顶点及必要法线 | 合规 UPDATE | bounds/地址需要时更新 | previous pose 重投影 |
| 箱子静止、同皮肤/材质 | 无 | 无 | 无 | 可接受 |
| 模型拓扑/LOD/feature 改变 | 新布局 | BUILD | 更新地址/slot | 局部 reject |
| 贴图动画，仅 RGB | 纹理增量 | 无 | 无 | reactive |
| alpha 分类变动 | 纹理及材质 | 分类改变时 BUILD | 必要更新 | coverage reject |
| 对象移除/区块卸载 | 无 | 延迟退休 | 移除 | 原位置 disocclusion |

第一步允许 CPU 保留 current/previous posed vertices；骨骼调色板和 GPU skinning 在实测 CPU capture 瓶颈后考虑。不要为每个模型立即实现骨骼框架。静态模型多实例共享 BLAS；动态顶点不可被不同 pose 的对象错误共享。

离屏实例仍需存在以支持反射/阴影。基于 loaded object registry、距离/影响范围保留已观察对象；若捕获仍依赖可见提交，离屏更新 translation、保持最后 pose，并公开 `poseAge/stalePose`。独立离屏 pose extraction 是后续明确工作，不能称已完整支持。实体卸载立即从 pending scene 移除，不以 TTL 替代卸载事件。对象数量超预算时稳定优先选择，禁止随机每帧闪烁。

手持使用独立 `VIEW_MODEL` instance mask 与 native HUD/world FOV 适配。主射线可见，默认排除世界 shadow/secondary reflection，避免放大变换后的手臂成为世界中的巨大遮挡；是否允许自阴影由单独 mask 控制。手持光源是世界空间虚拟源，位置/强度不依赖这套视觉投影。成功 PT 捕获并显示才抑制 native hand，失败同帧恢复。

## 5. 持久 geometry/AS/上传资源

沿用 `VulkanRtBuffer` owner，逐步允许 arena slice；池负责 backing allocation，slice 不能直接销毁 backing buffer。先持久化高频动态 vertex buffer/AS/scratch，再为 shader geometry 引入 free-list。不同时替换全部资源管理。

- 静态 geometry arena：按容量 size class/free-list，保留 headroom，编辑只替换受影响 section。静态 BLAS 优先 fast trace，compaction 在冷阶段且测得收益后执行。
- 动态 vertex/attribute capacity：拓扑容量内复用；容量不足按 size class 扩容。原地覆盖之前同步所有旧读者；多帧并行时使用 frame slot 或 copy-on-write。
- AS storage pool：依 memory requirements 对齐 suballocate；Vulkan AS handle 引用具体 buffer range，回收 handle/storage 均等待最后 GPU 使用。
- scratch arena：满足 `minAccelerationStructureScratchOffsetAlignment`；同批可并行 build 的 scratch 区间不重叠。复用同区间需要明确 build→build barrier/串行依赖，不能仅靠记录顺序。
- staging ring：frame slot 与 native submission completion 绑定，host-visible 需要正确 flush/coherency。保持实际 `writeToBuffer` / command encoder 合同，不假设引擎公开一个不存在的映射 API。
- shader geometry：稳定 region + instance indirection，attribute 不再根据全局紧凑 triangle base 寻址。禁止地形 admission 使全部 emitter 重编号。

UPDATE 合法性记录完整 build signature：type、flags/ALLOW_UPDATE、geometry count/type/flags、vertex format/stride、index type、primitive/layout 与规范规定的其他条件；变动不合规时 BUILD。相同 vertex count 仅是其中一个条件。动态 refit 的 AS 质量通过 traversal 时间/bounds 扩张和 refit 次数判断，低优先后台 BUILD 替换，不用硬编码“永远 refit”。[Vulkan UPDATE 规范](https://docs.vulkan.org/spec/latest/chapters/accelstructures.html)

TLAS instance buffer 持久；相同实例布局且合规时 UPDATE，数量/布局等不允许 UPDATE 的变化 BUILD。完全不变不录制命令；每帧收集完 terrain/dynamic 后统一一次全局 TLAS commit。

## 6. 同步与场景提交

首期所有 Vulkan PT/AS 命令使用原版 device 和可追踪的提交序列，不引入独立 async compute queue。原地更新只在依赖链明确且不会同时被跨队列读时开启。

| 资源边 | 必要依赖（按引擎可用同步 API 落地） |
|---|---|
| geometry upload → AS build | transfer write → AS build read |
| shader attribute upload → trace | transfer write → RT shader read |
| BLAS build/update → TLAS build | AS build write → AS build read |
| TLAS build → trace | AS build write → RT AS read |
| trace → signal reconstruction | RT shader write → compute/fragment read |
| previous trace/read → in-place overwrite | 旧 RT/shader/AS read → transfer/AS write |
| queue change / CUDA bridge | release/acquire、semaphore 与实际 queue ownership 合同 |

allocation 保存 `lastUseSubmission`，frame slot 保存 completion；descriptor、texture view、AS handle 和 backing memory 都不能提前回收。资源退休只登记，不每 frame `vkDeviceWaitIdle`。异步提交 snapshot 失败时保留完整上一版本；若输入已编辑，则覆盖状态标 stale 而不是继续声称 ready。

`SceneCommit` 至少包含 frame ID、committed versions、changed instance/page ranges、coverage summary、TLAS handle/地址、allocation retirement 信息。material/light 对新 primitive 的引用只能与该 commit 一起发布；不能上一帧 TLAS 搭配下一帧 emitter 表。

## 7. 材质和纹理生命周期

保留 alpha.22 的 atlas crop 与 UV remap。首期改为**内容变化才复制**：skin、静态 item tile、箱子纹理保持驻留；动画 sprite 依据帧/插值版本更新，动态 mod 纹理无内容版本时保守更新并计数。纹理 view identity 只表示对象，不表示像素未变。

`TextureRecord` 保存 source format/colorspace、sprite rect、crop remap、sampler/mip/alpha policy、content/resource epoch、last-use submission。资源 F3+T 重建 sprite/material 绑定，旧 descriptor 保护到最后读者完成；旧 view pointer 不跨 resource epoch 使用。

中期仅在原生 texture handle/descriptor 生命周期可安全观察时直接采样 native atlas/skin，减少 128² resample/copy。先检查格式、采样 usage、同步和 descriptor indexing 能力；不把内部对象强转后假设永久有效。缺 descriptor indexing 时使用有界 atlas + indirection；容量溢出采用明确 fallback，不把纹理槽复用成随机外观。

RT mip 基于 ray cone/footprint，首期粗估 `coneWidth + hitDistance * coneSpread`，折射/反射更新 footprint；每 sprite 独立边界/gutter，alpha mip 尽可能保持 coverage。物品 16px 图在大 atlas 中仍按原 sprite footprint 取样；材质 roughness/normal/emission 数据线性采样，albedo 的 sRGB 只解码一次。nearest/像素画风允许作为外观设置，但不是关闭所有远距离抗闪烁的默认理由。

## 8. 全局预算和生命周期

CPU：压缩页、snapshot queue、解压临时、dynamic current/previous mesh 均计数。GPU：geometry、attributes、BLAS、TLAS、scratch、staging、material/texture、path、signal/history、interop、**retiring allocations** 全部计数；区分 allocated/resident/used/peak。

预算 admission 先预测新增与旧版本重叠峰值，保留安全 headroom；满足单 buffer storage range、AS/descriptor/dispatch 限制。适配 `VK_EXT_memory_budget` 时也扣除游戏原生资源压力，缺扩展使用可配置总上限。不能把 CPU 256 MiB/GPU 64 MiB 两个局部参数当全局 budget。

压力次序：回收已完成退休资源 → 驱逐不保护的远页/冷纹理 → 延后低优先 build/capture → 按已验证质量档降低内部尺寸/历史资源 → 主覆盖无法保证则原生回退。禁止驱逐本帧尚在使用的 resources；reference 提示范围/预算不完整。

世界/维度切换：停止 admission、提升 world epoch、清 pending/feedback/history、退休 GPU snapshot。Reload：提升 resource epoch、取消旧捕获、重建材料与 geometry 分类、保留合法 CPU 世界 key 但旧 material-linked snapshot 不可直接恢复。Resize：只改 resolution-dependent targets/queues/history，通常不重建地形 AS。Backend off/failure：恢复 native owner，退休 PT 资源，记录原因与重试条件。
