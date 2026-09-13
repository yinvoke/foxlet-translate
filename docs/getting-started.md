# 快速开始

本文对应 0.4.0 统一 API。本次是**不兼容重构**，不提供旧入口的兼容层，包名仍为 `io.github.yinvoker.foxlet`。发行 AAR 与 Demo 见 [v0.4.0 Release](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.4.0)，也可按下方步骤从源码构建。

支持 Android 9+、arm64-v8a。使用 JDK 17、Android SDK 36、NDK 29.0.13113456 与 CMake 3.31.6。

## 1. 构建与接入

```bash
./gradlew :foxlet:packageWithoutPrefixes :demo:assembleRelease
adb install -r demo/build/outputs/apk/release/demo-release.apk
```

`demo/` 消费最终 AAR 并启用 R8，演示模型准备、翻译、更新检查和清理。首次准备需要联网；准备完成后可断网翻译。`sample/` 是独立评测 app。

默认产物 `foxlet/build/outputs/aar/foxlet-release.aar` 包含 25 种语言的分句前缀表。`foxlet-no-prefixes-release.aar` 不包含 LGPL-2.1 前缀数据；两者二选一。将 AAR 复制到宿主的 `libs/` 并显式声明协程依赖：

```kotlin
android { defaultConfig { minSdk = 28 } }
dependencies {
    implementation(files("libs/foxlet-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

下载和更新检查需要网络权限；翻译不会上传文本：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## 2. 创建一个长期持有的客户端

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

同一进程只能有一个活动客户端。应用应让页面共享它；一个页面销毁时不要关闭其他页面仍在使用的客户端。所有使用者结束后调用 `shutdown()`，等待结束才能创建下一个。

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

`prepare` 优先选择本地最新且校验通过的安装；没有才下载内置版本。它不查询远端“最新版本”，也不会把本地可用的新版本降为内置旧版本。纯离线使用 `PreparePolicy.LocalOnly`；缺模型时抛 `ModelNotInstalledException`，不联网。

```kotlin
val pair = LanguagePair("en", "zh-Hans")
val model = foxlet.models.prepare(pair, PreparePolicy.LocalOnly)
val single: String = foxlet.translator.translate("Hello.", model)
val batch: List<String> = foxlet.translator.translate(listOf("Hello.", "Thank you."), model)
```

单条输入返回字符串，列表返回同序列表。空列表直接返回空列表，不加载模型；空字符串不会被自动过滤。`LanguagePair` 保留完整语言标签，规范化大小写，但不会把 `zh` 猜成 `zh-Hans` 或 `zh-Hant`。

## 4. 模型查询与下载

| 方法 | 结果与行为 |
| --- | --- |
| `listBundled()` / `findBundled(pair)` | 查询 SDK 固定快照；find 缺失返回 null |
| `listInstalled(pair, verification)` | 列出安装；默认只读元数据，Check 会校验文件 |
| `findUsable(pair)` | 从新到旧校验候选，返回第一份可用安装或 null |
| `verify(model)` | 返回整体及逐文件校验报告，不修复、不下载 |
| `download(descriptor)` | 下载指定内容；校验后复用完整目录或发布新目录 |

`download`、`prepare`、`findUsable` 都返回 `InstalledModel`。它是只读快照，可以直接传给翻译和删除。`id` 区分存储目录与修复副本；`identity` 标识内容，二者用途不同。

校验状态分为 `NotChecked`、`Verified`、`Invalid` 和 `Unverifiable`。未知清单的目录可以列出、删除，不能当作可用模型。目录读取失败会报 `ModelStorageException`，不会伪装成空库存。快照不绕过首次 native 加载时的文件校验。

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

所有下载路径使用同一个 `DownloadProgress`。默认断点续传、最多 3 次重试，重试仅覆盖传输失败与 HTTP 408/429/5xx。大小或 SHA-256 不符不会重试；回调异常也不会作为网络错误重试。回调应轻量执行，不要阻塞等待或重入同一个模型管理操作。

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

默认 `Threading.Fixed(1)`；自动策略使用 `Threading.Auto(Workload.SINGLE/BATCH/PIVOT)`，沿用 1/2/4/6 档位，固定线程允许 1–64。`translator.threadingInfo` 提供只读实际配置及设备依据；固定配置不伪造推荐依据。

保留策略包括 `Idle(duration)`、`AfterRequest`、`UntilShutdown`。默认空闲 60 秒；AfterRequest 在本次请求结束、下一次翻译开始前卸载。批次参数允许 `miniBatchWords = 256..65536`、`cacheSize = 0..1000000`，默认 512 / 0。缓存、输入组合和过短批次可能影响输出，历史性能结论不能当作新版本耗时承诺。

外部模型显式声明语向，不进入 SDK 删除/清理接口：

```kotlin
val external = ExternalModel(
    LanguagePair("en", "zh-Hans"),
    ModelFiles.fromDirectory(directory).copy(expectedSha256 = trustedPublisherHashes),
)
val translated = foxlet.translator.translate("Hello.", external)
```

`expectedSha256` 必须来自可信发布者，不能把现下载文件自身的 hash 当成信任依据。完整匹配内置模型的文件可省略该映射。模型、词表、shortlist 和自供前缀在使用期间必须保持不可变。自供 UTF-8 前缀文件使用 `ModelFiles.nonbreakingPrefixFile`，最多 1 MiB；`translation { nonbreakingPrefixes = false }` 禁用前缀读取，仍会进行基础分句。

中转显式传两个方向相连的模型，不自动下载或猜测路由：

```kotlin
val first = foxlet.models.prepare(LanguagePair("ja", "en"))
val second = foxlet.models.prepare(LanguagePair("en", "zh-Hans"))
val translated = foxlet.translator.translatePivot("こんにちは。", first, second)
```

中转同时驻留两个模型；节省内存时分两次 translate，在中间等待 `unloadModels`。HTML 使用 `TextFormat.Html`，仍属实验能力。非法 UTF-16 代理项会报错；emoji、扩展汉字和空字符按 UTF-8 转换，最终保留与否由分词器和模型决定。

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

旧 `ModelCatalog`、`FoxletEngine`、`EngineConfig`、`ThreadTuning`、`NonbreakingPrefixes` 入口已移除。宿主需重新编译并迁移到 `Foxlet`；没有废弃别名或旧 ABI 保留。删除了无效 `workspaceMb` 参数、Future 生命周期方法及多种进度 lambda。`ModelFiles` 仅保留为外部模型文件描述。完整设计与变更映射见 [统一 API 设计](api-design-review.md)。

本地验证、分支推送与重新发布的步骤见 [推送与发布准备](releasing.md)。旧版本的 APK 或设备结果不能作为新 API 构件的验收记录。

## 9. 许可

AAR 中的 `io/github/yinvoker/foxlet/licenses/` 包含 NOTICE、第三方许可和 SOURCE.txt，Demo 提供展示入口。默认 AAR 含 LGPL-2.1 的 Moses 数据；无前缀版不含这些数据。模型单独受 MPL-2.0 约束。AAR/Demo 不含 FLORES-200，评测材料出处见 [评测引用](../benchmarks/CITATION.md)。
