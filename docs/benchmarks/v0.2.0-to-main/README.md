# v0.2.0 → 当前 main 整体验收(2026-09-07)

沿用 [v0.1.0 → v0.2.0](../v0.2.0/README.md) 的协议:同一份[测量程序](../../../tools/version-bench/main.cpp)分别编进两版引擎树,同一台小米 10(骁龙 865,无 i8mm,两版都走 ruy),每场景每版本 3 个独立进程、版本顺序 AB / BA / AB、每进程 3 遍。首次翻译含建服务、模型创建、懒加载与第一遍翻译;峰值为首遍后的 VmHWM。原始数据 [results.json](results.json),中位数 [summary.json](summary.json)。

版本:v0.2.0 `db1ccfd`,main `a1ae178`(C/E/F/G 四簇合入后)。板温 29–33°C,A77 / prime 频率上限全程 2,246,400 / 2,745,600 kHz。

## 1. 引擎对引擎(同配置:mini-batch-words 1024,AsyncService,逐条提交)

这是 v0.2.0 页面的四个场景,原样重跑,两版参数完全相同:

| 场景 | 首次翻译 v0.2.0 → main | 比值 | 热态 v0.2.0 → main | 峰值 RSS v0.2.0 → main |
|---|---|---|---|---|
| 英→中 · 1 worker | 4.72 → 4.71 秒 | 0.997 | 4.50 → 4.49 | 185 → 185 MiB |
| 英→中 · 2 workers | 2.81 → 2.83 秒 | 1.006 | 2.59 → 2.56 | 276 → 275 MiB |
| 英→中 · 4 workers | 2.05 → 1.98 秒 | 0.966 | 1.70 → 1.69 | 485 → 487 MiB |
| 日→中 · 1 worker | 10.21 → 10.17 秒 | 0.996 | 9.69 → 9.66 | 290 → 289 MiB |

**结论:引擎在相同配置下速度与内存持平**(差异全在 ±2% 噪声内)。这符合预期:v0.2.0 之后合入的 C / E / F / G 四簇没有改内核,B1 与 E1 两项内核/调度改动经 A/B 撤回;F5 与 G 的改动在这条逐条提交的路径上不改变计算量。v0.2.0 的 4.74 秒 / 182 MiB 与本轮 4.72 秒 / 185 MiB 复现一致。

## 2. 各自 AAR 默认参数(产品口径)

v0.2.0 的库默认走 AsyncService 1 worker、mini-batch-words 1024、无分句前缀表;当前 main 的库默认走 BlockingService、mini-batch-words 512、带源语言前缀表。两版引擎都用同一份测量程序按对方的默认参数各跑一遍(`workers = 0` 走 BlockingService):

| 场景 | v0.2.0 默认 | main 默认 | 比值 | 峰值 RSS |
|---|---|---|---|---|
| 英→中 · 单线程 | 4.72 秒(async w1,mbw 1024) | 4.25 秒(blocking,mbw 512,前缀表) | **0.90** | 185 → 184 MiB |
| 日→中 · 单线程 | 10.21 秒 | 8.66 秒 | **0.85** | 290 → 289 MiB |
| 热态 | 4.50 / 9.69 | 4.05 / 8.28 | 0.90 / 0.85 | |

这 10–15% 来自默认值而不是引擎:把 v0.2.0 的引擎也按 main 的默认参数跑,结果一样(英→中 4.27 秒、日→中 8.72 秒,见 results.json 的 `enzh_b512p` / `pivot_b512p`)。收益来源是 mini-batch-words 1024 → 512(C1)、单线程改走 blocking 路径(E4)与前缀表把 226 句合成 212 句(C5);前缀表还去掉了「博士。 …」这类碎片译文(输出哈希随之变化,两版在同配置下哈希相同)。

同配置的 2 workers(mbw 512 + 前缀表):英→中 2.64 → 2.60 秒(0.99),日→中 5.77 → 5.52 秒(0.96,首次)/ 5.15 → 5.16(热态),峰值 264 / 411 MiB 不变。

## 3. 本轮未在耗时里体现、但已验收的变化

- **库体积**:libbergamot.so 9.39 → 6.98 MB(−25.6%),导出符号 9,780 → 9(G 簇,小米 10 / 14 哈希与异常路径不变)。
- **多线程输出可复现**:AAR 的批量路径(`translateMultiple` / `pivotMultiple`)在任意线程数下与单线程逐字节相同(F5,小米 10 / 14 w2–w6 哈希 = 正典)。本页的 AsyncService 场景走的是逐条提交的旧测量路径,不在这条契约内,所以 4 workers 的哈希两版都仍随时序变化;这是测量程序的路径,不是 AAR 的路径。
- **空闲卸载真的发生**:60 秒无翻译后模型自动释放(C6),之前只在下一次翻译时顺带检查;重载 + 首句约 220 ms。
- **质量**:分句前缀表(C5)修掉缩写切句;缓存冷热契约、纯函数语义写进 KDoc 与 README(F4 / F6);`workspaceMb` 废弃但保留兼容(G2)。
- **本轮没有重算 COMET**;分句变化的 33/200 行已人工审阅(c-params.md §4)。

## 复现

```bash
# 两版引擎树各编一份测量程序(main.cpp 现在支持 workers = 0 走 BlockingService)
git archive v0.2.0 | tar -x -C /tmp/vb/v0.2.0 && cp tools/version-bench/main.cpp /tmp/vb/v0.2.0/tools/smoke/smoke.cpp   # main 同理
cmake -S /tmp/vb/v0.2.0 -B /tmp/vb/v0.2.0/build -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_static \
  -DSSPLIT_USE_INTERNAL_PCRE2=ON -DCOMPILE_TESTS=OFF -DBUILD_ARCH=armv8-a -DBUILD_SMOKE=ON && cmake --build /tmp/vb/v0.2.0/build --target smoke
# 设备上需有 /data/local/tmp/bg/ 下的 config-mbw1024.yml、config-jaen.yml、config-mbw512on.yml(带 ssplit-prefix-file)、config-jaen512.yml、eng200.txt、jpn200.txt
python3 tools/version-bench/run-pair.py mi10 /tmp/vb/results.json /tmp/vb   # /tmp/vb 里放 smoke-v0.2.0 与 smoke-main
```
