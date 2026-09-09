# Android app 对比与质量复测

本工具对应新增的 **`android-app-v1`**，用于 v0.3.0 AAR 与 Google ML Kit 的 app 级比较。它不替换 [`android-native-v1`](../version-bench/README.md) 的固定八场景，也不把 app PSS 与 native RSS 混算。旧版 150 条语料的图表另存于[历史归档](../../benchmarks/v0.1.0/mlkit.md)。

## 固定协议

每个版本保留以下八行，不能根据结果删减：

| 方向 | 引擎 | 本库线程配置 |
|---|---|---:|
| 英→中 | ML Kit | SDK 不提供线程控制 |
| 英→中 | Bergamot | 1 |
| 英→中 | Bergamot | 2 |
| 英→中 | Bergamot | 4 |
| 日→英→中 | ML Kit | SDK 内部中转，不提供线程控制 |
| 日→英→中 | Bergamot | 1 |
| 日→英→中 | Bergamot | 2 |
| 日→英→中 | Bergamot | 4 |

- FLORES-200 devtest 前 200 条，输入来自仓库 `sample/src/main/assets/bench/`；双方使用同一批源文。Bergamot 使用 AAR 批量接口，ML Kit 按 SDK 接口逐条调用。
- 每行三个独立进程，场景顺序为正序 / 逆序 / 正序；每进程完整翻译三遍。首遍包含引擎创建、模型加载和翻译，后两遍保持引擎与模型常驻。下载在另一进程预先完成，双方均不做额外预热。
- 每行固定记录首次速度、热态速度、首次峰值 PSS、热态峰值 PSS，以及输出哈希、状态和原始结果。首次速度为 `200000 / 首遍耗时中位数 ms`；热态先取每进程后两遍耗时中位数，再取三个进程的中位数换算。
- PSS 每 250 ms 采样，报告绝对 app PSS，包含 JVM/UI；不扣空载值。每进程热态峰值取后两遍峰值的较大值，再取三个进程的中位数。短暂内存尖峰可能被采样遗漏。
- Bergamot 结果缓存关闭，ML Kit 不公开缓存控制。系统文件缓存不清空，因此“首次”不是冷磁盘启动。
- 每次先强制停止基准 app，再打开不创建引擎的空闲页面，在前台等待频率恢复；随后重建测试 Activity。保持屏幕常亮，不使用 taskset。不要退回桌面或与其他基准并行运行。
- 新采集器追加 `foreground_checks: awake-unlocked-focused-before-after-v1`：每进程前后检查屏幕为 Awake、锁屏关闭、焦点属于基准 app，任一缺失或不满足即停止并保留结果。`am start` 返回成功不等于设备已解锁。这是运行条件的额外证据，不改八场景、重复次数或计时口径；只采集前后快照，不宣称连续监控全程前台状态。
- 当前设备布局为小米 14：起跑时 CPU 2 / 7 的频率上限均不低于 2630400 kHz，最多等待 180 秒；进程间休息 15 秒。门槛不是锁频，前后温度与频率上限均保存。
- APK、输入语料、模型文件、源码和设备都有指纹记录。ML Kit 依赖固定为 `com.google.mlkit:translate:17.0.3`；实际下载的模型以文件 SHA-256 标识，不能仅凭 SDK 版本认定模型相同。

新增语向、线程档位、设备布局或指标时，保留这些行及其定义，新增协议版本；更换模型、语料或计时方法时重跑双方，不覆盖历史结果。

## 构建、准备与运行

需要 JDK 17、Android SDK/NDK 和 Python 3.9+。先[构建基准 app](../../docs/benchmarking.md)；debug app 的**原生库仍使用 Release 优化**，但报告中需要保留 APK 指纹，不能直接与任意另一 APK 混比。

```bash
./gradlew :sample:assembleDebug
adb -s DEVICE install -r sample/build/outputs/apk/debug/sample-debug.apk

# 单独准备 ML Kit 模型；Bergamot 模型使用 sample 的 files/models 目录。
python3 tools/app-bench/run.py prepare /path/to/new-prepare \
  --adb /path/to/adb --serial DEVICE \
  --apk sample/build/outputs/apk/debug/sample-debug.apk

# 完整三轮；不同时运行 native benchmark 或其他 app 测试。
python3 tools/app-bench/run.py measure /path/to/new-measure \
  --adb /path/to/adb --serial DEVICE \
  --apk sample/build/outputs/apk/debug/sample-debug.apk

python3 tools/app-bench/check.py /path/to/new-measure \
  --output /path/to/new-measure/summary.json
```

