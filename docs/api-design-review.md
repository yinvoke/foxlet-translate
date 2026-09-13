# Kotlin API 审查与统一设计

状态：核心实现与本地验证已完成，重构后的真机验收待执行，发布保持撤回。审查日期：2026-09-13。重构前基线：`e699e1c`，开发版本 `0.4.0`。

本文审查 Android SDK 的全部公开 Kotlin 类型与操作，并结合下载、存储、更新、JNI 生命周期和 Demo 的使用方式核对行为。`engine/` 的 vendor C++ 接口、内部 JNI 及评测 CLI 不作为应用接入 API 重新设计。后续实施经确认采用不兼容重构：移除旧公开入口，不提供兼容别名或旧 ABI。本文保留设计推导，实际接口与用法以 [接入指南](getting-started.md) 为准。

## 1. 结论与目标

建议以一个 `Foxlet` 客户端作为应用入口，按职责暴露 `models` 和 `translator`。初始化统一使用配置对象，Kotlin DSL 负责生成同一个不可变配置；模型目录、来源、网络策略与翻译配置都在这里绑定。

接入者日常只需要认识 `LanguagePair`、`ModelDescriptor` 和 `InstalledModel`：语向用于选择，描述符用于下载具体版本，已安装模型用于翻译与管理。耗时操作统一使用 `suspend`，下载进度统一为一个结构化事件类型，查询与变更的返回值有明确约定。

优先解决业务含义和生命周期的不一致，再简化调用表达。实现应复用现有下载器、存储、校验及 native 引擎，不重新实现这些能力。

## 2. 重构前公开 API 清单

| 领域 | 当前公开入口 | 当前主要行为 |
| --- | --- | --- |
| SDK 内置索引 | `ModelCatalog.models`、`find(from, to)`、`supportedMajorVersions` | 内置快照；`find` 找不到时抛异常 |
| 下载 | `ModelCatalog.download` 三个重载、`DownloadPolicy`、`DownloadProgress` | 按语向选择内置版本，或下载指定 `Model`；返回 `ModelFiles` |
| 本地查询 | `installed(root, verify)`、`installedFor(root, from, to)` | 前者返回元数据列表；后者校验候选并返回最新可用 `ModelFiles?` |
| 删除与清理 | 两个 `delete` 重载、`cleanup`、`CleanupReport` | 删除返回释放字节；清理返回目录、安装记录与跳过列表 |
| 更新 | `checkForUpdates`、`UpdateSource`、`UpdateCandidate`、`UpdateReport` | 获取远端索引并比较本地元数据；不下载更新 |
| 模型描述 | `ModelCatalog.Asset`、`ModelCatalog.Model`、`InstalledModel.files()` | 远端文件元数据、内容标识、安装位置与文件解析分散在多个类型 |
| 外部模型 | `ModelFiles` 构造/`copy`、`fromDirectory`、`sourceLanguageOf`、`MAX_LENGTH_BREAK` | 描述文件、可信哈希、源语言与自定义前缀；实际使用时校验 |
| 翻译配置 | `EngineConfig` 构造、`EngineConfig.forDevice` | 显式线程与自动推荐分别创建配置，配置同时携带推荐结果 |
| 线程查询 | `Workload`、`ThreadTuning.recommend` / `forDevice` / `bigCoreCount`、`Decision` | 纯计算与设备读取均为公开函数 |
| 翻译 | `FoxletEngine.translate`、`translatePivot` | `suspend`，列表输入输出，`html` 布尔参数 |
| 引擎生命周期 | 构造、`releaseAllModels`、`loadedModelCount`、`close` | 每进程一个引擎；查询/释放返回 `Future`，关闭阻塞 |
| 前缀资源 | `NonbreakingPrefixes.languages`、`bytesFor` | 对外暴露底层分句资源；不是支持的翻译语言列表 |

`ActiveModels`、`ModelDownloader`、`ModelStore`、`RemoteIndex`、`ModelManifest`、`IdleSweeper` 和 `NativeBridge` 已是 `internal`。它们应继续作为内部实现，不再增加平行的公共入口。

## 3. 重构前发现的问题

这里的优先级表示 API 重构顺序，不表示每项都是运行错误。

