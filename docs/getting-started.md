# 快速开始

本文对应 v0.4.0，包含模型管理、更新检测和断点续传 API。发行 AAR 与 Demo 见 [v0.4.0 下载页](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.4.0)。
从 v0.3.0 / v0.3.1 升级时，将 `BergamotEngine` 改为 `FoxletEngine`，AAR 文件名前缀改为 `foxlet`，构建模块改为 `:foxlet`。包名仍为 `io.github.yinvoker.foxlet`，需重新编译宿主；不要混用新旧 AAR 或 JNI 动态库。
支持 Android 9+、arm64-v8a。开发环境为 JDK 17、Android SDK 36、NDK 29.0.13113456、CMake 3.31.6；Gradle wrapper 固定工具链。

## 1. 先体验完整示例

```bash
./gradlew :demo:assembleRelease
adb install -r demo/build/outputs/apk/release/demo-release.apk
```

打开 Foxlet Translate，点击“下载 / 检查模型”，优先使用本地最新且校验通过的版本，没有可用版本时再下载 SDK 内置版本；完成后可断网输入英文并翻译。
下载支持进度、取消、断点续传、失败重试和 SHA-256 校验；“已安装 / 检查更新 / 清理”演示本地模型管理，其中只有“检查更新”会向 Mozilla 发一次请求。演示 APK 用开发签名，仅供体验。
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

宿主只在下载模型和显式检查更新时需要网络权限：

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

`download(root, from, to)` 选择 SDK 内置版本，并复用已校验的完整目录（包含损坏目录修复后生成的带 UUID 后缀的目录）；离线再次使用时无需重新联网。若希望保留此前安装的远端新版本，先调用 `installedFor`，仅在返回 null 时下载内置版本。
被中断的下载会从断点继续，超时和 HTTP 408/429/5xx 会自动重试（默认 3 次，带抖动的指数退避）；尺寸或 SHA-256 不符不重试，直接报错。需要调整重试、超时或查看逐文件进度时，用带 `DownloadPolicy` 的重载：

```kotlin
val files = ModelCatalog.download(root, "en", "zh-Hans", ModelCatalog.DownloadPolicy(maxRetries = 5)) { p ->
    // p.downloaded / p.total 为整套模型进度；p.assetName 为当前文件；p.attempt > 1 表示正在重试
}
```

模型根目录建议用 `File(context.noBackupFilesDir, "translation-models")`：Android Auto Backup 的配额只有 25 MB，单个模型 20–55 MB，不应进入备份。示例中的 `filesDir` 仍然可用，但要自行在备份规则中排除该目录。根目录只交给本 SDK 管理，不要在其中放别的文件。同一个根目录只由一个应用进程管理；下载互斥和引擎占用保护均为进程内机制。
文本不会发送到服务器。模型索引固定在 SDK 内，来自 `registry.json`，可用 `checkForUpdates` 与 Mozilla 当前发布比对（见第 6 节）；可以通过 `ModelCatalog.models` 查看语向及下载大小。

连续交互应在应用层长期持有一个 engine，避免每次加载；上面的短函数用于说明完整流程。
同一进程同时只能有一个 engine，重复创建会报错。关闭后才可以创建下一个。

## 4. 已有文件、自定义模型与中转

已有 Mozilla 模型可用 `ModelFiles.fromDirectory(directory)`，首次加载自动检查是否匹配 SDK 内固定模型索引。
该方法要求一套完整、不歧义的文件；模型、词表和 shortlist 必须来自同一语向/版本。

自定义模型需要显式传入来自可信发布者的完整 `expectedSha256: Map<String, String>`，以文件名为键。不能用刚下载文件自身算出的 hash 充当信任依据。结构校验不能保证任意第三方模型的架构或推理逻辑安全；本库的公开入口面向可信模型，不能把模型上传接口直接暴露给不可信用户。

模型目录在使用期间必须保持不可变。更新时下载到新目录，再切换 ModelFiles；不要覆写正在使用的文件。`download` 复用已校验目录，或将新下载发布到独立目录；旧目录之后由 `cleanup` 或 `delete` 回收（第 5 节）。缓存标识包括模型、两侧词表、shortlist、源语言、自供前缀和文件元数据；覆盖旧文件并保留原长度/时间戳不受支持。

```kotlin
val jaEn = ModelCatalog.download(modelRoot, "ja", "en")
val enZh = ModelCatalog.download(modelRoot, "en", "zh-Hans")
val result = engine.translatePivot(listOf("こんにちは。"), jaEn, enZh)
```

