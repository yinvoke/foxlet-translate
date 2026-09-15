# Foxlet Translate 文档

本索引按接入、开发和验证用途组织文档。[根 README](../README.md) 介绍项目定位、性能、接入方式和限制；[benchmark/data/](../benchmark/data/README.md) 按版本保存性能证据。

[English README](../README.en.md) 提供英文项目介绍与发行 AAR 接入步骤。

## 使用者

- [快速开始](getting-started.md)：引入 AAR、准备与更新模型、管理本地版本、调用 Kotlin API、处理生命周期。
- [支持的语言模型](../registry.json)：Mozilla 模型索引，包含下载地址、SHA-256 和文件大小。
- [第三方许可说明](../NOTICE)：代码、运行库、模型和评测数据的许可范围及随包声明。
- [SMMLA 兼容性清单](smmla-compatibility.md)：已经验证过 i8mm/SMMLA 路径的设备。

## 开发者

- [分句设计与规则配置](sentence-segmentation.md)：Unicode 句界、语言规则、自定义缩写及原生适配。
- [代码结构与运行时架构](architecture.md)：Android、JNI、Bergamot/Marian 引擎、工具和模型之间的边界。
- [公开 API 与签名快照](public-api.md)：客户端职责、行为契约、JVM 签名检查及更新方法。
- [构建与测试](benchmarking.md)：本地构建、单元测试、主机 smoke 和真机验证入口。
- [发布指南](releasing.md)：发行构建、CI 门禁、版本管理与发布方法。
- [贡献指南](../CONTRIBUTING.md)：改动范围、上游 vendor 规则、验证要求和文档约定。
- [上游来源与升级](../engine/UPSTREAM.md)：引擎 vendor 来源、裁剪范围和 re-vendor 流程。

## 性能与质量

- [版本基准](../benchmark/data/README.md)：各版本、设备的性能结果与回归规则。
- [v0.5.0 版本结果](../benchmark/data/v0.5.0/README.md)：当前归档的性能、翻译质量、体积与未完成项；历史版本由基准索引保留。
- [基准数据生成](../tools/benchmark-report/README.md)：从版本归档生成 README 与版本对比，不触发设备采集。
- [App 评测工具](../tools/app-bench/README.md)：AAR 与 ML Kit 的性能测量和质量评分。
- [版本回归工具](../tools/version-bench/README.md)：按固定协议构建、测量和检查版本差异。

## 文档存放约定

API 说明、签名快照和其他对外文档统一放在 `docs/`。过程文档、执行计划、发布准备记录、实验结果、质量回测、设计审查稿和审查提示词统一放在 `.docs-private/`，该目录由 Git 忽略。公开文档不得链接到私有文件；可复用的使用和维护方法才放在 `docs/`。

## 阅读约定

历史文档中的“main”指测量时记录的提交，不会随分支移动自动更新，也不代表已发布版本。性能数字必须结合设备、温度、线程数、模型版本和计时口径阅读；单一设备的结果不构成跨设备性能承诺。