目录必须不存在。工具不会自动安装 APK，先检查已安装包与 `--apk` 的哈希一致；不清除 app 数据、不删除模型和既有记录。每次使用唯一 run ID，原始结果保存在 app 内部 `files/isolated_<id>.json` 并复制到主机。读取撞上 app 写文件时，不完整的 UTF-8/JSON 会在原有超时范围内重读整份快照，不替换字符、不修补译文。归档时保留 `results.json`、各 run JSON 与 `source-files.json`。

`quality` 模式仅导出一轮八场景的译文，不等待频率门槛；其计时不能晋升为正式性能结果。`prepare` 模式不产生质量或速度结论。

检查器要求完整 24 个进程、模型不变、有效 PSS 曲线、正确输出哈希和满足起跑门槛。起跑温度跨度超过 3°C、同场景三进程任一指标极差超过中位数 10%，或相同调用输出变化，均需复查/复测，不能标为正式基线通过。退出码 0 表示通过，2 表示证据不完整或未通过稳定性检查；原始记录一律保留。

声明新版前台检查标记的报告必须包含有效的前后状态。旧报告不补写字段，仍按其原有证据检查；缺少标记不意味着已验证全程前台。新基线应使用带此前台记录的采集器。

## COMET 质量评分

从 [FLORES 官方发布入口](https://github.com/facebookresearch/flores/blob/main/flores200/README.md#download)取得原始 FLORES-200，使用 `devtest` 下的 `eng_Latn`、`jpn_Jpan` 和 `zho_Hans`，不要换成内容不同的新数据集。评分脚本会先核对英、日输入的前 200 行与 app 语料完全一致。

```bash
python tools/app-bench/score.py /path/to/new-measure \
  /path/to/flores200_dataset/devtest /path/to/new-scores.json \
  --checkpoint /path/to/wmt22-comet-da/checkpoints/model.ckpt
```

需要 `unbabel-comet`、PyTorch 及模型依赖，使用本地可信的 `wmt22-comet-da` checkpoint。模型来源见 [Unbabel 模型说明](https://huggingface.co/Unbabel/wmt22-comet-da)。输出记录 checkpoint 和参考译文哈希、依赖版本、随机种子、逐条分数及整体 COMET × 100。相同语向、完全相同的译文只计算一次，并映射回所有实际调用，避免重复评分。

默认拒绝未完成的采集。如果设备断开，但八个场景都已有完整的 200 条译文，可以显式追加 `--completed-quality-only`，仅为已完成调用计算质量分数。结果会列出原始报告的失败状态、已完成/计划进程数和所有缺项；它不补齐三轮性能结果，也不证明模型文件在整个场次中未变。

### 旧版原生路径的质量参考

`reference.cpp` 是只导出译文、不提供计时结论的辅助程序。它可针对[版本工具](../version-bench/README.md)导出的引擎源码构建，不修改原生计时程序：

```bash
cmake -S /path/to/exported-version -B /path/to/exported-version/build \
  -DCMAKE_PROJECT_INCLUDE=/absolute/path/to/tools/app-bench/reference.cmake
cmake --build /path/to/exported-version/build --target quality_reference
```

在设备上运行 `quality_reference workers config.yml [pivot-target.yml] < corpus.txt`，输出 200 个译文组成的 JSON 数组。worker 0 使用 blocking，正数使用 async；模型、语料、参数、二进制与源码指纹须与结果一同归档。不能把它叫作旧版 AAR 端到端实测。新版辅助程序的输出还应与新版 AAR 对照，确认路径一致。

评分时可追加 `--reference-output LABEL enzh /path/to/outputs.json` 或 `jazh`，保留版本和场景标签。旧 async 路径可能因组批改变输出，应保留重复进程的译文，不以单份输出证明“质量完全不变”。

## 工具自测

```bash
python3 -m unittest discover -s tools/app-bench -p 'test_*.py' -v
```

CI 只验证合成数据的检查逻辑，不伪装成手机性能测试。正式结果按版本保存到 [benchmarks/](../../benchmarks/README.md)。
