# 快速开始

本文使用 0.4.0 起提供的统一 API，与 0.3.x 的旧入口不兼容。包名为 `io.github.yinvoker.foxlet`。发行 AAR 与 SDK 示例见 [v0.5.0 Release](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0)，也可按下方步骤从源码构建。

支持 Android 9+、arm64-v8a。直接使用发行 AAR 无需安装 NDK / CMake；宿主 Java / Kotlin JVM target 使用 17。只有从源码构建 SDK 时才需要仓库指定的 JDK 17、Android SDK 36、NDK 29.0.13113456 与 CMake 3.31.6。

0.5.0 提供单一 AAR，内置自有多语言分句规则，并排除 Android SentencePiece 训练代码。v0.4.0 发行附件仍使用原有 Moses 数据及 LGPL-2.1 声明。分句配置与迁移差异见 [分句设计](sentence-segmentation.md)。

## 1. 下载与接入

从 [v0.5.0 Release](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0) 下载以下文件：

- [统一 AAR](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-v0.5.0.aar)：v0.5.0 SDK。
- [SDK 示例 APK](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-sdk-example-v0.5.0.apk)：直接安装体验模型准备、翻译、更新检查和清理，使用开发签名。
- [SHA256SUMS](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/SHA256SUMS)：用于校验下载文件。

0.5.0 AAR 约 2.8 MB，AAR 和 SDK 示例均不携带模型。首次准备需要联网下载对应模型（英→简体中文约 52 MB）；准备完成后可断网翻译。

将 AAR 保存到宿主的 `app/libs/foxlet-v0.5.0.aar`，在 `app/build.gradle.kts` 中显式声明文件依赖和协程依赖：

```kotlin
android {
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(files("libs/foxlet-v0.5.0.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

宿主 Kotlin JVM target 也应设为 17。本地 AAR 不包含依赖元数据，因此协程依赖需要显式声明。

Maven Central 发布配置已准备，但尚未上线；当前通过发行 AAR 接入。

在宿主 `AndroidManifest.xml` 中声明下载和更新检查所需权限；翻译不会上传文本：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 可选：从源码构建

在本仓库根目录执行：

```bash
./gradlew :foxlet:assembleRelease :sdk-example:assembleRelease
adb install -r sdk-example/build/outputs/apk/release/sdk-example-release.apk
```

源码构建产物为 `foxlet/build/outputs/aar/foxlet-release.aar`，与发行附件的版本化文件名不同。`sdk-example/` 消费最终 AAR 并启用 R8；`benchmark/app/` 是独立评测 app。工具链与设备检查见 [构建与测试](benchmarking.md)。

## 2. 客户端创建与作用域

配置对象和 DSL 使用同一套验证。`create` 是挂起函数，内部处理设备探测；初始化不下载、不扫描模型，也不预先创建模型目录。

```kotlin
import android.content.Context
import io.github.yinvoker.foxlet.*
import kotlin.time.Duration.Companion.seconds

suspend fun openFoxlet(context: Context): Foxlet = Foxlet.create(context) {
    models {
        source = ModelSource.Mozilla
        networkOptions = NetworkOptions(readTimeout = 20.seconds)
        downloadOptions = DownloadOptions(maxRetries = 3, resume = true)
    }
    translation {
        threading = Threading.Auto(Workload.BATCH)
        retention = ModelRetention.Idle(60.seconds)
    }
}

// 等价的配置对象入口：
suspend fun openWithConfig(context: Context): Foxlet =
    Foxlet.create(context, FoxletConfig(
        models = ModelsConfig(networkOptions = NetworkOptions(readTimeout = 20.seconds)),
        translation = TranslationConfig(threading = Threading.Auto(Workload.BATCH)),
    ))
