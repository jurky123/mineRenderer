"""Produce a concise report from immutable formal summaries and diagnostic evidence."""
import argparse,json,pathlib,statistics

def main(root):
 out=root/'outputs/alpha.50';formal=out/'正式测量';comparison=json.loads((out/'three-pair-stage-comparison.json').read_text(encoding='utf-8'))
 if comparison['rejected_pairs']:raise ValueError('Unexpected rejected formal pairs')
 cameras=('terrain','complex_building','multi_light','glass_water','dynamic_entity')
 lines=['# alpha.50 — VoxelLight 与 Caustica 性能剖析','',
 '本轮只增加测量、诊断与分析，没有实施算法性能优化，也没有验收“已加速”或“达到 Caustica”。三轮独立进程配对、五个场景均通过原始观测校验。最主要的稳定差距位于 PT 输入生成；RR 不是当前原生默认设置下的主要耗时差距。','',
 '## 测量口径','',
 '正式结果来自 round8、round9、round10，共六个独立客户端和三十个场景窗口。每场景预热 30 秒，实际视角与投影矩阵、冻结世界时钟、固定实体集合均有开始/结束记录。1048×672 输出、524×336 RR Performance 输入、1 SPP、RR 就绪、FG 关闭、VSync 关闭，最大 radiance bounce 为 5。vanilla frameLimit=260 为原始设置值，不能当作实际 260 FPS 限制。两边使用原生材质和光照；Caustica 太阳倾斜约 30°，VoxelLight 太阳竖直，故这不是等照明工作量实验。','',
 '采用 GPU timestamp，CPU 记录耗时单列。父子 scope 不相加；异步 Scene Build 的队列记录、提交等待和完成等待分列，不与 graphics scope 重叠相加。TOP/BOTTOM 时间是近似逻辑范围，包含流水线因素，不是硬件校准的关键路径；PT ready 范围是互不包含的 producer scope 之和，不包括 scope 之间空隙。[Khronos timestamp 示例](https://docs.vulkan.org/samples/latest/samples/api/timestamp_queries/README.html)、[Vulkan 查询规范](https://docs.vulkan.org/spec/latest/chapters/queries.html)。','',
 '没有新增正式路径 GPU barrier、submit 或同步等待。VoxelLight 原有每八帧反馈计数帧及受影响相邻 CPU interval 从正式时间统计中排除。仪器本身并非零开销；缺失 timestamp 不用 CPU 时间补齐，缺失计数不写成零。','',
 '笔记本 GPU 在持续负载下存在热降频记录；这些结果代表当前机器持续运行的默认表现，不能证明固定频率下的 shader 每 ray 成本。VoxelLight round10 有明确的遥测缺口，原始 GPU 时间记录仍有效；缺失时段频率和热状态未知。','',
 '## 三轮正式结果','',
 '下表为每次独立运行的 P50/P95 再取三轮中位数，单位 ms。帧不是独立实验样本，未将所有帧混合推导置信区间。配对差值及跨轮范围另见 three-pair-stage-comparison.json/.csv。','',
 '| 场景 | 模块 | Caustica P50 / P95 | VoxelLight P50 / P95 | 配对 P50 差中位数 | 跨轮差范围 |','| --- | --- | ---: | ---: | ---: | --- |']
 for camera in cameras:
  for stage in ('world','pt.ready_rr_inputs','primary_guides','transport','rr'):
   key='gpu|graphics|'+stage
   def value(renderer,q):return statistics.median([json.loads((formal/f'round{i}'/renderer/f'{camera}-summary.json').read_text(encoding='utf-8'))['stage_summary'][key]['timing'][q] for i in (8,9,10)])
   row=next(x for x in comparison['comparisons'] if x['scene_id']==camera and x['stage']==key)
   lines.append(f"| {camera} | {stage} | {value('caustica','p50_ms'):.3f} / {value('caustica','p95_ms'):.3f} | {value('voxellight','p50_ms'):.3f} / {value('voxellight','p95_ms'):.3f} | {row['median_delta_ms']:.3f} | {row['delta_range_ms'][0]:.3f}–{row['delta_range_ms'][1]:.3f} |")
 lines+=['','Primary/Guides 与 Transport 的边界对 pass 融合敏感：Caustica 的 Indirect 内重新追踪 bounce0，VoxelLight 的 Primary 已执行首层材质/直接光/续路径工作。不能把单个 pass 名称视为同等算法工作。共同 PT ready 范围更适合总量比较。','',
 '### 非等价模块单列','',
 '下列数据来自 round10 的代表性窗口，不能当作三轮配对模块差值；用来核验每模块量级与测量覆盖。未定义共同边界的模块不强行相减。','',
 '| 场景 | Renderer / domain / queue / scope | P50 ms | GPU 有效帧覆盖 |','| --- | --- | ---: | ---: |']
 details=[('multi_light','caustica','gpu|graphics|exposure'),('multi_light','caustica','gpu|graphics|bloom'),('multi_light','caustica','gpu|graphics|display'),('multi_light','caustica','gpu|graphics|output'),('multi_light','voxellight','gpu|graphics|unmapped.vulkan_pt_composite'),('multi_light','voxellight','gpu|graphics|unmapped.vulkan_rt_material_assets'),('multi_light','voxellight','gpu|graphics|unmapped.vulkan_rt_environment_prepare'),('dynamic_entity','caustica','gpu|graphics|scene.entity_blas'),('dynamic_entity','caustica','gpu|graphics|scene.tlas'),('dynamic_entity','caustica','gpu|compute|scene.async_build_batch'),('dynamic_entity','caustica','cpu|worker|scene.cpu_job_queue'),('dynamic_entity','caustica','cpu|worker|scene.cpu_job_record'),('dynamic_entity','caustica','cpu|worker|scene.cpu_build_completion_wait'),('dynamic_entity','voxellight','cpu|graphics|scene.update'),('dynamic_entity','voxellight','gpu|graphics|scene.update')]
 for camera,renderer,key in details:
  stage=json.loads((formal/'round10'/renderer/f'{camera}-summary.json').read_text(encoding='utf-8'))['stage_summary'].get(key)
  if stage and stage['timing']:lines.append(f"| {camera} | {renderer} / {key.replace('|',' / ')} | {stage['timing']['p50_ms']:.4f} | {stage['coverage']:.1%} |")
 lines+=['','不同 queue 的 GPU 时间与 worker 等待不得与 World 相加。CPU scene.update 是其覆盖的调用范围，不能代表全部模型准备。异步 CPU job 以 completion-frame 关联，不是 GPU wall-time 关键路径。CPU submit、Present 和全程临界等待尚未建立同等范围，保持未知；数据不支持在当前 PT 大差距下先把它们当主因。后处理边界不同，但已测代表性 pass 的量级远低于 PT 差距。','',
 '## 工作量诊断','',
 '计数使用单独原子计数 shader bank，formalTimingEligible=false，不能用诊断 FPS 或时间替代正式基线。Radiance 统计实际 tracing 调用，包括 Caustica 重复 camera-path traversal；visibility 与 RIS candidate 独立。材质统计真实 surface evaluation 调用；两边 BSDF eval 覆盖不同，所以 Caustica emitter_front_brdf_evals 与 VoxelLight bsdf_evals 不直接相除。AS 为 CPU 记录的 build-info 条目数，不是 GPU 完成数量或耗时。','']
 workload=out/'计数诊断/workload-comparison.json'
 if workload.exists():
  data=json.loads(workload.read_text(encoding='utf-8'))
  lines+=['| 场景 | 计数 / 帧中位数 | Caustica | VoxelLight | VL / C |','| --- | --- | ---: | ---: | ---: |']
  for camera in cameras:
   pair={r['renderer']:r for r in data if r['scene_id']==camera}
   for name in ('radiance_traces','visibility_traces','material_surface_decodes','ris_candidates','ris_positive_candidates','ris_selected'):
    c=pair['caustica']['counters'][name]['median_per_frame'];v=pair['voxellight']['counters'][name]['median_per_frame']
    lines.append(f'| {camera} | {name} | {c:,.0f} | {v:,.0f} | {v/c:.2f} |' if c and v is not None else f'| {camera} | {name} | {c} | {v} | 未定义 |')
  lines+=['','有效重跑中，VoxelLight radiance tracing 约为 Caustica 的 0.63–0.78 倍，visibility 约为 1.46–2.90 倍；多光源材质解码约为 1.06 倍。证据不支持“主要因为 radiance 路径更多”。visibility 工作增加支持先调查透明阴影、多种光连接与重复可见性，再配合每 ray 的计算/状态访问分析；数量差并不能独自解释全部时间差。','',
          '源码可确认一项结构差异：Caustica trace.slang 的 visibility() 计数后执行一次 shadow TraceRay，以 any-hit 处理 cutout/透明/水。VoxelLight material_transport.slang 的 EXACT visibility() 则通常先 opaqueBlocked()（当前 QUERY），未遮挡且场景存在非 opaque 几何时继续 traceSurface()，最多迭代 24 个边界；火焰源等分支可跳过 opaque 预测。因此一次 light connection 不等于一次 VoxelLight visibility traversal。多光源每帧 opaque 预测约 527,011 次、总 visibility traversal 740,129 次；Caustica 总量约 507,090。其余约 213,118 次不能全部称为透明命中，其中包含 miss、跳过预测的连接和多边界续查。','',
          '最小验证实验应在静态冻结、同光照、同源材质上分别回放空路径、opaque 命中、cutout 和多层玻璃/水 shadow rays，比较 exact 两步方案与保持 RGB extinction/Fresnel 的单 traversal 方案；同时记录连接数、预测数、非 opaque traversal、边界次数和图像误差。只有耗时下降且透射/遮挡质量验收通过后才能建议替换。现有 opaque Query/Trace replay 只验证 opaque 子问题，不足以证明透明方案等价。','',
          '| 场景 | Renderer | BLAS build / update 每记录帧 | TLAS build / update 每记录帧 |','| --- | --- | ---: | ---: |']
  for camera in cameras:
   for r in (x for x in data if x['scene_id']==camera):
    a=r['as_counts']['per_recording_frame']
    lines.append(f"| {camera} | {r['renderer']} | {a['blas_builds_recorded']:.3f} / {a['blas_updates_recorded']:.3f} | {a['tlas_builds_recorded']:.3f} / {a['tlas_updates_recorded']:.3f} |")
  lines+=['','两边 AS 导出 enabled=true 且 dropped_events=0，故稀疏记录中的无指令帧可以算作 0。VoxelLight 静态三个场景无逐帧 AS 更新，动态实体每帧约六条 BLAS update、两条 TLAS update；Caustica 存在每帧一条 TLAS build。不能仅用条目数断言 update 更快，也不能归因主 PT 时间差到 AS。']
 else:lines+=['VoxelLight 第一次新增计数缺少逐帧重置，输出累积量，已整套排除并保留到 排除数据/voxellight-cumulative-work-counters。修复仅作用于诊断缓冲区，正式测量基线未改变；有效重跑结果仍待归档。']
 lines+=['','## 差异 → 假设 → 证据 → 最小实验','',
 '| 差异 | 原因假设与当前证据 | 最小验证实验 | 下一步优化条件 |','| --- | --- | --- | --- |',
 '| Transport 显著更慢 | 多光源差约 8.6 ms；wavefront 多次 dispatch、完整路径状态读写和丰富 BSDF/光照可能增加成本，寄存器/纹理/遍历尚无硬件证据 | 在相同源二进制上采集 bounce1–5 的 Nsight timeline；再做相同光照/关闭单一采样功能的独立 A/B | 若 Ray 增多先调整 visibility/path budget；若 Ray 接近再调查每次材质与 traversal 成本 |',
 '| Primary/Guides 显著更慢 | 三轮重复；pass 融合和首层照明职责不同，不能直接认定 guide 解码导致全部差距 | 相同场景对照 endpoint 与单独 guide 路径，记录 guide tracing 和真实 material decode；保持 RR 输入有效 | 证实重复首命中或额外 guide 材质解码后才实现复用/快路径 |',
 '| GPU Scene / CPU Scene | 已有独立 queue 时间和 AS 指令计数；数量不等于成本，异步重叠不可累计 | 动态实体固定/运动配对，关联 AS record frame、compute completion、等待与 graphics timeline | 确认 build/refit 或等待占主导后才改异步准备/共享/更新策略 |',
 '| RR 差小，整帧差大 | 正式 RR 差仅约 0.05–0.08 ms，PT 差约 5.8–12.8 ms；目前不足以把 RR 当首要优化方向 | Nsight 检查 submit、空隙、Present 等待是否解释剩余差额 | 若 GPU pass 总量接近而帧间隔仍差，再改帧调度与同步 |',
 '| 最终清晰度不同 | 已捕获两边 HDR/guide/RR；方向、色域、曝光和照明尺度原先不统一，旧阴影 ROI 无效 | 先同光照/材质/时间同视角捕获稳定帧序列，再比较 PT 输入与 postRR 的同一纹理频谱/边缘 | PT 输入已丢细节则查材质采样；只有 RR 后丢失再查 guides、motion、jitter、重建契约 |','',
 '## Nsight 与硬件限制','',
 '对 Transport 与 Primary/Guides 做了独立 counter-free、formalTimingEligible=false 的 Nsight 采集。Python 子进程方式和直接 Java 未启用 layer 的尝试无 Vulkan 事件，均排除。仅在诊断进程树中显式启用安装目录内 VK_LAYER_NV_nsight-sys 后成功；没有修改全局 Vulkan 注册项。随后在实际 benchmark SAMPLE 后采集两秒。原始 .nsys-rep、SQLite、启动身份、错误与分析 JSON 保存于 nsight/。','',
 '两秒 trace 的每个主要阶段有 130 个 CPU begin marker、114 个 GPU begin marker，覆盖率约 87.7%；排除 visibility replay、要求阶段序列完整后剩 99 组。工具仍报告 8192 GPU timestamp 上限及部分事件丢失，所以不能据此还原完整关键路径、提交成本或全 GPU 空闲率。十秒 trace 也保留，但不以其缺失事件估计全帧。','',
 'Nsight 2024.5.1 导出的 GPU event 主要是 QueueSubmit 和 DebugUtils begin；没有单独 TraceRaysKHR workload。begin event 自身的约几微秒时长不是 ray shader 执行时间。下表为相邻 GPU begin 标记之间的间隔，包含实际执行、依赖调度和范围之间工作；不能把它叫作 idle gap 或独立内核时间。[NVIDIA 分析文档](https://docs.nvidia.com/nsight-systems/AnalysisGuide/index.html) 区分 API wall time 与 GPU workload。','',
 '| GPU begin 边界 | 短窗口 P50 ms | 解释 |','| --- | ---: | --- |',
 '| Primary → bounce1 | 3.773 | 首层工作加依赖调度 |',
 '| bounce1 → bounce2 | 5.296 | 第一续层工作加依赖调度 |',
 '| bounce2 → bounce3 | 4.233 | 第二续层工作加依赖调度 |',
 '| bounce3 → bounce4 | 0.373 | 尾部逐层工作 |',
 '| bounce4 → bounce5 | 0.118 | 尾部逐层工作 |',
 '| bounce5 → resolve | 0.070 | 尾部逐层工作 |',
 '| bounce1 → resolve | 10.107 | 全续层范围 |',
 '| Primary → RR | 14.603 | PT producer 总范围，含间隙 |','',
 '这将最小实验优先级收敛到首层材质/直接光和 bounce1、bounce2。单靠减少三个尾部 pass 不足以解释约 8.6 ms Transport 差距；五 pass 的数量本身不构成主要原因证据。短窗口总 PT 范围与正式多光源约 14.8 ms 方向一致，但诊断频率、layer 开销和 scope 口径不同，不替代三轮正式基线。','',
 '硬件 GPU metrics 当前返回 ERR_NVGPUCTRPERM；pipeline executable 查询返回零条记录。因此寄存器、spill、cache miss 和 occupancy 当前未知，不能写成 0 或宣称已确诊。硬件层精细归因尚未完成；下一阶段若需要区分 BSDF 算术、traversal 与纹理延迟，需获得硬件计数权限或可用的 Nsight Graphics shader profiler。','',
 '## 图像链','',
 '首次图像链验证/normalized 保存工作色域转换、Caustica 去预曝光及统一行方向后的 .npy、HDR 预览和检查 JSON。两边 raw normal 对齐的平均绝对误差约 0.00233，错误翻转单边后约 0.314；Caustica HDR 与同帧 final 的亮度相关约 0.998。raw final 本身也倒置，预览统一翻转。两边原生 radiance 尺度不同，因此预览分别曝光，但各自 pre/post RR 使用同一曝光；这不是绝对亮度对比。','',
 '现有 VoxelLight endpoint ZIP 含 13 个浮点层；已归档同一客户端回调请求的原生最终 PNG，但 HDR 与最终显示的实际读回不保证同一帧。Caustica 提供同完成帧的 raw final。两边同视角链均可检查，但不能据此给出严格逐帧 PT→RR→最终显示的因果清晰度结论。所有已验证浮点层有限；单帧噪声、不同太阳与材质、不同 jitter 仍限制跨 Renderer 清晰度评分。','',
 '## 验证与可追溯性','',
 '计时归一化 9 项、工作量归一化 4 项测试通过；默认 shader 验证显示 VoxelLight 110 项字节一致，Caustica 27 项剥除调试后规范化一致。诊断清零修复与默认关闭的可选 Nsight 标签 Java 编译通过。没有完整重建 VoxelLight 原生 SDK，native DLL 保留原始 SHA，不代表完整发布构建通过。','',
 '每个正式归档包含原始 CSV、process.json 的 PID/创建时间、真实相机/焦点/时钟、renderer SHA、source proof、fixture SHA 和 observation-validation.json。source_commit 只是基础提交，工作区有未提交修改；source proof 和产物 SHA 共同标识测试源码。正式 VoxelLight SHA 为 a2b79cdeaa95d00139d9617d65e810cbff091223c7639b6c8c47e9db82c03768，Caustica 为 e5014a1c7f7fe22734238f7cf0f3e5f5d4796193b0be60eed3b6da4ce383aa48。','',
 '未聚焦的 round9 Caustica 首次尝试、累积计数诊断、历史单轮/错误方向分析均排除。有效 round9 为重新启动的独立进程。正式 GPU scope 差距已重复确认，但每模块“为什么慢”的因果闭环仍须计数、Nsight 与最小 A/B 实验；本轮不据此验收速度改善。','']
 (out/'VOXELLIGHT-VS-CAUSTICA-PROFILING.md').write_text('\n'.join(lines),encoding='utf-8')

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('workspace',type=pathlib.Path);main(ap.parse_args().workspace.resolve())
