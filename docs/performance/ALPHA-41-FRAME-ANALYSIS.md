# alpha.41 frame 实测：两项回退，两项尚未生效

来源：`logs/rt-suite-1791385931979.zip`，schema 12，24 段完成，RTX 4060 Laptop / NVIDIA 591.74，1 spp。297 terrain sections，62,873,520 bytes 固定地形；动态约 198 models。这个 ZIP 的计时不能证明 DLSS RR 或 exclusive world 的收益。

## 实际执行与结论

| 项目 | 结果 | 结论 |
|---|---|---|
| Reconstruction | 每段 OptiX temporal AOV；214×120；没有 RR scope 或 DLSS fallback 字样 | RR 没有执行；不能把用户感知的画质改善归因于 RR，也不能假设像素密度增加了四倍 |
| World takeover | EXCLUSIVE 请求段仍报告 vanilla world retained，无 maintenance scope | 实际接管未发生。alpha.41 benchmark state 错把请求值当实际值，A/B 不成立 |
| FAST shadow | transport 两轮 -4.29% / -3.65%；均值 -3.97%，重复波动 0.81% | 本场景稳定更慢，默认改回 EXACT；FAST 保留对照 |
| Iterative indirect | 两轮 -23.34% / -24.69%；均值 -24.02%，波动 0.62% | 不采用；Wavefront 默认保留 |

world_takeover 首段约 6.06 ms，后续约 8.7–8.9 ms，重复波动 36.45%。同一 FULL baseline transport 从约 3.16 ms 变到约 4.45 ms。整组本就 not_comparable，加上实际没有接管，不能从这组推导 world takeover 的收益或回退。

稳定段 transport 约 4.4–4.6 ms，Primary 约 1.0 ms，B1 约 1.33 ms，B2 约 1.51 ms；FAST 的 Primary 约 1.14–1.16 ms。Iterative 间接约 4.64 ms，batch 约 5.7 ms，明显比 Wavefront 慢；计时不提供寄存器 spill 原因的证明。

OptiX exchange GPU 中位约 2.61 ms，temporal upscale 约 0.19 ms；world GPU 约 8.8 ms。客户端 frame wall 约 30.8–31.6 ms，P95 约 33.3 ms。这个 wall 包含 swapchain acquire、native update/extract、render、present 和 FramerateLimiter，不能把它全部当 CPU shading，也不能据 33.3 ms 单独确定是 VSync/30 FPS 限帧。

代表段 13：scene commit CPU 中位 5.71 ms、dynamic capture 1.65 ms；world submission CPU 8.51 ms。scope 有嵌套，不能相加；GPU commit 约 0.50 ms。host commit 和世界之外的等待/提取需继续分开测量。

原始采样 frame 窗口进一步显示：首段 53.6 FPS，此后 29.9–30.0 FPS。Minecraft 26.2 的 FramerateLimitTracker 在 InactivityFpsLimit.AFK 下，60 秒无输入触发 SHORT_AFK、上限30 FPS，600秒后10 FPS。这与站定自动测试的变化高度吻合；旧包没有 throttle reason，不能完全证明具体来源。alpha.42 测试期间每帧调用原版 onInputReceived，仅刷新活动时间，不移动相机、不改变持久 FPS/VSync 设置；避免测试自身进入 AFK 限帧。结束不再刷新。

## alpha.42 修正

1. 将 begin/tryReplaceWorld/native fallback/RT display/end 的生命周期收拢到 GameRenderer 的真实 LevelRenderer.render 调用 wrapper；LevelRenderer mixin 只提供维护 bridge。避免分散的 HEAD 与调用前后注入承担执行状态。保留成功才省略原版、失败同帧原版的规则。无法从 alpha.41 ZIP 确定具体 bypass 原因；本次增加 rtWorldReason，记录 inactive、wireframe、准备失败、display 未就绪等原因。
2. benchmark 的实际 world 状态来自该帧真实 display/replace 结果，EXCLUSIVE 请求实际仍 COMPOSITE 将不能进入有效采样。
3. Reconstruction status 记录 requested=DLSS/OptiX/Vulkan，继续保留 NGX fallback 原因。当前包没有客户端 latest.log/settings，不能断言是旧保存的 OptiX 设置还是其他控制覆盖。
4. 默认 EXACT + Wavefront。保留 FAST / Iterative A/B，但不再把同遍历/少 dispatch 当作已测赢家。
5. CPU wall 分出 acquire/extract/present/limiter，以定位 8.8 ms GPU 世界时间与约31 ms帧耗时之间的差距；只测量，不修改 VSync/FPS 上限。

## 下一次验收

替换 alpha.42，关闭客户端/驱动 VSync 与帧率上限（如需测吞吐）；显式执行 `rt_reconstruction dlss`、`rt_world exclusive`、`rt_shadow exact`、`rt_integrator wavefront`。先检查 stats 的 requested 与实际 RR 后端、SDK input/output dimensions、rtWorldReason。没有实际 RR 或 world takeover 时先提供客户端 latest.log 与 stats，不重跑完整 24 段。

确认生效后运行 `rt_benchmark frame`；结果将验证调用链修正，并定位实际等待。GPU 性能、画质和 exclusive 维护行为仍需 RTX 验收。
