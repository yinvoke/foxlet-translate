// Copyright (c) 2026 yinvoker. SPDX-License-Identifier: MIT
#pragma once
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
namespace foxlet {
inline void appendUtf8(std::string& out, uint32_t c) {
  if (c < 0x80) out.push_back(static_cast<char>(c));
  else if (c < 0x800) { out.push_back(0xc0 | (c >> 6)); out.push_back(0x80 | (c & 63)); }
  else if (c < 0x10000) { out.push_back(0xe0 | (c >> 12)); out.push_back(0x80 | ((c >> 6) & 63)); out.push_back(0x80 | (c & 63)); }
  else { out.push_back(0xf0 | (c >> 18)); out.push_back(0x80 | ((c >> 12) & 63)); out.push_back(0x80 | ((c >> 6) & 63)); out.push_back(0x80 | (c & 63)); }
}
inline std::string toUtf8(std::u16string_view in) {
  std::string out;
  for (size_t i = 0; i < in.size(); ++i) {
    uint32_t c = in[i];
    if (c >= 0xd800 && c <= 0xdbff) {
      if (i + 1 == in.size() || in[i + 1] < 0xdc00 || in[i + 1] > 0xdfff)
        throw std::invalid_argument("Unpaired UTF-16 surrogate");
      c = 0x10000 + ((c - 0xd800) << 10) + (in[++i] - 0xdc00);
    } else if (c >= 0xdc00 && c <= 0xdfff) throw std::invalid_argument("Unpaired UTF-16 surrogate");
    appendUtf8(out, c);
  }
  return out;
}
inline std::u16string toUtf16(std::string_view in) {
  std::u16string out;
  for (size_t i = 0; i < in.size();) {
    uint32_t c = static_cast<unsigned char>(in[i++]);
    int extra = 0; uint32_t minimum = 0;
    if (c >= 0xc2 && c <= 0xdf) { c &= 31; extra = 1; minimum = 0x80; }
    else if (c >= 0xe0 && c <= 0xef) { c &= 15; extra = 2; minimum = 0x800; }
    else if (c >= 0xf0 && c <= 0xf4) { c &= 7; extra = 3; minimum = 0x10000; }
    else if (c >= 0x80) throw std::invalid_argument("Invalid UTF-8 leading byte");
    if (static_cast<size_t>(extra) > in.size() - i) throw std::invalid_argument("Truncated UTF-8");
    while (extra--) {
      auto b = static_cast<unsigned char>(in[i++]);
      if ((b & 0xc0) != 0x80) throw std::invalid_argument("Invalid UTF-8 continuation");
      c = (c << 6) | (b & 63);
    }
    if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) throw std::invalid_argument("Invalid UTF-8 scalar");
    if (c < 0x10000) out.push_back(static_cast<char16_t>(c));
    else { c -= 0x10000; out.push_back(static_cast<char16_t>(0xd800 + (c >> 10))); out.push_back(static_cast<char16_t>(0xdc00 + (c & 1023))); }
  }
  return out;
}
}