```

默认目录为 `context.noBackupFilesDir/translation-models`。复用已有安装时，在 `models { directory = existingDirectory }` 中指定原目录；SDK 不搬迁文件。目录应由单个应用进程专用。客户端不持有 Activity context。

同一进程只能有一个活动客户端。客户端由应用作用域持有，页面共享使用；单个页面销毁不应结束其他页面仍在使用的客户端。所有使用者结束后调用 `shutdown()`，等待结束才能创建下一个。

## 3. 准备模型与翻译

```kotlin
suspend fun translateEnglish(foxlet: Foxlet, text: String): String {
    val pair = LanguagePair("en", "zh-Hans")
    val model = foxlet.models.prepare(pair) { progress ->
        // 回调在 SDK 的 IO 上下文执行；UI 更新需切回主线程。
        // Ready 才表示模型已校验并可用，下载字节到达总量不代表完成。
    }
    return foxlet.translator.translate(text, model)
}
```

`prepare` 优先选择本地最新且校验通过的安装；没有才下载内置版本。它不查询远端“最新版本”，也不会将本地可用的新版本降为内置旧版本。纯离线使用 `PreparePolicy.LocalOnly`；缺模型时抛 `ModelNotInstalledException`，不联网。

```kotlin
val pair = LanguagePair("en", "zh-Hans")
val model = foxlet.models.prepare(pair, PreparePolicy.LocalOnly)
val single: String = foxlet.translator.translate("Hello.", model)
val batch: List<String> = foxlet.translator.translate(listOf("Hello.", "Thank you."), model)
```

单条输入返回字符串，列表返回同序列表。空列表直接返回空列表，不加载模型；空字符串不会被自动过滤。`LanguagePair` 保留完整语言标签，规范化大小写，但不会将 `zh` 自动映射为 `zh-Hans` 或 `zh-Hant`。

## 4. 模型查询与下载

| 方法 | 结果与行为 |
| --- | --- |
| `listBundled()` / `findBundled(pair)` | 查询 SDK 固定快照；find 缺失返回 null |
| `listInstalled(pair, verification)` | 列出安装；默认只读元数据，Check 会校验文件 |
| `findUsable(pair)` | 从新到旧校验候选，返回第一份可用安装或 null |
| `verify(model)` | 返回整体及逐文件校验报告，不修复、不下载 |
| `download(descriptor)` | 下载指定内容；校验后复用完整目录或发布新目录 |

`download`、`prepare`、`findUsable` 都返回 `InstalledModel`。它是只读快照，可以直接传给翻译和删除。`id` 区分存储目录与修复副本；`identity` 标识内容，二者用途不同。

校验状态分为 `NotChecked`、`Verified`、`Invalid` 和 `Unverifiable`。缺少可识别清单的目录支持枚举和删除，但不作为可用模型返回。目录读取失败会报 `ModelStorageException`，不会返回空列表掩盖读取失败。快照不绕过首次 native 加载时的文件校验。

```kotlin
val descriptor = foxlet.models.findBundled(LanguagePair("en", "zh-Hans"))
    ?: error("没有内置模型")
