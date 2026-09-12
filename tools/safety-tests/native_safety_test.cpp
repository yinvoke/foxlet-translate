#include "engine/marian-fork/src/common/binary_validation.h"
#include "jni/utf_codec.h"
#include <fstream>
#include <iostream>
#include <random>
#include <functional>
#include <vector>

void require(bool ok) { if (!ok) throw std::runtime_error("test failed"); }
void rejects(const std::function<void()>& fn) {
  try { fn(); } catch (const std::invalid_argument&) { return; }
  throw std::runtime_error("invalid input was accepted");
}
template<class T> void append(std::vector<char>& out, T value) {
  const char* p = reinterpret_cast<const char*>(&value); out.insert(out.end(), p, p + sizeof(T));
}
int main(int argc, char** argv) {
  using marian::io::binary::validateModelBytes;
  std::vector<char> good;
  for (uint64_t x : {1ULL, 1ULL, 4ULL, 1028ULL, 1ULL, 4ULL}) append(good, x);
  good.insert(good.end(), {'w', 'x', 'x', 0}); append<int32_t>(good, 1);
  append<uint64_t>(good, 0); append<float>(good, 1);
  validateModelBytes(good.data(), good.size());
  for (size_t n = 0; n < good.size(); ++n) rejects([&] { validateModelBytes(good.data(), n); });
  auto invalid = good; invalid[51] = 'x'; rejects([&] { validateModelBytes(invalid.data(), invalid.size()); });
  std::vector<char> truncated(16); truncated[0] = 1;
  rejects([&] { validateModelBytes(truncated.data(), truncated.size()); });
  std::mt19937 random(42);
  for (int n = 0; n < 20000; ++n) {
    auto mutated = good;
    for (int i = 0; i < 4; ++i) mutated[random() % mutated.size()] = random();
    try { validateModelBytes(mutated.data(), mutated.size()); } catch (const std::invalid_argument&) {}
  }
  const std::u16string text = {u'A', 0xd83d, 0xde00, 0, 0xd840, 0xdc00, u'中'};
  require(foxlet::toUtf16(foxlet::toUtf8(text)) == text);
  require(foxlet::toUtf8(std::u16string{0xd83d, 0xde00}) == "\xf0\x9f\x98\x80");
  for (const auto& bad : {std::string("\xc0\x80"), std::string("\xed\xa0\xbd"), std::string("\xf4\x90\x80\x80"), std::string("\xe2\x82"), std::string("\x80")})
    rejects([&] { foxlet::toUtf16(bad); });
  rejects([] { foxlet::toUtf8(std::u16string{0xd800}); });
  rejects([] { foxlet::toUtf8(std::u16string{0xdc00}); });
  for (int i = 1; i < argc; ++i) {
    std::ifstream input(argv[i], std::ios::binary);
    require(bool(input));
    std::vector<char> bytes((std::istreambuf_iterator<char>(input)), {});
    validateModelBytes(bytes.data(), bytes.size());
    std::cout << "Valid model: " << argv[i] << '\n';
  }
  std::cout << "Native safety tests passed (20000 deterministic mutations)\n";
}
