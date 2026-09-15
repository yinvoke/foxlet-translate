<div align="center">

# Foxlet Translate

简体中文 · [English](README.en.md)

**面向 Android 的高性能本地翻译库：离线、隐私友好、针对 ARM 移动芯片优化**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#-构建)
[![Release](https://img.shields.io/badge/release-v0.5.0-blue)](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0)
[![minSdk](https://img.shields.io/badge/minSdk-28-blue)](#-兼容配置)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#-兼容配置)

[兼容配置](#-兼容配置) · [特性](#-特性) · [快速开始](#-快速开始) · [构建](#-构建) · [文档索引](docs/README.md) · [路线图](#-2026-roadmap)

</div>

---

Foxlet Translate 是面向 Android 的高性能离线翻译库，基于 Mozilla [Firefox Translations](https://github.com/mozilla/translations) 使用的 [Bergamot](https://browser.mt/) 引擎、Marian 推理运行时与 Mozilla 翻译模型，提供 Kotlin API 和可直接集成的 AAR。

当前发行版：**[0.5.0](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0)**。下载与接入示例使用此版本；[基准结果与已知限制](benchmark/data/v0.5.0/README.md)已归档。

项目针对 ARM 移动芯片优化矩阵计算、批处理、线程调度和内存管理，根据设备能力选择 I8MM/SMMLA 或 ruy（SDOT/NEON）内核。模型下载后即可在设备上完成翻译，无需将文本发送到云端。引擎、模型及训练流程公开，开发者可自行构建、固定模型版本并调整推理参数。

## ✨ 特性

SDK 通过 `Foxlet.models` 管理模型，通过 `Foxlet.translator` 执行翻译。

- **离线推理**：翻译全程无网络请求，联网仅用于模型下载与显式更新检查。
- **Mozilla 模型**：使用 Firefox Translations 官方模型（MPL-2.0），引擎、模型与训练流程公开。
- **模型管理**：本地模型枚举、删除与清理，支持断点续传、重试和更新检测。
- **Kotlin suspend API**：支持批量翻译、pivot 中转和 HTML 感知翻译（实验性）。
- **移动端适配**：按 CPU 能力选择 I8MM、DotProd 或 NEON 内核。
- **分句处理**：自研 UTF-8 分句器，优先验证中、英、日、韩、俄，处理常用缩写、混合文本、引号和 Unicode 句末标点；另有德、法、西、葡、意、土规则。支持自定义规则，不依赖 ssplit-cpp / PCRE2。
- **内存管理**：int8 embedding、模型按需加载、空闲自动卸载与释放确认，支持通过 `onTrimMemory` 触发释放。
- **线程与调度**：默认单线程使用同步路径；`Threading.Auto` 按设备能力和指定工作负载在创建客户端时确定线程数。

## 🚀 Android 性能优化

优化覆盖计算内核、推理调度、模型生命周期和构建产物：

- **ARM 内核适配**：支持具备 I8MM 的设备使用 SMMLA，其他 ARM64 设备自动回退到 ruy 的 SDOT 或普通 NEON 内核，覆盖骁龙 865、8 Gen 1、8 Gen 3 等不同代际。
- **推理计算优化**：使用 int8 embedding/权重路径，优化 Attention 小矩阵计算、shortlist 和 batch 形状，默认 `mini-batch-words` 为 512。
- **低延迟路径**：单线程配置使用 BlockingService，减少 worker 派发和同步开销；配置两个及以上线程时使用并行 worker。
- **设备感知调度**：根据内存、低内存标记和快核数量，在 1/2/4/6 个线程档位中自动选择，并支持显式覆盖。
- **内存生命周期**：模型按需加载，支持空闲自动卸载、`onTrimMemory` 主动释放和双模型 pivot 的内存权衡。
- **构建与兼容性**：通过链接裁剪与非 JNI 符号隐藏减小原生库体积，支持 16 KB page size，兼容具备和不具备 I8MM 的 ARM64 设备。

## 文档入口

| 目的 | 文档 |
|---|---|
| AAR 集成、模型下载与 Kotlin API | [快速开始](docs/getting-started.md) |
| Android、JNI、引擎与工具的模块职责 | [代码结构与运行时架构](docs/architecture.md) |
| 开发环境、测试方法与设备基准 | [构建、测试与基准](docs/benchmarking.md) |
| 发行构建与发布规范 | [发布指南](docs/releasing.md) |
| 性能报告、原始数据与回归检查 | [benchmark/data/](benchmark/data/README.md) |

## 📱 兼容配置

| 项目 | 最低要求 / 兼容基线 | 推荐配置 |
|---|---|---|
| Android 系统 | Android 9（API 28），minSdk >= 28 | Android 15（API 35）及以上 |
| CPU 与 ABI | 64 位 AArch64，系统支持 `arm64-v8a` 应用 | 支持 I8MM 的 CPU，例如骁龙 8 Gen 1、骁龙 8 Gen 3 |
| 设备总 RAM | >= 4 GB | >= 8 GB；多线程使用推荐 >= 12 GB |

发行包仅包含 [`arm64-v8a`](https://developer.android.com/ndk/guides/abis#arm64-v8a) 原生库，支持采用该 ABI 的 Armv8.x / Armv9 设备，要求 64 位 Android 应用运行环境。推理后端无 Google Play 服务依赖。

### 指令集与自动回退

| 指令集 / 扩展 | 代表指令 | 用途 | 要求 |
|---|---|---|---|
| AArch64 + NEON / Advanced SIMD | `SMULL`、`SMLAL`、`FMLA` | 基础整数、浮点运算及普通 ruy 内核 | 必需，最低设计基线 |
| DotProd | `SDOT` | ruy 的 int8 点积加速 | 可选，按运行时 CPU 能力启用 |
| I8MM | `SMMLA` | 专用 int8 矩阵乘内核 | 可选，按运行时 CPU 能力启用 |
| SVE / SVE2 / SME / SME2 | — | 当前无专用加速路径 | 暂未实现 |

int8 矩阵乘根据 CPU 报告的能力选择内核，优先级为 I8MM / SMMLA、ruy / SDOT、ruy / NEON。实现见 [I8MM 分派](engine/marian-fork/src/tensors/cpu/ruy_interface.h)、[DotProd 检测](engine/marian-fork/src/3rd_party/ruy/ruy/cpuinfo.cc)和 [ruy 回退路径](engine/marian-fork/src/3rd_party/ruy/ruy/ctx.cc)。

Armv8.0-A / API 28 为兼容目标；Android 9 和无 DotProd 的旧设备不在现有实测覆盖范围内。具体设备、系统与指令路径见 [兼容性清单](docs/smmla-compatibility.md)。

### 内存与存储预算

运行内存包括模型驻留、推理工作区、缓存、分词及输出缓冲，峰值取决于线程数、批量长度和同时驻留的模型数。设备总 RAM 表示硬件容量，PSS 衡量进程内存占用，Java 堆上限仅约束托管堆。系统内存压力可能导致进程被回收，参见 [Android 进程内存说明](https://developer.android.com/topic/performance/memory-management)。

<!-- BENCHMARK:MEMORY:BEGIN -->

| 场景 | 进程峰值 PSS 参考 | 翻译场景内存预算建议 |
|---|---|---|
| 英→中 / 1 | ≈ 257 MiB | ≈ 512 MiB |
| 日→英→中 / 1 | ≈ 356 MiB | ≈ 1 GiB |
| 日→英→中 / 2 | ≈ 548 MiB | ≈ 2 GiB |

<!-- BENCHMARK:MEMORY:END -->

## 📈 性能表现

<!-- BENCHMARK:BEGIN -->

[小米 14 历史原生对照](benchmark/data/v0.3.0/README.md)中，**v0.1.0 → v0.3.0** 均使用 Release 构建，默认单线程首次翻译速度约提升 **100%**、峰值 RSS 约降低 **40%**，其中包含默认参数变化。该组为跳过频率起跑门槛的参考测量；上述数字不表示早期 Android App 到当前 SDK 的全部改善，也不表示相对 ML Kit 的提升。当前版本的 App 实测见下表及[版本报告](benchmark/data/v0.5.0/README.md)。

### 与 Google ML Kit 端侧翻译对比

| 项目 | 配置 |
|---|---|
| 测试版本 | Foxlet 0.5.0 |
| 设备 | 小米 14 |
| 处理器 | 骁龙 8 Gen 3 |
| 操作系统 | Android 16 |
| 基准协议 | `android-app-v2` |
| 测试配置 | Google ML Kit；Foxlet 单线程 / 双线程 |
| 测试语料 | FLORES-200 devtest，每个语向前 200 条源文 |
| 测试语向 | 英→中、日→中 |
| 构建配置 | Debug 基准 APK，原生库使用 Release 优化 |

已归档测量中，每场景完成 1–2 个独立进程、每进程三遍，原定三轮尚未完成；以下为参考实测，不代表回归基线验收通过。[原始数据与方法](benchmark/data/v0.5.0/README.md)。

| 语向 / 引擎 / 线程 | 首次速度（条/秒） | 热态速度（条/秒） | 首次 PSS MiB | 热态 PSS MiB | COMET × 100 |
|---|---:|---:|---:|---:|---:|
| 英→中 / ML Kit | 16.35 | 14.60 | 181.00 | 191.00 | 72.69 |
| 英→中 / Foxlet / 1 | 88.24 | 101.41 | 256.90 | 227.30 | 87.27 |
| 英→中 / Foxlet / 2 | 131.50 | 162.83 | 371.60 | 320.70 | 87.27 |
| 日→中 / ML Kit | 7.60 | 6.92 | 233.30 | 239.40 | 68.93 |
| 日→中 / Foxlet / 1 | 41.83 | 36.42† | 355.15 | 331.85 | 86.76 |
| 日→中 / Foxlet / 2 | 63.63 | 65.52 | 547.35 | 501.70 | 86.76 |

首次计时包含引擎/模型创建。**PSS（Proportional Set Size，按比例分摊的物理内存）**是进程独占的物理内存，加上按比例分摊的共享内存。**首次 PSS / 热态 PSS**分别取各进程首遍 / 后两遍翻译期间的峰值，再计算中位数；包含 JVM、UI 和原生库，每 250 ms 采样。COMET 是质量评分，不是准确率百分比。† 已测进程的极差 / 中位数超过 10%；轮次不足时不能据此判定稳定性。

**通常推荐双线程**，兼顾速度和内存；内存紧张时选单线程。继续增加线程通常收益边际递减，并增加内存和调度开销。

| 语向 | 4 线程预估 | 6 线程预估 |
|---|---:|---:|
| 英→中 | ≈ 234 条/秒 | ≈ 273 条/秒 |
| 日→中 | ≈ 109 条/秒 | ≈ 140 条/秒 |

四、六线程为**粗略预估，未经实测**，按单、双线程热态耗时外推，未计入额外带宽竞争、调度和发热成本；[计算依据](benchmark/data/v0.5.0/derived/summary.json)已公开，不作为性能承诺。

<!-- BENCHMARK:END -->

## 🌍 支持的语言模型

内置索引包含 **106 个语向模型、54 种语言**，以英语为中转语言：52 个语言标签与英语双向互译（简体与繁体中文分别计为一个标签，语言数合并计为中文），阿塞拜疆语仅支持英→阿，阿尔巴尼亚语仅支持阿→英。

`translatePivot` 使用“源语言→英语”和“英语→目标语言”两个模型完成中转翻译，例如日→中。模型版本与下载信息见 [registry.json](registry.json)；`from` / `to` 语向代码与 `LanguagePair` 一致。上游模型由 Mozilla 随 Firefox 更新。

<details>
<summary>完整模型列表（106 个语向）</summary>

| 源语言 | 目标语言 | from | to | 版本 | 大小 |
|---|---|---|---|---|---|
| 英语 | 南非荷兰语 | `en` | `af` | 2.0 | 36 MB |
| 南非荷兰语 | 英语 | `af` | `en` | 2.0 | 36 MB |
| 英语 | 阿拉伯语 | `en` | `ar` | 2.2 | 36 MB |
| 阿拉伯语 | 英语 | `ar` | `en` | 2.2 | 37 MB |
| 英语 | 阿塞拜疆语 | `en` | `az` | 1.0 | 21 MB |
| 英语 | 保加利亚语 | `en` | `bg` | 2.0 | 36 MB |
| 保加利亚语 | 英语 | `bg` | `en` | 2.0 | 37 MB |
| 英语 | 孟加拉语 | `en` | `bn` | 1.0 | 21 MB |
| 孟加拉语 | 英语 | `bn` | `en` | 1.0 | 23 MB |
| 英语 | 波斯尼亚语 | `en` | `bs` | 2.0 | 36 MB |
| 波斯尼亚语 | 英语 | `bs` | `en` | 2.0 | 38 MB |
| 英语 | 加泰罗尼亚语 | `en` | `ca` | 2.0 | 37 MB |
| 加泰罗尼亚语 | 英语 | `ca` | `en` | 2.0 | 37 MB |
| 英语 | 捷克语 | `en` | `cs` | 2.0 | 36 MB |
| 捷克语 | 英语 | `cs` | `en` | 2.0 | 37 MB |
| 英语 | 丹麦语 | `en` | `da` | 1.0 | 22 MB |
| 丹麦语 | 英语 | `da` | `en` | 1.0 | 22 MB |
| 英语 | 德语 | `en` | `de` | 2.1 | 37 MB |
| 德语 | 英语 | `de` | `en` | 2.0 | 37 MB |
| 英语 | 希腊语 | `en` | `el` | 1.0 | 21 MB |
| 希腊语 | 英语 | `el` | `en` | 1.1 | 22 MB |
| 英语 | 西班牙语 | `en` | `es` | 2.1 | 37 MB |
| 西班牙语 | 英语 | `es` | `en` | 2.0 | 37 MB |
| 英语 | 爱沙尼亚语 | `en` | `et` | 2.0 | 36 MB |
| 爱沙尼亚语 | 英语 | `et` | `en` | 2.0 | 37 MB |
| 英语 | 巴斯克语 | `en` | `eu` | 2.0 | 36 MB |
| 巴斯克语 | 英语 | `eu` | `en` | 2.1 | 36 MB |
| 英语 | 波斯语 | `en` | `fa` | 1.1 | 22 MB |
| 波斯语 | 英语 | `fa` | `en` | 1.0 | 22 MB |
| 英语 | 芬兰语 | `en` | `fi` | 2.0 | 36 MB |
| 芬兰语 | 英语 | `fi` | `en` | 2.0 | 38 MB |
| 英语 | 法语 | `en` | `fr` | 2.0 | 37 MB |
| 法语 | 英语 | `fr` | `en` | 2.0 | 37 MB |
| 英语 | 加利西亚语 | `en` | `gl` | 2.1 | 35 MB |
| 加利西亚语 | 英语 | `gl` | `en` | 2.1 | 37 MB |
| 英语 | 古吉拉特语 | `en` | `gu` | 1.0 | 21 MB |
| 古吉拉特语 | 英语 | `gu` | `en` | 1.0 | 22 MB |
| 英语 | 希伯来语 | `en` | `he` | 1.0 | 21 MB |
| 希伯来语 | 英语 | `he` | `en` | 1.0 | 23 MB |
| 英语 | 印地语 | `en` | `hi` | 1.0 | 22 MB |
| 印地语 | 英语 | `hi` | `en` | 1.0 | 23 MB |
| 英语 | 克罗地亚语 | `en` | `hr` | 1.0 | 21 MB |
| 克罗地亚语 | 英语 | `hr` | `en` | 1.0 | 22 MB |
| 英语 | 匈牙利语 | `en` | `hu` | 2.1 | 36 MB |
| 匈牙利语 | 英语 | `hu` | `en` | 2.1 | 37 MB |
| 英语 | 印尼语 | `en` | `id` | 1.0 | 21 MB |
| 印尼语 | 英语 | `id` | `en` | 1.0 | 22 MB |
| 英语 | 冰岛语 | `en` | `is` | 2.0 | 36 MB |
| 冰岛语 | 英语 | `is` | `en` | 2.0 | 36 MB |
| 英语 | 意大利语 | `en` | `it` | 2.1 | 37 MB |
| 意大利语 | 英语 | `it` | `en` | 2.0 | 37 MB |
| 英语 | 日语 | `en` | `ja` | 2.3 | 50 MB |
| 日语 | 英语 | `ja` | `en` | 2.1 | 55 MB |
| 英语 | 卡纳达语 | `en` | `kn` | 1.0 | 21 MB |
| 卡纳达语 | 英语 | `kn` | `en` | 1.0 | 23 MB |
| 英语 | 韩语 | `en` | `ko` | 2.1 | 52 MB |
| 韩语 | 英语 | `ko` | `en` | 2.1 | 54 MB |
| 英语 | 立陶宛语 | `en` | `lt` | 2.1 | 36 MB |
| 立陶宛语 | 英语 | `lt` | `en` | 1.0 | 23 MB |
| 英语 | 拉脱维亚语 | `en` | `lv` | 2.1 | 36 MB |
| 拉脱维亚语 | 英语 | `lv` | `en` | 1.0 | 22 MB |
| 英语 | 马拉雅拉姆语 | `en` | `ml` | 1.0 | 21 MB |
| 马拉雅拉姆语 | 英语 | `ml` | `en` | 1.0 | 23 MB |
| 英语 | 马拉地语 | `en` | `mr` | 2.0 | 35 MB |
| 马拉地语 | 英语 | `mr` | `en` | 2.0 | 37 MB |
| 英语 | 马来语 | `en` | `ms` | 1.0 | 22 MB |
| 马来语 | 英语 | `ms` | `en` | 1.0 | 22 MB |
| 英语 | 挪威语(书面) | `en` | `nb` | 2.0 | 22 MB |
| 挪威语(书面) | 英语 | `nb` | `en` | 2.0 | 22 MB |
| 英语 | 荷兰语 | `en` | `nl` | 2.1 | 36 MB |
| 荷兰语 | 英语 | `nl` | `en` | 2.0 | 37 MB |
| 英语 | 波兰语 | `en` | `pl` | 2.1 | 36 MB |
| 波兰语 | 英语 | `pl` | `en` | 2.0 | 37 MB |
| 英语 | 葡萄牙语 | `en` | `pt` | 2.1 | 36 MB |
| 葡萄牙语 | 英语 | `pt` | `en` | 2.0 | 37 MB |
| 英语 | 罗马尼亚语 | `en` | `ro` | 1.0 | 22 MB |
| 罗马尼亚语 | 英语 | `ro` | `en` | 1.0 | 23 MB |
| 英语 | 俄语 | `en` | `ru` | 2.1 | 35 MB |
| 俄语 | 英语 | `ru` | `en` | 2.1 | 37 MB |
| 英语 | 斯洛伐克语 | `en` | `sk` | 2.1 | 36 MB |
| 斯洛伐克语 | 英语 | `sk` | `en` | 1.0 | 23 MB |
| 英语 | 斯洛文尼亚语 | `en` | `sl` | 2.1 | 36 MB |
| 斯洛文尼亚语 | 英语 | `sl` | `en` | 2.1 | 37 MB |
| 阿尔巴尼亚语 | 英语 | `sq` | `en` | 1.0 | 22 MB |
| 英语 | 塞尔维亚语 | `en` | `sr` | 2.0 | 35 MB |
| 塞尔维亚语 | 英语 | `sr` | `en` | 1.0 | 23 MB |
| 英语 | 瑞典语 | `en` | `sv` | 1.0 | 22 MB |
| 瑞典语 | 英语 | `sv` | `en` | 1.0 | 23 MB |
| 英语 | 泰米尔语 | `en` | `ta` | 2.0 | 35 MB |
| 泰米尔语 | 英语 | `ta` | `en` | 2.0 | 38 MB |
| 英语 | 泰卢固语 | `en` | `te` | 1.0 | 21 MB |
| 泰卢固语 | 英语 | `te` | `en` | 1.0 | 23 MB |
| 英语 | 泰语 | `en` | `th` | 2.0 | 36 MB |
| 泰语 | 英语 | `th` | `en` | 2.0 | 37 MB |
| 英语 | 土耳其语 | `en` | `tr` | 1.0 | 21 MB |
| 土耳其语 | 英语 | `tr` | `en` | 1.0 | 23 MB |
| 英语 | 乌克兰语 | `en` | `uk` | 2.2 | 36 MB |
| 乌克兰语 | 英语 | `uk` | `en` | 1.1 | 22 MB |
| 英语 | 乌尔都语 | `en` | `ur` | 2.0 | 35 MB |
| 乌尔都语 | 英语 | `ur` | `en` | 2.1 | 36 MB |
| 英语 | 越南语 | `en` | `vi` | 2.0 | 37 MB |
| 越南语 | 英语 | `vi` | `en` | 1.0 | 22 MB |
| 英语 | 中文(简体) | `en` | `zh-Hans` | 2.2 | 52 MB |
| 中文(简体) | 英语 | `zh-Hans` | `en` | 2.1 | 55 MB |
| 英语 | 中文(繁体) | `en` | `zh-Hant` | 2.0 | 49 MB |
| 中文(繁体) | 英语 | `zh-Hant` | `en` | 2.0 | 52 MB |

</details>

## 📁 仓库结构

```
foxlet/        Android 库与 Kotlin API
native/        自有 C++：jni/ 适配层、sentence/ UTF-8 分句器与编译期语言规则
engine/        Bergamot / Marian 引擎与第三方组件
patches/       保留的上游改动补丁与迁移索引
sdk-example/   最小 SDK 消费与离线翻译演示
benchmark/     app/ 内部基准测试 app（含评测数据）、data/ 版本基准与原始数据
tools/         构建、测试与性能分析工具
docs/          集成、架构与开发文档
registry.json  Mozilla 模型下载索引
```

工具和基准 app 不会打包进 AAR。模块职责与上游维护方式见[代码结构与运行时架构](docs/architecture.md)。

## 🚀 快速开始

**0.5.0** 延续 0.4.0 的统一 `Foxlet` 客户端 API；从 0.3.x 升级需要更新调用代码并重新编译，详见 [接入指南](docs/getting-started.md)。

### 1. 下载发行包

直接下载 [v0.5.0 Release](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0) 中的 AAR，无需克隆本仓库或安装 NDK / CMake：

| 文件 | 用途 |
|---|---|
| [foxlet-v0.5.0.aar](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-v0.5.0.aar) | v0.5.0 SDK |
| [foxlet-sdk-example-v0.5.0.apk](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-sdk-example-v0.5.0.apk) | 可直接安装的 SDK 示例，演示模型管理和离线翻译，使用开发签名 |
| [SHA256SUMS](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/SHA256SUMS) | 发行文件的 SHA-256 校验值 |

0.5.0 AAR 约 **2.8 MB**，AAR 和 SDK 示例均不包含翻译模型。首次准备某个语向时需要联网下载模型，例如英→简体中文约 **52 MB**；准备完成后可断网翻译。完整下载大小见 [模型索引](registry.json)。

> 0.5.0 提供单一 AAR，内置自有多语言分句规则并移除 Android SentencePiece 训练代码。发布前归档 AAR 为 2,809,980 字节，相比 v0.4.0 缩小 25.75%；最终文件大小及校验值以发行附件为准。设计与配置见 [分句说明](docs/sentence-segmentation.md)。

### 2. 添加依赖和权限

将 AAR 保存为宿主 app 模块的 `app/libs/foxlet-v0.5.0.aar`，在 `app/build.gradle.kts` 中加入：

```kotlin
android {
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(files("libs/foxlet-v0.5.0.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

本地 AAR 不带 Maven 依赖元数据，因此需要显式声明协程依赖；宿主 Kotlin JVM target 也应设为 17。

Maven Central 发布配置已准备，**尚未在 Maven Central 上线**，当前通过 GitHub 发行 AAR 接入。

在宿主 `AndroidManifest.xml` 中声明下载模型和检查更新所需的网络权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 3. 准备模型并翻译

应用长期持有一个 `Foxlet`，模型目录和翻译配置在创建时绑定。默认目录是应用的 `noBackupFilesDir/translation-models`：

```kotlin
import io.github.yinvoker.foxlet.*

val foxlet = Foxlet.create(context) {
    translation { threading = Threading.Fixed(2) }
}
try {
    val pair = LanguagePair("en", "zh-Hans")
    val model = foxlet.models.prepare(pair)
    val text = foxlet.translator.translate("Hello, world!", model)
    val batch = foxlet.translator.translate(listOf("Good morning.", "Thank you."), model)
    val updates = foxlet.models.checkUpdates(setOf(pair))
    val cleanup = foxlet.models.cleanup(keep = setOf(model.id))
} finally {
    foxlet.shutdown()
}
```

上述示例需在协程或 `suspend` 函数中调用，包括 `Foxlet.create` 和 `shutdown`。示例在结束时关闭客户端；实际应用应让多个页面复用同一客户端，并在不再使用时统一关闭。`prepare` 优先使用本地最新可用安装，缺失时下载内置版本；`PreparePolicy.LocalOnly` 限制为本地模型。下载、准备和查询返回 `InstalledModel`，用于翻译和删除。`checkUpdates` 查询远端索引，`download(update.target)` 安装指定更新。

线程配置默认为 `Threading.Fixed(1)`，支持固定线程数和 `Threading.Auto(Workload.SINGLE/BATCH/PIVOT)`；`translator.threadingInfo` 提供实际配置。模型默认空闲 60 秒卸载，驻留策略包括 `ModelRetention.Idle`、`AfterRequest` 和 `UntilShutdown`。`unloadModels` 释放运行内存，`models.delete/cleanup` 管理磁盘安装。

模型文件在使用期间保持不可变；更新安装到独立目录，后续翻译显式使用新模型。每个进程同时支持一个活动客户端；`shutdown` 等待正在执行的 native 批次结束并释放资源，完成后允许创建新客户端。

配置、下载进度、错误处理、中转和外部模型用法见 [接入指南](docs/getting-started.md)。

## 🔨 构建

Android AAR 可通过 NDK 在 ARM 或 x86_64 主机交叉编译；主机 smoke CLI
仅支持 ARM（Apple Silicon / ARM Linux）。本仓库的构建工具链为：
JDK 17、Android SDK 36（`compileSdk = 36`）、NDK 29.0.13113456、CMake 3.31.6，
以及仓库 Gradle Wrapper 9.7.1、AGP 9.3.1、Kotlin 2.4.10。

直接集成发行 AAR 无需安装 NDK / CMake；宿主需显式声明协程依赖，推荐版本为
`kotlinx-coroutines-android:1.10.2`。最低运行系统版本由 `minSdk` 指定，
`targetSdk` 由宿主应用配置；Android 版本与 API level 对照见
[Android 官方说明](https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels)。

```bash
./gradlew :foxlet:assembleRelease    # SDK AAR
./gradlew :sdk-example:assembleRelease             # SDK 示例（消费 AAR，开启 R8）
./gradlew :benchmark-app:assembleDebug             # 基准 app（含评测数据）
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DCOMPILE_TESTS=OFF && cmake --build build-host --target smoke   # 主机 CLI
```

设备基准结果以 JSON 保存于 app files 目录，包含各阶段内存与 CPU 曲线：

```bash
adb shell am start -n io.github.yinvoker.foxlet.bench/.MainActivity \
  --ez autorun true --ei threads 2
```

源码构建的 AAR 位于 `foxlet/build/outputs/aar/`，文件名为 `foxlet-release.aar`，与上面下载的版本化文件名不同。SDK 示例 APK 位于 `sdk-example/build/outputs/apk/release/sdk-example-release.apk`。

环境配置、SMMLA 测试与设备基准方法见 [构建、测试与基准](docs/benchmarking.md)。

## ⚠️ 范围与限制

- 最低运行目标、可选指令集、推荐配置与实测边界见 [兼容配置](#-兼容配置)。
- 随包原生库支持 4 KB / 16 KB page size；宿主 APK 的打包方式及其他原生依赖也需满足
  [16 KB 页面兼容要求](https://developer.android.com/guide/practices/page-sizes)。

## 🗺️ 2026 Roadmap

- [x] 基础引擎与 NDK 移植
- [x] CI 构建与测试
- [x] AAR 发布
- [x] GEMM 内核优化
- [x] Attention 小矩阵计算优化
- [x] 内存占用与模型释放优化
- [x] 多线程与调度优化
- [x] 参数与批处理调优
- [x] 模型更新检测
- [x] 本地模型管理
- [x] 模型下载续传与重试
- [x] 统一模型管理与翻译 API
- [x] 构建优化与 16 KB 页面兼容
- [x] 减小发行包体积
- [ ] 完善 HTML 翻译支持
- [ ] 增加 SME2 指令集加速

## 🤝 参与贡献

问题反馈、Pull Request、开发规范及上游补丁维护约定见 [贡献指南](CONTRIBUTING.md)。

## 📦 库、第三方组件与模型

本项目原创代码及其修改按 [MIT](LICENSE) 许可发布；对引入代码的修改沿用相应
上游许可证。来源与许可范围见 [NOTICE](NOTICE) 和各 vendor 目录中的许可文件。
AAR 内附带许可文本及对应源码取得说明。

### 引擎与运行时

- [Bergamot translator](https://github.com/mozilla/translations)：Mozilla Firefox 本地翻译引擎，MPL-2.0；本项目使用其 `inference/` 代码并做 Android/ARM 适配。
- [Marian NMT](https://github.com/marian-nmt/marian)：C++ 神经机器翻译运行时，MIT；本仓库使用 Bergamot 维护的 fork。
- [ruy](https://github.com/google/ruy)：ARM CPU 矩阵乘后端，Apache-2.0；提供 SDOT 和普通 NEON 内核。
- [SentencePiece](https://github.com/google/sentencepiece)：模型分词与词表处理，Apache-2.0。
- [Unicode](https://www.unicode.org/license.txt)：分句属性数据采用 Unicode-3.0 许可；自有分句代码按 MIT 发布。
- cpuinfo、zlib、pathie-cpp、faiss 子集和 `half_float`：分别按各自目录中的 BSD/MIT/Apache 或其他许可分发。

### Android 依赖与基准工具

- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines)：Android suspend API 的协程调度，Apache-2.0。
- [Google ML Kit Translate](https://developers.google.com/ml-kit/language/translation)：用于 `benchmark/app/` 基准 app 的对比评测，按其自身服务条款使用；不属于 SDK 运行时依赖。
- [FLORES-200](https://github.com/facebookresearch/flores)：仅用于评测，采用 CC-BY-SA 4.0，不包含在 SDK AAR 或 SDK 示例 APK 中。源码评测数据保留[出处和 NLLB 2022 引用](benchmark/data/CITATION.md)。

### 翻译模型

模型来自 Mozilla Firefox Remote Settings，文件信息及 SHA-256 见 [模型索引](registry.json)。模型按对应发布许可分发，当前内置索引中的模型采用 MPL-2.0。应用通过 SDK 下载模型或预置模型文件；模型管理、完整性校验和更新配置见 [接入指南](docs/getting-started.md)。

## 📄 许可

本仓库原创代码为 **MIT**，对第三方文件的修改仍遵守其原许可。
`engine/` 内捆绑的第三方组件各按其自身许可分发(含 MPL-2.0 的
Bergamot 翻译层文件),见 [NOTICE](NOTICE) 与各 vendor 目录内的
LICENSE 文件。模型为 Mozilla 官方发布，MPL-2.0。FLORES-200 等评测数据独立遵守数据许可。

---

<div align="center">

[![Star History Chart](https://api.star-history.com/svg?repos=yinvoke/foxlet-translate&type=Date)](https://www.star-history.com/#yinvoke/foxlet-translate&Date)

</div>