| 优先级 | 问题与接入影响 | 源码依据 | 建议 |
| --- | --- | --- | --- |
| 高 | `ModelCatalog` 同时承担静态目录、下载、文件管理和更新；几乎每个操作重复 `root`，来源和传输配置另传 | [ModelCatalog](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L18) | 根客户端绑定配置，`models` 统一组织模型操作 |
| 高 | `download` / `installedFor` 返回文件，`installed` 返回安装记录，更新又返回嵌套 `Model`；调用者反复转换，版本/身份信息容易丢失 | [下载与查询](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L118) | 下载、准备、查找均返回 `InstalledModel`，直接用于翻译和删除 |
| 高 | 下载是否带 `policy` 会改变进度 lambda 的形状；只想要逐文件进度也必须传默认策略 | [下载重载](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L123) | 所有下载路径统一 `DownloadProgress`，策略与进度独立 |
| 高 | `download(from, to)` 选择内置版本；本地可能已有远端新版本，Demo 必须手写“本地优先，否则下载” | [Demo 准备流程](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/demo/src/main/kotlin/io/github/yinvoker/foxlet/demo/MainActivity.kt#L71) | 增加语义明确的 `prepare`，`download` 只接收精确描述符 |
| 高 | 翻译/模型管理是 `suspend`，释放与状态是 `Future`，关闭需要调用者额外切后台；Demo 包裹 IO 并逐次创建引擎 | [生命周期](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/FoxletEngine.kt#L363)、[Demo 翻译](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/demo/src/main/kotlin/io/github/yinvoker/foxlet/demo/MainActivity.kt#L90) | 新入口全部采用协程生命周期，长持有客户端，提供挂起式 `shutdown` |
| 高 | 模型内存驻留与磁盘保留是不同状态；普通引用不会阻止清理，应用要从 `files.model.parentFile` 推导保留目录 | [占用契约](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L208)、[Demo 清理](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/demo/src/main/kotlin/io/github/yinvoker/foxlet/demo/MainActivity.kt#L158) | 用安装标识表达 `keep`，显式区分磁盘删除与内存卸载 |
| 中 | 自动配置、显式配置、纯推荐、设备推荐分散；公开 `tuning` 可以与 `threads` 构造出不一致状态 | [EngineConfig](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/FoxletEngine.kt#L136) | 输入只接收 `Threading` 策略，解析结果从 translator 只读取得 |
| 中 | `find` 缺失抛异常，`installedFor` 缺失返回 null；`verified: Boolean?` 把“未检查”和“无法核验”合并 | [查询和安装记录](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L169) | 统一 find 的空值契约，以显式校验状态表示差别 |
| 中 | 更新状态由多个 nullable 字段与布尔值组合；`updates` 的元素仍要求处理 nullable `available` | [UpdateCandidate](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L285) | `updates` 返回具有非空 installed/target 的 `ModelUpdate`，完整报告另列状态和附加提示 |
| 中 | `isCurrentCatalogVersion` 容易被解释成远端最新；更新比较使用未校验的本地最高版本，不等于可用模型选择 | [安装状态](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L157)、[更新比较](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/RemoteIndex.kt#L261) | 分开表示“匹配内置快照”“远端比较结果”“文件校验状态” |
| 中 | 下载超时/重试可配置，更新请求固定 15 秒且无重试；自定义来源和允许的下载域名在两个对象中设置 | [DownloadPolicy](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L61)、[索引请求](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/RemoteIndex.kt#L125) | 来源统一描述索引与附件，网络参数同型；保留两类请求各自默认重试次数 |
| 中 | `IllegalArgumentException` 既表示参数错误，也表示远端字节校验失败；HTTP/索引/native 错误类型不统一 | [下载器](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelDownloader.kt#L25)、[校验](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt#L343)、[JNI](../jni/foxlet_jni.cpp#L54) | 公共边界转换为结构化领域异常，保留 cause 与诊断字段 |
| 中 | `delete` 只返回字节数，无法直接说明已不存在或部分删除；清理报告也没有统一的失败项 | [删除实现](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelStore.kt#L193) | 使用 `DeleteReport` / `CleanupReport`，明确部分失败和已释放字节 |
| 低 | `from/to`、`Pair<String,String>`、文件名推导语言并存；毫秒 Int/Long、epoch 时间及 bytes 命名不一致 | [模型目录 API](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/ModelCatalog.kt)、[ModelFiles](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/FoxletEngine.kt#L14) | 语向用 `LanguagePair`；新配置用 `Duration`，时间点用 `Instant`，字节字段带 `Bytes` |
| 低 | `workspaceMb` 已无效果，仍出现在自动配置入口；闲置保留用负数/零表达策略，HTML 用布尔开关 | [配置](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/FoxletEngine.kt#L150)、[翻译](https://github.com/yinvoke/foxlet-translate/blob/e699e1ccdec689ad52e5e0668a5e51ccc6012d7e/foxlet/src/main/kotlin/io/github/yinvoker/foxlet/FoxletEngine.kt#L321) | 新入口省略无效参数，使用保留策略与 `TextFormat` |

## 4. 统一入口与配置

建议对外结构：

```text
Foxlet
├── models: ModelManager
│   ├── listBundled / findBundled
│   ├── listInstalled / findUsable / verify
│   ├── prepare / download
│   ├── checkUpdates
│   └── delete / deleteAll / cleanup
├── translator: Translator
│   ├── translate / translatePivot
│   ├── threadingInfo / getState
│   └── unloadModels
├── capabilities: FoxletCapabilities
└── shutdown
```

统一在 `Foxlet.create` 创建：配置对象重载和 DSL 重载生成同一个 `FoxletConfig`，只运行同一套验证与初始化逻辑。`create` 为 `suspend`，设备探测与可能的 native 库加载在内部切换线程；返回时线程策略已经解析。初始化不下载、扫描或校验模型，文件目录按需创建。

```kotlin
// 设计示例：在协程中初始化，随后由应用长期持有。
val foxlet = Foxlet.create(context) {
    models {
        directory = File(context.noBackupFilesDir, "translation-models")
        source = ModelSource.Mozilla
        downloadOptions = DownloadOptions(maxRetries = 3, resume = true)
    }
    translation {
        threading = Threading.Auto(Workload.BATCH)
        retention = ModelRetention.Idle(60.seconds)
    }
}
```

默认目录为应用 `noBackupFilesDir/translation-models`。迁移已有安装时必须显式设置旧目录；SDK 不擅自移动或复制历史目录。客户端仅保留 application context。

配置规则：

| 配置 | 设计契约 |
| --- | --- |
| `threading` | `Fixed(1)` 为默认；自动使用 `Auto(SINGLE/BATCH/PIVOT)`；固定线程 1–64，自动档位沿用 1/2/4/6 |
| `retention` | `Idle(60.seconds)` 默认；另有 `AfterRequest`、`UntilShutdown`，替代原来的零和负数 |
| 批次/缓存 | `miniBatchWords = 512`、`cacheSize = 0`，边界沿用现实现；不改变短输入或缓存影响输出的契约 |
| 分句前缀 | 默认使用内置前缀；保留禁用和外部模型自带 UTF-8 前缀文件的能力；禁用前缀不等于彻底关闭分句 |
| `networkOptions` | 连接/读取超时使用 `Duration`，默认各 15 秒，供索引与附件共享；转换成底层毫秒参数时验证正值与范围 |
| `downloadOptions` | 断点续传默认开启，默认 3 次重试；退避上下限使用 `Duration`，保留现有重试状态码与校验失败不重试的语义 |
| `updateOptions` | 索引默认 0 次重试，与当前行为一致；允许显式覆盖重试参数 |
| `ModelSource` | 索引 URL、附件基地址、允许附件域名和 User-Agent 放在同一个来源配置中；自定义镜像必须完整声明 |
| 单次覆盖 | `download` / `prepare` / `checkUpdates` 可传对应 options；null 表示使用客户端配置，非 null 替换该组选项，不做隐式字段合并 |

`DownloadOptions` 和 `UpdateOptions` 各自可带一个可选 `networkOptions`；未设置时继承客户端的共享网络配置，设置时整组替换。这样既统一默认超时，也保留单次操作调整网络参数的能力。

新配置中没有 `workspaceMb` 和可写的推荐结果。线程策略在创建时解析一次，后续既不按输入条数偷偷改变，也不随温度动态改变；调整策略需要关闭并重建客户端。`Auto(BATCH)` 不会因调用 `translatePivot` 自动变成 `Auto(PIVOT)`，中转任务应从初始化就选择 `PIVOT`。

## 5. 统一模型类型

| 类型 | 表达什么 | 不承担什么 |
| --- | --- | --- |
| `LanguagePair(source, target)` | 一条明确语向，统一用于目录查询、准备、筛选更新 | 不推导或自动添加中转路径 |
| `ModelDescriptor` | 精确语向、版本、内容身份和完整资产清单；可下载目标 | 不表示已安装或内存驻留 |
| `InstalledModel : LocalModel` | 一个具体安装目录的只读快照，含安装 ID、描述符（可能缺失）、磁盘字节与校验状态 | 不因对象仍被引用就永久保留目录 |
| `ExternalModel : LocalModel` | 显式语向与宿主提供的 `ModelFiles`，用于高级自定义模型场景 | 不交给 SDK 的安装删除/清理接口 |
| `InstallationId` | 模型存储身份加安装目录身份；区分同内容的多个修复目录 | 不等同于版本号或内容哈希 |

`LocalModel` 是翻译所需的本地模型引用。模型管理返回的 `InstalledModel` 可以直接传给翻译；`ModelFiles` 留作外部模型的高级文件描述，不再是普通下载流程的返回值。

`LanguagePair` 保留完整语言标签（特别是 `zh-Hans` 与 `zh-Hant`），仅规范化已定义的大小写规则，不凭空把 `zh` 映射为某一种文字。内置支持语向从 `listBundled` 获取；`capabilities.prefixLanguages` 仅代表分句资源覆盖范围。

安装记录的校验状态改为 `NotChecked`、`Verified`、`Invalid`、`Unverifiable`。列出一个目录不代表它可以翻译；`download`、`prepare` 和 `findUsable` 成功返回的是当次已验证的安装记录。记录是时间点快照，首次加载仍需验证文件未被外部修改；未知清单的安装可列出和删除，但不得当成可用模型。

`ModelDescriptor` 资产排序、字段验证及内容身份计算由 SDK 集中处理，不要求调用者知道排序约定。身份算法、目录布局与现有 manifest 保持一致，防止新增 API 使旧安装失去识别或产生重复下载。

## 6. 模型操作签名与语义

以下用接口形式汇总签名；实际 `ModelManager` 是由客户端持有、不可自行构造的类。默认值含义见后面的契约。

```kotlin
interface ModelManager {
    suspend fun listBundled(): List<ModelDescriptor>
    suspend fun findBundled(pair: LanguagePair): ModelDescriptor?

    suspend fun listInstalled(
        pair: LanguagePair? = null,
        verification: VerificationMode = VerificationMode.Skip,
    ): List<InstalledModel>

    suspend fun findUsable(pair: LanguagePair): InstalledModel?
    suspend fun verify(model: InstalledModel): VerificationReport

    suspend fun prepare(
        pair: LanguagePair,
        policy: PreparePolicy = PreparePolicy.LocalOrBundled,
        options: DownloadOptions? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): InstalledModel

    suspend fun download(
        model: ModelDescriptor,
        options: DownloadOptions? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): InstalledModel

    suspend fun checkUpdates(
        pairs: Set<LanguagePair>? = null,
        options: UpdateOptions? = null,
    ): UpdateReport

    suspend fun delete(model: InstalledModel): DeleteReport
    suspend fun deleteAll(pair: LanguagePair): DeleteReport

    suspend fun cleanup(
        options: CleanupOptions = CleanupOptions(),
        keep: Set<InstallationId> = emptySet(),
    ): CleanupReport
}
```

命名约定：`list*` 缺失返回空列表，`find*` 缺失返回 null；不能把目录读取错误一律伪装为“没有模型”。`prepare` 表达必须得到可用模型，无法满足策略时抛具体领域异常。动作方法不加 `Async` 后缀，Kotlin 耗时操作统一 `suspend`。

| 方法 | 选择/执行规则 | 网络行为 |
| --- | --- | --- |
| `listBundled` / `findBundled` | SDK 打包的固定快照 | 本地 |
| `listInstalled` | 返回已发布且可识别的目录；默认只读元数据；`VerificationMode.Check` 同时检查字节 | 本地 |
| `findUsable` | 按当前版本规则从新到旧验证，跳过损坏与无法核验的候选，返回第一份可用安装 | 本地 |
| `verify` | 检查指定安装并返回逐资产结果；不修复、不下载 | 本地 |
| `prepare(LocalOnly)` | 使用 `findUsable` 规则；没有可用安装抛 `ModelNotInstalledException` | 本地 |
| `prepare(LocalOrBundled)` | 先使用本地最新可用安装；没有才下载内置描述符。不会为查询“最新”请求索引，也不会把本地可用新版本换成内置旧版本 | 缺少可用本地模型时可能下载 |
| `download(descriptor)` | 确保该精确版本/身份已安装；校验成功的同身份目录直接复用，否则续传或新建目录 | 按需下载附件，不重新选择远端版本 |
| `checkUpdates` | 获取一次索引快照并比较本地安装元数据；options 显式开启重试时请求可多于一次 | 获取索引，不下载模型 |
| `delete` / `deleteAll` | 删除具体安装 / 语向所有已安装版本 | 本地 |
| `cleanup` | 清理过期临时目录、删除残留及有已验证替代版本的旧安装；保留 keep 与正在使用的目录 | 本地 |

`CleanupOptions` 保留默认 7 天临时文件保留期和默认清理被替代版本的行为，时间使用 `Duration`。高级的 `keepDirectories` 仍可表达需要保留的临时目录；正常安装保留使用 `InstallationId`。同版本不同内容的重打包不自动互删，修复产生的同内容重复目录沿用现有清理规则。

下载后模型成功进入可用状态，与 `prepare` / `findUsable` 返回的是同一种对象；失败不发布半套模型。取消和失败是否保留临时文件由 `resume` 决定，损坏的正式目录不原地覆盖。

### 下载进度

所有准备/下载路径只使用一种 `DownloadProgress`，建议字段为 `stage`、`downloadedBytes`、`totalBytes`、`assetName`、`assetIndex`、`assetCount`、`assetDownloadedBytes`、`assetTotalBytes`、`attempt`。本地查找/复用等阶段的资产字段可以为空；资产索引明确为从 0 开始，attempt 从 1 开始。

阶段为 `CheckingLocal`、`Downloading`、`Verifying`、`Ready`，重试次数通过 attempt 表达。`Ready` 只在校验及目录发布/复用都成功后发出；下载字节达到 total 不等于准备完成。本地复用也发出 Ready。`totalBytes` 指模型资产总字节，`downloadedBytes` 包括续传已有部分；它们不是本次实际网络流量。服务端不接受续传而重新开始时，进度允许回退。

回调固定在 SDK 的 IO 执行上下文串行调用，无隐式主线程切换。回调异常向调用者传播，不作为网络失败自动重试；回调只做轻量观察，不能重入等待同一个模型管理操作。主界面需要显式转发状态。第一版保留 suspend 加结构化回调，不同时增加会重新触发下载的冷 Flow 重载；以后如增加 Flow 必须单独约定订阅是否启动任务。

### 查询更新与安装更新

`UpdateReport.updates` 改为 `List<ModelUpdate>`，每项都包含非空 `installed: InstalledModel`、`target: ModelDescriptor` 和 `reason`（更高版本或同版本不同内容）。调用者可以直接 `download(update.target)`，不再使用 `available!!`。

完整报告保留 `assessments`，选择状态区分 `NotInstalled`、`UpdateAvailable`、`Current`、`LocalAhead` 和 `NoCompatibleRelease`。本地文件是否校验、已安装身份是否仍被上游列出、是否存在不兼容的新大版本，作为独立状态或 finding 表达；这些信息可能同时成立，不能压进互斥的单个“有/无更新”布尔值。

`checkedAt` 和 `indexUpdatedAt` 使用 `Instant`，分别代表本次检查时间和远端快照时间。`isBundledVersion` 仅表示与 SDK 内置身份一致，不代表远端最新。`checkUpdates` 沿用当前元数据比较，不做隐含的全盘 hash；需要验证可用性时调用 `verify` 或 `findUsable`。

更新查询不修改客户端的默认目录快照。下载 target 会安装查询时选定的精确内容；下载成功后由应用把新 `InstalledModel` 传给下一次翻译。旧模型目录及已开始的翻译保持原有身份。模型更新不会同时触发删除旧模型或替换正在执行的任务；清理是独立操作。暂不增加语义重叠的 `update` / `refresh` / `sync` 入口。

### 删除与清理结果

`DeleteReport` 明确给出每个目标的 `Deleted`、`AlreadyAbsent`、`PartiallyDeleted` 或 `Failed`，并记录 `freedBytes`；`CleanupReport` 同样列出移除、跳过、失败和实际释放的字节。删除残留仍可由后续 cleanup 回收，不能把仅移出正式目录等同于全部释放。

删除单安装时重新验证其存储归属和身份。删除语向所有版本时，在占用检查阶段仍然全有或全无：任一版本使用中就抛 `ModelInUseException`，不删除其他版本。该承诺不扩展成文件系统事务；实际逐目录删除期间的 IO 失败进入报告，不能宣称自动回滚。

## 7. 翻译、线程与关闭

```kotlin
interface Translator {
    val threadingInfo: ThreadingInfo

    suspend fun translate(
        text: String,
        model: LocalModel,
        format: TextFormat = TextFormat.Plain,
    ): String

    suspend fun translate(
        texts: List<String>,
        model: LocalModel,
        format: TextFormat = TextFormat.Plain,
    ): List<String>

    suspend fun translatePivot(
        text: String,
        first: LocalModel,
        second: LocalModel,
        format: TextFormat = TextFormat.Plain,
    ): String

    suspend fun translatePivot(
        texts: List<String>,
        first: LocalModel,
        second: LocalModel,
        format: TextFormat = TextFormat.Plain,
    ): List<String>

    suspend fun unloadModels(): UnloadReport
    suspend fun getState(): TranslatorState
}

// Foxlet 的挂起式终止操作
suspend fun shutdown()
```

单条与列表只按输入类型重载，保持输入输出形状对应。`TextFormat.Html` 继续标记实验能力。空列表返回空列表且不加载模型；空字符串保留字符串输入语义，不自动 trim 或过滤。批量输出保持输入顺序，单条包装与 `listOf(text)` 的翻译行为相同。

中转明确接收两个模型，验证第一段目标语言与第二段源语言匹配；不自动下载或猜测路由。语向标签验证不扩大现有模型能力。两套模型同时驻留仍有既有内存成本，顺序中转释放内存仍需显式执行第一段翻译、卸载、第二段翻译。

`threadingInfo` 为已解析配置快照，含请求策略、实际配置线程数和自动推荐的设备依据；手动策略没有伪造的推荐依据。线程数表示翻译 worker 配置，不表示整个进程的线程总数。`getState` 在引擎队列中取得准确的模型驻留快照，因此是 suspend，可能等待当前批次。

`unloadModels` 仅卸载内存，返回当次卸载及尚未确认销毁的信息；不能把报告字节当作系统 RSS 立即下降。对象仍然可以再次用于翻译，下次会重新加载。此方法不是暂停翻译的永久屏障：卸载后若新任务又提交，删除仍可能因占用被拒绝；delete 必须在实际执行时再次检查。

根客户端拥有一个 native engine。第一版继续遵守同进程只能有一个活动客户端的限制。统一入口不意味着支持多个并发 native engine。

`shutdown` 的设计契约是：进入终止状态后拒绝新的模型操作和翻译；取消尚未开始的任务及进行中的网络子操作；等待当前不可中断的 native 批次结束；释放模型、计时器和执行器；最后释放进程占用。它只终止 SDK 注册的操作子任务，不取消调用者整个协程作用域。多次 shutdown 等待同一个终止过程，清理阶段不会因调用方取消而半途退出。实现时必须对操作注册、关闭与提交之间的竞争进行测试；不能只用 `withContext(IO) { close() }` 声称实现了全部契约。

下载、文件扫描和网络取消均是协作式行为；阻塞读取的退出受底层取消能力与超时约束。`suspend` 不代表可以立即中断正在执行的 JNI 或文件读取。旧版 Closeable/Future 生命周期入口移除，普通调用仅使用挂起式 shutdown。

## 8. 统一错误约定

公共业务方法成功直接返回结果；意外失败抛结构化异常，不同时提供 `Result<T>`、布尔成功值和 callback 错误三套模式。取消始终传播 `CancellationException`。参数范围错误仍用 `IllegalArgumentException`，已关闭客户端使用明确的 `ClientClosedException`。

可恢复业务失败使用 `FoxletException` 的具体子类：`ModelNotInstalledException`、`UnsupportedLanguagePairException`、`ModelInUseException`、`ModelIntegrityException`、`NetworkException`、`ModelStorageException`、`ModelIndexException`、`TranslationException`。异常保留原始 cause，并按需要提供 pair、installationId、assetName、HTTP status、attempt 等字段；调用者无需解析 message。

`verify` 的正常结果允许 Invalid/Unverifiable；批量删除和清理的已知部分失败使用对应报告。这是操作本身的检查/批处理语义，权限等使整个操作无法开始的失败仍抛异常。JNI 的 std::exception 可在边界归入 TranslationException；native abort 不能通过 API 包装变成可恢复异常，不增加此类承诺。

## 9. 完整调用流程示例

以下例子使用新 API，运行于宿主协程；省略 UI 展示与 import。

```kotlin
val foxlet = Foxlet.create(context) {
    translation {
        threading = Threading.Auto(Workload.BATCH)
        // 手动时替换为 threading = Threading.Fixed(4)
    }
}

try {
    val pair = LanguagePair(source = "en", target = "zh-Hans")

    // 支持语向和本地库存查询都从 models 进入。
    val bundled = foxlet.models.listBundled()
    val installed = foxlet.models.listInstalled(pair)

    // 本地优先；缺少可用版本时下载内置版本。
    var model = foxlet.models.prepare(pair) { progress ->
        // 同一个 DownloadProgress 类型；需要更新 UI 时转发到主线程。
    }
    val first = foxlet.translator.translate("Hello, world!", model)
    val batch = foxlet.translator.translate(listOf("Good morning.", "Thank you."), model)
    val threads = foxlet.translator.threadingInfo.threads

    // 查询和执行是两个明确步骤；更新目标有非空的类型保证。
    val report = foxlet.models.checkUpdates(pairs = setOf(pair))
    val update = report.updates.firstOrNull()
    if (update != null) {
        model = foxlet.models.download(update.target)
        val afterUpdate = foxlet.translator.translate("Welcome back.", model)
    }

    // 即使空闲卸载了模型，仍可明确保留应用接下来要用的安装。
    val cleanup = foxlet.models.cleanup(keep = setOf(model.id))
} finally {
    // shutdown 内部负责完成取消后的资源清理。
    foxlet.shutdown()
}
```

纯离线流程：

```kotlin
val model = foxlet.models.prepare(pair, policy = PreparePolicy.LocalOnly)
val translated = foxlet.translator.translate(texts, model)
```

具体版本下载和删除：

```kotlin
val descriptor = foxlet.models.findBundled(pair)
    ?: error("没有该语向的内置模型")
val model = foxlet.models.download(
    descriptor,
    options = DownloadOptions(maxRetries = 5),
) { progress -> /* 同一种进度对象 */ }

// 此流程中，宿主已经停止向这个模型提交新任务。
val unloaded = foxlet.translator.unloadModels()
val deleted = foxlet.models.delete(model)
// 如需删除某语向全部版本，显式使用 deleteAll(pair)。
```

这些流程共享同一个客户端，不会每次翻译创建和关闭引擎。应用启动、页面销毁与模型更新事件由宿主安排；SDK 不新增定时联网任务。

## 10. 旧 API 到新 API 的映射

| 当前 API / 参数 | 新入口 / 表达 |
| --- | --- |
| `ModelCatalog.models` | `foxlet.models.listBundled()` |
| `ModelCatalog.find(from, to)` | `foxlet.models.findBundled(LanguagePair(...))`；缺失由抛异常改为 null |
| `download(root, from, to)` | 需要原有精确内置语义时 `findBundled` 后 `download`；本地优先流程使用 `prepare` |
| `download(root, model, policy, callback)` | `foxlet.models.download(descriptor, options, onProgress)` |
| `installed(root, verify)` | `foxlet.models.listInstalled(verification = ...)` |
| `installedFor(root, from, to)` | `foxlet.models.findUsable(pair)` |
| `InstalledModel.files()` | 日常直接传 `InstalledModel`；外部文件使用 ExternalModel + ModelFiles |
| `delete(root, installed)` | `foxlet.models.delete(installed)` |
| `delete(root, from, to)` | `foxlet.models.deleteAll(pair)`，显式体现删除范围 |
| `cleanup(root, staleTempAgeMillis, removeSuperseded, keep)` | `foxlet.models.cleanup(CleanupOptions(...), keep = installationIds)` |
| `checkForUpdates(root, source, pairs)` | `foxlet.models.checkUpdates(pairs)`；source 来自客户端配置 |
| `UpdateSource` + `DownloadPolicy.allowedHosts` | `ModelSource` 的索引/附件配置；原约束与身份校验继续存在 |
| `ModelCatalog.Model` / `Asset` | 非 Catalog 嵌套的 `ModelDescriptor` / `ModelAsset`，保留原身份算法 |
| `isCurrentCatalogVersion` / `verified` | `isBundledVersion` / `verificationStatus` |
| `downloaded` / `total` / `bytesFreed` | `downloadedBytes` / `totalBytes` / `freedBytes` |
| `EngineConfig(threads = n)` | `translation { threading = Threading.Fixed(n) }` |
| `EngineConfig.forDevice(context, workload)` | `translation { threading = Threading.Auto(workload) }` |
| `config.tuning` | `foxlet.translator.threadingInfo` 的只读推荐依据 |
| `ThreadTuning.forDevice/recommend/bigCoreCount` | 普通接入由客户端执行；底层探测收为内部实现 |
| `translate(listOf(text), files).single()` | `foxlet.translator.translate(text, model)` |
| `translate(texts, files, html)` | `foxlet.translator.translate(texts, model, format)` |
| `translatePivot(texts, first, second, html)` | 同名方法和一致的 LocalModel / TextFormat 参数 |
| `releaseAllModels().get()` | `foxlet.translator.unloadModels()` |
| `loadedModelCount().get()` | `foxlet.translator.getState().loadedModelCount` |
| `close()` | 新客户端 `shutdown()`；旧引擎入口移除 |
| `ModelFiles` / 自定义 hash / 前缀文件 | `ExternalModel(pair, files)` 高级入口；可信 hash 和文件不可变契约保留 |
| `NonbreakingPrefixes.languages/bytesFor` | 资源读取收为内部实现，覆盖语言由 capabilities.prefixLanguages 查询 |
| `supportedMajorVersions` | 新客户端只读 `capabilities.supportedModelMajorVersions`；仅说明引擎能加载的模型版本 |
| `MOZILLA_ATTACHMENT_HOST` / 默认来源常量 | 默认 `ModelSource.Mozilla`；旧常量入口移除 |
| `MAX_LENGTH_BREAK` / `sourceLanguageOf` | 原 ModelFiles 工具继续可用；不作为新普通接入必须了解的概念 |

旧公开构造器、嵌套类型、companion/object 方法及字段 getter 属于兼容性表面，不能仅靠 Kotlin typealias 宣称 Java/JVM 二进制兼容。

## 11. 迁移顺序与实现边界

1. 新增统一客户端、配置与模型类型，复用现有内部实现；按实施确认移除旧 API，不保留兼容层。保留同一 native 引擎占用机制、文件布局和模型身份算法。
2. 优先打通 `prepare → translate → checkUpdates → download → cleanup`；下载与查询统一返回 InstalledModel，并增加单字符串翻译入口。用完整 Demo 流程检验调用是否仍需拆文件路径或手工重建元数据。
3. 完成生命周期协调、结构化异常/状态和准确的删除报告。这些部分涉及行为实现，不能当作纯别名包装。先补验证再切换 Demo。
4. 更新快速开始与 Demo，明确破坏性迁移；检查最终 AAR 不含旧入口类，并编译新 API 的实际消费者。

需要单独记录的行为变化：新 find 缺失返回 null；新 create/shutdown 为 suspend；下载返回安装记录；进度增加明确阶段；新模型查询应区分权限/IO 失败与空库存；新增单字符串入口及空列表不加载模型的短路；新异常类型；新删除报告；新 root 客户端关闭也协调模型网络操作。旧入口已移除，宿主需要重新编译并显式迁移。

此次设计不增加多引擎支持、模型自动中转、动态调整线程数、后台定时更新或并发下载承诺。模型管理仍保留当前进程级写操作串行与 ActiveModels 占用保护；多进程管理相同目录的限制继续明确说明。统一配置来源也不会自动增加 Remote Settings 集合签名验证能力。

## 12. 实施后的验收清单

| 场景 | 验收要求 |
| --- | --- |
| 配置一致性 | DSL 和配置对象生成相同结果；自动策略、手动策略及无效参数验证一致；默认保持单线程 |
| 首次准备 / 已安装新版本 | 缺本地时下载内置；可用本地新版本优先；重复 prepare 无联网；不把远端新版本降为内置版本 |
| 离线和错误 | LocalOnly 无网络请求；缺失与不可读目录可区分；取消不被包装为普通失败 |
| 下载所有路径 | 下载、续传、重试、本地复用的进度类型相同；Ready 发生在校验发布后；校验失败不重试；回调错误不误判为传输错误 |
| 模型身份 | 原目录/manifest 可识别；修复副本有独立安装 ID；来源/版本/哈希及自定义前缀不在转换中丢失 |
| 校验状态 | 四种状态区分；未知清单与损坏安装不进入可用翻译路径；报告快照不绕过首次加载校验 |
| 更新报告 | 无安装、有更新、相同版本重打包、本地较新、无兼容目标、上游撤回和不兼容大版本都有样例；updates.target 非空且精确 |
| 更新期间翻译 | 已提交任务使用原模型；新任务显式使用新安装；更新不会覆盖活动文件或自行删除旧版本 |
| 删除 / 清理 | 占用时拒绝删除；保留集跨空闲卸载仍生效；文件系统部分删除报告准确；释放内存不等于永久禁止再加载 |
| 生命周期 | create 保持进程级互斥；关闭与提交的竞争有测试；取消下载后释放网络资源；native 批次结束后才能重建；shutdown 可重复且取消安全 |
| 翻译 | 单条与列表包装一致；顺序、空输入、Unicode、HTML 实验标记、中转语向验证及既有输出契约保持清楚 |
| 回归与发行 | 新 API 示例可编译；新公开 API 与旧入口移除检查；Release AAR、R8 Demo 与设备验证按项目门禁执行 |

实施记录和运行验证见 [构建与验证](benchmarking.md)。原基线的历史性能结果不作为本次 API 重构的性能结论。
