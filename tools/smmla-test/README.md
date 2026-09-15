# SMMLA GEMM 测试集

本目录验证 SMMLA 内核的正确性、运行时能力判断与指令隔离，并提供形状级性能比较。测试目标通过根 CMake 的 `BUILD_SMMLA_TEST` 开关启用。

## 工具职责

| 工具 | 用途 |
|---|---|
| `smmla_test.cpp` / `smmla_test` | 形状族、页边界守卫、int8 极值、打包器、one-hot、缓存代际、并发和 tile 覆盖；与 ruy 逐字节比较 |
| `check-opcodes.sh` | 检查 `smmla/ummla/usmmla` 仅出现在隔离的 SMMLA 内核符号中 |
| `ab-bench.cpp` / `ab_bench` | 比较 decode、encoder、shortlist 等矩阵形状下的 ruy 与 SMMLA 耗时，同时验证输出并汇总几何均值 |
| [引擎哈希回归](../regress-hash.sh) | 比较双路径输出并检查指定平台、批次和分句配置的正典哈希 |

`smmla_test` 的四线程用例验证并发正确性，独立于版本性能协议中已取消的四线程常规测量。`SMMLA_TEST_REQUIRE_I8MM=1` 将缺少 i8mm 的测试跳过判为失败；不具备该能力的主机应使用默认模式。诊断选项 `--killswitch-probe` 和 `--force-sigill` 的用途见源文件头部，后者用于显式非法指令探测。

## 主机运行

从仓库根目录执行：

```bash
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DCOMPILE_TESTS=OFF -DBUILD_SMMLA_TEST=ON
cmake --build build-host --target smoke smmla_test ab_bench
ctest --test-dir build-host --output-on-failure
BERGAMOT_NO_I8MM=1 build-host/tools/smmla-test/smmla_test --killswitch-probe
```

已确认支持 i8mm 的主机可在 `ctest` 前设置 `SMMLA_TEST_REQUIRE_I8MM=1`，确保实际执行内核测试。macOS 指令扫描命令为：

```bash
tools/smmla-test/check-opcodes.sh build-host/tools/smoke/smoke "$(xcrun -f llvm-objdump)"
build-host/tools/smmla-test/ab_bench
```

Apple objdump 默认使用 `--mattr=+v9.2a`；NDK 或 Linux LLVM objdump 使用 `MATTR=+i8mm`。同一 smoke 二进制可通过 `BERGAMOT_NO_I8MM=1` 强制回退 ruy，用于引擎级对照。

## 测量约束

设备测量须固定 CPU 簇，并记录温度和 `scaling_max_freq`。A/B 程序保持 B 矩阵缓冲的生命周期，避免地址复用使 ruy 预打包缓存命中旧内容；产品仅缓存具备稳定生命周期的常量权重。

历史设备覆盖见 [兼容性清单](../../docs/smmla-compatibility.md)。内核测试通过不等同于当前发行 AAR 的设备验收，发行检查另见 [构建与测试](../../docs/benchmarking.md)。
