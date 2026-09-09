# 文档索引

这里按使用、开发和验证组织文档。[根 README](../README.md) 介绍项目定位、性能、接入方式和限制；[benchmarks/](../benchmarks/README.md) 按版本保存性能证据。

## 使用者

- [快速开始](getting-started.md)：引入 AAR、准备模型、调用 Kotlin API、处理生命周期。
- [支持的语言模型](../registry.json)：Mozilla 模型索引，包含下载地址、SHA-256 和文件大小。
- [SMMLA 兼容性清单](smmla-compatibility.md)：已经验证过 i8mm/SMMLA 路径的设备。

## 开发者

- [代码结构与运行时架构](architecture.md)：Android、JNI、Bergamot/Marian 引擎、工具和模型之间的边界。
- [构建与测试](benchmarking.md)：本地构建、单元测试、主机 smoke 和真机验证入口。
- [贡献指南](../CONTRIBUTING.md)：改动范围、上游 vendor 规则、验证要求和文档约定。
- [上游来源与升级](../engine/UPSTREAM.md)：引擎 vendor 来源、裁剪范围和 re-vendor 流程。

## 性能与验收归档

- [版本基准与回归规则](../benchmarks/README.md)：每个版本的数据入口、指标口径、阈值及新增流程。
- [v0.1.0 → v0.2.0 基准](../benchmarks/v0.2.0/README.md)：历史配对实测与原始数据。
- [与 Google ML Kit 的历史评测](../benchmarks/v0.1.0/mlkit.md)：v0.1.0 阶段的质量分数、真机图表与 app PSS 方法。
- [v0.3.0 性能记录](../benchmarks/v0.3.0/README.md)：版本源码与实测的对应关系、基线与参考数据的边界。
- [2026-09-09 小米 14 复测](../benchmarks/v0.3.0/mi14-2026-09-09/README.md)：新版 COMET、app 原始译文及未完成的性能测试。
- [同日重连场次](../benchmarks/v0.3.0/mi14-2026-09-09/reconnected/README.md)：旧版→新版质量对照、新版 AAR 译文一致性和独立性能复测。
- [同日充电后场次](../benchmarks/v0.3.0/mi14-2026-09-09/charged/README.md)：加入前后前台核验；原生 48 进程通过，app 24 进程完整但六项波动超限，附已执行复核。
- [app 对比与质量评分工具](../tools/app-bench/README.md)：独立进程的 ML Kit/AAR 对照，与原生基准分开记录。
- [版本测量工具](../tools/version-bench/README.md)：从 tag 或未提交代码构建、运行及检查。

## 阅读约定

历史文档中的“main”指测量时记录的提交，不会随分支移动自动更新，也不代表已发布版本。性能数字必须结合设备、温度、线程数、模型版本和计时口径阅读；未经标注的数字不要直接当作跨设备承诺。
