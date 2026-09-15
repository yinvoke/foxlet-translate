# 分句设计与规则配置

本文描述 0.5.0 的分句实现。0.4.0 发行包使用 ssplit-cpp、PCRE2 与 Moses 前缀数据；历史版本及其基准按原有实现解释。

## 模块边界

`native/sentence/` 提供 C++17 UTF-8 分句器，独立于 Bergamot 与 Android。`Segmenter` 返回原始文本的字节边界或 `string_view`，不改写输入；调用方须保证输入存储在视图使用期间有效。配置完成后可并发读取，规则加载不得与读取并发执行。

Bergamot 的 `TextProcessor` 将句段交给 SentencePiece 分词，再按 `max-length-break = 128` 分段并追加 EOS。词法 shortlist 在推理批次内汇总候选词，与分句缩写规则相互独立。分句变化可能改变批次组成和译文，因此句界质量与翻译质量分别验证。

原生接口拒绝非法 UTF-8。JNI 负责 Java UTF-16 与标准 UTF-8 的转换，拒绝孤立代理项；分句边界始终按原始 UTF-8 字节偏移表示。

## Unicode 与语言规则

属性索引由固定的 Unicode **17.0.0** 数据生成，使用 ASCII 直接索引和非 ASCII 区间查询。数据版本与 **Unicode-3.0 许可证标识**含义不同。生成器、输入指纹和测试数据位于 `tools/sentence-tests/`。

| 层次 | 行为 |
|---|---|
| `Profile::Unicode` | 按 UAX #29 句界规则计算边界，供标准一致性测试使用 |
| `Profile::Translation` | 默认翻译模式；在 Unicode 属性基础上处理缩写、网址、数字、引号及语言定制规则 |
| 内置缩写规则 | 覆盖 `en/de/fr/es/pt/it/ru/tr/zh/ja/ko`；中日韩及俄语还处理混入的拉丁缩写 |
| 其他语言 | 使用通用翻译规则；部分语言存在独立标点定制，例如希腊语问号，不要求具有缩写表 |

重点验证语言为中文、英语、日语、韩语和俄语。`foxlet.capabilities.prefixLanguages` 仅表示内置缩写规则覆盖范围；模型支持语向由内置模型索引与远端描述符确定。

## SDK 配置

SDK 使用 `LocalModel.pair.source` 作为分句语言，转换为原生 `ssplit-language`。直接使用 `ModelFiles.fromDirectory` 时可从 Mozilla 文件名推断两字母标签；`InstalledModel` 和 `ExternalModel` 在翻译前以显式语向覆盖该推断值。

| 配置 | 结果 |
|---|---|
| `nonbreakingPrefixes = true`，无自定义文件 | 使用对应语言的编译期缩写规则 |
| `nonbreakingPrefixes = true`，指定 `ModelFiles.nonbreakingPrefixFile` | 读取应用提供的 UTF-8 文件，通过 JNI 字节数组传递，替换该模型的内置缩写表 |
| `nonbreakingPrefixes = false` | 不读取自定义文件，关闭内置缩写表；仍执行 Unicode 分句与语言标点处理 |

自定义文件最大为 1 MiB。文件在模型加载和驻留期间须保持不可变；更新规则应创建独立文件，并重新加载模型。

```kotlin
val external = ExternalModel(
    pair = LanguagePair("en", "zh-Hans"),
    files = ModelFiles.fromDirectory(modelDirectory).copy(
        expectedSha256 = trustedPublisherHashes,
        nonbreakingPrefixFile = customPrefixes,
    ),
)
val translated = foxlet.translator.translate("Dr. Smith arrived.", external)
```

`trustedPublisherHashes` 为可信来源的模型文件哈希映射，`customPrefixes` 为应用维护的本地文件。文件格式为每行一个不含末尾句点的缩写词项；空行和以 `#` 开头的注释行被忽略，`#NUMERIC_ONLY#` 将词项限定为后接数字时生效。规则区分大小写，重复词项采用最后一项。自定义表替换缩写词项，不关闭通用或语言标点规则。

```text
# 称谓
Dr
Prof
# 后接数字的编号
No #NUMERIC_ONLY#
```

## 原生适配

`TextProcessor` 保留文件路径和内存数据两种构造入口。非空内存数据优先，其次为 `ssplit-prefix-file` 路径；均未提供时由 `ssplit-language` 与 `ssplit-builtin` 决定内置规则。SDK 仅通过字节数组传递自定义表，不设置 YAML 前缀文件路径。

| `ssplit-mode` | 分段方式 |
|---|---|
| `paragraph` | 默认模式；先按换行或段落分隔符划分文本，再在各段内检测句界 |
| `sentence` | 按行读取预分句输入，不再按句末标点拆分 |
| `wrapped_text` | 单个 CR/LF 在句界判断中视为空格，空行保留段落边界；返回视图仍指向原始字节 |

`wrapped_text` 使用等字节长度的临时副本进行边界判断；`paragraph` 不复制源文。以上模式属于原生配置，统一 Kotlin API 不暴露逐请求模式选项。

## 验证与维护

- [构建与测试](benchmarking.md#分句规则)：Unicode 数据一致性、标准句界、语言规则、并发与 ASan/UBSan 检查。
- [分句测试工具](../tools/sentence-tests/README.md)：测试入口、语料清单、旧分句器比较工具的用途。
- [翻译质量评分](../tools/translation-tests/README.md)：固定模型、输入分组和推理参数的质量验证。

生成的 `unicode_data.h` 通过 `generate_unicode.py` 更新。上游集成改动须遵循 [vendor 维护说明](../engine/UPSTREAM.md)；历史原生基准中的显式 Moses 配置保持原协议含义，不替换为当前 SDK 的默认规则。
