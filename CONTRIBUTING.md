# 贡献指南

## 项目结构

项目职责与维护边界见 [文档索引](docs/README.md)、[代码结构](docs/architecture.md) 和 [上游说明](engine/UPSTREAM.md)。确认改动属于 Android 封装、JNI、引擎 vendor、工具还是文档，再选择对应验证路径。

## 改动规则

- Kotlin API 的行为、线程和生命周期必须同步更新 KDoc 与 `docs/getting-started.md`；公开签名变化还需按 [API 快照说明](docs/public-api.md) 审阅和更新。
- JNI 改动要同时检查句柄所有权、异常/错误边界、数组顺序和释放时机。
- `engine/` 的改动需记录上游来源、原因和验证；提交时按逻辑单独组织 commit，并按 [上游说明](engine/UPSTREAM.md) 维护对应 vendor 补丁。未提交阶段保留可审查的工作区差异，不另存重复的临时 patch；[补丁目录](patches/README.md) 是保留的迁移参考，不是完整自动重放序列。
- 产品构建选项在根 CMake 或对应模块入口维护；版本比较工具显式记录固定 flags，不得将它们当成 AAR 全链路构建的替代。
- 性能结论必须带设备、模型、语料、线程和温度/频率口径；没有复测证据时使用“假设”或“待验证”而不是确定性表述。
- 对外文档（包括 API 说明和签名快照）统一放在 `docs/`，新增说明文档挂到 `docs/README.md`；命令示例应注明执行目录，默认从仓库根目录执行。
- 过程文档、执行计划、发布准备记录、实验与回测结果、设计审查稿和审查提示词统一放在 `.docs-private/`；该目录由 Git 忽略，不纳入公开文档索引，也不从公开文档链接过去。`docs/` 只保留供 GitHub 读者使用的项目文档，不夹带会话进度或本地验收记录。

## 文档与清理规范

- 文档采用书面语，直接说明职责、前提、行为和限制，避免会话进度、临时结论及缺少版本依据的“新”“旧”表述。
- 注释说明公开契约、资源所有权及必要实现约束；接口或算法变更同步修订注释，生成文件通过生成器更新。
- 历史版本报告、协议定义、采集器快照及第三方许可保留原始语义。当前设计文档通过版本标识与历史记录区分。
- 临时日志、可再生成的编译缓存及编辑器残留可清理；模型、正式基准、未归档实验证据和本机配置须按用途保留。禁止用无差别清空忽略文件的方式代替分类清理。
- 常规基准仅采集当前版本，采用原生 v2 七场景和 App v2 六场景；保留 200 条输入、三进程三遍及全部指标。旧版引用归档，补测须有明确请求。
- 发行体积按最终 AAR 压缩大小衡量；收益不足 1% 的实验不保留到正式实现。

## 提交前检查

```bash
./gradlew :foxlet:test :foxlet:assembleRelease :benchmark-app:assembleDebug
python3 tools/distribution/check_public_api.py
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DCOMPILE_TESTS=OFF
cmake --build build-host --target smoke
python3 -B -m unittest discover -s tools/version-bench -p 'test_*.py' -v
```

如果改动触及 native kernel、batch、模型加载或线程调度，再运行对应的 SMMLA、smoke、哈希和真机测试，并在提交说明中列出结果。性能结果按 [基准约定](benchmark/data/README.md) 存档，回归检查退出码 1/2 均不能视为通过；不得覆盖基线来隐藏退步。文档改动至少检查相对链接、命令路径和示例版本是否与代码一致。

提交后的源码标识、分支推送和 tag 发布步骤见 [发布指南](docs/releasing.md)。只改文档时不重复运行已经通过且不受影响的 native 性能/输出回归；需要交付产物时仍应从最终提交重新打包，使 `SOURCE.txt` 对应实际源码。
