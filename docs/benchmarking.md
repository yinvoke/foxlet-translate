# 构建、测试与基准

## 环境

- JDK 17
- Android SDK、NDK `29.0.13113456`
- CMake `3.31.6`
- Android 真机验证需要 `arm64-v8a` 设备；主机 smoke 需要 ARM 主机

## 常用命令

```bash
# Kotlin/JVM 单元测试
./gradlew :foxlet:test

# 模型索引离线回归（与 Kotlin 共用 changeset 样本）
python3 -B -m unittest discover -s tools/distribution -p 'test_*.py' -v

# 发布 AAR
./gradlew :foxlet:assembleRelease

# 基准 app
./gradlew :sample:assembleDebug

# 主机 smoke
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DSSPLIT_USE_INTERNAL_PCRE2=ON -DCOMPILE_TESTS=OFF
cmake --build build-host --target smoke
```

SMMLA 正确性与指令扫描：

```bash
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DSSPLIT_USE_INTERNAL_PCRE2=ON -DCOMPILE_TESTS=OFF \
  -DBUILD_SMMLA_TEST=ON
cmake --build build-host --target smoke smmla_test ab_bench
SMMLA_TEST_REQUIRE_I8MM=1 ctest --test-dir build-host --output-on-failure
tools/smmla-test/check-opcodes.sh build-host/tools/smoke/smoke "$(xcrun -f llvm-objdump)"
```

完整说明见 [SMMLA 测试集](../tools/smmla-test/README.md) 和 [兼容性清单](smmla-compatibility.md)。

## 真机验证

基准 app 的启动方式：

```bash
adb shell am start -n io.github.yinvoker.foxlet.bench/.MainActivity \
  --ez autorun true --ei threads 2
```

报告至少应记录：设备型号与 SoC、Android 版本、模型版本、语料句数、线程数、`mini-batch-words`、是否启用 i8mm、温度/频率状态、计时是否包含模型加载，以及内存指标是 RSS 还是 PSS。不同口径不要直接放在同一张图里比较。

哈希回归入口：

```bash
tools/regress-hash.sh build-host/tools/smoke/smoke \
  /path/to/enzh-config.yml sample/src/main/assets/bench/eng.txt \
  /path/to/jaen-config.yml sample/src/main/assets/bench/jpn.txt
```

替换配置路径，确保模型参数与脚本注释中的正典条件匹配（host 使用 batch 1024，device 使用 512）；不要把已有前缀表配置再重复追加。它验证 ruy/SMMLA 路径和正典输出；如果修改了浮点累加顺序、batch 组成或缓存策略，应先说明预期差异，再更新对应基线和文档。

## 版本性能与防回归

版本数据统一保存到 [benchmarks/](../benchmarks/README.md)。可从 tag 或未提交的本地代码构建相同 harness，记录原始样本与输入指纹，并检查耗时/RSS 是否退步。命令见 [版本基准工具](../tools/version-bench/README.md)。不要把 native RSS 基准当成 AAR/JNI、内存卸载或 app PSS 的验收替代品。

## CI 与发布

`.github/workflows/build.yml` 在 Ubuntu 上构建 AAR、运行 JVM 测试和构建 sample；在 macOS ARM 与 Linux ARM 上运行主机 smoke、SMMLA 测试及指令隔离检查。macOS 兼容不支持 i8mm 的设备，Linux ARM 必须实际执行 i8mm 测试，不能跳过。发布由 `.github/workflows/release.yml` 的 `v*` tag 触发，并要求存在对应的 `.github/releases/<tag>.md`。

普通构建工作流在 `main` 推送或 Pull Request 时运行；单独推送开发分支不会触发它。当前分支、推送预检与重新发布的步骤见 [推送与发布准备](releasing.md)。

不要提交 `build/`、下载模型或临时 logcat；经检查的版本基准原始 JSON 应保存在 `benchmarks/<version>/` 并挂到索引。CI 验证基准检查器自身的测试，固定设备上的实测仍需单独运行。


## 发行构件与 Android 门禁

```bash
python3 tools/distribution/sync_catalog.py --check
./gradlew :foxlet:test :foxlet:packageWithoutPrefixes :demo:assembleRelease :demo:assembleReleaseAndroidTest
python3 tools/distribution/check_hardening.py
python3 tools/distribution/check_public_api.py
python3 tools/distribution/verify_artifacts.py --aar foxlet/build/outputs/aar/foxlet-release.aar --no-prefixes foxlet/build/outputs/aar/foxlet-no-prefixes-release.aar --apk demo/build/outputs/apk/release/demo-release.apk
```

`python3 tools/distribution/fetch_registry.py --check` 会报告 `registry.json` 与 Mozilla 当前面向 Android 正式版发布的模型之间的差异（新增或移除的语向、版本变化、哈希变化）；不带 `--check` 运行则刷新快照，之后需重新运行 `sync_catalog.py` 生成 `models.tsv`。

设备解锁并首次联网下载 Mozilla 模型，设置 adb 设备序列号后运行：

脚本会依次安装 Demo 和测试 APK。MIUI 等系统若显示 USB 安装确认，需要分别允许两个包；`INSTALL_FAILED_USER_RESTRICTED` 表示安装被手机限制或确认已取消，应处理手机提示后重试。首次测试还会下载约 52 MB 的英译中模型，耗时取决于设备到 Mozilla CDN 的网络速度。

```bash
python3 tools/distribution/run_device_tests.py --serial YOUR_DEVICE_SERIAL --apk demo/build/outputs/apk/release/demo-release.apk --test-apk demo/build/outputs/apk/androidTest/release/demo-release-androidTest.apk --output build/device-result.json
```

