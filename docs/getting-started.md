# 快速开始

本文面向把 Bergamot 集成到 Android 应用的开发者。Bergamot 在设备上离线运行，应用需要自行把模型文件下载或随应用分发到私有目录。

## 1. 添加 AAR

从 [v0.3.0 Release](https://github.com/yinvoke/bergamot-android/releases/tag/v0.3.0) 下载 `bergamot-v0.3.0.aar`，放入应用模块的 `libs/` 目录。

```kotlin
dependencies {
    implementation(files("libs/bergamot-v0.3.0.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

AAR 不携带 Kotlin 协程的传递依赖。项目源码内的 `bergamot` 模块也可以直接作为 Gradle module 使用。

也可运行 `./gradlew :bergamot:assembleRelease` 从源码构建，产物位于 `bergamot/build/outputs/aar/bergamot-release.aar`；自行构建时按实际文件名调整依赖。

## 2. 准备模型

`registry.json` 是 Mozilla Remote Settings 模型索引快照。每个方向通常包含模型、SentencePiece 词表和 lexical shortlist；这些文件必须放在同一目录，并且下载后应校验 SHA-256。

下面的脚本下载英译简中模型到 `models/enzh/`：

```bash
python3 - <<'EOF'
import hashlib
import json
import pathlib
import urllib.request

src, dst = "en", "zh-Hans"
registry = json.load(open("registry.json", encoding="utf-8"))
model = next(item for item in registry["models"]
             if item["from"] == src and item["to"] == dst)
out = pathlib.Path("models/enzh")
out.mkdir(parents=True, exist_ok=True)
for item in model["files"]:
    path = out / item["name"]
    urllib.request.urlretrieve(item["url"], path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != item["sha256"]:
        raise SystemExit(f"sha256 mismatch: {path}")
    print("ok", path)
EOF
```

应用运行时应把模型下载到应用私有目录，校验成功后再创建 `ModelFiles`。模型文件属于 Mozilla 发布物，许可信息见 [NOTICE](../NOTICE)。

## 3. 翻译文本

```kotlin
// 在后台协程中执行，避免 use 结束时的 close() 阻塞主线程。
BergamotEngine(EngineConfig()).use { engine ->
    val enZh = ModelFiles.fromDirectory(File(modelsDir, "enzh"))
    val result = engine.translate(listOf("Hello, world."), enZh)
}
```

`translate` 是 `suspend` 函数，返回值与输入一一对应。非英语语言之间可通过英语中转：

```kotlin
BergamotEngine(EngineConfig()).use { engine ->
    val result = engine.translatePivot(
        texts = listOf("こんにちは。"),
        first = ModelFiles.fromDirectory(File(modelsDir, "jaen")),
        second = ModelFiles.fromDirectory(File(modelsDir, "enzh")),
    )
}
```

如果设备内存紧张，也可以先翻译 ja→en，在后台线程调用 `releaseAllModels().get()` 确认卸载成功后，再翻译 en→zh。仅仅顺序调用两次 `translate` 不会立即卸载第一个模型。同时驻留两个模型的 `translatePivot` 通常更快，但峰值内存更高。

## 4. 生命周期与线程

- 同一进程同时只创建一个 `BergamotEngine`。底层 Marian 运行时持有进程级全局状态。
- `EngineConfig()` 默认 1 个线程，适合一次一句的交互式调用；大批量文本使用 `EngineConfig.forDevice(context, Workload.BATCH)`。
- 模型首次使用时加载，默认空闲 60 秒自动卸载；下一次翻译会透明重载。
- 在 `onTrimMemory` 中调用 `releaseAllModels()`；它返回 `Future<Boolean>`，需要确认释放完成时再等待结果。
- `close()` 会等待原生资源销毁，应放在后台线程调用，不要阻塞主线程。

## 5. 常见边界

- 当前发布 ABI 为 `arm64-v8a`，最低 Android API 为 28。
- 支持 HTML 感知翻译，但 HTML 模式仍属于实验能力，宿主应自行覆盖 DOM、脚本和特殊节点场景。
- `cacheSize = 0` 时，同一输入、模型和配置具备跨进程的稳定输出契约；开启缓存后，缓存碰撞可能使重新计算的句子进入不同 batch。
- `workspaceMb` 为保留兼容参数，当前没有实际效果；新代码不要继续依赖它。