中转会同时驻留两个模型。内存较少时可顺序翻译，并在中间等待 `releaseAllModels().get()` 完成。仅顺序调用两次 translate 不会立即卸载第一套模型。

无前缀表 AAR 可通过 `files.copy(nonbreakingPrefixFile = myUtf8PrefixFile)` 使用自有前缀数据；文件最多 1 MiB。`EngineConfig(nonbreakingPrefixes = false)` 完全关闭前缀读取。

## 5. 本地模型管理

以下函数只读写 `root`，不联网；都是 suspend 函数，内部切到 IO 线程。`root` 下只有本 SDK 发布的模型目录（`<from>-<to>-<version>-<identity>`）和它的临时目录。

- `installed(root)`：列出每个已发布的模型目录，按语向、版本从新到旧排序。`InstalledModel` 含语向、版本、目录、占用字节和 `isCurrentCatalogVersion`（是否就是 SDK 内置索引当前指向的字节）。默认只扫描目录，不做 hash；`installed(root, verify = true)` 会对每个能识别的目录做 SHA-256 校验（每个语向几十 MB 的读取开销），`verified` 为 true/false，无法识别的目录保持 null。
- `installedFor(root, from, to)`：返回该语向最新且通过校验的 `ModelFiles`，没有可用目录时返回 null。这是离线场景“先用本地已有模型”的入口，之后再决定是否 `download` 或 `checkForUpdates`。
- `delete(root, model)` / `delete(root, from, to)`：删除一个目录或该语向的全部版本，返回释放的字节数。目录正在加载或被 engine 持有时抛 `IllegalStateException`，该次删除不执行；先停止向旧模型提交翻译，等待 `releaseAllModels().get()` 或 `close()` 完成后再删。
- `cleanup(root)`：回收超过 7 天没有动过的下载临时目录（`staleTempAgeMillis` 可调，进行中的续传不受影响）、中断删除留下的残余，以及在更新版本已安装并通过校验后被取代的旧版本（`removeSuperseded = false` 关闭）。正在加载或驻留于 engine 的目录被跳过，并记录在 `CleanupReport.skippedInUse`；`keep` 中的目录直接保留，不计入该列表；报告还包含删掉的临时目录、被取代的版本和释放的字节数。不下载任何东西。

```kotlin
val root = File(context.noBackupFilesDir, "translation-models")
val files = ModelCatalog.installedFor(root, "en", "zh-Hans")   // 离线：本地有就直接用
    ?: ModelCatalog.download(root, "en", "zh-Hans")            // 没有再下载
for (m in ModelCatalog.installed(root)) {
    println("${m.from}→${m.to} ${m.version} ${m.sizeBytes / 1_000_000} MB " + if (m.isCurrentCatalogVersion) "SDK 内置版本" else "其他版本")
}
engine.releaseAllModels().get()                                 // 后台线程；删除前先释放 engine 持有的目录
ModelCatalog.delete(root, "ja", "en")
val report = ModelCatalog.cleanup(root, keep = setOf(files.model.parentFile!!)) // 保留应用仍选中的目录
```

仅保存 `ModelFiles` 不会保留占用；空闲卸载后引擎也会解除占用。应用仍计划使用的目录应加入 `cleanup(keep = ...)`，Demo 的“清理”按钮会保留当前选中的目录。目录中嵌套的自供前缀文件同样受到占用保护，清理不会沿符号链接遍历外部目录。

## 6. 模型更新检测

更新检测分两层：

- 离线：`installed(root)` 返回的 `isCurrentCatalogVersion` 表示该目录是否就是 SDK 内置索引当前指向的字节。false 仅表示字节与内置索引不同，也可能是更高版本或同版本重打包，不能据此判定“旧版”。下载内置版本用 `download(root, from, to)`；判断上游是否有更新用 `checkForUpdates`。
- 显式联网：`checkForUpdates(root)` 对 Mozilla Remote Settings 的 `translations-models` changeset 做一次 HTTPS GET（约 80 KB，gzip），请求头里除 User-Agent 外不带任何标识；不下载、不删除任何文件，也不会定时运行——何时检查由宿主决定，例如用户点“检查更新”时。索引取不到抛 `IOException`，解析不了抛 `IllegalStateException`。

版本排序沿用 Mozilla 规则：每个语向取 `supportedMajorVersions`（当前 1.x–2.x）内最高的完整正式版本。过滤仅接受空表达式或精确的 Android 表达式；不求值通用 JEXL。缺文件、重名或文件布局无法识别的版本会跳过，尝试下一个完整版本。