val model = foxlet.models.download(
    descriptor,
    options = DownloadOptions(maxRetries = 5),
) { progress ->
    // stage: CheckingLocal / Downloading / Verifying / Ready
    // downloadedBytes / totalBytes: 含续传字节的模型进度，不是网络流量
    // assetIndex 从 0 开始；attempt 从 1 开始；非文件阶段的资产字段可为空
}
```

所有下载路径使用同一个 `DownloadProgress`。默认断点续传、最多 3 次重试，重试仅覆盖传输失败与 HTTP 408/429/5xx。大小或 SHA-256 不符不会重试；回调异常也不会作为网络错误重试。回调应保持轻量；阻塞等待或重入同一模型管理操作可能阻碍当前下载。

下载中断时，`resume = true` 保留临时文件供续传；false 会清理该次临时目录。服务端拒绝 Range 时进度可以回退。正式模型不会原地覆盖，修复会产生独立安装目录。

## 5. 查询和安装更新

```kotlin
val pair = LanguagePair("en", "zh-Hans")
val report = foxlet.models.checkUpdates(setOf(pair))
val update = report.updates.firstOrNull()
if (update != null) {
    val model = foxlet.models.download(update.target)
    val text = foxlet.translator.translate("Welcome back.", model)
}
```

`updates` 中每项的 `installed` 和 `target` 都非空；`reason` 区分更高版本与同版本重打包。`assessments` 完整列出 `NotInstalled`、`UpdateAvailable`、`Current`、`LocalAhead` 和 `NoCompatibleRelease`。`installedStillListed` 与 `newerMajorVersion` 独立表达撤回和不兼容大版本。`checkedAt` 与 `indexUpdatedAt` 使用 `Instant`。

更新比较读取本地元数据，不隐含全盘 hash。`isBundledVersion` 只表示匹配内置快照，不表示远端最新。索引默认不重试；可显式传 `UpdateOptions(maxRetries = 2)`。更新查询不安装、不删除，也没有定时联网任务。

`NetworkOptions` 的连接和读取超时默认都是 15 秒。模型配置中的 `networkOptions` 供两类请求共用，`DownloadOptions` / `UpdateOptions` 内的非空 `networkOptions` 会替换它。单次传入非空 options 时，替换对应的整个选项组，不按字段合并。

自建镜像使用 `ModelSource(indexUrl, attachmentBaseUrl, allowedAttachmentHosts, userAgent)`。地址必须是 HTTPS，附件基地址以 / 结尾，域名统一声明；不跟随重定向。内置描述符保留内容身份，将 Mozilla 附件基地址替换为镜像基地址，因此镜像应提供相同相对路径。

远端索引依赖 HTTPS 和记录中的大小、SHA-256，**未实现 Remote Settings 集合签名验证**。模型大版本能力从 `foxlet.capabilities.supportedModelMajorVersions` 查询，当前为 1.x–2.x。

## 6. 删除、清理与内存卸载

```kotlin
val model = foxlet.models.prepare(LanguagePair("en", "zh-Hans"))
val cleanup = foxlet.models.cleanup(keep = setOf(model.id))

