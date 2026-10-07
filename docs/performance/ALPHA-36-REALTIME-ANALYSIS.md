# alpha.36 第三轮实机分析与 alpha.37 修复

数据来自用户上传的 `rt-suite-1791350713155.zip`。24 段均完成且 workload valid，214×120、1 spp、295 个固定地形 section；动态模型与光照仍实时变化。

| 比较 | transport 改善 | 重复波动 | 判定 |
|---|---:|---:|---|
| FULL → CACHE | -0.40% | 1.97% | within_variation |
| FULL → SPARSE | -2.05% | 1.72% | within_variation |
| FULL → CACHE_SPARSE | 无可用整体收益 | 17.68% | not_comparable |

负数表示候选更慢。前两项没有稳定加速，组合项不能据此得出收益。cache 候选和组合候选的 queries/hits/trained/probes 均为零；pure sparse 每段复用约 0.65–1.09%，组合候选约 0.39–1.40%。primary 从约 1.49ms 增至约 1.76ms，resolve 从约 0.023ms 增至约 0.050ms；bounce1 减少约 0.05–0.10ms，不足以抵消入口开销。第三轮并未达到合理效果。

## 明确的入口错误

默认 atlas 法线写入 RG=(128,128)，解码 `value/127.5-1` 得到约 0.00392，归一化后与几何法线点积约 0.9999846。alpha.36 的 cache guard 要求 >0.999999，所以连 vanilla 平面也没有调用 rtTail，所有训练/查询为零。alpha.37 guard 改为 >0.9999，只放宽默认量化误差；明显倾斜的法线仍 exact，不改变原 BRDF 或 reference normal decoding。

sparse 重投影只看一个最近像素，再比较抖动采样得到的纹理颜色，会在静止纹理面制造大量失配。alpha.37 保留最近像素快检查，失败时搜投影周围 2×2 历史 texels，所有候选仍通过原身份、距离、法线和 epoch 检查。纯 diffuse 允许以反照率去调制统计照明方差，并将复用 RGB/diffuse 按当前反照率恢复；含镜面分量的 rough diffuse 不这样缩放，暗通道变化也回退。该措施不能消除实际光照的蒙特卡洛高方差，不能保证 sparse 达到 0.25–0.6 密度。

## 下次验收

替换 alpha.37，保持同一视角、分辨率与 spp，执行 `/voxellight rt_benchmark realtime`。每段 `.realtime.json` 和 summary `realtimeCoverage` 直接提供实际路径密度、复用率、缓存命中率，零分母为 null。候选 cache 零查询会提示“timing alone does not validate the cache algorithm”。GPU workload valid 只表示测量可比较，不代表算法得到了足够覆盖。

先确认 cache queries/probes/trained 不再为零，再检查 cache hits、B1/B2 alive 与完整路径密度是否下降，最后检查整个 transport batch 和整帧耗时。查询发生但命中仍低时，应继续定位训练覆盖、成熟计数、方差、epoch 与 TTL，不能把门槛全部取消以制造收益。新增邻域读取也有成本，客户端计时是判断净收益的依据。

生产 Slang CPU target 回归覆盖默认 RG8 准入、明显法线偏差保护、纹理 diffuse 重调制、暗通道回退、rough specular 保护、邻域匹配与表面 ID 拒绝。CPU 构建不验证 GPU 原子并发、实机时间或视觉。应继续对照收敛 reference 检查漏光、纹理颜色、残影和编辑/灯光变化。
