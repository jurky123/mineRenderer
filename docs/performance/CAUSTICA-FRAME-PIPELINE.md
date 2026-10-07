# alpha.41：世界接管与 Vulkan DLSS RR

本轮借鉴 Caustica 的执行与重建方式，独立实现，不复制其代码。目标是消除原版世界绘制后再覆盖的固定开销、把低分辨率 OptiX beauty 降噪与放大改成 Vulkan 原生联合重建，并提供单次 dispatch 间接路径对照。GPU 提速与画质仍待 RTX 实机验收，不承诺达到 Caustica 的帧率。

## 执行链

```text
LevelRenderer HEAD
  → 准备 RT 场景/动态模型/环境
  → Primary → Wavefront 或 Iterative indirect → Resolve
  → DLSS RR Vulkan → display composite
  → 成功：跳过原版世界 frame graph
  → 未就绪/失败：本帧继续原版世界
```

接管保留原版相机区块维护、脏 section 编译、GPU upload、occlusion 更新与加载完成 callback。HUD 路径保留；手持几何只有在 RT capture/display 成功后才替代原版。`rt_world composite` 保留旧世界绘制后覆盖，用于同版本 A/B。

Realtime 默认 `rt_shadow fast`：单次 traversal，opaque 接受并终止，cutout alpha 检查，玻璃/水累计近似界面透射。保留当前介质到最近出口的吸收；带 OMM 的 flame 自遮挡例外使用原精确路径。它是有偏实时近似，不等价于 reference 的有序多界面输运。Reference 强制 EXACT。

`rt_integrator iterative` 将 B1–B5 在一个 raygen invocation 内循环，复用同一个 advance、随机状态、MIS、介质和 RR，尾部一次存储。Wavefront 继续默认，避免未经 GPU 验证的寄存器压力回退；Reference 强制 Wavefront。材质/最大路径深度没有改成 Caustica 的完整算法，也没有实现新的 Primary visibility/shading split 或 B0 cache。

## DLSS RR 契约

默认 realtime 优先 RR Performance；SDK 查询实际 input resolution，不再把 `rt_scale 0` 的旧 4× 自动输入当成 DLSS Performance。RR 成功时直接生成 display-resolution HDR，绕过 OptiX/CUDA exchange 和旧 upscale。无帧生成。

- 官方 NVIDIA/DLSS SDK：`374959484e79a640feaba44c93ac8cfb0a03f5b5`，RR runtime 310.9.1；Windows x86_64 / Linux x86_64 bridge 与 runtime 随本 mod 打包，许可证位于 jar 的 `voxellight/dlss/`。
- NGX 所需实例/device extensions 在 Vulkan 创建之前查询并启用受支持项；运行时能力或 NGX 初始化/evaluate 失败时记录原因并回退原 OptiX/Vulkan 重建。
- 输入：linear HDR beauty、反向 Z hardware depth、像素单位 previous-current motion、世界法线/roughness、diffuse/specular albedo，提供 world→view/view→clip 矩阵、frame-global Halton jitter、history reset。
- 深度与 motion 使用未 jitter 的 clip 矩阵；天空按方向重投影。相机变化、资源与场景 epoch 的历史失效继续传递。
- 当前没有 specular reflection motion；玻璃后的 guide endpoint 没有跟随所有透射/反射链。动态 guide 受现有捕获覆盖限制。因此“接入 RR”不代表已经达到 Caustica 的镜面/玻璃稳定性与清晰度。
- resize/reset/destruction 才等待 device idle，正常 evaluate 在 Minecraft 原 Vulkan queue 上记录与提交，不逐帧等待或 CPU 读回。

RR 活跃时以 SDK input dimensions 为准，`rt_scale` 不覆盖它。要使用原重建尺寸，执行 `rt_reconstruction optix` 或 `vulkan` 再设 `rt_scale 2`。stats 必须显示 `DLSS RR Performance; Vulkan native`，仅执行命令不能证明初始化成功。