// 先停止提交使用该模型的新翻译，再等待卸载。
val unload = foxlet.translator.unloadModels()
if (unload.allReleased) {
    val deletion = foxlet.models.delete(model)
}
// 删除某语向的全部版本：
val all = foxlet.models.deleteAll(LanguagePair("ja", "en"))
```

模型正在加载或驻留时，删除抛 `ModelInUseException`。`deleteAll` 在占用检查阶段全有或全无；实际文件系统删除不承诺事务回滚。每个 `DeleteResult` 区分 `Deleted`、`AlreadyAbsent`、`PartiallyDeleted`、`Failed`，并记录实际 `freedBytes` 和失败信息。

`cleanup` 默认回收超过 7 天未写入的下载临时目录、删除残留，以及有已验证较新版本替代的旧安装。`CleanupOptions` 可调整保留期、关闭旧版本清理或保留额外临时目录。报告包含移除、占用跳过、失败与释放字节；不会沿目录内符号链接清理外部文件。

保存 `InstalledModel` 引用不会保留磁盘目录，仍要使用的安装须传入 `keep`。`unloadModels` 释放内存，不删除安装，也不是阻止后续翻译的屏障。释放未确认时，`unconfirmedReleaseCount` 会报告数量，文件保持占用保护，直至 `shutdown` 销毁服务。

## 7. 翻译策略、外部模型与关闭

默认 `Threading.Fixed(1)`；自动策略使用 `Threading.Auto(Workload.SINGLE/BATCH/PIVOT)`，沿用 1/2/4/6 档位，固定线程允许 1–64。`translator.threadingInfo` 提供只读实际配置及设备依据；固定配置的设备推荐信息为 null。通常建议显式使用双线程；内存受限或交互单句场景采用单线程。自动档位是资源上限策略，不保证所选线程数具有最佳吞吐。

保留策略包括 `Idle(duration)`、`AfterRequest`、`UntilShutdown`。默认空闲 60 秒；`AfterRequest` 在请求的 `finally` 中执行卸载，早于下一次翻译开始。批次参数允许 `miniBatchWords = 256..65536`、`cacheSize = 0..1000000`，默认 512 / 0。缓存、输入组合和过短批次可能影响输出，历史性能结论不能当作新版本耗时承诺。

外部模型显式声明语向，不进入 SDK 删除/清理接口：

```kotlin
val external = ExternalModel(
    LanguagePair("en", "zh-Hans"),
    ModelFiles.fromDirectory(directory).copy(expectedSha256 = trustedPublisherHashes),
)
val translated = foxlet.translator.translate("Hello.", external)
```

`expectedSha256` 必须来自可信发布者，下载文件自身计算出的 hash 不能作为独立的信任依据。完整匹配内置模型的文件可省略该映射。模型、词表、shortlist 和自供前缀在使用期间必须保持不可变。核心验证语言为中、英、日、韩、俄。`capabilities.prefixLanguages` 返回 `en/de/fr/es/pt/it/ru/tr/zh/ja/ko`：中日韩复用拉丁缩写规则来处理混合文本，同时使用各自的标点、引述、日期规则；俄语也处理混入的拉丁缩写。这不影响支持的翻译语向。自供 UTF-8 前缀文件替换对应模型的内置规则，使用 `ModelFiles.nonbreakingPrefixFile`，最多 1 MiB；`translation { nonbreakingPrefixes = false }` 禁用前缀读取，仍会进行 Unicode 分句和语言标点处理。

中转要求显式传入两个语向相连的模型，模型准备与路由选择由应用负责：

```kotlin
val first = foxlet.models.prepare(LanguagePair("ja", "en"))
val second = foxlet.models.prepare(LanguagePair("en", "zh-Hans"))
val translated = foxlet.translator.translatePivot("こんにちは。", first, second)
```

中转同时驻留两个模型；需要限制模型驻留内存时，可拆分为两次 `translate`，在两次调用之间等待 `unloadModels` 并确认 `allReleased`。HTML 使用 `TextFormat.Html`，仍属实验能力。非法 UTF-16 代理项会报错；emoji、扩展汉字和空字符按 UTF-8 转换，最终保留与否由分词器和模型决定。

```kotlin
val foxlet = Foxlet.create(context)
try {
    // 运行应用所需的模型操作和翻译。
} finally {
    foxlet.shutdown()
}
```

`shutdown` 拒绝新操作，取消 SDK 登记的子任务，等待不可中断的 native 批次，再释放模型、计时器和执行器。它不取消宿主整个协程作用域；重复调用等待同一次终止，调用方取消也不会打断清理。阻塞网络读取的取消仍受传输栈和配置超时约束。

## 8. 错误与迁移

正常结果直接返回；业务失败使用 `FoxletException` 子类：`ModelNotInstalledException`、`UnsupportedLanguagePairException`、`ModelInUseException`、`ModelIntegrityException`、`NetworkException`、`ModelStorageException`、`ModelIndexException`、`TranslationException`。保留 cause 和适用的 installationId、assetName、HTTP status、attempt 等字段。参数错误仍为 `IllegalArgumentException`；关闭后的调用为 `ClientClosedException`。`CancellationException` 继续传播。

旧 `ModelCatalog`、`FoxletEngine`、`EngineConfig`、`ThreadTuning`、`NonbreakingPrefixes` 入口已移除。宿主需重新编译并迁移到 `Foxlet`；没有废弃别名或旧 ABI 保留。删除了无效 `workspaceMb` 参数、Future 生命周期方法及多种进度 lambda。`ModelFiles` 仅保留为外部模型文件描述。公开 JVM 签名及检查方法见 [公开 API 快照](public-api.md)。

本地验证、分支推送与重新发布的步骤见 [发布指南](releasing.md)。旧版本的 APK 或设备结果不能作为新 API 构件的验收记录。

## 9. 许可

AAR 中的 `io/github/yinvoker/foxlet/licenses/` 包含 NOTICE、第三方许可和 SOURCE.txt，SDK 示例提供展示入口。当前源码构建的 AAR 使用 MIT 许可的自有规则，不包含 Moses 数据；历史 v0.4.0 下载的许可范围不变。模型单独受 MPL-2.0 约束。AAR/SDK 示例不含 FLORES-200，评测材料出处见 [评测引用](../benchmark/data/CITATION.md)。
