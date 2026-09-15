# SMMLA（i8mm）路径兼容性清单

本表汇总已记录的设备与主机验证覆盖，包括 SMMLA 测试、能力判断、回退和引擎哈希回归。状态表示对应历史测试通过，不代表当前发行 AAR 已在全部设备重新验收；具体版本性能与限制见 [版本基准](../benchmark/data/README.md)。无 i8mm 设备记录 ruy 自动回退路径，Apple 主机结果独立于 Android 真机结果。

| 设备 | SoC | 核心 | ISA | i8mm | 实际路径 | 状态 |
|---|---|---|---|---|---|---|
| 小米 14(23127PN0CC,Android 16) | 骁龙 8 Gen 3(SM8650) | Cortex-X4 / A720 / A520 | Armv9.2 | 有 | SMMLA | 通过 |
| 小米 12(2201123C,Android 13) | 骁龙 8 Gen 1(SM8450) | Cortex-X2 / A710 / A510 | Armv9.0 | 有 | SMMLA | 通过 |
| 小米 10(umi,Android 13) | 骁龙 865(SM8250) | Cortex-A77 / A55 | Armv8.2 | 无 | ruy(自动回退) | 通过 |
| Apple M3 Pro(macOS,CI 主机口径) | Apple M3 | — | Armv8.6+ | 有 | SMMLA | 通过 |

验证内容与新设备验证流程见 [构建、测试与基准](benchmarking.md)。
