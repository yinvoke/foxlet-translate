// SPDX-License-Identifier: MIT
#include "sentence/segmenter.h"
#include "sentence/rules.h"
#include <fstream>
#include <filesystem>
#include <chrono>
#include <iostream>
#include <random>
#include <sstream>
#include <stdexcept>
#include <thread>
using namespace foxlet::sentence;
void coreCases();
void russianCases();
void measurementCases();
void check(bool value, std::string_view message) { if (!value) throw std::runtime_error(std::string(message)); }
void expect(const Segmenter& s, std::string_view input, std::vector<std::string_view> expected, Mode mode = Mode::Paragraph) {
  auto actual = s.sentences(input, mode);
  if (actual != expected) {
    std::cerr << "Unexpected boundaries: " << input << '\n';
    for (auto item : actual) std::cerr << '[' << item << ']'; std::cerr << '\n';
    throw std::runtime_error("sentence boundary mismatch");
  }
  for (auto span : actual) check(span.data() >= input.data() && span.data() + span.size() <= input.data() + input.size(), "range must address original text");
}
void cases() {
  Segmenter en("en");
  for (size_t i = 1; i < rules.size(); ++i) check(lessRule(rules[i-1], rules[i]), "Duplicate or unsorted builtin rule");
  for (const auto& rule : rules) {
    auto sentence = std::string(rule.text) + (rule.kind == Kind::Number ? ". 5 is ready." : rule.kind == Kind::Lower ? ". items follow." : ". Alex arrived.");
    expect(Segmenter(rule.language), sentence, {sentence});
    if (rule.kind == Kind::Number || rule.kind == Kind::Lower) {
      auto first = std::string(rule.text) + ".";
      expect(Segmenter(rule.language), first + " Next comes text.", {first, "Next comes text."});
    }
  }
  for (auto title : {"Mr", "Mrs", "Ms", "Dr", "Prof"}) {
    auto sentence = std::string(title) + ". Green arrived.";
    expect(en, sentence + " We left.", {sentence, "We left."});
  }
  for (auto ref : {"No", "Fig", "Eq", "Sec", "Ch", "pp"}) {
    auto sentence = std::string(ref) + ". 5 is missing.";
    expect(en, sentence + " Look again.", {sentence, "Look again."});
    auto end = std::string("The label is ") + ref + ".";
    expect(en, end + " Next comes the text.", {end, "Next comes the text."});
  }
  expect(en, "Options include e.g. Java and Kotlin.", {"Options include e.g. Java and Kotlin."});
  expect(en, "Choose A vs. B.", {"Choose A vs. B."});
  expect(en, "St. Peter's Square is busy. We left.", {"St. Peter's Square is busy.", "We left."});
  expect(en, "This is Main St. Next is the park.", {"This is Main St.", "Next is the park."});
  expect(en, "Use plan A. Next comes testing.", {"Use plan A.", "Next comes testing."});
  expect(en, "John F. Green met Lyndon B. White.", {"John F. Green met Lyndon B. White."});
  expect(en, "U.S. President George W. Green arrived.", {"U.S. President George W. Green arrived."});
  expect(en, "The U.S. They moved there.", {"The U.S.", "They moved there."});
  expect(en, "Acme Inc. Next comes Beta.", {"Acme Inc.", "Next comes Beta."});
  expect(en, "Some fruit, etc. Next comes bread.", {"Some fruit, etc.", "Next comes bread."});
  expect(en, "The value is 3.14. Version 2.1 works.", {"The value is 3.14.", "Version 2.1 works."});
  expect(en, "Open https://example.com/a. Then wait.", {"Open https://example.com/a.", "Then wait."});
  expect(en, "Contact a.b@example.com. Then wait.", {"Contact a.b@example.com.", "Then wait."});
  expect(en, "He said \"Go.\" She stayed.", {"He said \"Go.\"", "She stayed."});
  expect(en, "He paused...I waited.", {"He paused...I waited."});
  expect(en, "He paused... I waited.", {"He paused...", "I waited."});
  expect(en, "What? yes! Fine.", {"What?", "yes!", "Fine."});
  expect(Segmenter("de-DE"), "Dr. Müller kommt. Hr. Becker wartet.", {"Dr. Müller kommt.", "Hr. Becker wartet."});
  expect(Segmenter("de"), "Wir wählen z. B. Berlin. Dann Hamburg.", {"Wir wählen z. B. Berlin.", "Dann Hamburg."});
  expect(Segmenter("de"), "Am 3. Mai treffen wir uns.", {"Am 3. Mai treffen wir uns."});
  expect(Segmenter("de"), "Es waren 3. Danach gingen wir.", {"Es waren 3.", "Danach gingen wir."});
  expect(Segmenter("fr"), "M. Dupont arrive. MM. Martin et Durand attendent.", {"M. Dupont arrive.", "MM. Martin et Durand attendent."});
  expect(Segmenter("es"), "El Sr. García espera. La Sra. Pérez llega.", {"El Sr. García espera.", "La Sra. Pérez llega."});
  expect(Segmenter("pt-BR"), "O Sr. Silva chegou. A Dra. Costa espera.", {"O Sr. Silva chegou.", "A Dra. Costa espera."});
  expect(Segmenter("it"), "Il Sig. Rossi arriva. La Sig.ra Bianchi aspetta.", {"Il Sig. Rossi arriva.", "La Sig.ra Bianchi aspetta."});
  expect(Segmenter("ru"), "ул. Ленина рядом. А. С. Пушкин здесь.", {"ул. Ленина рядом.", "А. С. Пушкин здесь."});
  expect(Segmenter("tr"), "Prof. Yılmaz geliyor. Doç. İpek bekliyor.", {"Prof. Yılmaz geliyor.", "Doç. İpek bekliyor."});
  expect(Segmenter("tr"), "3. Mayıs günü geldik.", {"3. Mayıs günü geldik."});
  expect(Segmenter("zh-Hans"), "你好。再见！好吗？", {"你好。", "再见！", "好吗？"});
  expect(Segmenter("ja"), "「こんにちは。」次です。", {"「こんにちは。」", "次です。"});
  expect(Segmenter("ja"), "彼は「行こう。」と言った。次です。", {"彼は「行こう。」と言った。", "次です。"});
  expect(Segmenter("ja"), "前です。「次です。」", {"前です。", "「次です。」"});
  expect(en, ". Scientists explained it.", {".", "Scientists explained it."});
  expect(en, ".Hello there.", {".Hello there."});
  expect(en, "Before.“After.”", {"Before.", "“After.”"});
  expect(Segmenter("ja"), ".科学者が説明した。", {".科学者が説明した。"});
  expect(Segmenter("ko"), "안녕하세요. 다시 만나요.", {"안녕하세요.", "다시 만나요."});
  expect(Segmenter("ar"), "كيف حالك؟ أنا بخير.", {"كيف حالك؟", "أنا بخير."});
  expect(Segmenter("hi"), "मैं ठीक हूँ। फिर मिलेंगे।", {"मैं ठीक हूँ।", "फिर मिलेंगे।"});
  expect(Segmenter("ur"), "یہ ٹھیک ہے۔ پھر ملیں گے۔", {"یہ ٹھیک ہے۔", "پھر ملیں گے۔"});
  expect(Segmenter("el"), "Πώς είσαι; Είμαι καλά.", {"Πώς είσαι;", "Είμαι καλά."});
  expect(en, "First; Second.", {"First; Second."});
  expect(en, "\r\nAlpha.\r\n\r\nBeta.\n", {"Alpha.", "Beta."});
  expect(en, "Two. Sentences.\nAnother.", {"Two. Sentences.", "Another."}, Mode::Sentence);
  expect(en, "A long\nwrapped sentence.\n\nNext paragraph.", {"A long\nwrapped sentence.", "Next paragraph."}, Mode::Wrapped);
  expect(en, "Line one\r\nline two.\r\n \r\nLast.", {"Line one\r\nline two.", "Last."}, Mode::Wrapped);
  expect(en, "", {}); expect(en, " \t\n ", {});
  expect(Segmenter("en", false), "Mr. Green arrived.", {"Mr.", "Green arrived."});
  expect(Segmenter("ar", false), "كيف حالك؟ أنا بخير.", {"كيف حالك؟", "أنا بخير."});
  Segmenter custom("en"); custom.setCustomRules("Zed\r\nAaa\r\nNo\r\nNo # NUMERIC_ONLY #");
  expect(custom, "Zed. Green arrived.", {"Zed. Green arrived."});
  expect(custom, "No. Next please.", {"No.", "Next please."});
  expect(custom, "No. 5 is ready.", {"No. 5 is ready."});
  expect(custom, "Mr. Green arrived.", {"Mr.", "Green arrived."});
  custom.setCustomRules(""); expect(custom, "Zed. Green arrived.", {"Zed.", "Green arrived."});
  expect(Segmenter("unknown"), "Dr. Green arrived.", {"Dr.", "Green arrived."});
  expect(Segmenter(" EN_us "), "Dr. Green arrived.", {"Dr. Green arrived."});
  expect(Segmenter("de", false), "Am 3. Mai treffen wir uns.", {"Am 3. Mai treffen wir uns."});
  expect(Segmenter("ja", false), "彼は「行こう。」と言った。", {"彼は「行こう。」と言った。"});
  // Long lookahead and UTF-8 tails must preserve all bytes without quadratic space scans.
  auto spaced = std::string("Dr.") + std::string(100000, ' ') + "Green arrived.";
  expect(en, spaced, {spaced});
  // Verify the file API, failed-update atomicity, and explicit empty custom overrides.
  auto path = std::filesystem::temp_directory_path() / ("foxlet-sentence-" +
      std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) + ".txt");
  struct Cleanup { std::filesystem::path path; ~Cleanup() { std::error_code error; std::filesystem::remove(path, error); } } cleanup{path};
  { std::ofstream file(path); file << "Zed\n"; }
  custom.load(path.string());
  expect(custom, "Zed. Green arrived.", {"Zed. Green arrived."});
  bool rejected = false;
  try { custom.setCustomRules(std::string("Bad \x80")); } catch (const std::invalid_argument&) { rejected = true; }
  check(rejected, "Invalid custom UTF-8 accepted");
  expect(custom, "Zed. Green arrived.", {"Zed. Green arrived."});
  { std::ofstream file(path); }
  custom.load(path.string()); expect(custom, "Zed. Green arrived.", {"Zed.", "Green arrived."});
  std::filesystem::remove(path); rejected = false;
  try { custom.load(path.string()); } catch (const std::invalid_argument&) { rejected = true; }
  check(rejected, "Missing custom file accepted");
  auto copy = en; auto moved = std::move(copy); expect(moved, "Dr. Green arrived.", {"Dr. Green arrived."});
}
void appendUtf8(std::string& s, unsigned c) {
  if (c < 0x80) s += char(c);
  else if (c < 0x800) { s += char(0xC0 | (c >> 6)); s += char(0x80 | (c & 63)); }
  else if (c < 0x10000) { s += char(0xE0 | (c >> 12)); s += char(0x80 | ((c >> 6) & 63)); s += char(0x80 | (c & 63)); }
  else { s += char(0xF0 | (c >> 18)); s += char(0x80 | ((c >> 12) & 63)); s += char(0x80 | ((c >> 6) & 63)); s += char(0x80 | (c & 63)); }
}
void unicodeTests(const char* path) {
  std::ifstream file(path); check(bool(file), "Missing Unicode conformance file");
  size_t count = 0, failed = 0;
  Segmenter s;
  for (std::string line; std::getline(file, line);) {
    auto body = line.substr(0, line.find('#')); if (body.find("÷") == std::string::npos) continue;
    std::istringstream tokens(body); std::string token, input; std::vector<size_t> expected;
    while (tokens >> token) {
      if (token == "÷") expected.push_back(input.size());
      else if (token != "×") appendUtf8(input, std::stoul(token, nullptr, 16));
    }
    auto actual = s.boundaries(input, Profile::Unicode); ++count;
    if (actual != expected) { if (failed++ < 8) { std::cerr << line << '\n'; for (auto i : actual) std::cerr << i << ','; std::cerr << '\n'; } }
  }
  std::cout << "Unicode cases=" << count << " failures=" << failed << '\n'; check(count > 100 && !failed, "Unicode sentence conformance failed");
}
int main(int argc, char** argv) {
  check(argc == 2, "Expected Unicode test file"); unicodeTests(argv[1]); cases(); coreCases(); russianCases(); measurementCases();
  Segmenter s("en");
  for (std::string invalid : {std::string("\xC0\xAF"), std::string("\xED\xA0\x80"), std::string("\xF4\x90\x80\x80"), std::string("\xE2\x82"), std::string("\x80")}) {
    bool rejected = false; try { s.sentences(invalid); } catch (const std::invalid_argument&) { rejected = true; } check(rejected, "Malformed UTF-8 accepted");
  }
  bool rejected = false; try { s.setCustomRules(std::string(1024*1024+1,'A')); } catch (const std::invalid_argument&) { rejected = true; } check(rejected, "Oversized data accepted");
  std::mt19937 random(137);
  for (int iteration = 0; iteration < 2000; ++iteration) {
    std::string input;
    for (int i = 0; i < 64; ++i) { unsigned cp = random() % 0x110000; if (cp >= 0xD800 && cp <= 0xDFFF) cp = '.'; appendUtf8(input, cp); }
    auto cuts = s.boundaries(input); check(cuts.front() == 0 && cuts.back() == input.size(), "Incomplete coverage");
    for (size_t i = 1; i < cuts.size(); ++i) check(cuts[i] > cuts[i-1] && (cuts[i] == input.size() || (static_cast<unsigned char>(input[cuts[i]]) & 0xC0) != 0x80), "Invalid byte boundary");
  }
  std::vector<std::thread> workers;
  for (int i = 0; i < 4; ++i) workers.emplace_back([&] { for (int n = 0; n < 100; ++n) expect(s, "Dr. Green arrived. We left.", {"Dr. Green arrived.", "We left."}); });
  for (auto& worker : workers) worker.join();
  std::cout << "PASS multilingual rules, modes, original byte spans, invalid UTF-8, custom rules and concurrent reads\n";
}
