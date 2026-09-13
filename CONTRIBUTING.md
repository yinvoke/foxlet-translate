# 贡献指南

## 开始前

先阅读 [文档索引](docs/README.md)、[代码结构](docs/architecture.md) 和 [上游说明](engine/UPSTREAM.md)。确认改动属于 Android 封装、JNI、引擎 vendor、工具还是文档，再选择对应验证路径。

## 改动规则

- Kotlin API 的行为、线程和生命周期必须同步更新 KDoc 与 `docs/getting-started.md`；公开签名变化还需按 [API 快照说明](api/README.md) 审阅和更新。
- JNI 改动要同时检查句柄所有权、异常/错误边界、数组顺序和释放时机。
- `engine/` 内的上游代码不要直接编辑。每个本地逻辑改动使用一个可解释的 commit，并按 `engine/UPSTREAM.md` 刷新 `patches/`。
- 产品构建选项在根 CMake 或对应模块入口维护；版本比较工具显式记录固定 flags，不能把它们当成 AAR 全链路构建的替代。
- 性能结论必须带设备、模型、语料、线程和温度/频率口径；没有复测证据时使用“假设”或“待验证”而不是确定性表述。
- 新增文档先挂到 `docs/README.md`；用户只需要一条命令时，命令应可以从仓库根目录直接复制执行。

## 提交前检查

```bash
./gradlew :foxlet:test :foxlet:assembleRelease :sample:assembleDebug
python3 tools/distribution/check_public_api.py
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DSSPLIT_USE_INTERNAL_PCRE2=ON -DCOMPILE_TESTS=OFF
cmake --build build-host --target smoke
python3 -B -m unittest discover -s tools/version-bench -p 'test_*.py' -v
```

如果改动触及 native kernel、batch、模型加载或线程调度，再运行对应的 SMMLA、smoke、哈希和真机测试，并在提交说明中列出结果。性能结果按 [基准约定](benchmarks/README.md) 存档，回归检查退出码 1/2 均不能视为通过；不要覆盖基线来隐藏退步。文档改动至少检查相对链接、命令路径和示例版本是否与代码一致。

提交后的源码标识、分支推送和 tag 发布步骤见 [推送与发布准备](docs/releasing.md)。只改文档时不重复运行已经通过且不受影响的 native 性能/输出回归；需要交付产物时仍应从最终提交重新打包，使 `SOURCE.txt` 对应实际源码。
