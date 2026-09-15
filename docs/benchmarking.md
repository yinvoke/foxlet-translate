# 构建、测试与基准

## 环境

- JDK 17
- Android SDK 36、NDK `29.0.13113456`
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
./gradlew :benchmark-app:assembleDebug

# 主机 smoke
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DCOMPILE_TESTS=OFF
cmake --build build-host --target smoke
```

SMMLA 正确性与指令扫描：

```bash
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DCOMPILE_TESTS=OFF \
  -DBUILD_SMMLA_TEST=ON
cmake --build build-host --target smoke smmla_test ab_bench
SMMLA_TEST_REQUIRE_I8MM=1 ctest --test-dir build-host --output-on-failure
tools/smmla-test/check-opcodes.sh build-host/tools/smoke/smoke "$(xcrun -f llvm-objdump)"
```

完整说明见 [SMMLA 测试集](../tools/smmla-test/README.md) 和 [兼容性清单](smmla-compatibility.md)。

## 分句规则

自有分句器的标准和语言规则测试可独立于 Android 构建运行：

```bash
python3 tools/sentence-tests/generate_unicode.py --check
cmake -S tools/sentence-tests -B build/sentence-tests \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DSENTENCE_SANITIZERS=ON
cmake --build build/sentence-tests --parallel 2
UBSAN_OPTIONS=halt_on_error=1 ctest --test-dir build/sentence-tests --output-on-failure
```

测试覆盖 Unicode 标准句界、中英日韩俄规则、自定义规则和并发调用。公开语料的固定来源见 `tools/sentence-tests/corpora.json`；翻译质量评分方法见 [评分工具](../tools/translation-tests/README.md)。本地回测结果和实验记录归档到 `.docs-private/`。

## 真机验证

基准 app 的启动方式：

```bash
adb shell am start -n io.github.yinvoker.foxlet.bench/.MainActivity \
  --ez autorun true --ei threads 2
```

报告至少应记录：设备型号与 SoC、Android 版本、模型版本、语料句数、线程数、`mini-batch-words`、是否启用 i8mm、温度/频率状态、计时是否包含模型加载，以及内存指标是 RSS 还是 PSS。不同计时范围和内存指标须分别展示。

哈希回归入口：

```bash
tools/regress-hash.sh build-host/tools/smoke/smoke \
  /path/to/enzh-config.yml benchmark/app/src/main/assets/bench/eng.txt \
  /path/to/jaen-config.yml benchmark/app/src/main/assets/bench/jpn.txt
```

替换配置路径，确保模型参数与脚本注释中的正典条件匹配（host 使用 batch 1024，device 使用 512）；不得将已有前缀表配置再重复追加。当前自有分句器已记录 host/200 的正典，其他平台/批次需显式给出经检查的 `EXPECT_ENZH` / `EXPECT_PIVOT`。它验证 ruy/SMMLA 路径和正典输出；如果修改了浮点累加顺序、batch 组成或缓存策略，应先说明预期差异，再更新对应基线和文档。

## 版本性能与防回归

常规采集仅运行当前版本：`android-native-v2` 七场景、`android-app-v2` 六场景，均固定 200 条输入、三进程三遍及全部指标。历史协议与原始数据保持不变；展示调整通过 [报告生成器](../tools/benchmark-report/README.md) 完成，缺项和协议差异须明确标注，补测仅在明确请求后执行。

版本数据统一保存到 [benchmark/data/](../benchmark/data/README.md)。可从 tag 或未提交的本地代码构建相同 harness，记录原始样本与输入指纹，并检查耗时/RSS 是否退步。命令见 [版本基准工具](../tools/version-bench/README.md)。native RSS 测量不覆盖 AAR/JNI、内存卸载或 App PSS 验收。

## CI 与发布

`.github/workflows/build.yml` 在 Ubuntu 上构建 AAR、运行 JVM 测试和构建 benchmark-app；在 macOS ARM 与 Linux ARM 上运行主机 smoke、SMMLA 测试及指令隔离检查。macOS 兼容不支持 i8mm 的设备，Linux ARM 必须实际执行 i8mm 测试，不能跳过。发布由 `.github/workflows/release.yml` 的 `v*` tag 触发，并要求存在对应的 `.github/releases/<tag>.md`。

普通构建工作流在 `main` 推送或 Pull Request 时运行；单独推送开发分支不会触发它。提交预检与发布步骤见 [发布指南](releasing.md)。

不要提交 `build/`、下载模型或临时 logcat；经检查的版本基准原始 JSON 应保存在 `benchmark/data/<version>/` 并挂到索引。CI 验证基准检查器自身的测试，固定设备上的实测仍需单独运行。


## 发行构件与 Android 门禁

```bash
python3 tools/distribution/sync_catalog.py --check
./gradlew :foxlet:test :foxlet:assembleRelease :sdk-example:assembleRelease :sdk-example:assembleReleaseAndroidTest
python3 tools/distribution/check_hardening.py
python3 tools/distribution/check_public_api.py
python3 tools/distribution/verify_artifacts.py --aar foxlet/build/outputs/aar/foxlet-release.aar --apk sdk-example/build/outputs/apk/release/sdk-example-release.apk
```

`python3 tools/distribution/fetch_registry.py --check` 会报告 `registry.json` 与 Mozilla 当前面向 Android 正式版发布的模型之间的差异（新增或移除的语向、版本变化、哈希变化）；不带 `--check` 运行则刷新快照，之后需重新运行 `sync_catalog.py` 生成 `models.tsv`。

设备验收要求设备解锁、可联网访问 Mozilla 模型，并显式指定 adb 序列号。脚本依次安装 SDK 示例和测试 APK。MIUI 等系统若显示 USB 安装确认，需要分别允许两个包；`INSTALL_FAILED_USER_RESTRICTED` 表示安装被手机限制或确认已取消，应处理手机提示后重试。首次测试还会下载约 52 MB 的英译中模型，耗时取决于设备到 Mozilla CDN 的网络速度。

```bash
python3 tools/distribution/run_device_tests.py --serial YOUR_DEVICE_SERIAL --apk sdk-example/build/outputs/apk/release/sdk-example-release.apk --test-apk sdk-example/build/outputs/apk/androidTest/release/sdk-example-release-androidTest.apk --output build/device-result.json
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

FLORES-200 仅是评测集，引用和许可见 [CITATION](../benchmark/data/CITATION.md)。
发行 AAR/SDK 示例的包检查会拒绝混入 benchmark 文件或已知评测原文样本。
