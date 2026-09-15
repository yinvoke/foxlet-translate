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

当前 Release CI 只发布 GitHub 附件，尚未接入 Maven Central。Gradle 的 Central 上传目标仅在 `-Pfoxlet.publishToCentral=true` 时开启；该配置采用上传后手动发布方式，本地预览和普通构建不会触发远端上传。接入 Central 的发布工作流应复用已验收的构件，并在坐标可下载后更新用户接入说明。