## 客户端验收

选择 Vulkan 图形 API，替换旧 jar，进入已加载世界：

```text
/voxellight rt_backend vulkan_pt
/voxellight rt_mode realtime
/voxellight rt_spp 1
/voxellight rt_reconstruction dlss
/voxellight rt_world exclusive
/voxellight rt_shadow fast
/voxellight rt_integrator wavefront
/voxellight rt_realtime full
/voxellight rt_omm off
/voxellight rt_ser off
/voxellight stats
/voxellight rt_benchmark frame
```

`frame` 默认每段采样 10 秒，24 段、三组双轮 ABBA，约 7 分钟加管线初始化。站定、关闭菜单，执行过程固定太阳/天气/手持/水面控制与 terrain snapshot。结束/停止恢复原执行设置；实际 dynamic geometry 仍可能活动。可用 `rt_benchmark frame 4` 缩短采样，但足够样本覆盖与稳定性门限不降低。

| 比较 | A → B | 判定 scope |
|---|---|---|
| world_takeover | composite → exclusive，均 EXACT | vulkan_world_total |
| realtime_shadow | EXACT → FAST，均 exclusive | vulkan_rt_batch_fixed |
| iterative_indirect | Wavefront → Iterative，均 FAST | vulkan_rt_batch_fixed |

schema 12 导出 ZIP：`benchmark-results/voxellight/rt-suite-*.zip`。每段 `.passes.csv` 保留 GPU/CPU scope，`.cpu_timings.json` 单独提供 maintenance 和 client_render_frame_wall 等中位/P95；后者是包括提交/呈现等待的墙钟耗时，不冒充 GPU 时间。world total 包含世界绘制、transport、重建、合成与维护的 GPU 提交范围，不等于包含 HUD/present 的完整 GPU 帧。

检查实际 RR 后端和 input/display dimensions；对照同 GPU、同输出分辨率、同场景与视角、关闭帧生成/VSync/FPS 上限。既要看世界总时间，也要看 RR、scene commit、primary、indirect、CPU wall 与重复段波动。快速阴影画质需另行检查薄玻璃、多层玻璃、水下、树叶与火焰；接管需检查出生加载、区块移动、编辑、实体、手持和 HUD。自动计时不代替视觉验证。

## 构建依赖

`./gradlew build clientKit` 同时构建两平台 bridge，运行 Java、Minecraft GLSL、SPIR-V 与生产 Slang CPU 回归。本轮 348 项 Java 回归零失败，80 个 SPIR-V stages / 48 份 shader source hashes 已核对；额外执行实际 GLSL 的 RR motion/depth CPU fixture 与打包 JNI 的无设备 extension query。没有 NVIDIA GPU 的 host 不验证 NGX evaluate 或 GPU 性能。

`tools/build_dlss.py` 使用：

- `DLSS_SDK`（默认 `/tmp/voxellight-dlss-sdk`）：官方 https://github.com/NVIDIA/DLSS ，checkout 上述固定 commit，include 和 lib 子目录完整。
- `VULKAN_HEADERS`（默认 `/tmp/voxellight-vulkan-headers`）：官方 https://github.com/KhronosGroup/Vulkan-Headers ，本轮使用 `c46850864f4661461b0f6cb9922c058ffea4915e`。
- `JAVA_HOME`：Java 25；Linux `c++`；既有 `MINGW_ROOT` LLVM-MinGW clang/lld。
- `MSVC_SDK`（默认 `build/msvc-sdk`）：Windows CRT 与 Windows SDK，使用 clang 的 MSVC target 链接官方 NGX static library，不能用 GNU CRT 直接链接。可使用 xwin 0.10.0 的 `--accept-license splat --output build/msvc-sdk` 下载官方 SDK。构建依赖与临时输出不得提交仓库。

可重建源是本项目 JNI adapter；随包 NVIDIA RR runtime 保持原文件和 LICENSE，不打包 Frame Generation 或外部 Fabric mod。
