// Copyright (c) 2026 yinvoker. SPDX-License-Identifier: MIT
#pragma once
#include <cstdint>
#include <cstring>
#include <limits>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

namespace marian { namespace io { namespace binary {
// All input-controlled pointer movement uses remaining bytes, never unchecked sums.
class CheckedBytes {
  const unsigned char* p_;
  size_t remaining_;
public:
  CheckedBytes(const void* data, size_t size) : p_(static_cast<const unsigned char*>(data)), remaining_(size) {
    if (!data && size) throw std::invalid_argument("Null binary model");
  }
  size_t remaining() const { return remaining_; }
  const unsigned char* take(uint64_t count) {
    if (count > remaining_) throw std::invalid_argument("Truncated binary model");
    const auto* out = p_;
    if (count) p_ += static_cast<size_t>(count);
    remaining_ -= static_cast<size_t>(count);
    return out;
  }
  template<class T> T read() { T out; std::memcpy(&out, take(sizeof(T)), sizeof(T)); return out; }
};
inline void validateModelBytes(const void* data, size_t size) {
  struct Header { uint64_t name, type, shape, bytes; };
  CheckedBytes in(data, size);
  if (in.read<uint64_t>() != 1) throw std::invalid_argument("Unsupported binary model version");
  uint64_t count = in.read<uint64_t>();
  if (!count || count > in.remaining() / sizeof(Header)) throw std::invalid_argument("Invalid tensor count");
  std::vector<Header> headers;
  headers.reserve(static_cast<size_t>(count));
  for (uint64_t i = 0; i < count; ++i) headers.push_back(in.read<Header>());
  std::set<std::string> names;
  for (const auto& h : headers) {
    if (!h.name) throw std::invalid_argument("Empty tensor name");
    const auto* name = in.take(h.name);
    if (name[h.name - 1] || std::memchr(name, 0, h.name - 1)) throw std::invalid_argument("Invalid tensor name");
    if (!names.emplace(reinterpret_cast<const char*>(name), h.name - 1).second)
      throw std::invalid_argument("Duplicate tensor name");
  }
  for (const auto& h : headers) {
    if (!h.shape || h.shape > 4 || h.shape > in.remaining() / sizeof(int32_t))
      throw std::invalid_argument("Invalid tensor rank");
    uint64_t elements = 1;
    for (uint64_t j = 0; j < h.shape; ++j) {
      int32_t dim = in.read<int32_t>();
      // Marian Shape uses signed int products; reject overflow before it reaches Shape.
      if (dim <= 0 || elements > uint64_t(std::numeric_limits<int32_t>::max()) / uint64_t(dim))
        throw std::invalid_argument("Invalid tensor dimensions");
      elements *= static_cast<uint64_t>(dim);
    }
    const uint64_t width = h.type & 255;
    const uint64_t kind = h.type & ~uint64_t(255);
    const bool scalar = ((kind == 0x100 || kind == 0x200) && (width == 1 || width == 2 || width == 4 || width == 8))
                     || (kind == 0x400 && (width == 2 || width == 4 || width == 8));
    const bool quant = kind == 0x4100 && (width == 1 || width == 2);
    if (!scalar && !quant) throw std::invalid_argument("Unsupported tensor type");
    if (h.bytes < elements * width + (quant ? sizeof(float) : 0))
      throw std::invalid_argument("Tensor data shorter than its shape");
  }
  uint64_t padding = in.read<uint64_t>();
  in.take(padding);
  for (const auto& h : headers) in.take(h.bytes);
  if (in.remaining()) throw std::invalid_argument("Trailing binary model data");
}
}}}
