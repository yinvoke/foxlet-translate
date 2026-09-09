<div align="center">

# Foxlet Translate

**面向 Android 的高性能本地翻译库：离线、隐私友好、针对 ARM 移动芯片优化**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#-构建)
[![minSdk](https://img.shields.io/badge/minSdk-28-blue)](#-范围与限制)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#-范围与限制)

[特性](#-特性) · [快速开始](#-快速开始) · [构建](#-构建) · [文档索引](docs/README.md) · [路线图](#-roadmap)

</div>

---

Foxlet Translate 是面向 Android 的高性能离线翻译库，基于 Mozilla [Firefox Translations](https://github.com/mozilla/translations) 使用的 [Bergamot](https://browser.mt/) 引擎、Marian 推理运行时与 Mozilla 翻译模型，提供 Kotlin API 和可直接集成的 AAR。

项目针对 ARM 移动芯片优化矩阵计算、批处理、线程调度和内存管理，根据设备能力选择 i8mm/SMMLA 或 ruy/SDOT 内核。模型下载后即可在设备上完成翻译，无需将文本发送到云端。引擎、模型及训练流程公开，开发者可自行构建、固定模型版本并调整推理参数。

## 文档入口

| 目的 | 文档 |
|---|---|
| 集成 AAR、下载模型、调用 Kotlin API | [快速开始](docs/getting-started.md) |
| 了解 Android/JNI/引擎/工具边界 | [代码结构与运行时架构](docs/architecture.md) |
| 本地构建、测试和真机基准 | [构建、测试与基准](docs/benchmarking.md) |
| 每版性能、原始数据与回归检查 | [benchmarks/](benchmarks/README.md) |

## ✨ 特性

- **离线推理**:全程无网络请求,模型来自 Mozilla 官方(MPL-2.0)
- **Mozilla 模型**:沿用 Firefox Translations 的官方模型，优化 Android 运行效率；引擎源码、模型与训练流程公开
- **Kotlin suspend API**:批量翻译、pivot 中转、HTML 感知翻译（实验性）
- **移动端适配**:i8mm / NEON 内核加速,不支持 i8mm 的设备自动回退 ruy
- **性能优化**:相较初版 Android 移植，首次翻译速度提升约 **102–112%**，峰值 RSS 降低约 **41–42%**（小米 14、默认单线程参考测量，见[性能参考](#-性能结果)）
- **智能分句**:按源语言自带 Moses 前缀表(25 种语言),`Dr.`、`U.S.`、`No. 5` 的句号不再被当成句尾
- **内存管理**:int8 embedding、模型按需加载、空闲自动卸载与释放确认,可挂 `onTrimMemory`
- **线程与调度**:单句走同步路径,批量按机型内存与快核数自动定档(`EngineConfig.forDevice`)

## 🚀 Android 性能优化

优化覆盖计算内核、推理调度、模型生命周期和构建产物：

- **ARM 内核适配**：支持具备 i8mm 的设备使用 SMMLA，其他 ARM64 设备自动回退到 ruy/SDOT，覆盖骁龙 865、8 Gen 1、8 Gen 3 等不同代际。
- **推理计算优化**：使用 int8 embedding/权重路径，优化 Attention 小矩阵计算、shortlist 和 batch 形状，默认 `mini-batch-words` 为 512。
- **低延迟路径**：一次一句的交互式翻译默认使用 BlockingService，减少 worker 派发和同步开销；批量任务才启用并行 worker。
- **设备感知调度**：根据内存、低内存标记和快核数量，在 1/2/4/6 个线程档位中自动选择，并支持显式覆盖。
- **内存生命周期**：模型按需加载，支持空闲自动卸载、`onTrimMemory` 主动释放和双模型 pivot 的内存权衡。
- **构建与兼容性**：通过链接裁剪与非 JNI 符号隐藏减小原生库体积，支持 16 KB page size，兼容具备和不具备 i8mm 的 ARM64 设备。

## 📈 性能结果

### 最新版相对初版的提升

小米 14（骁龙 8 Gen 3），FLORES-200 前 200 条源文，相同模型、各版本默认单线程配置。首次翻译包含模型加载，内存为原生进程峰值 RSS；数据为系统动态调频下三轮交替测量的中位数，仅作性能参考。

| 场景 | 未优化版本 v0.1.0 | 最新版 v0.3.0（参考） | 改善 |
|---|---:|---:|---:|
| 英→中，首次翻译速度 | 27.97 条/秒 | 56.61 条/秒 | **2.02×（+102.4%）** |
| 英→中，峰值 RSS | 348 MiB | 207 MiB | **−40.6%** |
| 日→英→中，首次翻译速度 | 12.79 条/秒 | 27.11 条/秒 | **2.12×（+111.9%）** |
| 日→英→中，峰值 RSS | 530 MiB | 308 MiB | **−41.8%** |

测试方法与原始数据见[小米 14 基准报告](benchmarks/v0.3.0/mi14-2026-09-09/initial-to-current/ungated/README.md)，各版本完整对比保存在 [benchmarks/](benchmarks/README.md)。

### 与 Google ML Kit 端侧翻译对比

小米 14（骁龙 8 Gen 3），使用 FLORES-200 前 200 条源文，对比 v0.3.0 与 [Google ML Kit 端侧翻译 SDK](https://developers.google.com/ml-kit/language/translation)。在这组英→中、日→中语料上，Mozilla 模型的 COMET 评分更高；Android 优化后的批量翻译速度中位数也更高，但内存开销仍大于 ML Kit。

**翻译质量**（COMET × 100，越高越好）：

| 方向 | Google ML Kit | v0.3.0 / Mozilla 模型 |
|---|---:|---:|
| 英→中 COMET × 100 | 72.69 | **87.27** |
| 日→中 COMET × 100 | 68.93 | **86.71** |

**翻译速度与内存**（实测中位数）：首次包含模型加载，热态为模型常驻后的翻译；PSS 为 app 进程内存。Bergamot 使用批量接口，ML Kit 按 SDK 逐条调用。

| 方向 | 引擎 / 本库线程 | 首次速度（条/秒） | 热态速度（条/秒） | 首次峰值 PSS（MiB） | 热态峰值 PSS（MiB） |
|---|---|---:|---:|---:|---:|
| 英→中 | ML Kit / SDK 默认 | 14.42† | 14.58 | 185.9 | 189.6 |
| 英→中 | v0.3.0 / 1 | 65.52 | 86.51 | 263.0 | 232.1 |
| 英→中 | v0.3.0 / 2 | 114.48 | 132.64 | 376.5 | 320.3 |
| 英→中 | v0.3.0 / 4 | 177.12† | 246.82 | 599.2 | 600.1† |
| 日→中 | ML Kit / SDK 默认 | 6.82 | 6.89 | 236.9 | 238.1 |
| 日→中 | v0.3.0 / 1 | 44.19† | 39.77 | 353.2 | 329.7 |
| 日→中 | v0.3.0 / 2 | 53.82† | 61.92 | 550.4 | 505.0 |
| 日→中 | v0.3.0 / 4 | 88.80† | 109.86 | 931.7 | 933.6 |

单线程首次翻译速度中位数为 ML Kit 的 **4.54×（英→中）/ 6.48×（日→中）**，首次峰值 PSS 为 **1.41× / 1.49×**。

> † 标记项的极差 / 中位数超过 10%（速度按耗时计算，最高约 41%），上述性能数据及倍率仅供参考。测试方法与原始数据见[基准报告](benchmarks/v0.3.0/mi14-2026-09-09/charged/README.md)。

## 🌍 支持的语言模型

`registry.json` 当前索引 **104 个模型、53 种语言**,全部以英语为轴:
51 种语言与英语互译,阿塞拜疆语仅英→阿、阿尔巴尼亚语仅阿→英。
具备“源语言→英语”和“英语→目标语言”两个模型时，可用 `translatePivot` 经英语中转（如日→中）。
模型由 Mozilla 随 Firefox 持续更新。`from` / `to` 为 `registry.json`
中的语向代码,可直接用于下载脚本(见[快速开始](#-快速开始))。

<details>
<summary>展开完整模型列表(104 个)</summary>

| 源语言 | 目标语言 | from | to | 版本 | 大小 |
|---|---|---|---|---|---|
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
| 英语 | 匈牙利语 | `en` | `hu` | 2.0 | 36 MB |
| 匈牙利语 | 英语 | `hu` | `en` | 1.0 | 23 MB |
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
| 俄语 | 英语 | `ru` | `en` | 1.1 | 23 MB |
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
| 乌尔都语 | 英语 | `ur` | `en` | 2.0 | 36 MB |
| 英语 | 越南语 | `en` | `vi` | 2.0 | 37 MB |
| 越南语 | 英语 | `vi` | `en` | 1.0 | 22 MB |
| 英语 | 中文(简体) | `en` | `zh-Hans` | 2.2 | 52 MB |
| 中文(简体) | 英语 | `zh-Hans` | `en` | 2.1 | 55 MB |
| 英语 | 中文(繁体) | `en` | `zh-Hant` | 2.0 | 49 MB |
| 中文(繁体) | 英语 | `zh-Hant` | `en` | 2.0 | 52 MB |

</details>

## 📁 仓库结构

```
bergamot/      Android 库与 Kotlin API
jni/           JNI 胶水层
engine/        Bergamot / Marian 引擎与第三方组件
patches/       上游引擎的 Android 适配补丁
sample/        示例与基准测试 app
tools/         构建、测试与性能分析工具
benchmarks/    版本基准与原始数据
docs/          集成、架构与开发文档
registry.json  Mozilla 模型下载索引
```

工具和基准 app 不会打包进 AAR。模块职责与上游维护方式见[代码结构与运行时架构](docs/architecture.md)。

## 🚀 快速开始

### 引入 AAR

从 [v0.3.0 Release](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.3.0) 下载 `bergamot-v0.3.0.aar`，放入应用模块的 `libs/` 目录：

```kotlin
dependencies {
    implementation(files("libs/bergamot-v0.3.0.aar"))
    // 本地 AAR 不携带传递依赖,须自行声明:
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

也可运行 `./gradlew :bergamot:assembleRelease` 从源码构建 AAR，产物为 `bergamot/build/outputs/aar/bergamot-release.aar`，依赖文件名需相应调整。

### 获取模型

模型是 Mozilla 为 Firefox 发布的官方翻译模型(MPL-2.0),经
Firefox Remote Settings 分发;`registry.json` 是其索引快照,记录每个
语向的文件 URL、sha256 与大小。每个方向 3–4 个文件(模型、
SentencePiece 词表、lexical shortlist),合计 21–55 MB,下载到同一个
目录即可——`ModelFiles.fromDirectory` 按文件名识别,目录名随意:

```bash
python3 - <<'EOF'
import json, hashlib, urllib.request, pathlib
src, dst = 'en', 'zh-Hans'                    # 语向,取值见 registry.json
m = next(x for x in json.load(open('registry.json'))['models']
         if x['from'] == src and x['to'] == dst)
out = pathlib.Path('models/enzh'); out.mkdir(parents=True, exist_ok=True)
for f in m['files']:
    p = out / f['name']
    urllib.request.urlretrieve(f['url'], p)
    assert hashlib.sha256(p.read_bytes()).hexdigest() == f['sha256'], f['name']
    print('ok', f['name'])
EOF
```

集成到 app 时同理:运行时下载到应用私有目录,按 sha256 校验后交给
`ModelFiles.fromDirectory`。

### 调用

```kotlin
// 在后台协程中调用；实际应用可长期持有 engine，在宿主销毁时关闭。
BergamotEngine(EngineConfig()).use { engine ->
    val model = ModelFiles.fromDirectory(File(modelsDir, "enzh"))
    val translated = engine.translate(listOf("Hello, world."), model)
}
```

`releaseAllModels()` 的 Future 完成后可确认释放结果;如需等待,请在后台线程调用
`get()`,不要阻塞主线程。从 v0.1.0 升级需重新编译调用方。

**同一时刻只能有一个 `BergamotEngine`**(底层 marian 运行时持有进程级
全局状态)。`close()` 等原生侧释放完毕才返回,之后可以再建一个。

### 分句与前缀表

引擎先按 `.`、`?`、`!` 把段落切成句子再翻译。`ModelFiles.fromDirectory`
从模型文件名推断源语言(`model.enzh.*.bin` → `en`),加载时自动带上该语言的
Moses 前缀表，避免将 `Dr. Smith`、`U.S.`、`No. 5` 等缩写中的句号误判为句尾。
宿主已完成分句时，可使用 `EngineConfig(nonbreakingPrefixes = false)` 关闭前缀表；
没有表的语言(日、韩、泰等)行为不变。25 张表随 AAR 打包,约 120 KB。

### 空闲卸载

模型在首次使用时加载,空闲超过 `idleUnloadMillis`(默认 60 s)后由引擎线程上的
定时任务自动卸载，下一次 `translate` 自动重新加载。
`0` 表示翻完一批立刻卸,负数表示不自动卸(基准或自行管理释放时用);
`loadedModelCount()` 返回当前常驻模型数。退到后台不必等计时器,直接释放:

```kotlin
override fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) engine.releaseAllModels()
}
```

### 选择线程数

`EngineConfig()` 默认使用 1 个线程，适合一次一句的交互式翻译。批量任务可按设备能力自动选择线程数：

```kotlin
val config = EngineConfig.forDevice(context, Workload.BATCH)   // 双模型中转用 Workload.PIVOT
BergamotEngine(config).use { engine -> /* … */ }
```

`forDevice` 根据可用设备信息，在 1 / 2 / 4 / 6 个线程中选择配置；双模型中转使用 `Workload.PIVOT`。可通过 `EngineConfig(threads = 4)` 显式指定，并从 `config.tuning` 查看自动选择依据。

更多线程通常能提高批量吞吐，也会增加内存占用。持续翻译可能受到设备温控影响，应用应根据内存预算与交互延迟选择配置。详细用法见[集成指南](docs/getting-started.md)。

## 🔨 构建

以下是最短构建路径；完整环境要求、SMMLA 测试和真机验证见
[构建、测试与基准](docs/benchmarking.md)。

Android AAR 可通过 NDK 在 ARM 或 x86_64 主机交叉编译;主机 smoke CLI
仅支持 ARM(Apple Silicon / ARM Linux)。依赖:JDK 17、Android SDK、
NDK r29、CMake 3.31.6。

```bash
./gradlew :bergamot:assembleRelease          # AAR
./gradlew :sample:assembleDebug              # 基准 app
cmake -B build-host -DCMAKE_BUILD_TYPE=Release -DSSPLIT_USE_INTERNAL_PCRE2=ON \
  -DCOMPILE_TESTS=OFF && cmake --build build-host --target smoke   # 主机 CLI
```

真机一键基准(结果写入 app files 目录的 JSON,含每阶段内存/CPU 曲线):

```bash
adb shell am start -n io.github.yinvoker.bergamot.bench/.MainActivity \
  --ez autorun true --ei threads 2
```

## ⚠️ 范围与限制

- 仅 arm64-v8a,minSdk 28,支持 16 KB page size。
- int8 矩阵乘按 CPU 能力选择 i8mm SMMLA 或 ruy(含 SDOT)内核。
  已验证设备见 [兼容性清单](docs/smmla-compatibility.md)。
- 分句前缀表取自 ssplit-cpp 上游,覆盖 25 种源语言;`etc.`、`Approx.`、`Jan. 4`
  这类不在表里的缩写仍会被切句。
- 输出与输入一一对应,库不做「翻过就跳过」的去重:同一句每次调用都会重翻。
  网页 / 文档场景的已译状态由宿主按「节点身份 + 源文版本 + 目标语言 + 模型版本」
  维护(重新加载可复用、节点改动与切换语言必须重译),不要用全局译文字符串集合跳过输入。
- 输出一致性：`cacheSize = 0` 时，相同输入列表、模型与线程配置可在不同进程中得到一致输出。
  改变线程数可能影响短批次的组批方式；开启缓存后，部分译文也可能与未命中缓存时不同。

## 🗺️ Roadmap

- [x] 基础引擎与 NDK 移植
- [x] CI 构建与测试
- [x] AAR 发布
- [x] GEMM 内核优化
- [x] Attention 小矩阵计算优化
- [x] 内存占用与模型释放优化
- [x] 多线程与调度优化
- [x] 参数与批处理调优
- [ ] HTML 模式验证
- [ ] SME2 指令集支持
- [x] 构建优化

## 🤝 参与贡献

欢迎提交问题报告、兼容性反馈和 Pull Request。开发环境、测试要求与上游补丁维护约定见[贡献指南](CONTRIBUTING.md)。

## 📦 库、第三方组件与模型

本项目的 Android 封装、JNI 胶水层、sample、工具和构建脚本由本项目维护，按
[MIT](LICENSE) 许可发布。下面列出随仓库构建或打包的主要开源组件；更完整的来源与许可
索引见 [NOTICE](NOTICE) 和各 vendor 目录中的许可文件。

### 引擎与运行时

- [Bergamot translator](https://github.com/mozilla/translations)：Mozilla Firefox 本地翻译引擎，MPL-2.0；本项目使用其 `inference/` 代码并做 Android/ARM 适配。
- [Marian NMT](https://github.com/marian-nmt/marian)：C++ 神经机器翻译运行时，MIT；本仓库使用 Bergamot 维护的 fork。
- [ruy](https://github.com/google/ruy)：ARM CPU 矩阵乘后端，Apache-2.0；用于非 SMMLA 设备的 int8/SDOT 回退路径。
- [SentencePiece](https://github.com/google/sentencepiece)：模型分词与词表处理，Apache-2.0。
- [ssplit-cpp](https://github.com/browsermt/ssplit-cpp)：句子切分与 non-breaking prefix 数据，Apache-2.0 及其数据源对应条款。
- [PCRE2](https://github.com/PCRE2Project/pcre2)、cpuinfo、zlib、pathie-cpp、faiss 子集和 `half_float`：分别按各自目录中的 BSD/MIT/Apache 或其他许可分发。

### Android 依赖与基准工具

- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines)：Android suspend API 的协程调度，Apache-2.0。
- [Google ML Kit Translate](https://developers.google.com/ml-kit/language/translation)：只用于 `sample/` 基准 app 的对比评测，不是 Bergamot AAR 的翻译后端；依赖其自身服务条款。
- [FLORES-200](https://github.com/facebookresearch/flores)：sample 基准语料，按 CC-BY-SA 4.0 使用；它只用于测量，不随库运行时提供。

### 翻译模型

模型来自 Mozilla Firefox Remote Settings 发布的 [Bergamot 模型索引](registry.json)，模型文件按 Mozilla 对应发布许可（当前索引为 MPL-2.0）分发。模型不内置在 AAR 中，应用需要自行下载或随应用部署，并使用 `registry.json` 中的 SHA-256 校验。

## 📄 许可

本仓库自有代码(jni/、bergamot/、sample/、tools/、构建脚本)为 **MIT**。
`engine/` 内捆绑的第三方组件各按其自身许可分发(含 MPL-2.0 的
Bergamot 翻译层文件),见 [NOTICE](NOTICE) 与各 vendor 目录内的
LICENSE 文件。模型为 Mozilla 官方发布,MPL-2.0。

---

<div align="center">

[![Star History Chart](https://api.star-history.com/svg?repos=yinvoke/foxlet-translate&type=Date)](https://www.star-history.com/#yinvoke/foxlet-translate&Date)

</div>
