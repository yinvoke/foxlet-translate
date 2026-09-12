# 构建、测试与基准

## 环境

- JDK 17
- Android SDK、NDK `29.0.13113456`
- CMake `3.31.6`
- Android 真机验证需要 `arm64-v8a` 设备；主机 smoke 需要 ARM 主机

## 常用命令

```bash
# Kotlin/JVM 单元测试
./gradlew :bergamot:test

# 发布 AAR
./gradlew :bergamot:assembleRelease

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

不要提交 `build/`、下载模型或临时 logcat；经检查的版本基准原始 JSON 应保存在 `benchmarks/<version>/` 并挂到索引。CI 验证基准检查器自身的测试，固定设备上的实测仍需单独运行。


## 发行构件与 Android 门禁

```bash
python3 tools/distribution/sync_catalog.py --check
./gradlew :bergamot:test :bergamot:packageWithoutPrefixes :demo:assembleRelease :demo:assembleReleaseAndroidTest
python3 tools/distribution/check_hardening.py
python3 tools/distribution/verify_artifacts.py --aar bergamot/build/outputs/aar/bergamot-release.aar --no-prefixes bergamot/build/outputs/aar/bergamot-no-prefixes-release.aar --apk demo/build/outputs/apk/release/demo-release.apk
```

设备解锁并首次联网下载 Mozilla 模型，设置 adb 设备序列号后运行：

```bash
python3 tools/distribution/run_device_tests.py --serial YOUR_DEVICE_SERIAL --apk demo/build/outputs/apk/release/demo-release.apk --test-apk demo/build/outputs/apk/androidTest/release/demo-release-androidTest.apk --output build/device-result.json
```

该测试没有缺模型时的跳过分支，必须完成真实翻译、Unicode 输入、损坏模型拒绝、释放后重建及许可资源读取。
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
