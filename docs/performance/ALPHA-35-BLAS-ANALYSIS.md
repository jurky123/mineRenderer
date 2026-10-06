# alpha.35 BLAS A/B 实机结果

RTX 4060 Laptop，alpha.35，8 段两轮 ABBA，内部 214×120、1 spp、299 个静态 section，同一 terrain signature。两侧 Query、Fixed、RIS、OMM/SER off，仅改变 scene update。8 段均 valid、无中断、无 skipped queries；109,568 次 opaque replay 无 mismatch。此 replay 不构成动态 cutout 动画/画质验证。

## BLAS 优化确认有效

各侧四段 GPU median 的均值：

| scope | legacy ms | optimized ms | 下降 |
| --- | ---: | ---: | ---: |
| scene commit | 3.155 | 0.377 | 88.04% |
| native BLAS | 2.979 | 0.208 | 93.00% |
| BLAS submit 外部跨度 | 2.985 | 0.215 | 92.81% |
| TLAS | 0.079 | 0.086 | 增加 0.006 ms |
| geometry copy | 0.022 | 0.024 | 增加 0.0015 ms |

自动比较对两轮分别计算比例后平均，给出 scene commit 降幅 **88.09%**，两轮为 90.73%、85.45%，重复波动 3.61%。表格则对四个 block medians 取均值后计算比例，所以其 88.04% 与自动汇总略有差别。

legacy 的 native BLAS 与外部 submit 时间均接近 3 ms，说明上一轮外部计时没有把主要问题凭空夸大。新的 native 时间戳支持瓶颈确实位于 AS 更新及其依赖执行中；timestamp span 仍含命令内依赖等待，不能等同于隔离的单个 refit kernel 时间。

## 收益来源：scratch 依赖，而非跳过动画

动态分组保持 218，主 BLAS refit 每次 commit 仍约 154–156，triangle 更新数约 3,000。静态 build 与单独 opaque 更新的计数差均为零。

- legacy：每次 commit 约 156–158 个 scratch slices，同样约 156–158 个 scratch barriers。
- optimized：slice 数基本相同，**barriers 降至每次 commit 3 个**。
- 两侧 `blasAttributeOnly` 差值均为 **0**；几乎全部变化记录为 position update。
- 两侧 alive-path fractions 基本一致，例如 B1 均约 0.89091，B2 均约 0.51723。

这直接支持主要收益来自独立 AS 使用不重叠 scratch 区间，消除逐对象复用同一区间的串行依赖；不是减少动态模型、跳过必要 refit 或明显提前终止路径。属性-only 优化在这个场景没有命中，不能给它单独归因性能收益。

常驻 scratch 从 legacy 0.5MiB 增至 optimized 16MiB，增加约 **15.5MiB**。这仍是 scene-owned arena，不是每 BLAS 永久 scratch。新增空间换取依赖减少，在本场景收益合理。

## 不应把 88% 当作整帧收益

各侧四段 transport median 均值为 5.079/5.724 ms，即 optimized 一侧的 transport 反而高 12.7%。分轮看，第一轮 transport 是 4.563/4.237 ms，第二轮是 5.595/7.212 ms；material assets 和 OptiX exchange 也在第二轮 optimized 段上升。这不是单纯 BLAS 指标中的重复噪声，说明测试过程中其他成本发生了变化。没有设备 clocks、温度、功率或焦点数据，不能断定由热降频、功耗分配或后台负载中的哪一项引起，也不能排除新的调度对后续成本的影响。

额外按每帧汇总 `.passes.csv` 中 parent_scope_id=0 的有效 GPU scopes，排除 counter frames，避免把 BLAS/TLAS 等子 scope 重复相加。各段捕获到的顶层 scope 总和的 median：

| 轮次 | A1 | B1 | B2 | A2 | ABBA 均值比较 |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 10.786 | 7.495 | 6.739 | 9.816 | 约下降 30.9% |
| 2 | 11.658 | 11.024 | 11.178 | 11.788 | 约下降 5.3% |

该总和只代表捕获到的 GPU scopes，不含未记录的渲染工作、CPU、帧率上限与等待，不能称作完整帧时间或据此推算实际 FPS。两轮都节省了这一捕获总和，但收益幅度不稳定，尚不能给出可靠统一的整帧提速百分比。

## 判断

保留 optimized：BLAS 子系统显著受益，先前约 3 ms 的主要串行工作已降到约 0.2 ms，继续优化这部分的绝对空间已经很小。无需为了缩小 16MiB arena 或继续追低 AS 时间而立即改算法。

下一阶段更应关注 transport 与重建，以及在固定客户端帧率、供电和前台状态下复核完整帧时间。若只是决定是否保留当前 BLAS 实现，本份结果已经足够；若要承诺最终 FPS 提升，需要稳定的整帧实测。动态动作、手持物、粒子和切换纹理的视觉仍需客户端观察，日志本身不能代替这些检查。
