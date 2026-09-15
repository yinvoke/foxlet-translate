# 公开 API 与签名快照

0.4.0 以 `Foxlet.create`、`foxlet.models`、`foxlet.translator` 和 `shutdown` 为应用入口。旧的 `BergamotEngine`、`FoxletEngine`、`EngineConfig`、`ModelCatalog`、`ThreadTuning`、`NonbreakingPrefixes` 类及嵌套类型不再出现在最终 AAR 中；0.3.x 用户需要迁移调用代码。

## 接口职责

| 入口 | 职责与约束 |
|---|---|
| `Foxlet.create` | 创建进程内唯一活动客户端；绑定模型根目录、网络与翻译配置，不扫描或下载模型 |
| `foxlet.models` | 查询内置索引与本地安装、准备和下载模型、显式检查更新、删除与清理 |
| `foxlet.translator` | 接受 `InstalledModel` 或 `ExternalModel`，执行单条、批量和中转翻译，查询或释放模型驻留状态 |
| `foxlet.shutdown` | 拒绝新操作，取消 SDK 子任务，等待 native 批次并完成资源销毁；完成后允许创建下一客户端 |

`InstalledModel.id` 标识安装目录，`identity` 标识模型内容。保存安装快照不会保留磁盘文件；磁盘清理保留项通过 `cleanup(keep)` 显式指定。模型加载与驻留期间由 SDK 维护文件占用保护。

批量结果与输入一一对应；空列表不加载模型。中转模型必须满足 `first.pair.target == second.pair.source`。原生批次不可中途打断，输出回归需要固定模型、输入分组、线程、分句和推理配置。具体配置、错误类型与示例见 [接入指南](getting-started.md)。

0.5.0 延续 0.4.0 的统一客户端入口。`nonbreakingPrefixes` 现控制自有分句器的缩写规则，`prefixLanguages` 表示内置缩写规则覆盖范围；二者不表示模型可翻译的语向集合。行为差异见 [分句设计](sentence-segmentation.md)。

## JVM 签名检查

[public-jvm.txt](public-jvm.txt) 记录当前公开 Kotlin 类型及其公开嵌套类型的 JVM 签名。检查基于 Release AAR 中的 `classes.jar`，同时验证新入口存在、旧入口已移除。CI 构建和发布流程都会执行：

```bash
./gradlew :foxlet:assembleRelease
python3 tools/distribution/check_public_api.py
```

工具需要 JDK 17 的 `javap`，可通过 `JAVA_HOME` 指定。默认检查模式不改动快照。主动修改 API 后，先审阅源代码、更新 [接入指南](getting-started.md) 和消费者，再生成快照并审阅差异：

```bash
python3 tools/distribution/check_public_api.py --update
git diff -- docs/public-jvm.txt
python3 tools/distribution/check_public_api.py
```

签名快照覆盖编译产物的公开 JVM 成员，不包含完整的 Kotlin 元数据兼容性分析：它包含编译器生成的默认参数方法，也可能包含在字节码中可见、在 Kotlin 元数据中标记为 internal 的成员。它不赋予这些成员公共使用契约。可空性、默认值和行为契约仍需结合源代码、消费者编译与回归测试审查。使用方法以接入指南为准。

SDK 示例的 instrumentation 测试位于独立 APK，目标 app 的 R8 无法看到这些调用。`generateSdkDeviceTestRules` 从快照中的类型生成 SDK 示例专用规则，保留公开调用边界，同时允许实现优化与混淆，防止默认参数构造器等入口被裁剪。普通宿主使用的 AAR consumer rules 仍只保护 JNI 入口。更新快照后应重新构建并运行设备测试，不能只验证两个 APK 各自编译成功。
