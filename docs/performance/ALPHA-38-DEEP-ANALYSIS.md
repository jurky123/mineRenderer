# alpha.38 深入定位：失效契约、cache identity 与 sparse 置信度

本轮是源码审计和已上传数据的继续分析，没有修改渲染代码，也没有生成新客户端包。数据仍是 `rt-suite-1791355439893.zip`。下述区分代码已确认、实机计数和待验证推断。

## 1. scene/edit 的含义过宽

组合四个候选段的静态 BLAS build 数均保持 296，geometryCompactions 均为 0，驻留地形数量/精确版本签名不变。前三段 realtimeSceneResets 各增加一次，第四段没有 scene reset，改为 sun drift bound。没有证据支持“每次 scene/edit 都是当前驻留地形重建”。

VulkanRtContext.runtimeLighting 使用 `scene.historyGeneration()+RtInvalidationQueue.generation()` 作为全局 epoch 输入。WorldSceneBridge.markDirty/markRangeDirty 对任意非零 reasons 都调用 queue.record，且 record 在检查该 section 是否被 bridge 跟踪之前执行；LIGHT-only 事件也增长 revision。LightUpdateScope 已能识别原版 light packet 更新为 LIGHT；bridge 自己的 geometryVersion 又明确只在 LOAD/GEOMETRY/RESOURCE 时更新。因此原本分开的原因在 RT invalidation 入口重新合并了。

同一个原始 queue generation 还进入 realtime reconstruction epoch。只改 radiance cache 的 epoch 入口，会留下重建历史的全局重置，必须协调修改。

不能直接忽略所有 LIGHT 事件：RtGeometryStream 仍把原版 skylight 位写入 flags，terrain_material 用它计算雨湿 clearcoat。正确策略是保留 geometry/page 抓取的过期保护，并区分“原版 lightmap 事件但 RT 内容未变”和“RT sky exposure/材质属性确实变化”。现有报告没有逐事件 reason 和受影响区域，无法定量证明 LIGHT 占了多少 reset。

## 2. 局部失效前必须修正坐标与边界契约

queue 的实际生产坐标是 section 坐标，不是 block/cell 坐标。SectionKey.fromBlock 用 floorDiv(block,16)，WorldSceneBridge 直接 record section keys；RtGeometryStream.revision 的查询也传 section keys。未来 shader cache point 需要转换为 floor(point/16)，不能把 floor(point/2) 的 cell 坐标直接用于同一 region。

调用方用 `max+1` 构造 region，但 queue.revision 用 `<=max` 判断。用当前编译出的实际 Java 类执行审计：`record(1,1,1,2,2,2)` 会使 `(2,1,1)` 的 revision 变化。按调用方单 section 语义，正方向邻居被额外覆盖。应明确统一半开区间，不能靠含糊的 world-space 注释推断。

ClientScene.chunkChanged 的整列事件使用 Integer.MIN_VALUE / Integer.MAX_VALUE。WorldSceneBridge 对 maxY 加 1 会溢出为 Integer.MIN_VALUE。实际 Java 验证：该事件使 global generation 增长，但普通高度 `(0,4,0)` 的 local revision 不变。当前全局 reset 掩盖了这一局部查找错误；若只删除全局 reset，会引入 stale geometry/cache。

修复应采用可表达独占上界的 long region，或显式 column 标记/实际世界 section 高度范围；保持异步几何 snapshot、CPU backing page 和负坐标边界的正确性。不能破坏旧 revision 消费者。

## 3. cache 的键不足以表达 Minecraft 表面

rtCacheAddress 用 2-block cell 的 xyz 和六向 normal bucket 哈希，一个地址只保存一个 anchor/plane。rtTrain 对 plane 距离 >0.08 或支持半径 >1.5 的候选拒绝；48 帧内已有记录持续被更新时，另一平面无法替换它。

因此同一 cell 内楼梯两个台阶、半砖与地面、其他同向平面会争用一个槽，即使完全没有随机哈希碰撞。这是键表达能力的问题。train_collision 合并了随机 hash 占用、同 cell 多平面和支持范围不匹配，不能把它全解释为哈希表太小。

组合 56,457 次 probe 提议中，6,120 次锁失败、13,462 次空间/hash 占用失败，实际写入 36,586。很多路径已经付出了精确后缀成本，最终却没有帮助 cache 成熟。probe_dropped=0 只说明全局上限没有耗尽，不说明预算有效。

还需修正上轮解释：rtCacheStatus 先检查 count<24，再检查 key/plane。约 55.28% 的 cache_immature 会遮蔽空间不匹配，不能全部当成“正确 cell 只差更多样本”。下版应先区分 empty/epoch、key/plane mismatch，再统计 matching cell 的成熟数、年龄和误差，并拆开 collision 原因。

建议保留总 65536 slots / 10MiB，先评估四路 bucket，而非盲目扩大内存。键包含 cell、法线类别与稳定的平面标识，plane 应在 cell-local 坐标量化，避免大世界坐标精度和默认 RG8 法线偏差造成漂移；仍保留完整 key、精确平面与支持距离校验。只有真正匹配才读 SH。多路查询的额外读取必须纳入 batch A/B。

