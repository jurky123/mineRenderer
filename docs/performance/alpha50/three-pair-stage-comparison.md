# VOXELLIGHT-VS-CAUSTICA-PROFILING

测量结果。正差值表示 VoxelLight 耗时更高；父子范围不能相加。

| 场景 | 阶段 | 配对数 | P50 差值 ms | 跨轮差值范围 ms |
| --- | --- | ---: | ---: | --- |
| multi_light | cpu|graphics|client_frame_interval | 3 | 13.3912 | [12.7136, 13.474800000000002] |
| complex_building | cpu|graphics|client_frame_interval | 3 | 13.2829 | [12.5736, 13.4996] |
| multi_light | gpu|graphics|world | 3 | 13.1231 | [12.517391999999997, 13.193632000000003] |
| complex_building | gpu|graphics|world | 3 | 13.0870 | [12.426816, 13.301216000000004] |
| complex_building | gpu|graphics|pt.ready_rr_inputs | 3 | 12.7638 | [12.134336, 12.876304] |
| multi_light | gpu|graphics|pt.ready_rr_inputs | 3 | 12.6851 | [12.13664, 12.814432] |
| glass_water | cpu|graphics|client_frame_interval | 3 | 11.0419 | [10.751500000000002, 11.2447] |
| glass_water | gpu|graphics|world | 3 | 10.9313 | [10.62896, 11.103152] |
| glass_water | gpu|graphics|pt.ready_rr_inputs | 3 | 10.5847 | [10.339599999999999, 10.7668] |
| multi_light | gpu|graphics|transport | 3 | 8.5585 | [8.337536, 8.620112] |
| dynamic_entity | cpu|graphics|client_frame_interval | 3 | 7.0645 | [6.7493, 7.1454] |
| complex_building | gpu|graphics|transport | 3 | 6.9982 | [6.776352000000001, 7.0921759999999985] |
| dynamic_entity | gpu|graphics|world | 3 | 6.9980 | [6.685008, 7.076016000000001] |
| glass_water | gpu|graphics|transport | 3 | 6.6282 | [6.491968, 6.703888] |
| dynamic_entity | gpu|graphics|pt.ready_rr_inputs | 3 | 6.6189 | [6.381279999999999, 6.700768] |
| terrain | cpu|graphics|client_frame_interval | 3 | 6.1977 | [6.063500000000001, 6.488600000000001] |
| terrain | gpu|graphics|world | 3 | 6.1301 | [5.982336, 6.423023999999999] |
| terrain | gpu|graphics|pt.ready_rr_inputs | 3 | 5.8310 | [5.792896, 6.026944] |
| complex_building | gpu|graphics|primary_guides | 3 | 5.5379 | [5.179264, 5.574096] |
| dynamic_entity | gpu|graphics|primary_guides | 3 | 4.1225 | [3.982784, 4.240863999999999] |
| multi_light | gpu|graphics|primary_guides | 3 | 3.9926 | [3.769568, 4.10768] |
| glass_water | gpu|graphics|primary_guides | 3 | 3.7554 | [3.6933759999999998, 3.868992] |
| terrain | gpu|graphics|primary_guides | 3 | 2.9666 | [2.9547999999999996, 3.090528] |
| terrain | gpu|graphics|transport | 3 | 2.6634 | [2.631296, 2.7312] |
| dynamic_entity | gpu|graphics|transport | 3 | 2.2594 | [2.230048, 2.311888] |
| dynamic_entity | cpu|graphics|world | 3 | 0.5842 | [0.51835, 0.585] |
| glass_water | cpu|graphics|world | 3 | 0.3681 | [0.3603, 0.40190000000000003] |
| multi_light | cpu|graphics|world | 3 | 0.2811 | [0.23350000000000004, 0.29569999999999996] |
| complex_building | cpu|graphics|world | 3 | 0.2506 | [0.20559999999999995, 0.26330000000000003] |
| terrain | cpu|graphics|world | 3 | 0.2091 | [0.17700000000000005, 0.22579999999999995] |
| complex_building | gpu|graphics|rr | 3 | 0.0761 | [0.016255999999999826, 0.15567999999999982] |
| multi_light | gpu|graphics|rr | 3 | 0.0733 | [0.0015680000000000138, 0.12014400000000003] |
| glass_water | gpu|graphics|rr | 3 | 0.0548 | [0.024607999999999963, 0.06768000000000018] |
| dynamic_entity | gpu|graphics|rr | 3 | 0.0547 | [0.015231999999999912, 0.06222400000000006] |
| terrain | gpu|graphics|rr | 3 | 0.0451 | [0.0018879999999998898, 0.13135999999999992] |
| multi_light | cpu|graphics|rr | 3 | 0.0374 | [0.02460000000000001, 0.040199999999999986] |
| glass_water | cpu|graphics|rr | 3 | 0.0364 | [0.02890000000000001, 0.03889999999999999] |
| complex_building | cpu|graphics|rr | 3 | 0.0360 | [0.024599999999999997, 0.03789999999999999] |
| terrain | cpu|graphics|rr | 3 | 0.0357 | [0.03109999999999999, 0.03975000000000001] |
| dynamic_entity | cpu|graphics|rr | 3 | 0.0352 | [0.02750000000000001, 0.0378] |

## 未通过比较检查的配对

```json
[]
```

## 原因闭环

每个主要差异必须另附原因假设、支持证据及反证、最小验证实验和建议优化。阶段排行榜自身不能证明原因或优化收益。
