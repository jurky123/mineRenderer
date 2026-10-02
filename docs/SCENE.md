# P0b 局部场景基础（0.2.0）

此阶段提供后续阴影/体素数据的 CPU 基础，不改变光照。0.2.0 不包含阴影；0.3.0 的独立 caster/shadow map 见 [SHADOWS.md](SHADOWS.md)。真实 terrain GBuffer 和 GPU voxel DB 上传尚未实现。

## 数据与失效

ClientScene 仅跟踪相机实体附近 5×5×5 个已加载 section，世界高度边界或未加载 chunk 会缩小窗口。查询使用 create=false，不触发加载。WorldSceneBridge 容量 128，当前使用最多 125。每个条目只保存一个合并的 dirty marker、一个请求和一份当前快照；重复变化增加版本并合并 LOAD/GEOMETRY/LIGHT/RESOURCE 原因。失效立即隐藏旧快照。

客户端线程复制 PalettedContainer；单 daemon worker 只读取拥有独立存储的副本和不可变 BlockState，不访问实时世界。输出短整数索引、state ID palette、材质 flags、emission 和 full-occluder 位图。flags 为 1=full occluder、2=non-air、4=fluid、8=non-model；这是保守分类，不包含模型几何、纹理 albedo、透明度或 PBR 材质。state ID 只适用于当前游戏 registry。

任务携带 world generation、resource generation 和条目 version。0.3.0 增加独立 geometryVersion：LOAD/GEOMETRY/RESOURCE 推进，LIGHT 单独更新不推进，因此不会仅因 lightmap 更新重新编译 caster。完成时三者及当前请求必须匹配才发布。卸载/移出窗口删除条目；切世界清空、取消任务并推进 world generation；reload 推进 resource generation 并使所有条目失效。旧结果不能清除新请求，被覆盖的未发布变化保留原 dirty 原因。

## 预算

| 资源 | 上限/策略 |
| --- | --- |
| 驻留窗口 | 最多 125 section；离开立即撤销 |
| dirty marker | 每个 tracked section 一份；不保存事件列表 |
| 已提交任务 | 最多 2；单 worker，队列容量 2 |
| 客户端复制 | 每 tick 最多两份；2 ms 软预算，在复制之间检查 |
| 快照数组 payload | 每 section 8,704 + 6×paletteSize 字节，最多 33,280；125 份最多 4,160,000 字节 |
| GPU voxel DB 资源 | 0；caster GPU 预算另见 SHADOWS.md |

数组 payload 不包括 JVM 对象、HashMap、源 palette 副本及编码临时数组。status 的 payloadBytes 仅计算当前有效快照数组；不能当作进程内存。两份在途任务与单 worker 限制临时数据数量。2 ms 不是硬实时保证，单次 palette copy 可能超时。dirty 按最早失效次序处理，重复编辑不会重置队列优先级。客户端仅在 Future.isDone 后取结果，不等待运行中的 worker。

## 使用与验证

`/voxellight scene on` 启用；`scene` 查看 resident/tracked、dirty/inFlight、generations、accepted/stale/merged 和 CPU 时间；`scene inspect` 查询准星方块；`export` 写 scene 汇总 CSV；`scene off` 清空。scene 默认关闭，与视觉 mode 独立，可在 OpenGL 下使用。

`/voxellight mode normal` 是 Vulkan 深度方向诊断，使用当前 projection 重建位置，在邻域中选择较小深度断层方向再计算法线。天空黑色，透明/不写深度物体与边缘会有近似误差；不是实际模型法线或新光照。

自动测试覆盖 10,000 次编辑合并、队列公平性、容量、旧版本结果、世界/资源重置、同坐标卸载重载、未发布变化原因保留、快照数据所有权、位图边界及负坐标。shader 用 Minecraft 编译器离线编译；最终 JAR 检查 mixin 包、目标和 draw 参数。实机 normal、编辑/火把、reload、切维度和飞行验证见 [INSTALL.md](INSTALL.md)，构建通过不能代替这些检查。

0.3.0 的 block 变化在 section 边界扩展到相邻 section；setSectionDirtyWithNeighbors 扩展 3×3×3；chunk load/unload 使相邻边界几何失效，避免保留旧 exposed faces。caster 获取 geometry token 时核对快照仍为当前对象，并在发布前再次核对版本。

用户已确认 0.2.0 的 normal、125/125 section 暖机、F3+T 和下界切换正常；此记录不等于后续阴影画面验收。

0.5.1 shadow geometry 的客户端编译使用独立 GeometryToken（world/resource/geometry version），不等待 occupancy snapshot。worker 的 token/version 校验与背压照旧，因此 GPU caster 就绪可能早于 CPU scene resident；snapshot 编码完成不会覆盖/推进几何 token。light packet 的 section rebuild 以 scope 分类为 LIGHT，避免误失效 caster。
