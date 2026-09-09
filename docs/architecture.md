# 代码结构与运行时架构

## 总体分层

```text
Android app / sample
        │ Kotlin suspend API
        ▼
bergamot/                 Android library: model discovery, lifecycle, tuning
        │ JNI
        ▼
jni/                      handle ownership, arrays, affinity and native bridge
        │ CMake
        ▼
engine/src/translator/    Bergamot service, batching, sentence splitting, HTML
        │
engine/marian-fork/       vendored Marian runtime and ARM tensor backends
```

`engine/` 是 vendor 区域，升级和本地改动必须遵守 [engine/UPSTREAM.md](../engine/UPSTREAM.md)。Android 侧的产品代码集中在 `bergamot/` 和 `jni/`，测试工具集中在 `tools/`，基准 app 集中在 `sample/`。

## 目录职责

| 目录 | 职责 | 变更注意事项 |
| --- | --- | --- |
| `bergamot/src/main/kotlin` | 对外 Kotlin API、模型文件识别、线程档位、空闲卸载 | API 兼容性和 KDoc 是发布契约 |
| `bergamot/src/test` | 不依赖设备的模型识别、配置和调度单元测试 | 新增配置边界先在这里覆盖 |
| `bergamot/src/androidTest` | 真机 native smoke、模型加载和 JNI 路径 | 需要设备与模型，不能当作普通 JVM 测试 |
| `jni/` | Kotlin/NativeBridge 与引擎之间的窄接口 | 保持句柄、数组和释放顺序清晰；修改后必须跑 smoke |
| `engine/src/translator` | Bergamot 翻译服务和批处理实现 | 不是独立上游；本地改动应有 patch 记录 |
| `engine/marian-fork` | Marian、ruy、SentencePiece 等 vendored 依赖 | 不要直接做无来源的“顺手整理” |
| `tools/smoke` | 主机/adb shell 翻译入口 | 用于真实模型冒烟和基准，不随 AAR 发布 |
| `tools/smmla-test`、`tools/*-bench` | 内核正确性、性能和回归工具 | 需要在文档中记录设备、编译选项和口径 |
| `sample/` | ML Kit 与 Bergamot 的 Android 基准 app | 仅用于测量与演示，不是库 API |
| `docs/` | 用户指南、架构、构建/验证 | 日常入口从 `docs/README.md` 开始 |
| `benchmarks/` | 分版本的性能协议、原始结果、汇总与局限 | 不覆盖历史结果；同配置门禁与默认参数收益分开 |

## 一次翻译的生命周期

1. `BergamotEngine` 在单线程 executor 上串行管理 native service 和模型句柄。
2. `ModelFiles.fromDirectory` 根据文件名找到模型、词表和 shortlist；加载时可附带源语言的 non-breaking prefix 表。
3. `threads = 1` 使用 blocking 路径；`threads >= 2` 创建 worker 并行处理 batch。
4. JNI 把输入数组交给 `engine/src/translator`，引擎完成分句、batch、模型推理和结果构造。
5. Kotlin 层按输入顺序返回结果，并重置该模型的 idle-unload 计时器。
6. 空闲 sweep、`releaseAllModels()` 和 `close()` 都在同一个 engine thread 上执行，避免与 native batch 并发释放。

## 构建边界

根 `CMakeLists.txt` 是 native 的唯一入口：主机侧构建 `engine + tools/smoke`，Android 侧由 AGP 的 externalNativeBuild 构建 `engine + jni`。Gradle 负责 AAR、Kotlin 测试和 sample app；不要在多个模块复制 CMake 编译选项。

根 CMake 明确要求 ARM 目标。Android 可以在 x86_64 CI 主机交叉编译，因为检查的是目标架构；主机 smoke 则需要 ARM 主机，以便和设备使用同一类 NEON/ruy 路径。
