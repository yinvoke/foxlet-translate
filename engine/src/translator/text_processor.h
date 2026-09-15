#ifndef SRC_BERGAMOT_TEXT_PROCESSOR_H_
#define SRC_BERGAMOT_TEXT_PROCESSOR_H_

#include <vector>

#include "aligned.h"
#include "annotation.h"
#include "data/types.h"
#include "data/vocab.h"
#include "definitions.h"
#include "vocabs.h"

#if !defined(WASM)
// The WASM implementation uses the Intl.Segmenter within the JavaScript environment.
// Native builds use the project-owned UTF-8 sentence scanner.
#include "sentence/segmenter.h"
#endif // !defined(WASM)

namespace marian {
namespace bergamot {

class TextProcessor {
  /// TextProcessor handles loading the sentencepiece vocabulary and also
  /// contains an instance of sentence-splitter with Unicode and locale rules.
  ///
  /// Used in Service to convert an incoming blob of text to a vector of
  /// sentences (vector of words). In addition, the ByteRanges of the
  /// source-tokens in unnormalized text are provided as string_views.
#if !defined(WASM)
 public:
  /// Configures the native scanner from ssplit-language, ssplit-builtin and
  /// ssplit-mode, then optionally replaces abbreviations with the UTF-8 file.
  /// max-length-break controls token wrapping after sentence segmentation.
  /// @param [in] ssplit_prefix_file: Optional custom table path; empty uses configured built-in rules.
  TextProcessor(Ptr<Options>, const Vocabs &vocabs, const std::string &ssplit_prefix_file);

  /// Uses nonempty UTF-8 rule bytes in preference to ssplit-prefix-file.
  /// Empty memory falls back to the configured file path, then built-in rules.
  /// @param [in] memory: Custom abbreviation table; limited to 1 MiB by the scanner.
  TextProcessor(Ptr<Options>, const Vocabs &vocabs, const AlignedMemory &memory);

 private:
  /// Project-owned UTF-8 sentence scanner.
  foxlet::sentence::Segmenter splitter_;

  /// Mode of splitting, can be line ('\n') based, paragraph based, also supports a wrapped mode.
  foxlet::sentence::Mode splitMode_;

  void parseCommonOptions(Ptr<Options> options);
#elif defined(WASM)
 public:
  /// Constructs a TextProcessor object from the given vocabs.
  TextProcessor(const Vocabs &vocabs): vocabs_(vocabs) {}

  /// Registers the source language that the text processor will use for sentence segmentation.
  void registerSourceLanguage(const std::string& language) {
    sourceLanguage_ = language;
  }

 private:
  /// The source language that the text processor will use for sentence segmentation.
  std::string sourceLanguage_;
#endif // defined(WASM)

 public:
  /// Wrap into sentences of at most maxLengthBreak_ tokens and add to source.
  /// @param [in] blob: Input blob, will be bound to source and annotations on it stored.
  /// @param [out] source: AnnotatedText instance holding input and annotations of sentences and pieces
  /// @param [out] segments: marian::Word equivalents of the sentences processed and stored in AnnotatedText for
  /// consumption of marian translation pipeline.

  void process(std::string &&blob, AnnotatedText &source, Segments &segments) const;

  void processFromAnnotation(AnnotatedText &source, Segments &segments) const;

 private:
  /// Tokenizes an input string, returns Words corresponding. Loads the
  /// corresponding byte-ranges into tokenRanges.
  Segment tokenize(const string_view &input, std::vector<string_view> &tokenRanges) const;

  /// Wrap into sentences of at most maxLengthBreak_ tokens and add to source.
  void wrap(Segment &sentence, std::vector<string_view> &tokenRanges, Segments &segments, AnnotatedText &source) const;

  const Vocabs &vocabs_;   ///< Vocabularies used to tokenize a sentence
  size_t maxLengthBreak_;  ///< Parameter used to wrap sentences to a maximum number of tokens
};

}  // namespace bergamot
}  // namespace marian

#endif  // SRC_BERGAMOT_TEXT_PROCESSOR_H_
