# 版本基准工具

Python 3.9+、CMake、NDK r29、adb 和固定 ARM64 真机。工具只测 native harness，不包含 Android/JNI 或 app PSS。报告入口与判定规则见 [benchmarks/](../../benchmarks/README.md)。

## 1. 构建版本

在仓库根目录运行，输出目录必须在仓库外且没有同名构建：

```bash
BENCH_BUILD=$(mktemp -d /tmp/bergamot-version-build.XXXXXX)
python3 tools/version-bench/build.py --ndk "$ANDROID_NDK_HOME" \
  --output "$BENCH_BUILD" --refs v0.2.0 --working-tree-version v0.3.0
```

`v0.3.0` 在此是本地快照标签，不要求先提交、打 tag 或推送。快照包含已跟踪及未忽略的新文件，不包含构建产物/下载模型。测量期间不要并行修改源码。

每版输出源码快照、`<version>/build/tools/smoke/smoke`、`<version>/build-manifest.json` 和便于推送的 `smoke-<version>`。manifest 记录 HEAD、未提交状态、原生输入摘要、harness/二进制 SHA-256、NDK 与 CMake 配置；导出树里的空 Git commit 仅供上游 CMake 生成版本头，不是被测提交。

只构建本地候选版：

```bash
python3 tools/version-bench/build.py --ndk "$ANDROID_NDK_HOME" \
  --output "$BENCH_BUILD" --refs --working-tree-version candidate
```

后续有正式 v0.3.0 tag 后，可使用 `--refs v0.3.0 --working-tree-version candidate` 在一个新目录中构建两版。没有 tag 时，继续使用已保存的 v0.3.0 快照与 manifest，不要把移动中的 main 当成固定基线。

## 2. 准备设备

目前提供 `mi10` 和 `mi14` 两个固定布局，不会自动识别或调参；其他机型需先添加并验证布局。用 `adb devices -l` 确认设备，再传 `--serial`，不要误选模拟器。

保持手机屏幕常亮、前台环境一致。可以打开 `sample/` 的空闲主界面（**不启动 app 翻译测试**），其 `KEEP_SCREEN_ON` 会保持显示；原生工具本身不会自动操作界面。不要在原生运行期间退回桌面、切换应用或并行跑 app 基准。前台/息屏状态可能影响厂商调度，频率门槛超时应检查环境，不要调低门槛。

设备必须先解锁；`am start` 成功并不证明 app 已获得前台。新版采集器通过共享的 `tools/bench_device.py` 在每进程前后读取 Awake/锁屏/焦点状态，写入 `before/after.foreground`，并声明 `foreground_checks: awake-unlocked-focused-before-after-v1`。状态缺失、锁屏或焦点离开基准 app 时，停止并保留已采集结果。前后快照不是全程连续监控；原有频率门槛、八场景与计时范围不变。

带此前台标记的报告由检查器强制核对状态，旧场次不补写未观测字段。采集器与共享检查代码的 SHA-256 另存于 `collector_files_sha256`，不与原生计时二进制的 `harness_sha256` 混淆。

| 配置 | 小米 10 | 小米 14 |
|---|---|---|
| 基础目录 | `/data/local/tmp/bg` | `/data/local/tmp/bergamot` |
| CPU 亲和性 | `f0`（CPU 4–7） | `7c`（CPU 2–6） |
| 起跑前监测 | CPU 4 / 7 频率上限 | CPU 2 / 7 上限 ≥ 2630400 kHz |
| 进程间等待 | 4 s | 8 s，另等频率恢复，最多 180 s |

基础目录需要下列文件；模型目录可以放在别处，但 YAML 中必须使用设备上的绝对路径：

- `eng200.txt`、`jpn200.txt`：分别从 `sample/src/main/assets/bench/eng.txt`、`jpn.txt` 推送，不能换语料。
- `nonbreaking_prefix.en`：来自 `engine/3rd_party/ssplit-cpp/nonbreaking_prefixes/`。
- `config-mbw1024.yml`、`config-jaen.yml`：enzh / jaen，batch 1024，无前缀表。
- `config-mbw512on.yml`、`config-jaen512.yml`：相同模型，batch 512；仅 enzh 添加 `ssplit-prefix-file: <基础目录>/nonbreaking_prefix.en`。
- enzh 的模型、src/trg 词表、shortlist 四个文件；jaen 的模型、共享词表、shortlist 三个文件。下载与校验方法见 [接入指南](../../docs/getting-started.md)。

