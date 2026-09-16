# 发布指南

## 提交和推送前检查

代码按 [贡献指南](../CONTRIBUTING.md) 和 [构建与测试](benchmarking.md) 选择检查；文档与 patch 整理需检查链接、差异语法、保留改动范围和历史记录的版本标识。

```bash
git status --short
git log --oneline -5
git diff --check
```

审阅必须包括未跟踪的新文件，审阅范围不能仅包含 `git diff`。提交范围应只含源码、测试、文档和构建配置；模型、APK/AAR、构建缓存、临时日志与语料原文留在忽略目录。本地过程记录和验证结果统一保存在 `.docs-private/`，不进入发行附件。

[普通 CI](../.github/workflows/build.yml) 由 `main` 推送或 PR 触发，单独推送开发分支不会触发。提交后建立 PR，并验证合并后的最终提交；分支推送不要附带发行 tag。

## 从最终提交生成产物

`SOURCE.txt` 记录构建时的 commit 和工作区修改状态。提交完成后重新打包正式交付产物，不得将提交前的本地预览标为最终提交构件。

```bash
./gradlew :foxlet:assembleRelease :sdk-example:assembleRelease :sdk-example:assembleReleaseAndroidTest :benchmark-app:assembleDebug
python3 tools/distribution/check_public_api.py
python3 tools/distribution/check_hardening.py
python3 tools/distribution/verify_artifacts.py --aar foxlet/build/outputs/aar/foxlet-release.aar --apk sdk-example/build/outputs/apk/release/sdk-example-release.apk
python3 tools/distribution/sync_catalog.py --check
```

公共 API 检查使用 [已审阅快照](public-api.md)，不为让检查通过而直接执行 `--update`。构件和设备验收必须针对准备发布的源码版本，历史结果不能替代当次验证。

## 发布步骤

1. 确定新的版本号，更新 `gradle.properties`，创建对应 `.github/releases/<tag>.md`。说明该版本的行为变化及实际验证范围，保留历史发布说明。
2. 完成最终提交的普通 CI，并准备带 `android-arm64` 标签的隔离 runner，核对 `FOXLET_ANDROID_SERIAL` 和实际设备/模拟器连接。不要根据本地旧记录假定 runner 仍可用。
3. 使用本次构建的 R8 SDK 示例和测试 APK 完成 [Android 设备验收](benchmarking.md#发行构件与-android-门禁)。结果必须关联当次 APK 的 SHA-256；ARM64 模拟器只验证功能，真机性能另测。
4. 核对版本号、发布说明与新的 tag 一致后，在最终提交上创建该 tag。[发布 CI](../.github/workflows/release.yml) 的 `build` 和 `device` 均通过后，才会发布同一套附件。
5. 发布后核对下载附件、源码标识和许可，再同步中英文 README 的接入说明。

已发布的 tag 与附件保持不可覆盖。

## 发行附件命名

当前工作流发布 `foxlet-<tag>.aar`、`foxlet-sdk-example-<tag>.apk`、许可及 `SOURCE.txt`、`SHA256SUMS` 和设备验收结果。v0.4.0 历史示例附件名为 `foxlet-demo-v0.4.0.apk`，历史发布说明保留该已发布名称；源码模块名为 `sdk-example`。

性能与质量说明从版本归档生成。发布说明须区分完整基线、参考实测、缺项与估算；文档排版或汇总更新不触发设备复测。发行体积收益以最终 AAR 压缩文件大小计算；缩减不足 1% 的实验不纳入正式代码或构建配置。

## Maven 产物验证

项目的发布坐标为 `io.github.yinvoke:foxlet-translate:<version>`，版本来自根目录 `gradle.properties`。仅发布 Android `release` 变体，包含 AAR、POM、Gradle 模块元数据、源码包和 Dokka API 文档包。

先在本地生成 Maven 仓库：

```bash
./gradlew :foxlet:publishAllPublicationsToLocalPreviewRepository
```

输出为 `build/maven-repository/`，无需发布凭据。消费工程添加该目录为 Maven 仓库后，通过上述坐标接入，验证编译、协程依赖解析以及 APK 中的原生库。核对 Maven 中的 AAR 与已通过验收的发行 AAR 的 SHA-256 一致。

## Maven Central 发布

[Maven Central 工作流](../.github/workflows/maven-central.yml) 在 GitHub `release` 工作流成功后运行，也可为已发布且通过设备验收的版本手动触发：

```bash
gh workflow run maven-central.yml -f tag=v0.5.0
```

工作流检出指定 tag，下载对应 GitHub Release，校验 `SHA256SUMS`、`SOURCE.txt` 和 `device-result.json`，确认 AAR 的原生库与已验收 APK 一致。随后复用这个 AAR，仅生成 Maven 元数据、源码包和 Dokka 文档，签名后上传并发布至 Central，最后下载 Central AAR 与 GitHub 附件逐字节比较。不会移动 tag、替换 GitHub 附件或重新采集性能基准。

### 首次配置

发布账号须在 [Central Portal](https://central.sonatype.com/publishing) 完成 `io.github.yinvoke` 命名空间验证，并准备 [Portal 用户 token](https://central.sonatype.org/publish/generate-portal-token/) 和已向公共 keyserver 分发公钥的 [GPG 签名密钥](https://central.sonatype.org/publish/requirements/gpg/)。GitHub 仓库需配置以下 Actions secrets：

| Secret | 内容 |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Portal 用户 token 的 username |
| `MAVEN_CENTRAL_PASSWORD` | Portal 用户 token 的 password |
| `MAVEN_SIGNING_KEY` | ASCII-armored GPG 私钥 |
| `MAVEN_SIGNING_PASSWORD` | 私钥口令；无口令时可省略 |

工作流缺少必要凭据时会停止，不能据此宣称 Maven 已发布。密钥与 token 不写入仓库或日志。Central 不允许覆盖已发布坐标；若发布成功而下载验证暂未通过，先检查 Central 状态与同步进度，再决定是否重试。

Gradle 的 Central 上传目标仅在 `-Pfoxlet.publishToCentral=true` 时开启。`publishToMavenCentral` 只上传待发布部署；本工作流使用 `publishAndReleaseToMavenCentral` 完成正式发布，并等待 `PUBLISHED`。本地预览和普通构建不会触发上传。任务及签名参数见 [Gradle Maven Publish Plugin 文档](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)。

### 本地准备已验收版本

[准备脚本](../tools/distribution/prepare_maven_release.py) 要求独立、干净的 tag checkout。`--assets` 指向已下载的完整 Release 附件目录，并放在 checkout 外或被 Git 忽略的目录。执行顺序如下：

```bash
python3 tools/distribution/prepare_maven_release.py \
  --source /path/to/tag-checkout --assets /path/to/release-assets --tag v0.5.0
/path/to/tag-checkout/gradlew -p /path/to/tag-checkout \
  :foxlet:publishAllPublicationsToLocalPreviewRepository \
  -x :foxlet:bundleReleaseAar -x :foxlet:generateDistributionResources
python3 tools/distribution/prepare_maven_release.py \
  --source /path/to/tag-checkout --assets /path/to/release-assets --tag v0.5.0 \
  --verify-repository
```

这里跳过 AAR 打包及源码标识重写，是为了保留已经验收的发行字节；仅适用于先通过准备脚本的发行版本。正式坐标可从 Central 下载后，再同步中英文 README 和 Release 公告中的 Maven 接入示例。