```kotlin
val root = File(context.noBackupFilesDir, "translation-models")
val report = ModelCatalog.checkForUpdates(root)                          // 唯一的联网点之一
for (candidate in report.updates) {                                      // 已安装且上游有更新的语向
    val newer = ModelCatalog.download(root, candidate.available!!)       // 下载到新目录，旧目录不动
    if (candidate.from == "en" && candidate.to == "zh-Hans") enZh = newer // 之后的 translate 改用新 ModelFiles
}
// 停止向旧目录提交翻译，再等待释放；仅换一个 ModelFiles 不会立即卸载旧模型：
withContext(Dispatchers.IO) { engine.releaseAllModels().get() }
ModelCatalog.cleanup(root, keep = setOf(enZh.model.parentFile!!))
```

`UpdateReport.updates` 是已安装且可更新的语向（版本号相同但字节不同也算），`notInstalled` 是上游有、本地没有的语向，`candidates` 是全部语向，每项带 `downloadSizeBytes`；`indexTimestamp` 是上游索引的时间戳。`checkForUpdates(root, pairs = setOf("en" to "zh-Hans"))` 只报告指定语向。
`UpdateCandidate.newerMajorVersion` 非空表示上游存在本引擎版本无法加载的新大版本模型，需要升级 SDK；`installedStillListed = false` 表示 Mozilla 已不再发布本地安装的这组字节（Firefox 会删除，本 SDK 只报告，删不删由宿主决定）。

自建镜像可传 `ModelCatalog.UpdateSource(changesetUrl, attachmentBaseUrl)`，两个地址都必须是 HTTPS，并把附件域名加入 `DownloadPolicy.allowedHosts` 后再 `download`。
内置索引的 hash 在构建时固定；远程索引依赖 HTTPS 与记录中的 SHA-256、大小。本 SDK 未实现 Remote Settings 集合签名验证；Firefox/Gecko 还会验证集合签名，具体步骤见 [Mozilla 客户端规范](https://remote-settings.readthedocs.io/en/latest/client-specifications.html#signature-verification)。

## 7. 生命周期、失败与限制

- `translate` / `translatePivot` 是 suspend API，结果与输入顺序一致。默认一个推理线程；批量处理可用 `EngineConfig.forDevice(context, Workload.BATCH)`。
- 默认空闲 60 秒卸载模型。`releaseAllModels()` 返回 Future，可接 `onTrimMemory`；等待 Future 或 `close()` 应放在后台线程。
- 关闭开始后拒绝新翻译；close 可重复调用，会等待资源释放完成。协程取消无法中断正在执行的单个原生批次，只有该批结束后才能释放资源。
- 下载会断点续传，并对超时和 HTTP 408/429/5xx 自动重试（`DownloadPolicy`）；尺寸/hash 不符、缺文件、目录有歧义等直接报错、不重试，失败仍不会发布半套模型。保留错误提示并允许用户重试；默认 `resume = true` 时已下载的部分留在 `root` 下供续传，超过 7 天未续传由 `cleanup` 回收。
- `delete` / `cleanup` 不会动 engine 正在加载或持有的目录：`delete` 抛 `IllegalStateException`，`cleanup` 记入 `skippedInUse`。停止提交旧模型的翻译，等待 `releaseAllModels().get()` 或 `close()` 完成后再删。
- 唯一联网点：`download` 与 `checkForUpdates`。`installed`、`installedFor`、`delete`、`cleanup` 和翻译都不发请求，SDK 内没有定时任务。
- 非法 UTF-16 代理项会报错。emoji、扩展汉字和空字符在 JNI 边界按标准 UTF-8 转换；译文是否保留这些字符仍由分词器/模型决定。
- HTML 翻译仍属实验能力，宿主负责 DOM、脚本和特殊节点处理。
- 自定义线程限 1–64、miniBatchWords 限 256–65536、cacheSize 限 0–1000000。较大设置可能需要大量内存。
- SHA-256 与结构校验会增加首次加载开销；README 的 v0.3.0 性能数据属于该历史版本，不能当作新版耗时承诺。

## 8. 许可与再分发

AAR 的 `io/github/yinvoker/foxlet/licenses/` 包含 NOTICE、完整第三方许可及 SOURCE.txt。可在宿主“开源许可”页面展示，demo 已提供示例。保留适用声明和 MPL 源码取得方式；源码和源码修改可从相应发布版本取得。

默认 AAR 含 LGPL-2.1 的 Moses 数据；无前缀版本不含这些数据。模型单独受 MPL-2.0 约束。FLORES-200 仅用于评测，AAR/demo 不含它；源码评测材料的出处和 BibTeX 见 [评测引用](../benchmarks/CITATION.md)。
