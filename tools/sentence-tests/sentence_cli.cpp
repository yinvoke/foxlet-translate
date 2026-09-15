// SPDX-License-Identifier: MIT
// Local evaluation protocol: language<TAB>hex-encoded UTF-8, one request per line.
#include "sentence/segmenter.h"
#include <iostream>
#include <stdexcept>
#include <string>
using namespace foxlet::sentence;
int hex(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  throw std::invalid_argument("Invalid hex input");
}
int main(int argc, char** argv) {
  try {
    const bool unicode = argc == 2 && std::string(argv[1]) == "--unicode";
    if (argc > 2 || (argc == 2 && !unicode)) return 2;
    for (std::string line; std::getline(std::cin, line);) {
      const auto tab = line.find('\t');
      if (tab == std::string::npos || (line.size() - tab - 1) % 2) return 2;
      std::string input;
      for (size_t i = tab + 1; i < line.size(); i += 2) input += char(hex(line[i]) * 16 + hex(line[i+1]));
      Segmenter splitter(line.substr(0, tab));
      if (unicode) {
        const auto cuts = splitter.boundaries(input, Profile::Unicode);
        for (size_t i = 1; i < cuts.size(); ++i) std::cout << cuts[i-1] << ':' << cuts[i] << ' ';
      } else {
        for (auto span : splitter.sentences(input)) {
          const auto start = span.data() - input.data();
          std::cout << start << ':' << start + span.size() << ' ';
        }
      }
      std::cout << '\n';
    }
  } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
