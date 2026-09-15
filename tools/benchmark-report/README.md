# 基准数据采集与展示

每个版本按固定协议采集当前版本，归档为原始数据；README、版本对比和发布说明从原始数据派生。修改展示不调用设备，不重新翻译或评分，不自动重跑旧版。

1. 原生默认 `android-native-v2` 七场景；App 默认 `android-app-v2` 六场景（ML Kit、单线程、双线程），均保留 200 条输入、每场景三进程三遍和既定指标。四线程不再常规复测。旧协议定义和数据不变。
2. 原生只构建当前版本，运行 `tools/version-bench/run-pair.py` 时省略 `--baseline`；工具名保留兼容，但默认只采集 `--candidate`。App 的 `tools/app-bench/run.py measure` 本来就只测传入的一个 APK。详见[原生工具](../version-bench/README.md)和 [App 工具](../app-bench/README.md)。
3. 保存新的版本/设备/协议原始报告、逐进程结果、指纹和质量评分，不覆盖旧档。`index.json` 为该版本指定原始数据位置，以及需要引用的历史基线。补测必须是单独、明确请求的采集操作。
4. 运行以下纯本地生成命令。它只读 JSON，校验输入和译文哈希，计算中位数、完成数量、波动、质量分数与线程估算，写入版本的 `derived/`；可同步替换两份 README 中带标记的性能部分。

```bash
python3 tools/benchmark-report/generate.py benchmark/data/v0.5.0/index.json --update-readmes
```

发布说明可引用 `derived/summary.json` 和 Markdown 表格，不另做一套采集。派生数据记录实际输入文件 SHA-256；归档清单负责整体文件完整性。估算字段明确为 `estimate`，不计入实测，也不作为回归门槛。

`index.json` 的结构见[实际索引](../../benchmark/data/v0.5.0/index.json)：`app.directory/scores` 指向本版 App 原始数据和评分；`native.report/version` 选择本版原生记录；`baseline` 指向已归档旧版；`initial_comparison` 为首页初版改善概述提供历史同场测量依据。路径均相对索引文件，展示链接从仓库根目录解析。

历史与本版设备、模型、语料、协议或采集条件不同时，应标明差异，不能将不同口径变成精确收益或验收 PASS；缺项和未完成轮次直接展示，不为补齐某一张表自动发起测试。生成成功仅表示数据能加工，不表示性能稳定性验收通过。
