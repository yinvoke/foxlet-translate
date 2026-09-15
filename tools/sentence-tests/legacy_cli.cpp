// SPDX-License-Identifier: MIT
// Optional evaluation adapter; old ssplit sources/data stay outside the product.
// Uses the sentence_cli protocol. FOXLET_LEGACY_PREFIX_DIR contains the old
// nonbreaking_prefix.{zh,en,ru}; Japanese/Korean had no bundled prefix table.
#include "ssplit/ssplit.h"
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>

int hex(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  throw std::invalid_argument("Invalid hex input");
}

int main(int argc, char**) {
  try {
    const char* directory = std::getenv("FOXLET_LEGACY_PREFIX_DIR");
    if (argc != 1 || !directory) throw std::invalid_argument("Set FOXLET_LEGACY_PREFIX_DIR");
    std::map<std::string, ug::ssplit::SentenceSplitter> splitters;
    for (const auto* language : {"zh", "en", "ja", "ko", "ru"}) {
      auto& splitter = splitters[language];
      if (std::string(language) == "ja" || std::string(language) == "ko") continue;
      const std::string path = std::string(directory) + "/nonbreaking_prefix." + language;
      if (!std::ifstream(path)) throw std::runtime_error("Missing baseline prefix file: " + path);
      splitter.load(path);
    }
    for (std::string line; std::getline(std::cin, line);) {
      const auto tab = line.find('\t');
      if (tab == std::string::npos || (line.size() - tab - 1) % 2) return 2;
      const auto found = splitters.find(line.substr(0, tab));
      if (found == splitters.end()) throw std::invalid_argument("Unsupported evaluation language");
      std::string input;
      for (size_t i = tab + 1; i < line.size(); i += 2) input += char(hex(line[i]) * 16 + hex(line[i+1]));
      ug::ssplit::SentenceStream stream(input, found->second,
          ug::ssplit::SentenceStream::splitmode::one_paragraph_per_line);
      ug::ssplit::string_view span;
      while (stream >> span) {
        if (span.empty()) continue;
        const auto start = span.data() - input.data();
        std::cout << start << ':' << start + span.size() << ' ';
      }
      if (!stream.error_message().empty()) throw std::runtime_error(stream.error_message());
      std::cout << '\n';
    }
  } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
