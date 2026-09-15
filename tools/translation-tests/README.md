# 翻译质量评分工具

本目录提供真实模型译文的 chrF++、BLEU 和 COMET 评分工具。句界 F1 使用 `tools/sentence-tests/` 中的工具单独评测。

## 输入准备

评分目录默认为 `build/translation-quality/`，可通过 `--work-dir` 指定其他目录。该目录需包含：

- `data/`：中、英、日、韩、俄的原文和参考译文，分别命名为 `<language>.single.txt` 与 `<language>.paragraph.txt`。
- `inputs.json`：`files` 字段保存各语料文件的 SHA-256。
- `outputs/`：八个方向、两种输入形态、两版程序共 32 份译文，命名为 `<direction>.<shape>.<label>.txt`，其中 `shape` 为 `single` 或 `paragraph`，`label` 为 `legacy` 或 `current`。
- `runs.json`：32 条运行记录，每条包含 `key` 和 `output_sha256`。

`inputs.py` 检查文件指纹、运行完整性及行数。当前评分协议要求每份单行译文 1,012 条、合成段落译文 253 条；不能将任意小样本直接交给此入口。两版必须使用相同模型、输入顺序、提交分组和推理配置。

## 运行

在仓库根目录使用 Python 3.12：

```bash
python3.12 -m venv build/translation-quality/venv
build/translation-quality/venv/bin/pip install -r tools/translation-tests/requirements.txt
build/translation-quality/venv/bin/python tools/translation-tests/score_matrix.py
build/translation-quality/venv/bin/python tools/translation-tests/score_comet.py
```

COMET 入口要求本地缓存中已有脚本指定的模型和 tokenizer；它检查模型指纹及输入长度。词面评分输出为 `scores.json`，语义评分输出为 `comet-scores.json`，均写入评分目录。需要指定其他完整矩阵时，向对应命令追加 `--work-dir /absolute/path/to/matrix`。

COMET 对发生变化的译文成对评分，相同译文的差值计为零，均值按完整样本数计算。分数应结合具体译文、分句边界与批次配置解读，不能仅凭区间包含零便宣称严格非劣效。

原始语料和逐条译文保存在被忽略的构建目录，不进入 AAR。阶段报告、冻结结果、诊断记录与实验结论统一归档到 `.docs-private/`，不作为此目录的公开文档发布。
