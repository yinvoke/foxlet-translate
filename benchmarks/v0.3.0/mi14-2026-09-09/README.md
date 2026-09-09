# 小米 14 · 2026-09-09 复测

**v0.3.0 对 ML Kit 的质量评分已完成；三轮性能基准未完成。** 手机在 app 测试第二轮断开 ADB，原生测试的先前尝试还遇到频率门槛超时。所有已完成和失败记录都保留，不把部分跑分标为正式基线。

## 本次质量结果

FLORES-200 devtest 前 200 条，中文参考为 `zho_Hans`；`wmt22-comet-da` × 100，越高越好。

| 方向 | Google ML Kit | v0.3.0 / Mozilla 模型 | COMET 分差 |
|---|---:|---:|---:|
| 英→中 | 72.69 | **87.27** | +14.57 |
| 日→英→中 | 68.93 | **86.71** | +17.79 |

分差由未舍入分数计算。以上结论限于这批 200 条语料、被测模型与 SDK，不外推为全部语向、云端 Google 翻译或人工评测结果。

质量使用已成功完成的 10 个独立进程、每进程 3 遍输出。第一轮完整覆盖两个语向下的 ML Kit 和 Bergamot 1/2/4 线程；第二轮还完成了日→中 4/2 线程。Bergamot 在本次已完成调用中，各线程档位、各遍译文完全一致；每个引擎/语向因此只有一组 200 条译文，共四组需要评分。重复输出复用评分，不作为更多独立测试句子。

这验证了**当前版本在该测试集上优于 ML Kit 的 COMET 表现**，尚未验证 v0.2.0 → v0.3.0 的质量变化。旧版原生路径的译文导出程序已构建，但手机断开后尚未运行；不能据此声称优化前后质量完全不变。

原始评分见 [quality-comet-completed.json](quality-comet-completed.json)，包含逐条分数、checkpoint 哈希、参考译文哈希、来源报告哈希、10/24 个进程的覆盖状态及未完成清单。报告明确标记 `performance_validated: false`。归档仅将来源报告 ADB 错误字符串中的设备序列号替换为 `<device-serial>`，另存归档副本哈希；计时、模型哈希、译文和分数均未改变。可运行的复核入口见 [verify.ipynb](verify.ipynb)。

## 采集与中断记录

| 数据 | 完成情况 | 能否作为正式性能基线 |
|---|---|---|
| [原生 v0.2.0 / v0.3.0 配对](native-mi14-attempt1.json) | 第一轮完成 7 个版本/场景进程；下一个进程起跑前等待频率恢复超时 | 否，固定八场景三轮未齐 |
| [ML Kit 模型准备](app-prepare/results.json) | 两个语向准备完成，前后模型文件哈希一致 | 不计时比较 |
| [app 首次尝试](app-measure/results.json) | 完成一个 ML Kit 英→中进程；等待下一项时由测试端中止，以统一前台等待条件 | 否 |
| [app 前台等待协议](app-measure-foreground/results.json) | 完成 10/24 个进程，第二轮下一项启动前 ADB 断开 | 否；可用完整译文做质量评分 |

前台等待协议下，每个进程先打开不创建引擎的空闲基准页面，保持屏幕常亮，再等待 CPU 2/7 上限达到既定门槛。此前观察到回到桌面与打开基准 app 时频率上限不同，因此统一等待环境；没有降低门槛或修改系统性能模式。

首次 app 尝试的 JSON 中 `status: running` 是中止前最后一次落盘状态；该进程已被测试端中止，并非仍在后台测量。原始文件不改写。

本页不提供缺少三轮统计的正式速度、PSS 比例；原始 JSON 中的单次测量可供排查。重新连接后应新开完整场次，避免把重连前后不同温度/调度条件的片段拼成三轮通过结果。

当前完整性检查见 [summary-review.json](app-measure-foreground/summary-review.json)。早期 `summary-invalid.json` 将“缺少结束指纹”笼统写成了“模型变化”，这不是观测到模型实际改变；检查器已区分两者，旧诊断保留供追踪。

## 版本与输入

- 设备：小米 14（`23127PN0CC`），Android 16。设备唯一标识保存为 SHA-256，不保存明文序列号。
- 本地目标：v0.3.0，HEAD `bfc6a08c0aeaa8a679170d517c5c041a83c01834`，含未提交的文档、基准工具及注释整理；没有创建 tag 或推送。引擎/JNI 未为这次测试做性能改动。
- 原生构建：同一 harness、NDK r29、Release、arm64-v8a；完整构建记录见 [v0.2.0 manifest](build-v0.2.0.json) 和 [v0.3.0 manifest](build-v0.3.0.json)。
- app：新增隔离测试入口，原有手动基准界面保留。使用 debug APK，原生库按项目配置使用 Release 优化；APK 与源码文件哈希见 [app 报告](app-measure-foreground/results.json) 和 [源码指纹清单](app-measure-foreground/source-files.json)。
- 模型：Bergamot app 内部模型与原生测试模型的模型/词表/shortlist 哈希逐一匹配。ML Kit 为 SDK `17.0.3`，已下载模型文件的哈希保存在报告中；本次断开后没有取得测量结束时的模型指纹，因此不补写“全程模型未变”。
- 语料：重新取得 [FLORES 官方发布](https://github.com/facebookresearch/flores/blob/main/flores200/README.md#download)的原始数据包，逐行确认英、日 devtest 前 200 条与仓库资产一致；中文参考同源、同切片。FLORES-200 采用 CC BY-SA 4.0。
- 评分：本地 `wmt22-comet-da` checkpoint，revision `2760a223ac957f30acfb18c8aa649b01cf1d75f2`；CPU、batch 8、seed 0、worker 2。依赖版本与精确文件哈希见评分 JSON。

评分中的 `source_file` 保留当时主机的原始路径。对应文件已按相同文件名完整复制到本目录的 `app-measure-foreground/`，复核笔记本从归档文件读取，不依赖临时目录。

## 继续复测

1. 重连小米 14，保持基准 app 在前台，按[固定原生协议](../../../tools/version-bench/README.md)重跑完整配对矩阵。
2. 按[app 协议](../../../tools/app-bench/README.md)重新采集完整三轮，补齐模型前后指纹、温度和波动检查；PSS 与 native RSS 分开报告。
3. 导出旧版/新版原生默认路径的译文，记录二进制及模型指纹，与新版 AAR 译文核对，再比较 COMET。旧 async 输出若变化，应保留重复进程样本。
4. 小米 10 后补独立基线，覆盖不支持 i8mm 的路径；不把小米 14 的结果外推到旧设备。
