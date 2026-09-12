# 快速开始

本文对应 v0.3.2；v0.3.1 用户请看[该版本文档](https://github.com/yinvoke/foxlet-translate/blob/v0.3.1/docs/getting-started.md)。
从 v0.3.1 升级时，将 `BergamotEngine` 改为 `FoxletEngine`，AAR 文件名前缀改为 `foxlet`，构建模块改为 `:foxlet`。包名仍为 `io.github.yinvoker.foxlet`，需重新编译宿主；不要混用新旧 AAR 或 JNI 动态库。
支持 Android 9+、arm64-v8a。开发环境为 JDK 17、Android SDK 36、NDK 29.0.13113456、CMake 3.31.6；Gradle wrapper 固定工具链。

## 1. 先体验完整示例

```bash
./gradlew :demo:assembleRelease
adb install -r demo/build/outputs/apk/release/demo-release.apk
```

打开 Foxlet Translate，点击“下载 / 检查模型”，完成后可断网输入英文并翻译。
下载支持进度、取消、失败重试和 SHA-256 校验。演示 APK 用开发签名，仅供体验。
`demo/` 直接依赖构建完成的 AAR，并开启 R8；不包含 ML Kit 或 FLORES-200。
`sample/` 是单独的内部评测 app，不是此演示。

## 2. 集成 AAR

```bash
./gradlew :foxlet:assembleRelease :foxlet:packageWithoutPrefixes
```

默认产物为 `foxlet/build/outputs/aar/foxlet-release.aar`，将其复制到宿主模块的 `libs/`：

```kotlin
// app/build.gradle.kts
android { defaultConfig { minSdk = 28 } }
dependencies {
    implementation(files("libs/foxlet-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

AAR 不携带传递依赖，请显式声明协程依赖。已验证仓库当前 AGP/Kotlin 工具链；旧 Kotlin 编译器可能无法读取新版 Kotlin 元数据，建议使用同等或更新版本。

`foxlet-no-prefixes-release.aar` 不包含 LGPL-2.1 分句前缀表。两种 AAR 二选一，不要同时引入；默认版本会保护 `Dr.` 等缩写，无表版本使用基础分句，也可以由宿主提供自有前缀表。

## 3. 下载模型并翻译

宿主只在下载模型时需要网络权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

下面是包含 imports、模型目录和后台线程的完整调用函数。可从 Activity 的生命周期协程中调用；界面及错误处理参考 `demo/`。

```kotlin
import android.content.Context
import io.github.yinvoker.foxlet.FoxletEngine
import io.github.yinvoker.foxlet.ModelCatalog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun translateEnglish(context: Context, text: String): String {
    val files = ModelCatalog.download(
        root = File(context.filesDir, "translation-models"),
        from = "en",
        to = "zh-Hans",
        onProgress = { downloaded, total ->
            // 回调在 IO 线程执行；更新 UI 时请切换到主线程。
        },
    )
    return withContext(Dispatchers.IO) {
        FoxletEngine().use { engine ->
            engine.translate(listOf(text), files).single()
        }
    }
}
```

`download` 会复用已校验的完整模型目录；离线再次使用时无需重新联网。
文本不会发送到服务器。模型索引固定在 SDK 内，来自 `registry.json`，可以通过 `ModelCatalog.models` 查看语向及下载大小。

连续交互应在应用层长期持有一个 engine，避免每次加载；上面的短函数用于说明完整流程。
同一进程同时只能有一个 engine，重复创建会报错。关闭后才可以创建下一个。

## 4. 已有文件、自定义模型与中转

已有 Mozilla 模型可用 `ModelFiles.fromDirectory(directory)`，首次加载自动检查是否匹配 SDK 内固定模型索引。
该方法要求一套完整、不歧义的文件；模型、词表和 shortlist 必须来自同一语向/版本。

自定义模型需要显式传入来自可信发布者的完整 `expectedSha256: Map<String, String>`，以文件名为键。不能用刚下载文件自身算出的 hash 充当信任依据。结构校验不能保证任意第三方模型的架构或推理逻辑安全；本库的公开入口面向可信模型，不能把模型上传接口直接暴露给不可信用户。

模型目录在使用期间必须保持不可变。更新时下载到新目录，再切换 ModelFiles；不要覆写正在使用的文件。缓存标识包括模型、两侧词表、shortlist、源语言、自供前缀和文件元数据；覆盖旧文件并保留原长度/时间戳不受支持。

```kotlin
val jaEn = ModelCatalog.download(modelRoot, "ja", "en")
val enZh = ModelCatalog.download(modelRoot, "en", "zh-Hans")
val result = engine.translatePivot(listOf("こんにちは。"), jaEn, enZh)
```

中转会同时驻留两个模型。内存较少时可顺序翻译，并在中间等待 `releaseAllModels().get()` 完成。仅顺序调用两次 translate 不会立即卸载第一套模型。

无前缀表 AAR 可通过 `files.copy(nonbreakingPrefixFile = myUtf8PrefixFile)` 使用自有前缀数据；文件最多 1 MiB。`EngineConfig(nonbreakingPrefixes = false)` 完全关闭前缀读取。

## 5. 生命周期、失败与限制

- `translate` / `translatePivot` 是 suspend API，结果与输入顺序一致。默认一个推理线程；批量处理可用 `EngineConfig.forDevice(context, Workload.BATCH)`。
- 默认空闲 60 秒卸载模型。`releaseAllModels()` 返回 Future，可接 `onTrimMemory`；等待 Future 或 `close()` 应放在后台线程。
- 关闭开始后拒绝新翻译；close 可重复调用，会等待资源释放完成。协程取消无法中断正在执行的单个原生批次，只有该批结束后才能释放资源。
- 下载的尺寸/hash 不符、缺文件、目录有歧义等会报错，下载失败不会发布半套模型；保留错误提示并允许重试。
- 非法 UTF-16 代理项会报错。emoji、扩展汉字和空字符在 JNI 边界按标准 UTF-8 转换；译文是否保留这些字符仍由分词器/模型决定。
- HTML 翻译仍属实验能力，宿主负责 DOM、脚本和特殊节点处理。
- 自定义线程限 1–64、miniBatchWords 限 256–65536、cacheSize 限 0–1000000。较大设置可能需要大量内存。
- SHA-256 与结构校验会增加首次加载开销；README 的 v0.3.0 性能数据属于该历史版本，不能当作新版耗时承诺。

## 6. 许可与再分发

AAR 的 `io/github/yinvoker/foxlet/licenses/` 包含 NOTICE、完整第三方许可及 SOURCE.txt。可在宿主“开源许可”页面展示，demo 已提供示例。保留适用声明和 MPL 源码取得方式；源码和源码修改可从相应发布版本取得。

默认 AAR 含 LGPL-2.1 的 Moses 数据；无前缀版本不含这些数据。模型单独受 MPL-2.0 约束。FLORES-200 仅用于评测，AAR/demo 不含它；源码评测材料的出处和 BibTeX 见 [评测引用](../benchmarks/CITATION.md)。
