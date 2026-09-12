# FLORES-200 评测数据出处

FLORES-200 用于本项目的翻译性能和质量评测，不随 SDK AAR 或 demo APK 发布。
原始数据为 [FLORES-200](https://github.com/facebookresearch/flores/tree/main/flores200)，
作者为 NLLB Team 等，许可为 [CC-BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
（[官方许可说明](https://github.com/facebookresearch/flores#licenses)，[许可全文](../licenses/CC-BY-SA-4.0.txt)）。

`sample/src/main/assets/bench/eng.txt`、`jpn.txt` 分别摘取 devtest 的
`eng_Latn`、`jpn_Jpan` 前 200 句；原文未改写。评测使用对应 `zho_Hans`
参考译文。`benchmarks/` 中部分记录包含这些数据的子集和机器译文，
机器译文为本项目运行引擎生成的改编内容，与上游原文区分；相关数据沿用
CC-BY-SA 4.0。测试脚本和数值统计代码按项目 MIT 许可。

源码仓库、源码归档和自行构建的 benchmark sample 含评测材料；
发行的 AAR 和独立 demo 不含。引用要求不能替代许可、署名和改编说明。

按数据发布方要求，使用这些数据的作品请引用：

```bibtex
@article{nllb2022,
  author = {NLLB Team, Marta R. Costa-jussà, James Cross, Onur Çelebi, Maha Elbayad, Kenneth Heafield, Kevin Heffernan, Elahe Kalbassi, Janice Lam, Daniel Licht, Jean Maillard, Anna Sun, Skyler Wang, Guillaume Wenzek, Al Youngblood, Bapi Akula, Loic Barrault, Gabriel Mejia Gonzalez, Prangthip Hansanti, John Hoffman, Semarley Jarrett, Kaushik Ram Sadagopan, Dirk Rowe, Shannon Spruit, Chau Tran, Pierre Andrews, Necip Fazil Ayan, Shruti Bhosale, Sergey Edunov, Angela Fan, Cynthia Gao, Vedanuj Goswami, Francisco Guzmán, Philipp Koehn, Alexandre Mourachko, Christophe Ropers, Safiyyah Saleem, Holger Schwenk, Jeff Wang},
  title = {No Language Left Behind: Scaling Human-Centered Machine Translation},
  year = {2022}
}
```
