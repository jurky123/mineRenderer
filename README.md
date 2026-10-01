# mineRenderer / VoxelLight

VoxelLight 是纯客户端 Fabric 光照引擎 mod：复用 Minecraft 原生渲染器，以缓存阴影、统一体素场景和渐进更新 GI 改善方块世界光照。

当前处于设计评审阶段，尚无源码、构建工程或可安装产物。目录/仓库名称为 `mineRenderer`，设计中的功能名称保留 `VoxelLight`；mod ID 和 Java 包名在首个原型中锁定。

## 文档

- [原始 v0.2 设计文档](VoxelLight_Design_v0.2_Native_Vulkan.docx)：保留原件。
- [v0.2 可搜索文本](docs/DESIGN-v0.2.md)：按段落提取，表格布局请看 DOCX。
- [设计评审](docs/REVIEW.md)：已核验依据、工程缺口和建议决策。
- [实施计划](docs/PLAN.md)：修订后的依赖顺序、实验和验收标准；后续实施按此计划推进。

## 边界与开发约定

目标首先验证 Minecraft 26.3 / Fabric / 原生 Vulkan；具体游戏、Java、Loader、Loom、Fabric API 版本及映射命名必须通过原型锁定，26.4 快照只跟踪，不承诺兼容。

本项目独立实现客户端世界光照，不属于 MineUI 界面 API、MineAudio 音频 API 或 MineDisplay 展示业务，无须服务端插件。需要其他项目新增能力时先提出需求。

先采用单个 Fabric 构建工程和功能包，版本相关 hook 集中在适配包。出现真实复用需求后再拆模块，不预建通用 renderer 框架。计划中的构建入口为 `./gradlew build`，当前尚未创建，不能用于验证。

性能预算是实验目标，尚无跑分。先验证 renderer 接入和缓存阴影，达到阶段门槛后再进入 GI。提交源码时同步记录构建、相关测试和游戏内验证结果。

独立 Git 仓库使用 `main` 分支；不改汇总仓库子模块指针。运行世界、日志、构建产物和本地代理配置不进入版本控制。当前未配置远程地址。
