<div align="center">

# Foxlet Translate

[简体中文](README.md) · English

**An offline translation library for Android: private, fast, and optimized for ARM mobile processors**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#-build)
[![Release](https://img.shields.io/badge/release-v0.5.0-blue)](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0)
[![minSdk](https://img.shields.io/badge/minSdk-28-blue)](#-compatibility)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#-compatibility)

[Compatibility](#-compatibility) · [Features](#-features) · [Quick start](#-quick-start) · [Build](#-build) · [Documentation](docs/README.md) · [Roadmap](#-2026-roadmap)

</div>

---

Foxlet Translate is an offline translation library for Android, built on the [Bergamot](https://browser.mt/) engine, Marian inference runtime, and Mozilla models used by [Firefox Translations](https://github.com/mozilla/translations). It provides a Kotlin API and ready-to-use AAR packages.

Current release: **[0.5.0](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0)**. The download and integration steps below use this release; see the archived [benchmark results and limitations](benchmark/data/v0.5.0/README.md).

The project optimizes matrix operations, batching, thread scheduling, and memory management for ARM mobile processors. It selects I8MM/SMMLA or ruy (SDOT/NEON) kernels according to device capabilities. Once models are downloaded, translation runs on the device without sending text to a server. The engine, models, and training workflow are publicly available, allowing developers to build the SDK, pin model versions, and tune inference settings.

## ✨ Features

Use `Foxlet.models` to manage models and `Foxlet.translator` to translate text.

- **Offline inference:** translation makes no network requests. Network access is used for model downloads and explicit update checks.
- **Mozilla models:** uses official Firefox Translations models (MPL-2.0), with publicly available engine code, models, and training workflow.
- **Model management:** list, delete, and clean up local models; resume downloads, retry transfers, and check for updates.
- **Kotlin suspend API:** batch translation, translation through an intermediate language, and experimental HTML-aware translation.
- **Mobile CPU support:** selects I8MM, DotProd, or NEON kernels according to CPU capabilities.
- **Sentence splitting:** a project-owned UTF-8 scanner prioritizing Chinese, English, Japanese, Korean and Russian, including abbreviations, mixed scripts, quotations and Unicode punctuation. Additional rules cover German, French, Spanish, Portuguese, Italian and Turkish. Custom rules are supported; there is no ssplit-cpp or PCRE2 runtime dependency.
- **Memory management:** int8 embeddings, on-demand loading, idle unloading, confirmed release, and integration with `onTrimMemory`.
- **Threads and scheduling:** a synchronous path for the default single-thread configuration; `Threading.Auto` selects a thread count when the client is created based on device capabilities and the requested workload.

## 🚀 Android optimizations

Optimizations cover compute kernels, inference scheduling, model lifecycle, and build output:

- **ARM kernels:** uses SMMLA on I8MM-capable devices and falls back to ruy SDOT or baseline NEON on other ARM64 devices, including different generations such as Snapdragon 865, 8 Gen 1, and 8 Gen 3.
- **Inference:** int8 embedding/weight paths, small attention matrix optimizations, shortlist and batch-shape tuning; `mini-batch-words` defaults to 512.
- **Low-latency path:** single-thread configurations use BlockingService to reduce dispatch and synchronization overhead. Configurations with two or more threads use parallel workers.
- **Device-aware scheduling:** automatic selection from 1/2/4/6 threads based on memory, low-memory status, and fast-core count, with an explicit override available.
- **Model lifecycle:** lazy loading, idle unloading, release from `onTrimMemory`, and memory tradeoffs for two-model pivot translation.
- **Build and compatibility:** link-time pruning and hidden non-JNI symbols reduce native library size; supports 16 KB pages and ARM64 devices with or without I8MM.

## Documentation

The detailed guides below are currently in Chinese. This README includes the download and integration steps in English.

| Purpose | Document |
|---|---|
| AAR integration, model downloads, and Kotlin API | [Getting started](docs/getting-started.md) |
| Android, JNI, engine, and tool responsibilities | [Architecture](docs/architecture.md) |
| Build environment, testing, and device benchmarks | [Building and benchmarking](docs/benchmarking.md) |
| Release builds and publication workflow | [Releasing](docs/releasing.md) |
| Performance reports, raw data, and regression checks | [Benchmarks](benchmark/data/README.md) |

## 📱 Compatibility

| Item | Minimum requirement / compatibility baseline | Recommended |
|---|---|---|
| Android | Android 9 (API 28), minSdk >= 28 | Android 15 (API 35) or later |
| CPU and ABI | 64-bit AArch64 with `arm64-v8a` app support | An I8MM-capable CPU, such as Snapdragon 8 Gen 1 or 8 Gen 3 |
| Total device RAM | >= 4 GB | >= 8 GB; >= 12 GB recommended for multithreaded use |

Release packages contain native libraries for [`arm64-v8a`](https://developer.android.com/ndk/guides/abis#arm64-v8a) only. They target Armv8.x / Armv9 devices with a 64-bit Android application environment. The inference backend does not depend on Google Play services.

### Instruction sets and automatic fallback

| Instruction set / extension | Example instructions | Use | Requirement |
|---|---|---|---|
| AArch64 + NEON / Advanced SIMD | `SMULL`, `SMLAL`, `FMLA` | Basic integer and floating-point operations; baseline ruy kernels | Required compatibility baseline |
| DotProd | `SDOT` | Accelerated int8 dot products in ruy | Optional; detected at runtime |
| I8MM | `SMMLA` | Dedicated int8 matrix multiplication | Optional; detected at runtime |
| SVE / SVE2 / SME / SME2 | — | No dedicated acceleration path yet | Not implemented |

Int8 matrix multiplication chooses kernels in this order: I8MM/SMMLA, ruy/SDOT, then ruy/NEON. See [I8MM dispatch](engine/marian-fork/src/tensors/cpu/ruy_interface.h), [DotProd detection](engine/marian-fork/src/3rd_party/ruy/ruy/cpuinfo.cc), and the [ruy fallback](engine/marian-fork/src/3rd_party/ruy/ruy/ctx.cc).

Armv8.0-A / API 28 is the compatibility target. Android 9 and older devices without DotProd are not covered by the current device measurements. See the [compatibility matrix](docs/smmla-compatibility.md) for tested devices, operating systems, and instruction paths.

### Memory and storage budgets

The PSS figures below are derived from the [existing 0.5.0 measurements](benchmark/data/v0.5.0/README.md), using 200 inputs. Values are the larger of the first/warm peak medians, rounded up; incomplete repeats and sampling cannot establish an upper bound. Validate budgets in the host app.

Runtime memory includes resident models, inference workspaces, caches, tokenization, and output buffers. Peak usage depends on thread count, batch length, and the number of resident models. Total device RAM is hardware capacity; PSS measures process memory; the Java heap limit applies only to the managed heap. Android may reclaim processes under memory pressure; see [Android memory management](https://developer.android.com/topic/performance/memory-management).

<!-- BENCHMARK:MEMORY:BEGIN -->

| Scenario | Measured peak PSS reference | Suggested translation memory budget |
|---|---|---|
| English → Chinese / 1 | ≈ 257 MiB | ≈ 512 MiB |
| Japanese → English → Chinese / 1 | ≈ 356 MiB | ≈ 1 GiB |
| Japanese → English → Chinese / 2 | ≈ 548 MiB | ≈ 2 GiB |

<!-- BENCHMARK:MEMORY:END -->

## 📈 Performance

<!-- BENCHMARK:BEGIN -->

In the [historical Xiaomi 14 native comparison](benchmark/data/v0.3.0/README.md), **v0.1.0 → v0.3.0**, both built in Release mode, showed roughly **100% higher single-thread first-pass throughput** and **40% lower peak RSS**, including changes to default parameters. This descriptive comparison skipped the frequency start gate; it does not measure the full improvement from the early Android app to the current SDK or the advantage over ML Kit. Current-version app measurements are reported below and in the [version report](benchmark/data/v0.5.0/README.md).

### Comparison with Google ML Kit on-device translation

| Item | Configuration |
|---|---|
| Version | Foxlet 0.5.0 |
| Device | Xiaomi 14 |
| Processor | Snapdragon 8 Gen 3 |
| Operating system | Android 16 |
| Benchmark protocol | `android-app-v2` |
| Test configurations | Google ML Kit; Foxlet with 1 / 2 threads |
| Dataset | First 200 FLORES-200 devtest inputs per direction |
| Directions | English → Chinese; Japanese → Chinese |
| Build configuration | Debug benchmark APK; Release-optimized native library |

Existing measurements: 1–2 completed processes per scenario, three passes each. The planned three-process run was not completed; these are reference values, not a passed regression baseline. [Raw data and method](benchmark/data/v0.5.0/README.md).

| Direction / engine / threads | First inputs/s | Warm inputs/s | First PSS MiB | Warm PSS MiB | COMET × 100 |
|---|---:|---:|---:|---:|---:|
| English → Chinese / ML Kit | 16.35 | 14.60 | 181.00 | 191.00 | 72.69 |
| English → Chinese / Foxlet / 1 | 88.24 | 101.41 | 256.90 | 227.30 | 87.27 |
| English → Chinese / Foxlet / 2 | 131.50 | 162.83 | 371.60 | 320.70 | 87.27 |
| Japanese → Chinese / ML Kit | 7.60 | 6.92 | 233.30 | 239.40 | 68.93 |
| Japanese → Chinese / Foxlet / 1 | 41.83 | 36.42† | 355.15 | 331.85 | 86.76 |
| Japanese → Chinese / Foxlet / 2 | 63.63 | 65.52 | 547.35 | 501.70 | 86.76 |

First-pass timing includes engine/model creation. **PSS (Proportional Set Size)** is the physical memory private to a process plus its proportional share of shared memory. **First/warm PSS** are medians of per-process sampled peaks during the first pass / subsequent two passes, including JVM, UI and native memory, sampled every 250 ms. COMET is a quality score, not an accuracy percentage. † Range / median exceeds 10%; incomplete repeats cannot establish stability.

**Two threads are usually recommended**, balancing speed and memory; use one when memory is tight. Further threads generally bring diminishing returns and more memory/scheduling overhead.

| Direction | 4 threads, estimated | 6 threads, estimated |
|---|---:|---:|
| English → Chinese | ≈ 234 inputs/s | ≈ 273 inputs/s |
| Japanese → Chinese | ≈ 109 inputs/s | ≈ 140 inputs/s |

These are **rough estimates, not measurements**, extrapolated from 1/2-thread warm times. Additional bandwidth, scheduling and thermal costs are excluded; see the [source calculations](benchmark/data/v0.5.0/derived/summary.json). They are not performance guarantees.

<!-- BENCHMARK:END -->

## 🌍 Supported language models

The bundled index contains **106 directional models covering 54 languages**, with English as the intermediate language. There are 52 bidirectional links with English, plus English → Azerbaijani and Albanian → English. Simplified and Traditional Chinese are separate language tags for the same language.

`translatePivot` uses a source → English model followed by an English → target model, for example Japanese → Chinese. See [registry.json](registry.json) for model versions and download details. The `from` / `to` codes match `LanguagePair`. Mozilla updates the upstream models with Firefox.

<details>
<summary>Full model list (106 directions)</summary>

| Source language | Target language | from | to | Version | Size |
|---|---|---|---|---|---|
| English | Afrikaans | `en` | `af` | 2.0 | 36 MB |
| Afrikaans | English | `af` | `en` | 2.0 | 36 MB |
| English | Arabic | `en` | `ar` | 2.2 | 36 MB |
| Arabic | English | `ar` | `en` | 2.2 | 37 MB |
| English | Azerbaijani | `en` | `az` | 1.0 | 21 MB |
| English | Bulgarian | `en` | `bg` | 2.0 | 36 MB |
| Bulgarian | English | `bg` | `en` | 2.0 | 37 MB |
| English | Bengali | `en` | `bn` | 1.0 | 21 MB |
| Bengali | English | `bn` | `en` | 1.0 | 23 MB |
| English | Bosnian | `en` | `bs` | 2.0 | 36 MB |
| Bosnian | English | `bs` | `en` | 2.0 | 38 MB |
| English | Catalan | `en` | `ca` | 2.0 | 37 MB |
| Catalan | English | `ca` | `en` | 2.0 | 37 MB |
| English | Czech | `en` | `cs` | 2.0 | 36 MB |
| Czech | English | `cs` | `en` | 2.0 | 37 MB |
| English | Danish | `en` | `da` | 1.0 | 22 MB |
| Danish | English | `da` | `en` | 1.0 | 22 MB |
| English | German | `en` | `de` | 2.1 | 37 MB |
| German | English | `de` | `en` | 2.0 | 37 MB |
| English | Greek | `en` | `el` | 1.0 | 21 MB |
| Greek | English | `el` | `en` | 1.1 | 22 MB |
| English | Spanish | `en` | `es` | 2.1 | 37 MB |
| Spanish | English | `es` | `en` | 2.0 | 37 MB |
| English | Estonian | `en` | `et` | 2.0 | 36 MB |
| Estonian | English | `et` | `en` | 2.0 | 37 MB |
| English | Basque | `en` | `eu` | 2.0 | 36 MB |
| Basque | English | `eu` | `en` | 2.1 | 36 MB |
| English | Persian | `en` | `fa` | 1.1 | 22 MB |
| Persian | English | `fa` | `en` | 1.0 | 22 MB |
| English | Finnish | `en` | `fi` | 2.0 | 36 MB |
| Finnish | English | `fi` | `en` | 2.0 | 38 MB |
| English | French | `en` | `fr` | 2.0 | 37 MB |
| French | English | `fr` | `en` | 2.0 | 37 MB |
| English | Galician | `en` | `gl` | 2.1 | 35 MB |
| Galician | English | `gl` | `en` | 2.1 | 37 MB |
| English | Gujarati | `en` | `gu` | 1.0 | 21 MB |
| Gujarati | English | `gu` | `en` | 1.0 | 22 MB |
| English | Hebrew | `en` | `he` | 1.0 | 21 MB |
| Hebrew | English | `he` | `en` | 1.0 | 23 MB |
| English | Hindi | `en` | `hi` | 1.0 | 22 MB |
| Hindi | English | `hi` | `en` | 1.0 | 23 MB |
| English | Croatian | `en` | `hr` | 1.0 | 21 MB |
| Croatian | English | `hr` | `en` | 1.0 | 22 MB |
| English | Hungarian | `en` | `hu` | 2.1 | 36 MB |
| Hungarian | English | `hu` | `en` | 2.1 | 37 MB |
| English | Indonesian | `en` | `id` | 1.0 | 21 MB |
| Indonesian | English | `id` | `en` | 1.0 | 22 MB |
| English | Icelandic | `en` | `is` | 2.0 | 36 MB |
| Icelandic | English | `is` | `en` | 2.0 | 36 MB |
| English | Italian | `en` | `it` | 2.1 | 37 MB |
| Italian | English | `it` | `en` | 2.0 | 37 MB |
| English | Japanese | `en` | `ja` | 2.3 | 50 MB |
| Japanese | English | `ja` | `en` | 2.1 | 55 MB |
| English | Kannada | `en` | `kn` | 1.0 | 21 MB |
| Kannada | English | `kn` | `en` | 1.0 | 23 MB |
| English | Korean | `en` | `ko` | 2.1 | 52 MB |
| Korean | English | `ko` | `en` | 2.1 | 54 MB |
| English | Lithuanian | `en` | `lt` | 2.1 | 36 MB |
| Lithuanian | English | `lt` | `en` | 1.0 | 23 MB |
| English | Latvian | `en` | `lv` | 2.1 | 36 MB |
| Latvian | English | `lv` | `en` | 1.0 | 22 MB |
| English | Malayalam | `en` | `ml` | 1.0 | 21 MB |
| Malayalam | English | `ml` | `en` | 1.0 | 23 MB |
| English | Marathi | `en` | `mr` | 2.0 | 35 MB |
| Marathi | English | `mr` | `en` | 2.0 | 37 MB |
| English | Malay | `en` | `ms` | 1.0 | 22 MB |
| Malay | English | `ms` | `en` | 1.0 | 22 MB |
| English | Norwegian Bokmål | `en` | `nb` | 2.0 | 22 MB |
| Norwegian Bokmål | English | `nb` | `en` | 2.0 | 22 MB |
| English | Dutch | `en` | `nl` | 2.1 | 36 MB |
| Dutch | English | `nl` | `en` | 2.0 | 37 MB |
| English | Polish | `en` | `pl` | 2.1 | 36 MB |
| Polish | English | `pl` | `en` | 2.0 | 37 MB |
| English | Portuguese | `en` | `pt` | 2.1 | 36 MB |
| Portuguese | English | `pt` | `en` | 2.0 | 37 MB |
| English | Romanian | `en` | `ro` | 1.0 | 22 MB |
| Romanian | English | `ro` | `en` | 1.0 | 23 MB |
| English | Russian | `en` | `ru` | 2.1 | 35 MB |
| Russian | English | `ru` | `en` | 2.1 | 37 MB |
| English | Slovak | `en` | `sk` | 2.1 | 36 MB |
| Slovak | English | `sk` | `en` | 1.0 | 23 MB |
| English | Slovenian | `en` | `sl` | 2.1 | 36 MB |
| Slovenian | English | `sl` | `en` | 2.1 | 37 MB |
| Albanian | English | `sq` | `en` | 1.0 | 22 MB |
| English | Serbian | `en` | `sr` | 2.0 | 35 MB |
| Serbian | English | `sr` | `en` | 1.0 | 23 MB |
| English | Swedish | `en` | `sv` | 1.0 | 22 MB |
| Swedish | English | `sv` | `en` | 1.0 | 23 MB |
| English | Tamil | `en` | `ta` | 2.0 | 35 MB |
| Tamil | English | `ta` | `en` | 2.0 | 38 MB |
| English | Telugu | `en` | `te` | 1.0 | 21 MB |
| Telugu | English | `te` | `en` | 1.0 | 23 MB |
| English | Thai | `en` | `th` | 2.0 | 36 MB |
| Thai | English | `th` | `en` | 2.0 | 37 MB |
| English | Turkish | `en` | `tr` | 1.0 | 21 MB |
| Turkish | English | `tr` | `en` | 1.0 | 23 MB |
| English | Ukrainian | `en` | `uk` | 2.2 | 36 MB |
| Ukrainian | English | `uk` | `en` | 1.1 | 22 MB |
| English | Urdu | `en` | `ur` | 2.0 | 35 MB |
| Urdu | English | `ur` | `en` | 2.1 | 36 MB |
| English | Vietnamese | `en` | `vi` | 2.0 | 37 MB |
| Vietnamese | English | `vi` | `en` | 1.0 | 22 MB |
| English | Chinese (Simplified) | `en` | `zh-Hans` | 2.2 | 52 MB |
| Chinese (Simplified) | English | `zh-Hans` | `en` | 2.1 | 55 MB |
| English | Chinese (Traditional) | `en` | `zh-Hant` | 2.0 | 49 MB |
| Chinese (Traditional) | English | `zh-Hant` | `en` | 2.0 | 52 MB |

</details>

## 📁 Repository layout

```text
foxlet/        Android library and Kotlin API
native/        Project-owned C++: jni/ bridge, sentence/ UTF-8 scanner and locale rules
engine/        Bergamot / Marian engine and third-party components
patches/       Retained upstream patches and migration index
sdk-example/   Minimal AAR consumer and offline translation demo
benchmark/     app/ internal benchmark app with evaluation data, data/ versioned benchmarks
tools/         Build, test, and performance tools
docs/          Integration, architecture, and development guides
registry.json  Mozilla model download index
```

Tools and the benchmark app are not included in the AAR. See [architecture](docs/architecture.md) for module responsibilities and upstream maintenance.

## 🚀 Quick start

**0.5.0** retains the unified `Foxlet` client API introduced in 0.4.0. Upgrading from 0.3.x requires updating call sites and recompiling. See the [integration guide](docs/getting-started.md).

### 1. Download a release package

Download an AAR from [v0.5.0 Release](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0). AAR integration requires neither a repository checkout nor the NDK / CMake:

| File | Purpose |
|---|---|
| [foxlet-v0.5.0.aar](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-v0.5.0.aar) | v0.5.0 SDK |
| [foxlet-sdk-example-v0.5.0.apk](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-sdk-example-v0.5.0.apk) | Installable demo of model management and offline translation; uses a development signing key |
| [SHA256SUMS](https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/SHA256SUMS) | SHA-256 checksums for release files |

The 0.5.0 AAR is about **2.8 MB**. Neither the AAR nor the SDK example includes translation models. Preparing a language pair for the first time requires a network download: English → Simplified Chinese is about **52 MB**, for example. Translation works offline once the model is ready. See the [model index](registry.json) for download sizes.

> 0.5.0 provides a single AAR with project-maintained multilingual sentence rules and Android SentencePiece training code removed. The archived pre-release AAR measured 2,809,980 bytes, 25.75% smaller than v0.4.0; use the release assets for final sizes and checksums. See the [sentence segmentation design](docs/sentence-segmentation.md).

### 2. Add dependencies and permissions

Save the AAR as `app/libs/foxlet-v0.5.0.aar` in the host app. Add the following to `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(files("libs/foxlet-v0.5.0.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

A local AAR carries no Maven dependency metadata, so the coroutine dependency must be declared explicitly. Also set the host app's Kotlin JVM target to 17.

Maven Central publishing is configured but **has not been released to Central**. Use the AAR integration above for now.

Add the network permission for model downloads and update checks to the host `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 3. Prepare a model and translate

Keep one `Foxlet` client for the application. Its model directory and translation configuration are bound at creation. The default directory is `noBackupFilesDir/translation-models`:

```kotlin
import io.github.yinvoker.foxlet.*

val foxlet = Foxlet.create(context) {
    translation { threading = Threading.Fixed(2) }
}
try {
    val pair = LanguagePair("en", "zh-Hans")
    val model = foxlet.models.prepare(pair)
    val text = foxlet.translator.translate("Hello, world!", model)
    val batch = foxlet.translator.translate(listOf("Good morning.", "Thank you."), model)
    val updates = foxlet.models.checkUpdates(setOf(pair))
    val cleanup = foxlet.models.cleanup(keep = setOf(model.id))
} finally {
    foxlet.shutdown()
}
```

Run this example in a coroutine or a `suspend` function, including `Foxlet.create` and `shutdown`. The example closes the client when it finishes; in an application, share one client between pages and close it when it is no longer needed.

`prepare` uses the latest usable local installation, or downloads the bundled model version if none is available. Use `PreparePolicy.LocalOnly` to restrict preparation to installed models. Download, prepare, and lookup operations return `InstalledModel`, which can be used for translation or deletion. `checkUpdates` reads the remote index; `download(update.target)` installs a specific update.

Threading defaults to `Threading.Fixed(1)`. You can select a fixed count or `Threading.Auto(Workload.SINGLE/BATCH/PIVOT)`; `translator.threadingInfo` exposes the actual configuration. Models unload after 60 idle seconds by default. Retention options are `ModelRetention.Idle`, `AfterRequest`, and `UntilShutdown`. `unloadModels` releases runtime memory; `models.delete/cleanup` manages files on disk.

Model files stay immutable during use. Updates install into separate directories, and subsequent translations explicitly select the new model. Only one client may be active per process. `shutdown` waits for an in-flight native batch to finish and releases resources; a new client can be created after shutdown completes.

See the [integration guide](docs/getting-started.md) for configuration, download progress, errors, pivot translation, and external models.

## 🔨 Build

The Android AAR can be cross-compiled with the NDK on ARM or x86_64 hosts. The host smoke CLI requires ARM (Apple Silicon / ARM Linux). The repository uses JDK 17, Android SDK 36 (`compileSdk = 36`), NDK 29.0.13113456, CMake 3.31.6, Gradle Wrapper 9.7.1, AGP 9.3.1, and Kotlin 2.4.10.

Integrating a release AAR does not require the NDK / CMake. Declare the coroutine dependency explicitly (`kotlinx-coroutines-android:1.10.2`). The minimum runtime version is determined by `minSdk`; the host app selects its own `targetSdk`. See the [Android version and API-level reference](https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels).

```bash
./gradlew :foxlet:assembleRelease    # SDK AAR
./gradlew :sdk-example:assembleRelease             # Demo consuming the AAR, with R8
./gradlew :benchmark-app:assembleDebug             # Benchmark app with evaluation data
cmake -B build-host -DCMAKE_BUILD_TYPE=Release \
  -DCOMPILE_TESTS=OFF && cmake --build build-host --target smoke   # Host CLI
```

Device benchmark results are saved as JSON in the app's files directory, including memory and CPU measurements for each stage:

```bash
adb shell am start -n io.github.yinvoker.foxlet.bench/.MainActivity \
  --ez autorun true --ei threads 2
```

The source-built AAR is `foxlet/build/outputs/aar/foxlet-release.aar`; its name differs from the versioned release download. The demo APK is at `sdk-example/build/outputs/apk/release/sdk-example-release.apk`.

See [building and benchmarking](docs/benchmarking.md) for environment setup, SMMLA tests, and device benchmarks.

## ⚠️ Scope and limitations

- See [compatibility](#-compatibility) for runtime targets, optional instructions, recommended hardware, and tested coverage.
- Native libraries support 4 KB / 16 KB pages. The host APK packaging and its other native dependencies must also meet the [16 KB page-size requirements](https://developer.android.com/guide/practices/page-sizes).

## 🗺️ 2026 Roadmap

- [x] Base engine and NDK port
- [x] CI builds and tests
- [x] AAR releases
- [x] GEMM kernel optimizations
- [x] Small attention matrix optimizations
- [x] Memory usage and model release improvements
- [x] Multithreading and scheduling improvements
- [x] Parameter and batching tuning
- [x] Model update detection
- [x] Local model management
- [x] Resumable downloads and retries
- [x] Unified model management and translation API
- [x] Build optimizations and 16 KB page support
- [x] Reduce release package size (0.5.0)
- [ ] Improve HTML translation support
- [ ] Add SME2 acceleration

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for issue reporting, pull requests, development conventions, and upstream patch maintenance.

## 📦 Libraries, third-party components, and models

Original project code is licensed under [MIT](LICENSE). Modifications to imported code remain subject to their upstream licenses. See [NOTICE](NOTICE) and the individual vendor license files for attribution and scope. AAR packages include license texts and corresponding source-access information.

### Engine and runtime

- [Bergamot translator](https://github.com/mozilla/translations): the Mozilla Firefox local translation engine, MPL-2.0. This project adapts its `inference/` code for Android/ARM.
- [Marian NMT](https://github.com/marian-nmt/marian): C++ neural machine translation runtime, MIT; this repository uses the fork maintained with Bergamot.
- [ruy](https://github.com/google/ruy): ARM CPU matrix multiplication backend, Apache-2.0, providing SDOT and baseline NEON kernels.
- [SentencePiece](https://github.com/google/sentencepiece): tokenizer and vocabulary processing, Apache-2.0.
- [Unicode](https://www.unicode.org/license.txt): sentence property data under Unicode-3.0; original sentence-scanner code under MIT.
- cpuinfo, zlib, pathie-cpp, the faiss subset, and `half_float`: distributed under the BSD/MIT/Apache or other licenses included in their respective directories.

### Android dependencies and benchmarking tools

- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines): coroutine scheduling for the Android suspend API, Apache-2.0.
- [Google ML Kit Translate](https://developers.google.com/ml-kit/language/translation): used for comparison in the `benchmark/app/` benchmark app under its own terms; not an SDK runtime dependency.
- [FLORES-200](https://github.com/facebookresearch/flores): evaluation data only, CC-BY-SA 4.0, excluded from the SDK AAR and demo APK. See [benchmark attribution](benchmark/data/CITATION.md) for provenance and the NLLB 2022 citation.

### Translation models

Models come from Mozilla Firefox Remote Settings. File metadata and SHA-256 hashes are recorded in the [model index](registry.json). Models in the current bundled index are licensed under MPL-2.0. Applications may download models through the SDK or provide model files. See [getting started](docs/getting-started.md) for model management, integrity checks, and update configuration.

## 📄 License

Original repository code is **MIT**. Modifications to third-party files retain their original licenses. Components bundled in `engine/` are distributed under their own licenses, including MPL-2.0 for the Bergamot translation layer. See [NOTICE](NOTICE) and vendor LICENSE files. Official Mozilla models are MPL-2.0; evaluation datasets such as FLORES-200 retain their separate licenses.

---

<div align="center">

[![Star History Chart](https://api.star-history.com/svg?repos=yinvoke/foxlet-translate&type=Date)](https://www.star-history.com/#yinvoke/foxlet-translate&Date)

</div>