完整可参考的 YAML 已存于 [小米 14 历史 JSON](../../benchmarks/v0.3.0/results-mi14.json) 的 `config_yaml`，复制对应内容并按实际设备路径调整。协议固定 beam 1、shortlist 开启、alignment soft、cache 0；`workspace: 128` 仅为兼容旧版保留，目前不起作用。路径使用模板中的未加引号绝对路径，不支持任意 YAML 语法。工具记录实际文件 SHA-256，不只记录文件名。

## 3. 配对测量与检查

```bash
python3 tools/version-bench/run-pair.py mi14 "$BENCH_BUILD/results-mi14.json" "$BENCH_BUILD" \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial YOUR_DEVICE \
  --baseline v0.2.0 --candidate v0.3.0

python3 tools/version-bench/check.py "$BENCH_BUILD/results-mi14.json" "$BENCH_BUILD/results-mi14.json" \
  --baseline-version v0.2.0 --candidate-version v0.3.0
```

默认按 [`android-native-v1`](suites/android-native-v1.json) 运行完整的八场景，固定记录首次耗时、热态耗时、峰值 RSS。每个场景两版使用**相同**参数；既有场景不会跟随产品默认值改变。`workers = 0` 表示 BlockingService 的单线程，不是零线程。

检查器对外输出 `cold_speed` / `warm_speed`（条源文/秒、倍率、速度变化），同一行附原始毫秒数。它们是固定计时结果的倒数换算，不是新增测量维度；例如耗时减半对应速度 2 倍（+100%）。回归仍按原耗时/RSS 阈值判定，未改成另一套速度阈值。公式和示例见 [速度展示口径](../../benchmarks/README.md#速度展示口径)。

临时排查时可以只测部分场景，例如追加：

```text
--scenarios enzh_b512p pivot_b512p
```

指定 `--scenarios` 的报告标记为 `exploratory`，不会通过完整版本基线检查（即使两版都只跑了相同子集）。正式版本报告须保留所有行，缺项标 `未测` / `不支持` / `失败`，模板见 [TEMPLATE.md](../../benchmarks/TEMPLATE.md)。

需要在当前系统调频状态下直接比较时，可显式追加 `--skip-frequency-gate`。该选项对两版同时生效：不等待频率上限恢复，但仍保留频率、温度、前台核验、进程间等待和完整三轮交替顺序。报告标记为 `exploratory`，记录 `gate_khz: null`、`frequency_gate_mode: skipped-explicitly` 和原参考门槛；不能作为严格回归基线。描述性速度与内存对比须注明未等待频率恢复，单独归档，不能把较慢场次仅用于旧版、较快场次仅用于新版。

其中 `w*` 场景走旧的 AsyncService 逐条提交，不走当前 AAR 的批量 JNI 接口，不能用于验证 AAR 多线程稳定输出。严格输出哈希检查只作用于 blocking 场景；质量验收仍需另跑 `tools/regress-hash.sh` 或对应 AAR 测试。

工具不会覆盖已有结果文件；每轮落盘，崩溃和频率等待超时都保留。若 adb 在翻译中超时，远程进程可能仍运行，检查设备后再重测。每次使用独立远程目录，路径记录在 JSON 的运行命令中；不会清空之前的设备实验目录，测量结束后可自行清理明确的本轮目录。

默认门槛、退出码和历史报告为什么不能直接通过检查，见 [回归规则](../../benchmarks/README.md#回归检查)。保存结果前，人工检查失败、温度、频率、波动及输出哈希；不要只看一个 PASS。

## 工具边界

- `main.cpp`：跨版本共用的原生计时程序，首遍 + 两遍热态、输出哈希及 VmHWM。
- `build.py`：导出源码、构建并记录来源；不修改当前 Git 分支或创建发布 tag。
- `run-pair.py`：指定设备上的配对实验及完整元数据采集。
- `check.py`：标准库实现的可比性与回归检查，适合固定设备流水线。
- `suites/` / `suite.py`：固定场景、指标和重复协议，采集与检查共用。新增维度保留原定义并使用新清单 ID；现有清单不就地修改。新增指标还需同步实现采集和检查。
- `test_check.py` / `test_run_pair.py`：合成数据及模拟 adb 覆盖通过、退步、缺失、崩溃、超时、噪声与哈希变化，CI 不伪装成真机测试。
- `run.py` / `plot.py`：保留用于 v0.1.0 → v0.2.0 历史协议与图表复现；不要用它们覆盖历史记录。
