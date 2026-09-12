# Foxlet Translate 文档

这里按使用、开发和验证组织文档。[根 README](../README.md) 介绍项目定位、性能、接入方式和限制；[benchmarks/](../benchmarks/README.md) 按版本保存性能证据。

## 使用者

- [快速开始](getting-started.md)：引入 AAR、准备模型、调用 Kotlin API、处理生命周期。
- [支持的语言模型](../registry.json)：Mozilla 模型索引，包含下载地址、SHA-256 和文件大小。
- [第三方许可说明](../NOTICE)：代码、运行库、模型和评测数据的许可范围及随包声明。
- [SMMLA 兼容性清单](smmla-compatibility.md)：已经验证过 i8mm/SMMLA 路径的设备。

## 开发者

- [代码结构与运行时架构](architecture.md)：Android、JNI、Bergamot/Marian 引擎、工具和模型之间的边界。
- [构建与测试](benchmarking.md)：本地构建、单元测试、主机 smoke 和真机验证入口。
- [贡献指南](../CONTRIBUTING.md)：改动范围、上游 vendor 规则、验证要求和文档约定。
- [上游来源与升级](../engine/UPSTREAM.md)：引擎 vendor 来源、裁剪范围和 re-vendor 流程。

## 性能与质量

- [版本基准](../benchmarks/README.md)：各版本、设备的性能结果与回归规则。
- [v0.3.0 测试结果](../benchmarks/v0.3.0/README.md)：小米 10 / 14 性能、翻译质量及 ML Kit 对比。
- [App 评测工具](../tools/app-bench/README.md)：AAR 与 ML Kit 的性能测量和质量评分。
- [版本回归工具](../tools/version-bench/README.md)：按固定协议构建、测量和检查版本差异。

## 阅读约定

历史文档中的“main”指测量时记录的提交，不会随分支移动自动更新，也不代表已发布版本。性能数字必须结合设备、温度、线程数、模型版本和计时口径阅读；未经标注的数字不要直接当作跨设备承诺。
