# VoxelLight 设置与静止累积（alpha.20）

暂停/Options → VoxelLight 或 `/voxellight settings` 打开原版控件界面。命令树提供可搜索的选择项，`rt_accumulate spp` 有独立数字输入。界面底部显示静止累积的完成样本数和目标，每秒刷新。

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_reconstruction optix
/voxellight held_lights on
/voxellight rt_accumulate freeze off
/voxellight rt_accumulate spp 256
/voxellight rt_accumulate on
```

**alpha.20 默认是 realtime**：OptiX temporal AOV 降噪（失败回退 Vulkan 时空滤波），相机移动用 RT 世界位置重投影，不再靠静止累积。`rt_mode reference` 才使用下面的渐进累积；`rt_reconstruction vulkan` 可对照纯 Vulkan 滤波。`rt_scale 0` 是原来的至少 4×、最高 640×360 自动分辨率；`1..8` 指定线性降采样倍数。设备描述符上限和 1 GiB continuation 预算可能进一步降低实际分辨率，以 stats 的 `internalResolution` 为准。没有接入 DLSS RR。

参考模式默认目标 64 spp，范围 4–4096。默认动态模式每帧 1 个独立路径样本：逐像素线性 HDR 平均至目标，之后采用 1/目标权重的动态平均，持续追踪并更新天空、手持光、动画纹理和水波。提高目标继续增加历史计数。移动、视角、FOV/尺寸、地形/世界变化和资源重载重置历史；手持光切换/移动、太阳方向约 1°或强度约 5%、天气/入水状态显著变化也重置。`rt_accumulate reset` 手动重置，`off` 不复用历史。

`rt_accumulate freeze on` 开启显式静止快照：冻结光照/动画资产，达到目标后停止追踪。`freeze off` 返回默认动态模式，两种模式切换会清空历史。快照期间地形变化仍能触发重建。法线调试不累积，PT 关闭光栅 projection jitter。

无效路径标记 alpha=0；平均 pass 拒绝非有限样本并恢复已损坏历史，各像素 alpha 记录有效计数。`accumulatedSpp` 是最多目标帧数的计数，无效样本对应像素可能少于该值。默认动态模式会继续补样；冻结模式按帧目标停止。状态同时提供 `accumulationFrozen`、`accumulationReset`、`vulkanRtHeldEnabled/Intensity`、`vulkanRtSunDirection` 和 `emissiveTriangles`。

原生发光三角形以面积×原生发光等级建立 CDF，实际采样辐射读取 GPU Material 3/LabPBR 材质并应用 cutout/透射可见性，BSDF 命中使用对应 MIS。表最多 8192 个三角形，超限优先附近；原生等级为零但仅材质定义发光的面目前仍靠 BSDF 命中。手持 BlockItem 读取主副手较亮的原生等级，15 级映射到场景线性点光强度 20（颜色来自 light materials），独立于 local_lights 开关；强度视觉标定仍需实机。
设置原子写入 `config/voxellight/settings.json`，进入世界时按顺序回放。选择 preset 清除此前个别覆盖值。累积开关、冻结模式与目标保存，reset/诊断操作不保存。全新安装没有覆盖值时仍默认 effects off。

旧 `preset rtx_quality` 保存值迁移为 `vulkan_quality`；旧 OptiX/CUDA backend 保存值迁移为 `vulkan_pt`；旧 `rt_reference spp` 迁移为 `rt_accumulate spp`。旧参考模式/旧 pathtrace 和旧缓存、去噪开关丢弃；这些命令不再注册。OpenGL 上 Vulkan backend/preset 显示切换图形 API 的提示。

Alpha.15 手持点光不参与天空/太阳混合随机抽样，每次有效表面着色和介质事件独立连接，保持真实遮挡和平方反比衰减，离散点光不与连续 BSDF 配对 MIS。状态新增 `vulkanRtHeldItems` 与 `vulkanRtHeldPosition` 以排查未识别物品/源位置；手持支持原生发光 BlockItem，其他物品尚无发光规则。

Alpha.16 为原生火把/灵魂火把及灯笼建立独立点光提案，同一方块合并一次，采用平方反比衰减、真实遮挡和离散选择 PDF；木杆及金属模型不再整面自发光，显式 LabPBR 发光保留。其他原生发光面使用场景线性辐射标定 8，并继续面积采样及 MIS。有限距离阴影连接增加按世界坐标精度调整的接收面偏移，减少自遮挡。

如手持照明仍异常，拿着火把对准附近不透明墙面运行 `/voxellight rt_lighting_probe`，等待约 30 帧，再提供 `Vulkan RT POC GPU diagnostic` 日志。`heldIncident` 为入射 RGB/距离，`heldBsdf` 为材质 RGB/PDF，`heldHemisphere` 为着色与几何法线方向余弦，`heldVisibility` 为透过率/介质允许标志。测试机没有 RTX，CPU 输运验证不能替代真实 GPU 验收。

Alpha.17 修正 SPIR-V 原始字节地址读取：Slang 对部分 12 字节对齐的 `Load3` 生成 uint3 数组，但 std430 ArrayStride 为 16，造成手持强度 144 字节被读作 192 字节。所有地形/天空/光源三分量字节读取改为三个标量 Load；构建直接检查最终 SPIR-V，拒绝此类填充数组别名。旧 alpha.16 二进制触发检查，新二进制通过；仍保留 GPU lighting probe 供实机确认。

Alpha.18 修正整数边界发光面的方块归属：沿面法线向模型内部偏移查询，避免邻接火把误标萤石。最近 16 个原生火把/灯笼源在每次有效表面/介质事件独立连接，使用与手持相同的强度和平方反比衰减；从随机提案中移除以免重复计光，较远源仍随机采样。近距离光源密集时阴影开销会增加。

`/voxellight rt_spp 1` 到 `8` 设置每帧路径样本数，建议先试 `2`。所有样本以 dispatch Z 通道批处理，分 bank 的 continuation、一次相机上传、一套六步调度和一次 radiance resolve/copy；每个样本独立随机种子，线性 HDR 平均；与静止累积目标独立，冻结时达到目标立即停止，无历史模式只平均当前帧。提高采样数会增加 GPU 时间，该功能不等于去噪或重投影。

手持萤石等整面发光方块的虚拟点源标定从 20 降至 8，与原生发光面辐射量级对齐；火把/灯笼仍为 20，灵魂变体按原生等级和色彩缩放。点源与面源空间分布不同，近场亮度不会完全相等。

Alpha.19：虚拟火把/灯笼点源的阴影连接略过同一方块内属于该光源的外壳几何，避免火把木杆在底部投出方形自遮挡。只作用于该放置光源的可见性连接：同格实心方块、其他火把和其他光源的遮挡保留；相机和间接射线仍看见完整模型。这是虚拟点源外壳的自遮挡约定，不是面积光源软阴影。


Alpha.20：近 16 个火把/灯笼仍在本地提案表，但只连接最近两个，并用八个廉价候选的 RIS 估计其余源，最后只测试一个被选源的可见性。手持源保持独立连接。更远源继续通过全局 CDF 抽样。

动态 RT 采用原生实体、方块实体、手持物/手臂、custom quad 和 quad particle 捕获；共用 primary/indirect 场景并参与照明、遮挡与反射。当前动态模型用原生 albedo/tint 与漫反射，粒子使用 cutout，尚未迁移 glint、透明/加法粒子混合或动态模型 LabPBR 法线/金属材质。捕获、纹理 atlas、几何均有预算，溢出模型会跳过。

场景使用 256 MiB 压缩 CPU backing cache、64 MiB GPU 地形/动态 working set。每八帧稀疏 miss feedback 请求 section，最多 16 个 ray-priority 页可以进入满场景；未由服务器加载的区块仍不能凭空重建。stats 显示 backing pages/bytes、命中/淘汰计数、miss 请求和动态模型数。
