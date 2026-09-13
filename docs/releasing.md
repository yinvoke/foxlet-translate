# 推送与发布准备

## 0.4.0 当前状态

记录日期：2026-09-13。API 统一实现为 `fa351ed`，基于 `e699e1c`，位于 `codex/unified-api`。本轮只整理文档与推送材料，版本保持 `0.4.0`。

| 项目 | 状态 |
| --- | --- |
| 统一 API、消费者和迁移文档 | 已完成；不保留旧公开接口或兼容别名 |
| 本地功能验证 | 153 项 Kotlin、66 项 Python 测试通过，详见 [验证记录](benchmarking.md) |
| AAR、R8 Demo 与接口检查 | 构建及包内容检查通过；58 个公开顶层类型的 JVM 快照匹配，旧入口类已移除 |
| native 回归 | SMMLA、安全测试和 200 句英译中/日语中转输出哈希通过；没有新的性能结论 |
| 新 API 真机验收 | 待执行；旧模型管理版本的设备结果不能复用 |
| 新分支远端 CI | 尚未触发；开发分支推送后还需建立 PR 才会触发普通构建 |
| 版本发布 | v0.4.0 标签已撤回且未重新发布；v0.3.0 Release 已撤下，历史 tag 保留 |

源码可以按下方流程准备分支推送。重新发布仍需完成设备验收和最终提交的 CI。

## 推送前检查

代码变动按 [贡献指南](../CONTRIBUTING.md) 和 [构建与测试](benchmarking.md) 选择检查。API 未改变的文档整理沿用 `fa351ed` 已通过的功能测试，并检查文档相对链接、示例、版本和 Git 差异。

```bash
git fetch --prune origin
git status --short
git log --oneline origin/main..HEAD
git diff --check origin/main...HEAD
git push --dry-run --no-follow-tags origin HEAD:refs/heads/codex/unified-api
```

提交范围应只含源码、测试、文档和构建配置。下载模型、APK/AAR、构建缓存、临时日志和设备结果留在已忽略的目录；历史基准证据继续保留。

准备动作不推送远端。需要执行分支推送时，使用明确的分支目标：

```bash
git push --no-follow-tags -u origin HEAD:refs/heads/codex/unified-api
```

[普通 CI](../.github/workflows/build.yml) 由 `main` 推送或 PR 触发，单独推送 `codex/unified-api` 不触发构建。[发布 CI](../.github/workflows/release.yml) 由 `v*` tag 触发，设备任务通过后会自动发布；开发分支推送时不要附带 tag。

## 从最终提交生成产物

`SOURCE.txt` 记录构建时的 commit 和工作区修改状态。提交文档后也应重新打包交付产物，再检查源码标识和文件哈希；不要将提交前的本地构件标成最终提交构件。

```bash
./gradlew :foxlet:packageWithoutPrefixes :demo:assembleRelease :demo:assembleReleaseAndroidTest :sample:assembleDebug
python3 tools/distribution/check_public_api.py
python3 tools/distribution/check_hardening.py
python3 tools/distribution/verify_artifacts.py --aar foxlet/build/outputs/aar/foxlet-release.aar --no-prefixes foxlet/build/outputs/aar/foxlet-no-prefixes-release.aar --apk demo/build/outputs/apk/release/demo-release.apk
python3 tools/distribution/sync_catalog.py --check
```

公共 API 检查使用 [已审阅的快照](../api/README.md)，不要为让检查通过而自动执行 `--update`。本地产物和检查日志保存于 `build/` 等构建目录；发布附件由对应 tag 的 CI 重新生成。

## 重新发布 0.4.0 前的待办

1. 使用新 API 的 Release Demo 与测试 APK 完成 [Android 设备验收](benchmarking.md#发行构件与-android-门禁)。设备需联网并允许两个安装弹窗；更换不同签名的包可能需要卸载，卸载会清除该 app 的本地数据。
2. 在 PR 上完成普通 CI，确认合并后的最终提交也通过检查。
3. 将 [0.4.0 发布说明](../.github/releases/v0.4.0.md) 的草稿状态更新为实际验证结果；核对 `gradle.properties` 版本、发布说明和拟创建 tag 一致。
4. 准备带 `android-arm64` 标签的隔离 runner，并配置 `FOXLET_ANDROID_SERIAL`。当前已撤销临时设备 runner 配置，重新发布前须恢复。
5. 在确定的最终提交上重新创建 `v0.4.0`，由发布工作流构建并测试同一套 CI APK。只有 `build` 与 `device` 均通过，工作流才会发布 Release。

新的 `device-result.json` 必须对应当次 CI APK 的 SHA-256；本地预检结果或旧版设备记录不能代替这个门禁。版本号仍为 0.4.0，不重写 v0.3.0 历史标签或已有提交。
