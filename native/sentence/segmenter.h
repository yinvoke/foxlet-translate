// SPDX-License-Identifier: MIT
#pragma once
#include <cstddef>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace foxlet::sentence {

enum class Mode { Paragraph, Sentence, Wrapped };
// Unicode follows UAX #29; Translation additionally applies scanner and locale rules.
enum class Profile { Unicode, Translation };

// Concurrent reads are safe after configuration; load/setCustomRules require exclusive access.
// Byte offsets and returned views address the unchanged input, whose lifetime belongs to the caller.
class Segmenter {
 public:
  explicit Segmenter(std::string_view language = {}, bool builtins = true);
  void load(std::string_view path);
  // Validates UTF-8 and the 1 MiB limit, then replaces built-in abbreviations.
  void setCustomRules(std::string_view data);
  // UAX #29 boundaries include trailing spaces; Translation adds locale rules.
  std::vector<size_t> boundaries(std::string_view text, Profile profile = Profile::Translation) const;
  std::vector<std::string_view> sentences(std::string_view text, Mode mode = Mode::Paragraph) const;
  static std::string primaryLanguage(std::string_view language);
 private:
  std::string language_;
  bool builtins_;
  std::vector<std::pair<std::string, bool>> custom_;
  size_t maxCustomLength_ = 0;
  bool protect(std::string_view text, size_t period, size_t next) const;
};
} // namespace foxlet::sentence
