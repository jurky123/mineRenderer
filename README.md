# mineRenderer / VoxelLight

VoxelLight 是纯客户端 Fabric 光照引擎 mod：复用 Minecraft 原生渲染器，以缓存阴影、统一体素场景和渐进更新 GI 改善方块世界光照。

当前是 **Minecraft 26.2 的 P0 接入原型**：已实现客户端入口、默认关闭的颜色复制/深度诊断 pass、backend 检测、非阻塞 GPU timestamp 和 CSV 导出。尚未实现阴影、GBuffer 材质扩展或 GI。目录/仓库名称为 `mineRenderer`，功能名称为 `VoxelLight`，mod ID 为 `voxellight`，Java 包为 `com.voxellight`。

## 文档

- [原始 v0.2 设计文档](VoxelLight_Design_v0.2_Native_Vulkan.docx)：保留原件。
- [v0.2 可搜索文本](docs/DESIGN-v0.2.md)：按段落提取，表格布局请看 DOCX。
- [设计评审](docs/REVIEW.md)：已核验依据、工程缺口和建议决策。
- [实施计划](docs/PLAN.md)：修订后的依赖顺序、实验和验收标准；后续实施按此计划推进。
- [26.2 接入能力与验证记录](docs/INTEGRATION.md)：实际 API、资源生命周期和尚未通过的实机门槛。
- [客户端安装与诊断](docs/INSTALL.md)：安装前置、命令和 smoke checklist。

## 边界与开发约定

按用户要求，当前开发基线改为 Minecraft **26.2** / Java **25** / Fabric Loader **0.19.5** / Fabric API **0.160.0+26.2** / Loom **1.17.21** / Gradle **9.5.1**。使用 26.2 官方未混淆类名，无额外 mappings。26.3/26.4 不属于当前支持范围；原始设计文档作为历史保留。

本项目独立实现客户端世界光照，不属于 MineUI 界面 API、MineAudio 音频 API 或 MineDisplay 展示业务，无须服务端插件。需要其他项目新增能力时先提出需求。

采用单个 Fabric 构建工程和功能包，版本相关 hook 集中在 `com.voxellight.adapter`，可独立测试的采样数据在 `com.voxellight.debug`。出现真实复用需求后再拆模块。

## 构建与使用

```sh
./gradlew build clientKit
```

mod：`build/libs/voxellight-client-26.2-0.1.0.jar`；安装包：`build/distributions/voxellight-client-kit-26.2-0.1.0.zip`。安装包只含本 mod 和安装说明；Fabric Loader/API 按安装文档配置。

进入世界后使用 `/voxellight mode color` 检查原画面复制，`/voxellight mode depth` 查看世界深度，`/voxellight mode off` 恢复原画面。`/voxellight status` 查看状态，`/voxellight export` 导出最近最多 14,400 个 pass 样本。仅 Vulkan 执行诊断，OpenGL 保留 vanilla。所有命令均在本地执行，无服务端要求。

`build` 包含采样逻辑测试、26.2 字节码 hook 契约检查和使用 Minecraft Vulkan GLSL 编译器生成 SPIR-V 的测试。尚未在真实 GPU 上启动游戏；构建通过不代表 Vulkan pipeline、画面和 GPU timing 已实机通过。

性能预算是实验目标，尚无跑分。先验证 renderer 接入和缓存阴影，达到阶段门槛后再进入 GI。提交源码时同步记录构建、相关测试和游戏内验证结果。

独立 Git 仓库使用 `main` 分支；不改汇总仓库子模块指针。运行世界、日志、构建产物和本地代理配置不进入版本控制。当前未配置远程地址。
