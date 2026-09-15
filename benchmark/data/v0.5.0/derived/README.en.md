Compared with the initial Android port of Bergamot, [historical Xiaomi 14 measurements](benchmark/data/v0.3.0/README.md) show roughly **100% faster single-thread translation and 40% lower peak native memory**; current-version measurements are kept in the [version report](benchmark/data/v0.5.0/README.md).

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
