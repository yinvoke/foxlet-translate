# 上游改动补丁索引

本目录保留仍与当前 vendor 代码有关的历史补丁，供审查和升级时移植。它们不是构建输入，也不是从任意上游版本执行一次 `git am *.patch` 就能恢复当前工作区的完整补丁序列。当前源码、Git 历史及 [上游说明](../engine/UPSTREAM.md) 共同记录实际实现。

编号沿用原有记录，允许缺号。标明 `excerpt` 的补丁只保留原提交的相关 vendor 差异，`From` 中的提交标识用于追溯来源；不要将其理解为原提交的完整导出。

| 编号 | 保留用途 |
| --- | --- |
| 0001–0004 | zlib、SentencePiece、ExceptionWithCallStack、simd_utils 的编译兼容修正 |
| 0005–0006 | ARM 目标限制及 Bergamot 编译宏 |
| 0009–0010 | ruy DotProd 内核编译及系统能力检测 |
| 0011 | ruy 上下文与权重预打包缓存 |
| 0012 | 请求顺序稳定性 |
| 0016 | 并发加载时模型内存所有权 |
| 0017–0018 | SMMLA 和 NEON 小矩阵路径 |
| 0021–0022 | 模型/缓存释放及 int8 embedding 表 |
| 0023 | AsyncService 的 MemoryBundle 模型创建入口 |
| 0024 | 批量翻译/中转提交及请求编号 |
| 0025 | 原生模型输入验证、生命周期修正及保留的上游许可 |

自有分句器位于 `native/sentence/`，不属于 vendor。Bergamot 适配层及升级步骤见上游说明。维护补丁时使用明确的基线，记录完整改动范围和验证方法；不要生成与工作区重复的临时 diff，也不要恢复已删除的 ssplit、PCRE2 或 Moses 数据。
