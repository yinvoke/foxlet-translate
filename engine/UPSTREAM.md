# Vendor provenance

Engine sources are vendored (flattened, no submodules) from:

- **Repository**: https://github.com/mozilla/translations
- **Commit**: `df4ab487a117903b53e62cf3ac4305966fbbd2d6` (2026-08-26)
- **Subdirectory**: `inference/` (this is the actively maintained home of the
  Bergamot translator engine; the original `browsermt/bergamot-translator`
  repository is frozen — Mozilla merged `browsermt/marian-dev` into
  `inference/marian-fork` in 2025 and maintains it there for Firefox.)

Submodules of the upstream tree were materialized at their pinned commits and
copied in as plain files: `sentencepiece`, `ruy`, `simd_utils` (under
`marian-fork/src/3rd_party/`) while `ssplit-cpp` and its PCRE2 dependency have now been removed.

## Deliberately NOT vendored

| Path | Reason |
|---|---|
| `src/app/` | upstream CLI; replaced by our own `tools/smoke` |
| `wasm/`, `3rd_party/emsdk` | WASM target, out of scope |
| `scripts/`, `tests/`, `docker/` | upstream dev tooling |
| `marian-fork/src/3rd_party/intgemm` | x86 int8 GEMM; this repo is ARM-only by design (ruy and the local SMMLA/NEON kernels provide the ARM paths) |
| `marian-fork/src/3rd_party/{fbgemm,nccl,onnxjs,simple-websocket-server}` | x86 / CUDA / WASM / server-only; verified unnecessary for native ARM builds |
| `marian-fork/src/tests`, `regression-tests`, `examples` | upstream tests |
| `marian-fork/src/3rd_party/catch.hpp`, `ruy/third_party/googletest` | test-only frameworks; builds are configured with tests off |
| `3rd_party/ssplit-cpp` including PCRE2 and Moses data | replaced by the project-owned `native/sentence/` scanner |

## Local changes

Keep vendor changes traceable to their upstream source, purpose and validation.
When committing an approved logical change, record it separately and refresh
the relevant vendor patch. The [patch index](../patches/README.md) lists retained
historical patches and curated excerpts. It is a migration reference, not a
complete, automatically replayable series for the current tree. First-party
Kotlin, root build files and tools are maintained in project history.

### Native sentence-scanner integration

Current native builds use `native/sentence/`, original MIT code with generated
Unicode 17.0.0 property data under the Unicode-3.0 license. All ssplit-cpp, PCRE2 and Moses data files are removed.
The Bergamot adapter selects the source locale and built-in/custom rules while
retaining the existing JNI byte-array and model configuration interfaces.
The integration spans: `engine/CMakeLists.txt`,
`engine/3rd_party/CMakeLists.txt`, and translator `CMakeLists.txt`, `parser.cpp`,
`text_processor.{h,cpp}`, `annotation.h` and `definitions.h`. The root CMake
adds `native/sentence/` before the engine. These files, the new first-party
scanner and vendor deletions together form the replacement.
Capture the vendor adapter against an explicit baseline and
document its dependency on the first-party scanner. Do not restore removed
vendor data or libraries on a future re-vendor.

## How to re-vendor (upgrade the engine)

Android builds exclude the SentencePiece training library. The Android-only
`FOXLET_SENTENCEPIECE_INFERENCE_ONLY` definition on `sentencepiece_vocab.cpp`
replaces vocabulary creation with an unsupported-operation error; vocabulary
loading, encoding and decoding retain the upstream implementations. Host builds
still link `sentencepiece_train` and retain training support. Preserve both the
source guard and the conditional library dependency when re-vendoring.

1. Sparse-clone `mozilla/translations` at the new commit (`inference/` only);
   `git submodule update --init` for: `marian-fork/src/3rd_party/{sentencepiece,ruy,simd_utils}`.
2. Overwrite `engine/` following the inclusion/exclusion table above
   (see the rsync recipe in the vendor commit message).
3. Update the commit hash at the top of this file. Commit as a single
   `vendor:` commit.
4. Review the patch index and current Git history, then port the still-needed
   changes in dependency order. A patch failing to apply can mean upstream fixed
   it or changed its context; inspect the code before dropping it. Do not blindly
   replay the directory or resurrect obsolete first-party paths.
5. Preserve the first-party sentence-scanner integration described above. Run
   sentence rules, host smoke, Android build/API/artifact checks and the Android
   device gate before releasing. See [validation](../docs/benchmarking.md).
