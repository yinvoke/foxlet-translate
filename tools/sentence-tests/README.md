# 分句测试工具

本目录验证自有 UTF-8 分句器的标准句界、语言规则和输入安全性。实现与配置见 [分句设计](../../docs/sentence-segmentation.md)。测试可独立于 Android 和 Marian 构建。

## 构建与测试

从仓库根目录执行：

```bash
python3 tools/sentence-tests/generate_unicode.py --check
cmake -S tools/sentence-tests -B build/sentence-tests \
  -DSENTENCE_SANITIZERS=ON
cmake --build build/sentence-tests --parallel 2
UBSAN_OPTIONS=halt_on_error=1 ctest --test-dir build/sentence-tests --output-on-failure
python3 -B -m unittest discover -s tools/sentence-tests -p 'test_*.py' -v
```

`sentence_test` 覆盖 Unicode 17.0.0 标准测试、自定义表、非法 UTF-8、并发读取及中英日韩俄定制规则。`generate_unicode.py --check` 校验属性头与固定数据源的一致性；不带 `--check` 时重新生成属性头。

## 文件职责

| 文件 | 职责 |
|---|---|
| `sentence_test.cpp` | 标准句界、模式、自定义规则、输入安全和并发测试入口 |
| `core_cases.cpp`、`russian_cases.cpp`、`measurement_cases.cpp` | 通用与语言定制回归样例 |
| `sentence_cli.cpp` | 分句命令行入口，以起止字节偏移输出句段范围 |
| `generate_unicode.py`、`data/` | 固定 Unicode 数据、来源指纹与属性头生成 |
| `corpora.json`、`evaluate_corpora.py` | 公开语料清单及句界指标计算 |
| `legacy_cli.cpp`、`compare.cpp` | 旧分句器评估适配与差异比较，不属于默认 CMake 测试目标 |

旧分句器比较依赖单独保存的历史源码及数据，不恢复到产品依赖。实验结果和诊断记录归档至私有过程目录；正式版本证据遵循 [基准归档规范](../../benchmark/data/README.md)，不覆盖已有报告。句界指标不能代替真实模型的翻译质量评分。
