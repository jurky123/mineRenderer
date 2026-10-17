# alpha.80：首两层 continuation 融合退化，不保留

2026-10-17。借鉴 Caustica Indirect 的内循环组织，仅在独立 FULL/QUERY/SER 候选中将首两次 continuation 放入同一循环，省去两者间的 PathHot/medium store-load；第二次原 dispatch 提前返回。每次 advance 后仍提交相同 output/AOV，所有材质、介质、采样、SER 和 RNG 函数不改。生产 Java 调度、Primary 和后续层不改，未叠加 alpha.78。

实际 Slang 的 65536 组状态边界测试逐位通过，覆盖 64B 热状态、RNG、0–8 层介质 enter/exit、终止/异常、AOV；这些是合成状态转换，不是 GPU 两次射线的逐命中证明。两个活跃 stage 通过 SPIR-V/ABI 校验。

| GPU P50 ms | A1 | B | A2 |
| --- | ---: | ---: | ---: |
| Transport | 3.446688 | 4.337056 | 3.465920 |
| PT | 5.413888 | 6.313664 | 5.443136 |
| World | 7.375872 | 8.221696 | 7.371648 |
| B1（候选包含两层） | 1.636928 | 4.103888 | 1.644000 |
| B2（候选提前返回） | 1.343392 | 0.011040 | 1.342624 |

三段 GPU 中位数和所有窗口样本均为 2520MHz。PT 退化 16.31%、Transport 退化 25.48%，基准漂移分别仅 0.54%/0.56%；主要工作量最大差约 0.00533%，资源状态一致。P95 也退化，不是减少一个 pass 就得到净收益。

两份端到端捕获 26 层有限、客户端正常退出 0。第二份 ENDPOINT_FAST 捕获时 scene signature/sections 已变化，因此它不作为与第一份同几何图像配对，也不影响此前稳定计时窗口的状态检查。没有继续做移动画质验收，因为性能已明确失败。

结论是这项融合在当前 FULL 实现中有负收益，不能据此直接认定寄存器 spill 或某项硬件 stall；这些指标仍不可用。候选留在隔离目录，不修改生产源或已安装客户端。alpha.73 画质和 alpha.67 shader 继续保留。

证据：`outputs/alpha.80` 的 candidate.patch、module-validation、shader-validation、self-comparison、workload-comparison、e2e-validation 和原始 suite；复现脚本位于 workspace `work/alpha80-*`。
