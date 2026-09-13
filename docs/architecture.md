# 代码结构与运行时架构

## 总体分层

```text
Android app / sample
        │ Kotlin suspend API
        ▼
foxlet/                 Android library: model discovery, lifecycle, tuning
        │ JNI
        ▼
jni/                      handle ownership, arrays, affinity and native bridge
        │ CMake
        ▼
engine/src/translator/    Bergamot service, batching, sentence splitting, HTML
        │
engine/marian-fork/       vendored Marian runtime and ARM tensor backends
```

`engine/` 是 vendor 区域，升级和本地改动必须遵守 [engine/UPSTREAM.md](../engine/UPSTREAM.md)。Android 侧的产品代码集中在 `foxlet/` 和 `jni/`，测试工具集中在 `tools/`，基准 app 集中在 `sample/`。

## 目录职责

| 目录 | 职责 | 变更注意事项 |
| --- | --- | --- |
| `foxlet/src/main/kotlin` | 对外 Kotlin API、模型下载/更新/本地管理、线程档位、空闲卸载 | 公开类型、行为和 KDoc 是发布契约 |
| `foxlet/src/test` | 不依赖设备的模型管理、HTTP 续传/重试、更新筛选、配置和调度测试 | 新增配置边界先在这里覆盖 |
| `foxlet/src/androidTest` | 真机 native smoke、模型加载和 JNI 路径 | 需要设备与模型，不能当作普通 JVM 测试 |
| `jni/` | Kotlin/NativeBridge 与引擎之间的窄接口 | 保持句柄、数组和释放顺序清晰；修改后必须跑 smoke |
| `engine/src/translator` | Bergamot 翻译服务和批处理实现 | 不是独立上游；本地改动应有 patch 记录 |
| `engine/marian-fork` | Marian、ruy、SentencePiece 等 vendored 依赖 | 不要直接做无来源的“顺手整理” |
| `tools/smoke` | 主机/adb shell 翻译入口 | 用于真实模型冒烟和基准，不随 AAR 发布 |
| `tools/smmla-test`、`tools/*-bench` | 内核正确性、性能和回归工具 | 需要在文档中记录设备、编译选项和口径 |
| `sample/` | ML Kit 与 Foxlet 的 Android 基准 app | 仅用于测量与演示，不是库 API |
| `docs/` | 用户指南、架构、构建/验证 | 日常入口从 `docs/README.md` 开始 |
| `benchmarks/` | 分版本的性能协议、原始结果、汇总与局限 | 不覆盖历史结果；同配置门禁与默认参数收益分开 |

## 统一客户端

`Foxlet.create` 用 `FoxletConfig` 或 DSL 建立同一配置，绑定模型目录、来源、网络策略和翻译策略。`ModelManager` 复用内部 `Catalog`、`ModelDownloader`、`ModelStore` 和 `RemoteIndex`；`Translator` 接受 `InstalledModel` 或显式语向的 `ExternalModel`，通过内部 `NativeEngine` 调用 JNI。旧公开入口已移除。

`ClientLifecycle` 在锁内登记操作子任务并协调终止：关闭开始后拒绝新任务，取消自有子任务，等待不可中断的 native 批次，再销毁引擎。宿主协程不属于该任务树；重复 shutdown 等待同一终止结果。`NativeRuntime` 是 JNI 的窄适配接口，允许 JVM 测试验证排队顺序和未确认释放时的占用保护。

## 一次翻译的生命周期

1. 内部 `NativeEngine` 在单线程 executor 上串行管理 native service 和模型句柄。
2. `ModelFiles.fromDirectory` 根据文件名找到模型、词表和 shortlist；加载时可附带源语言的 non-breaking prefix 表。
3. `threads = 1` 使用 blocking 路径；`threads >= 2` 创建 worker 并行处理 batch。
4. JNI 把输入数组交给 `engine/src/translator`，引擎完成分句、batch、模型推理和结果构造。
5. 请求结束时按驻留策略处理模型：`Idle` 更新空闲期限，`AfterRequest` 在下一批翻译开始前卸载，`UntilShutdown` 保持驻留，直到主动卸载或关闭。Kotlin 层按输入顺序返回结果。
6. 空闲 sweep、`translator.unloadModels()` 和 `shutdown()` 的 native 释放都在同一个 engine thread 上执行，避免与 native batch 并发释放。未确认销毁的模型继续保留磁盘占用保护，直到服务销毁。

## 模型管理边界

`Foxlet.models` 是统一模型入口；下载、删除和清理共用进程级 Mutex，在 IO 线程运行。`ModelDownloader` 先检查模型布局与 HTTPS 域名，再向 `.download-<directoryName>` 写入可续传文件；尺寸和 SHA-256 校验通过后写 manifest 并通过 rename 发布。完整目录及修复产生的 UUID 后缀目录可直接复用。

`ModelStore` 从目录名、manifest 或内置索引识别模型；查询最新可用版本时执行校验，清理只有在较新版本通过校验后才回收旧版本。`ActiveModels` 在校验和 native 加载前登记路径，失败时撤销，卸载时释放；删除的占用检查与文件操作共用同一把锁，覆盖嵌套前缀文件。应用保存的 `InstalledModel` 本身不构成占用，仍需使用的安装通过 `cleanup(keep = setOf(model.id))` 保留。同一模型根目录仅支持单个应用进程管理。

`RemoteIndex` 只在显式调用时读取 changeset，使用保守的 Android/正式版过滤并按完整文件集选择版本。远程信任边界为 HTTPS 加附件大小和 SHA-256，尚未实现集合签名验证。`tools/distribution/fetch_registry.py` 使用相同筛选规则刷新 `registry.json`，再由 `sync_catalog.py` 生成内置 TSV；Python 与 Kotlin 测试共用 changeset 样本。

## 构建边界

根 `CMakeLists.txt` 是 native 的唯一入口：主机侧构建 `engine + tools/smoke`，Android 侧由 AGP 的 externalNativeBuild 构建 `engine + jni`。Gradle 负责 AAR、Kotlin 测试和 sample app；不要在多个模块复制 CMake 编译选项。

根 CMake 明确要求 ARM 目标。Android 可以在 x86_64 CI 主机交叉编译，因为检查的是目标架构；主机 smoke 则需要 ARM 主机，以便和设备使用同一类 NEON/ruy 路径。
