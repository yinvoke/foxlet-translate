// SPDX-License-Identifier: MIT
// Optional research harness: link a separately retained legacy snapshot and ICU.
#include "sentence/segmenter.h"
#include "ssplit/ssplit.h"
#include <unicode/ubrk.h>
#include <unicode/utext.h>
#include <algorithm>
#include <chrono>
#include <fstream>
#include <iostream>
#include <sstream>
using namespace foxlet::sentence;
std::vector<std::string> legacy(const ug::ssplit::SentenceSplitter& s, const std::string& text) {
  std::vector<std::string> result;
  ug::ssplit::SentenceStream stream(text, s, ug::ssplit::SentenceStream::splitmode::one_paragraph_per_line);
  ug::ssplit::string_view span;
  while (stream >> span) if (!span.empty()) result.emplace_back(span);
  return result;
}
std::vector<std::string> modern(const Segmenter& s, const std::string& text) {
  std::vector<std::string> result; for (auto span : s.sentences(text)) result.emplace_back(span); return result;
}
struct Icu {
  UBreakIterator* iterator;
  explicit Icu(const std::string& locale) {
    UErrorCode error = U_ZERO_ERROR;
    iterator = ubrk_open(UBRK_SENTENCE, locale.c_str(), nullptr, 0, &error);
    if (U_FAILURE(error)) throw std::runtime_error(u_errorName(error));
  }
  ~Icu() { ubrk_close(iterator); }
  std::vector<std::string> split(const std::string& text) {
    UErrorCode error = U_ZERO_ERROR; UText input = UTEXT_INITIALIZER;
    utext_openUTF8(&input, text.data(), text.size(), &error);
    ubrk_setUText(iterator, &input, &error);
    if (U_FAILURE(error)) throw std::runtime_error(u_errorName(error));
    std::vector<std::string> result;
    int32_t start = ubrk_first(iterator);
    for (int32_t end = ubrk_next(iterator); end != UBRK_DONE; start = end, end = ubrk_next(iterator)) {
      auto part = text.substr(start, end - start);
      while (!part.empty() && (part.back() == ' ' || part.back() == '\n' || part.back() == '\r' || part.back() == '\t')) part.pop_back();
      if (!part.empty()) result.push_back(part);
    }
    utext_close(&input); return result;
  }
};
size_t checksum = 0;
template<class Split> void timing(std::string_view name, const std::vector<std::string>& corpus, Split split) {
  for (const auto& text : corpus) checksum += split(text).size();
  std::vector<double> samples;
  for (int sample = 0; sample < 7; ++sample) {
    auto begin = std::chrono::steady_clock::now();
    for (int repeat = 0; repeat < 100; ++repeat) for (const auto& text : corpus) for (const auto& span : split(text)) checksum += span.size();
    samples.push_back(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count()/100);
  }
  std::sort(samples.begin(),samples.end());
  std::cout << name << " median_ms=" << samples[3] << " min_ms=" << samples.front() << " max_ms=" << samples.back() << '\n';
}
int main(int argc, char** argv) {
  if (argc < 3 || argc > 5) return 2;
  const std::string language = argc >= 4 ? argv[3] : "en";
  std::ifstream input(argv[2]); std::vector<std::string> corpus;
  for (std::string line; std::getline(input,line);) corpus.push_back(line);
  ug::ssplit::SentenceSplitter oldEnglish(argv[1]), none;
  Segmenter en(language); Icu icu(language+"@ss=standard");
  if (argc < 5 || std::string(argv[4]) != "--boundaries-only") {
  timing("legacy+English",corpus,[&](auto& text){return legacy(oldEnglish,text);});
  timing("Foxlet",corpus,[&](auto& text){return modern(en,text);});
  timing("ICU-filtered",corpus,[&](auto& text){return icu.split(text);});
  }
  size_t differences=0;
  for(size_t i=0;i<corpus.size();++i) {
    auto a=legacy(oldEnglish,corpus[i]), b=modern(en,corpus[i]);
    if(a!=b) {++differences;std::cout<<"corpus_difference line="<<i+1<<" old="<<a.size()<<" new="<<b.size()<<" input="<<corpus[i]<<'\n';}
  }
  std::cout<<"corpus boundary differences="<<differences<<"/"<<corpus.size()<<'\n';
  const std::pair<const char*,const char*> examples[] = {
    {"en","Dr. Green arrived. We left."}, {"en","Use plan A. Next comes testing."},
    {"en","Options include e.g. Java and Kotlin."}, {"en","What? yes! Fine."},
    {"de","Dr. Müller kommt. Hr. Becker wartet."}, {"de","Am 3. Mai treffen wir uns."},
    {"de","Wir wählen z. B. Berlin. Dann Hamburg."}, {"fr","M. Dupont arrive demain."},
    {"es","El Sr. García espera."}, {"pt","O Sr. Silva chegou."}, {"it","Il Sig. Rossi arriva."},
    {"ru","ул. Ленина рядом. А. С. Пушкин здесь."}, {"tr","Prof. Yılmaz geliyor."},
    {"ar","كيف حالك؟ أنا بخير."}, {"hi","मैं ठीक हूँ। फिर मिलेंगे।"},
    {"el","Πώς είσαι; Είμαι καλά."}, {"ja","「こんにちは。」次です。"}
  };
  for(auto [language,text]:examples) {
    Segmenter s(language); Icu other(std::string(language)+"@ss=standard");
    std::cout << "case " << language << ' ' << text << '\n';
    auto show=[](const char* label,const auto& spans){std::cout<<label<<": ";for(auto& span:spans)std::cout<<'['<<span<<']';std::cout<<'\n';};
    show("legacy",legacy(std::string(language)=="en"?oldEnglish:none,text));
    show("Foxlet",modern(s,text));show("ICU",other.split(text));
  }
  std::cout<<"checksum="<<checksum<<'\n';
}