训练先做 cell/plane 级 frame claim，再占全局 ticket：重复候选退回正常完整路径或有效 cache 查询，减少已经追完尾部才被丢弃的样本。claim 与 SH 发布锁分开，冷/高误差/近期失效的实际可见 cells 优先；每帧预算仍有上限。每 cell 每帧一个 observation 的设计至少需要 24 帧才能成熟，这是可预测的启动边界，不能把一个样本复制成多个计数。

## 4. sparse 的主要损失不是高方差本身

| 组合主路径分类 | 数量 | 占全部主路径 |
|---|---:|---:|
| 材质保护 | 1,420,246 | 37.37% |
| 动态保护 | 73,500 | 1.93% |
| 无匹配历史/新暴露 | 610,935 | 16.07% |
| 真实更新数不足 | 538,700 | 14.17% |
| 均值误差大 | 191,751 | 5.05% |
| 成功复用 | 386,561 | 10.17% |
| 其余计划刷新 | 578,947 | 15.23% |

所以“19.22% high_variance”并不是全部高误差，其中大部分是 confidence<4。应先保留正确历史，再谈误差阈值。

rtPrevious 优先最近像素，失败后返回 2×2 中首个匹配；没有比较匹配历史的有效更新数。rtSameSurface 要求全局 triangle 地址相同；每帧 subpixel jitter 会跨过同一平面的不同三角形。普通 ROUGH_DIFFUSE 的纹理颜色差还会使历史失败。邻域匹配也可能把高置信度像素接到邻居的低置信度历史，持续稀释更新数。这些行为可从源码确认，但现在没有 confidence 分布、triangle-only 失败与候选质量统计，不能断言哪一项占全部 16.07% 的失配。

后续应增加失败细分与 confidence histogram，再评估稳定 surface/face identity，以及仅在最近候选质量低时选择更可靠的几何匹配邻居。不能直接删除 triangle 检查；新 key 至少要包含稳定 ownership、真实面身份/拓扑 revision，并继续检查 plane/位置/法线/材质。

ROUGH_DIFFUSE 含未着色镜面分量。现有 AOV 的 diffuse/reflection 是按路径事件路由，并不等于完整 BRDF 漫反射/镜面项的严格分离。不能仅因为 AOV 名叫 diffuse 就把整个值按 albedo 重调制。若要跨更多纹理变化复用，需先设计真实 lobe 分解并验证能量。

## 5. 本场景 sparse 目标有材质上限

材质与动态保护合计约 39.30%。若保持这一比例，且所有其他路径均能稳定隔帧复用，则全局路径密度至少约 `0.393+0.607*0.5=0.697`。组合各段 start/end 的 realtimeLightingGradual 都为 true；这不能证明每帧均 true，但说明日光渐变的二帧上限具有实际相关性。

所以不能在这个材质组合里要求 sparse 单独达到全局 0.25–0.6。更低的稳定密度只适用于符合条件的 diffuse 区域。Cache 仍可以在受保护 primary 之后遇到合格 diffuse B1/B2 时减少尾部，这是另一条加速通道；保护 primary 不等于它的所有后续 diffuse vertex 都禁止查询缓存。

## 6. 后续执行顺序与验收

1. 先统一 region 坐标/半开边界/整列上界；保留 geometry snapshot 保护，记录 reason 与 RT 内容是否真的变化。对缓存和重建采用协调的局部失效规则。
2. 区分 matching cell 未成熟与空间冲突；实现 cell/plane 训练去重及多平面存储对照。先减少无效精确后缀，再扩大预算。
3. 增加 sparse 失败原因/置信度分布；用稳定 face identity 与有界的候选质量选择保留历史。维持材质保护。
4. 评估 primary 分类/历史判断与完整材质 shading 分离，避免复用路径先付出完整准备成本。当前 primary 增加约 0.312ms 是 GPU 时间证据；究竟多少来自寄存器、随机访存或 shader 指令尚无硬件统计，不能宣称 L1/L2 或 spill 原因已证实。

局部失效不能只更新编辑所在的 cell：遮挡、发光源和间接光会影响邻区乃至更远路径。需要明确训练依赖/影响域，或保守 halo + 强光变化 fallback + 有限 TTL 的有偏边界；大范围事件、资源/世界变化和丢失事件窗口必须保留全局回退。共享 queue 不应由一个消费者 drain 后让其他消费者丢事件，应提供 consumer cursor / changesSince，并处理保留窗口溢出。

至少回归：负坐标和 section 边界、整列 load/unload、LIGHT-only 与 sky exposure 改变、实际附近挖放块与远处无关编辑、同 cell 双平面不同照明、并发 claim/有限 ticket、跨同一 quad 的 jitter 与相邻薄墙拒绝。CPU 数值/边界测试不能替代 GPU 并发、净耗时和收敛 Reference 画质验证。

当前 alpha.38 不需要重复测试。本轮增加的是明确的修复前提与实现优先级，第三轮仍未冻结。
