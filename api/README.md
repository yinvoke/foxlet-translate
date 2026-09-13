# 公开 API 快照

0.4.0 开发版以 `Foxlet.create`、`foxlet.models`、`foxlet.translator` 和 `shutdown` 为应用入口。旧的 `BergamotEngine`、`FoxletEngine`、`EngineConfig`、`ModelCatalog`、`ThreadTuning`、`NonbreakingPrefixes` 类及嵌套类型不再出现在最终 AAR 中；本次不提供源码或二进制兼容层。

[public-jvm.txt](public-jvm.txt) 记录当前公开 Kotlin 类型及其公开嵌套类型的 JVM 签名。检查基于 Release AAR 中的 `classes.jar`，同时验证新入口存在、旧入口已移除。CI 构建和发布流程都会执行：

```bash
./gradlew :foxlet:assembleRelease
python3 tools/distribution/check_public_api.py
```

工具需要 JDK 17 的 `javap`，可通过 `JAVA_HOME` 指定。默认检查模式不改动快照。主动修改 API 后，先审阅源代码、更新 [接入指南](../docs/getting-started.md) 和消费者，再生成快照并审阅差异：

```bash
python3 tools/distribution/check_public_api.py --update
git diff -- api/public-jvm.txt
python3 tools/distribution/check_public_api.py
```

这是一份编译产物签名快照，不是完整 Kotlin 元数据兼容性分析：它包含编译器生成的默认参数方法，也可能包含在字节码中可见、在 Kotlin 元数据中标记为 internal 的成员。它不赋予这些成员公共使用契约。可空性、默认值和行为契约仍需结合源代码、消费者编译与回归测试审查。使用方法以接入指南为准。