该测试没有缺模型时的跳过分支，必须完成真实翻译、Unicode 输入、损坏模型拒绝、释放后重建、许可资源读取，以及模型枚举/复用/占用保护/删除。更新检查会请求真实索引；不要求历史模型始终被上游保留，精确版本筛选和撤回行为由离线样本测试覆盖。
测试目标是经过 R8 的最终 AAR 消费 app。ARM64 模拟器可做功能验证，但不能用于真机性能结论。
发布工作流要求 `android-arm64` 标签的隔离 self-hosted runner（可以是连接 ARM64 设备/模拟器的 macOS/Linux 主机），
配置仓库变量 `FOXLET_ANDROID_SERIAL` 并确保 Python 3/adb 在 PATH；该门禁通过前不会发布 Release。
常规 PR CI 不接触自托管设备，只执行构建、R8、包内容与主机检查。

独立原生安全测试：

```bash
clang++ -std=c++17 -O1 -g -fsanitize=address,undefined -I . tools/safety-tests/native_safety_test.cpp -o /tmp/foxlet-native-safety
/tmp/foxlet-native-safety
```

FLORES-200 仅是评测集，引用和许可见 [CITATION](../benchmarks/CITATION.md)。
发行 AAR/demo 的包检查会拒绝混入 benchmark 文件或已知评测原文样本。

## v0.4.0 统一 API 验证记录

2026-09-13 在 `codex/unified-api` 实施破坏性重构，基线为 `e699e1c`，实现提交为 `fa351ed`。移除旧公开入口，统一客户端、配置、模型引用、网络选项、报告、异常和生命周期；Demo、sample 和设备测试同步迁移。实现契约见 [API 设计](api-design-review.md)，用法见 [接入指南](getting-started.md)。

- Kotlin/JVM：153 项通过，无失败、错误或跳过。新增测试覆盖配置一致性、安装对象贯穿完整流程、本地优先与纯离线选择、四种校验状态、更新重试、回调异常、部分删除、客户端取消/关闭竞争，以及实际引擎队列上的 AfterRequest 与未确认释放保护。
- Release AAR、无前缀 AAR、R8 Demo、Release 测试 APK、sample Debug APK 和库的 Debug instrumentation APK 构建通过；foxlet/demo Lint 无错误。
- 公开 API 检查通过：58 个公开顶层类型生成 JVM 签名快照；最终 AAR 不含旧入口类。构建与发布 CI 已接入检查，快照范围见 [说明](../api/README.md)。
- 产物许可、源码说明、前缀资源、评测数据隔离及 Android hardening 检查通过；内置模型索引同步检查通过。
- Python：索引工具 4 项、版本基准工具 36 项、App 基准工具 26 项通过。
- 主机 SMMLA 测试、禁用后端探针及 ASan/UBSan 原生输入安全测试通过。固定 SHA-256 模型的 200 句输出回归通过：en→zh-Hans 为 `16537889a77b25db`，ja→en→zh-Hans 为 `e9d84f82b99250ee`，两种 GEMM 路径均与既有正典一致。这验证输出一致性，不构成新的性能结论。

**新 API 的真机验收尚未执行。** 新 Demo 与测试 APK 已构建，等待设备安装确认；下方历史真机记录不证明本次重构后的构件。v0.4.0 标签与发布流程保持撤回，v0.3.0 Release 已撤下，v0.3.0 历史标签仍保留。当前没有重新发布。

## 统一 API 前的模型管理审查记录（历史）

以下记录对应 2026-09-12 至 13 日的模型管理功能审查构件，尚未写入 0.4.0 版本号。随后旧 v0.4.0 CI 完成构建，但设备验收中断，发布流程已取消；这些记录均早于统一 API 重构。

- Kotlin/JVM：132 项通过，无失败或跳过；Python：索引工具 4 项、版本基准工具 36 项、App 基准工具 26 项通过。
- Release AAR、无前缀 AAR、R8 Demo、Release 测试 APK 和 sample Debug APK 构建通过；foxlet/demo Release Lint 无错误，分别有 5/25 条警告。
- 产物许可、源码说明、前缀资源、评测数据隔离及 Android hardening 检查通过。
- 当前路径下的 `build-host-audit` 主机 smoke 构建通过，固定 SHA-256 的 en→zh-Hans 模型完成两句真实翻译。指向仓库改名前路径的 `build-host`、`build-host-ruy` 和 `build-android` 旧缓存已在发布整理时删除。
- `sync_catalog.py --check`、上游索引只读比对、106 个语向的 README 版本表、文档相对链接和 `git diff --check` 通过。

2026-09-13 补充真机验证：Xiaomi 12（2201123C，Android 13 / API 33，arm64-v8a）安装并运行本次经过 R8 的 Release Demo 和测试 APK。安装后读取手机上的 APK SHA-256，与本地构件逐一比对一致；`DeliveryTest` 返回 `OK (1 test)`，无失败或跳过。验证覆盖真实翻译、Unicode、损坏模型拒绝、释放后重建、许可资源、模型枚举/复用/清理、占用时拒绝删除、释放后删除及真实远端索引请求。

总耗时约 20 分 41 秒，包含首次联网下载约 52 MB 模型，不能作为推理性能数据。原始结果保存在本地 `build/review-device-result.json`（不提交构建目录），构件指纹如下：

| 构件 | SHA-256 |
| --- | --- |
| Demo Release APK | `186be9d797d4bae31c2a7c9f7d2949f337594f0463d9e526e8dc9ac6cf1e0a97` |
| Release 测试 APK | `134f91b342455a363705d538d78479e0563f35272fdfd38a9f9f442e236c5077` |

上述旧构件的真机验证已完成。统一 API 后须重新验收；正式发布时还须由 CI 对当次构建产物执行设备门禁。
