// SPDX-License-Identifier: MIT
#include "segmenter.h"
#include "rules.h"
#include "unicode_data.h"
#include <algorithm>
#include <fstream>
#include <iterator>
#include <stdexcept>

namespace foxlet::sentence {
namespace {
using P = Property;
P property(char32_t cp) {
  if (cp < 128) return asciiProperties[cp];
  auto first = std::begin(propertyRanges), last = std::end(propertyRanges);
  auto found = std::upper_bound(first, last, cp,
      [](char32_t c, const PropertyRange& r) { return c < r.first; });
  return found != first && cp <= (found - 1)->last ? (found - 1)->property : P::Other;
}
bool opening(char32_t cp) {
  auto begin = std::begin(openingRanges), end = std::end(openingRanges);
  auto found = std::upper_bound(begin, end, cp, [](char32_t c, const OpeningRange& r) { return c < r.first; });
  return found != begin && cp <= (found - 1)->last;
}
struct Point { char32_t value; size_t end; P kind; };
Point read(std::string_view text, size_t at) {
  const auto byte = [&](size_t i) { return static_cast<unsigned char>(text[i]); };
  const unsigned char lead = byte(at);
  if (lead < 0x80) return {lead, at + 1, asciiProperties[lead]};
  size_t count; char32_t value;
  if (lead >= 0xC2 && lead <= 0xDF) { count = 2; value = lead & 0x1F; }
  else if (lead >= 0xE0 && lead <= 0xEF) { count = 3; value = lead & 0x0F; }
  else if (lead >= 0xF0 && lead <= 0xF4) { count = 4; value = lead & 7; }
  else throw std::invalid_argument("Invalid UTF-8 at byte " + std::to_string(at));
  if (count > text.size() - at) throw std::invalid_argument("Truncated UTF-8 at byte " + std::to_string(at));
  for (size_t i = 1; i < count; ++i) {
    unsigned char next = byte(at + i);
    if ((next & 0xC0) != 0x80) throw std::invalid_argument("Invalid UTF-8 at byte " + std::to_string(at + i));
    value = (value << 6) | (next & 0x3F);
  }
  if ((count == 3 && value < 0x800) || (count == 4 && value < 0x10000) ||
      (value >= 0xD800 && value <= 0xDFFF) || value > 0x10FFFF)
    throw std::invalid_argument("Invalid UTF-8 scalar at byte " + std::to_string(at));
  return {value, at + count, property(value)};
}
bool ignore(P p) { return p == P::Extend || p == P::Format; }
bool separator(P p) { return p == P::CR || p == P::LF || p == P::Sep; }
bool space(P p) { return p == P::Sp || separator(p); }
bool letter(P p) { return p == P::Upper || p == P::Lower || p == P::OLetter; }
bool term(P p) { return p == P::ATerm || p == P::STerm; }
size_t previous(std::string_view text, size_t at) {
  --at;
  while (at && (static_cast<unsigned char>(text[at]) & 0xC0) == 0x80) --at;
  return at;
}
// SB8's lookahead is evaluated once per ATerm, not at every trailing space.
bool lowerAhead(std::string_view text, size_t at) {
  while (at < text.size()) {
    auto p = read(text, at); at = p.end;
    if (p.kind == P::Lower) return true;
    if (p.kind == P::Upper || p.kind == P::OLetter || separator(p.kind) || term(p.kind)) return false;
  }
  return false;
}
std::string_view trim(std::string_view text) {
  size_t begin = 0, end = text.size();
  while (begin < end) { auto p = read(text, begin); if (!space(p.kind)) break; begin = p.end; }
  while (end > begin) { size_t at = previous(text, end); if (!space(read(text, at).kind)) break; end = at; }
  return text.substr(begin, end - begin);
}
bool knownLanguage(std::string_view language) {
  return std::find(std::begin(ruleLanguages), std::end(ruleLanguages), language) != std::end(ruleLanguages);
}
bool cjk(std::string_view language) { return language == "zh" || language == "ja" || language == "ko"; }
bool begins(std::string_view text, std::string_view prefix) { return text.substr(0, prefix.size()) == prefix; }
bool quote(char32_t cp) { return cp == '"' || cp == '\'' || cp == 0x2019 || cp == 0x201D || cp == 0x300D || cp == 0x300F || cp == 0xFF63 || cp == 0xBB; }
bool openBracket(char32_t cp) { return cp == '(' || cp == '[' || cp == '{' || cp == 0xFF08 || cp == 0xFF3B || cp == 0xFF5B; }
bool closeBracket(char32_t cp) { return cp == ')' || cp == ']' || cp == '}' || cp == 0xFF09 || cp == 0xFF3D || cp == 0xFF5D; }
bool startsSentence(std::string_view language, std::string_view text) {
  constexpr std::string_view en[] = {"Next", "Then", "However", "This", "That", "We", "It", "The", "She", "He", "They", "But", "Now", "I", "So", "After", "If", "In", "On", "At", "As", "For", "From", "How", "What", "Why"};
  constexpr std::string_view ru[] = {"Он", "Она", "Они", "Мы", "Вы", "Я", "Это", "Этот", "Эта", "Эти", "В", "На", "Но", "А", "И", "Так", "Затем", "После", "Также"};
  if (language == "ru") return std::find(std::begin(ru), std::end(ru), text) != std::end(ru);
  return std::find(std::begin(en), std::end(en), text) != std::end(en);
}
// Scan each URL once. Internal .?! are text, while trailing punctuation remains
// available to end the surrounding sentence. No rewriting of source bytes.
size_t urlEnd(std::string_view text, size_t at) {
  auto tail = text.substr(at);
  if (!begins(tail, "https://") && !begins(tail, "http://")) return at;
  if (at) { auto prior = read(text, previous(text, at)); if (prior.kind == P::Upper || prior.kind == P::Lower || prior.kind == P::Numeric) return at; }
  size_t end = at;
  while (end < text.size()) {
    auto p = read(text, end);
    if (space(p.kind) || p.kind == P::Close || p.value == '<' || p.value == '>' ||
        (p.value > 127 && term(p.kind))) break;
    end = p.end;
  }
  while (end > at) {
    auto last = previous(text, end); auto cp = read(text, last).value;
    if (cp != '.' && cp != '?' && cp != '!' && cp != ',' && cp != ';' && cp != ':') break;
    end = last;
  }
  return end;
}
bool quoteSuffix(std::string_view language, std::string_view tail) {
  if (language == "zh") {
    for (auto suffix : {"他说", "她说", "我说", "他问", "她问", "我问", "他說", "她說", "我說", "他問", "她問", "我問"}) if (begins(tail, suffix)) return true;
  }
  if (language == "ja") return begins(tail, "と") || begins(tail, "って");
  if (language == "ko") {
    for (auto suffix : {"라고", "이라고", "라며", "이라며", "하고"}) if (begins(tail, suffix)) return true;
  }
  return false;
}
// Russian dialogue often uses "--" in plain text and may put a quote after
// the dash. Keep lowercase author clauses attached; uppercase begins a turn.
bool russianDialogue(std::string_view text, size_t at) {
  const size_t begin = at;
  bool dash = false;
  while (at < text.size() && at - begin < 64) {
    auto p = read(text, at);
    if (p.value == '-' || p.value == 0x2013 || p.value == 0x2014) dash = true;
    else if (p.kind != P::Sp && !opening(p.value) && p.value != '"' && p.value != '\'') return dash && p.kind == P::Upper;
    at = p.end;
  }
  return false;
}
// Korean numeric dates use periods in place of year/month/day: YYYY. M. D.
// Protect the first two dots only after recognizing a complete date. The final
// dot joins an attached particle, but can still end a sentence before a space.
bool koreanDate(std::string_view text, size_t period, size_t next) {
  size_t begin = period;
  while (begin && period - begin < 32 && ((text[begin-1] >= '0' && text[begin-1] <= '9') || text[begin-1] == '.' || text[begin-1] == ' ')) --begin;
  while (begin < period && text[begin] == ' ') ++begin;
  size_t at = begin; unsigned values[3]{}; size_t dots[3]{};
  for (int part = 0; part < 3; ++part) {
    size_t digits = 0;
    while (at < text.size() && text[at] >= '0' && text[at] <= '9' && digits < 5) { values[part] = values[part]*10 + text[at++]-'0'; ++digits; }
    if ((part == 0 ? digits != 4 : !digits || digits > 2) || at == text.size() || text[at] != '.') return false;
    dots[part] = at++;
    while (at < text.size() && text[at] == ' ') ++at;
  }
  if (!values[0] || !values[1] || values[1] > 12 || !values[2] || values[2] > 31) return false;
  if (period == dots[0] || period == dots[1]) return true;
  if (period != dots[2] || next != period + 1) return false;
  for (auto suffix : {"에", "부터", "까지", "은", "이"}) if (begins(text.substr(next), suffix)) return true;
  return false;
}

bool month(std::string_view language, std::string_view word) {
  constexpr std::string_view de[] = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"};
  constexpr std::string_view tr[] = {"Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"};
  if (language == "de") return std::find(std::begin(de), std::end(de), word) != std::end(de);
  if (language == "tr") return std::find(std::begin(tr), std::end(tr), word) != std::end(tr);
  return false;
}
} // namespace

std::string Segmenter::primaryLanguage(std::string_view language) {
  for (size_t at = 0; at < language.size();) at = read(language, at).end;
  auto clean = trim(language);
  std::string result;
  for (char c : clean) {
    if (c == '-' || c == '_') break;
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return {};
    result.push_back(c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c);
  }
  return result;
}
Segmenter::Segmenter(std::string_view language, bool builtins)
    : language_(primaryLanguage(language)), builtins_(builtins) {}

void Segmenter::setCustomRules(std::string_view data) {
  if (data.size() > 1024 * 1024) throw std::invalid_argument("Prefix data exceeds 1 MiB");
  // Validate before trimming: a stray continuation byte must not be mistaken
  // for part of trailing whitespace by backward UTF-8 traversal.
  for (size_t at = 0; at < data.size();) at = read(data, at).end;
  // Parse into temporary storage so a rejected update leaves the object intact.
  std::vector<std::pair<std::string, bool>> parsed;
  size_t longest = 0;
  for (size_t at = 0; at < data.size();) {
    size_t end = data.find('\n', at); if (end == std::string_view::npos) end = data.size();
    auto line = trim(data.substr(at, end - at)); at = end == data.size() ? end : end + 1;
    if (line.empty() || line.front() == '#') continue;
    size_t tokenEnd = 0;
    while (tokenEnd < line.size()) {
      auto p = read(line, tokenEnd);
      if (space(p.kind) || p.value == '#') break;
      tokenEnd = p.end;
    }
    if (!tokenEnd) continue;
    auto suffix = line.substr(tokenEnd);
    std::string compact;
    for (size_t i = 0; i < suffix.size();) { auto p = read(suffix, i); if (!space(p.kind)) compact.append(suffix.substr(i, p.end - i)); i = p.end; }
    parsed.emplace_back(std::string(line.substr(0, tokenEnd)), compact == "#NUMERIC_ONLY#");
    longest = std::max(longest, tokenEnd);
  }
  std::stable_sort(parsed.begin(), parsed.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
  size_t count = 0;
  for (size_t i = 0; i < parsed.size(); ++i) {
    if (count && parsed[count - 1].first == parsed[i].first) parsed[count - 1].second = parsed[i].second;
    else { if (count != i) parsed[count] = std::move(parsed[i]); ++count; }
  }
  parsed.resize(count); custom_ = std::move(parsed); maxCustomLength_ = longest; builtins_ = false;
}
void Segmenter::load(std::string_view path) {
  std::ifstream file(std::string(path), std::ios::binary | std::ios::ate);
  if (!file) throw std::invalid_argument("Cannot read prefix file: " + std::string(path));
  auto length = file.tellg();
  if (length < 0 || length > 1024 * 1024) throw std::invalid_argument("Prefix file exceeds 1 MiB or cannot be sized");
  file.seekg(0); std::string data(static_cast<size_t>(length), '\0');
  if (!data.empty() && !file.read(data.data(), static_cast<std::streamsize>(data.size()))) throw std::invalid_argument("Cannot read complete prefix file");
  setCustomRules(data);
}

bool Segmenter::protect(std::string_view text, size_t period, size_t next) const {
  if (language_ == "ko" && koreanDate(text, period, next)) return true;
  const bool mixed = cjk(language_);
  std::string_view ruleLanguage = mixed ? "en" : std::string_view(language_);
  while (next < text.size()) {
    auto p = read(text, next);
    if (!(p.kind == P::Sp || p.kind == P::Close || ignore(p.kind))) break;
    next = p.end;
  }
  if (next == text.size()) return false;
  auto following = read(text, next);
  size_t wordEnd = next;
  while (wordEnd < text.size()) { auto p = read(text, wordEnd); if (!letter(p.kind) && !ignore(p.kind)) break; wordEnd = p.end; }
  auto nextWord = text.substr(next, wordEnd - next);
  auto applies = [&](Kind kind) {
    if (kind == Kind::Number) return following.kind == P::Numeric;
    if (kind == Kind::Name) return letter(following.kind);
    if (kind == Kind::Lower) return following.kind == P::Lower || following.kind == P::Numeric;
    return letter(following.kind) || following.kind == P::Numeric;
  };
  // Bounded backward scan for builtins; custom tokens are bounded by their longest entry.
  size_t tokenStart = period;
  size_t limit = std::max(size_t(64), maxCustomLength_);
  while (tokenStart && period - tokenStart < limit) {
    size_t at = previous(text, tokenStart); auto p = read(text, at);
    if (mixed && p.kind == P::OLetter) break;
    if (!letter(p.kind) && !ignore(p.kind) && p.kind != P::Numeric && p.value != '.' && p.value != '-') break;
    tokenStart = at;
  }
  auto token = text.substr(tokenStart, period - tokenStart);
  if (language_ == "ru" && !token.empty() && std::all_of(token.begin(), token.end(), [](unsigned char c) { return c < 128; })) ruleLanguage = "en";
  // Locale date syntax is separate from the optional abbreviation rules.
  if (month(language_, nextWord) && !token.empty() && token.size() <= 2 &&
      std::all_of(token.begin(), token.end(), [](char c) { return c >= '0' && c <= '9'; })) {
    unsigned day = 0; for (char c : token) day = day * 10 + c - '0';
    if (day >= 1 && day <= 31) return true;
  }
  auto customMatch = [&](std::string_view candidate) {
    auto it = std::lower_bound(custom_.begin(), custom_.end(), candidate,
        [](const auto& a, std::string_view b) { return std::string_view(a.first) < b; });
    return it != custom_.end() && it->first == candidate && applies(it->second ? Kind::Number : Kind::Name);
  };
  if (!builtins_) {
    if (customMatch(token)) return true;
    auto dot = token.rfind('.');
    return dot != std::string_view::npos && customMatch(token.substr(dot + 1));
  }
  if (!knownLanguage(ruleLanguage)) return false;
  if (language_ == "ru" && token == "т" && nextWord == "есть") return true;
  // In a compound measure, см means centimetres even without a leading number.
  // Check the modifier at a word boundary; accept both кв. см and кв.см.
  if (language_ == "ru" && following.kind == P::Upper && period >= std::string_view("см").size() &&
      text.substr(period - std::string_view("см").size(), std::string_view("см").size()) == "см") {
    size_t before = period - std::string_view("см").size();
    while (before && space(read(text, previous(text, before)).kind)) before = previous(text, before);
    for (std::string_view modifier : {"кв.", "куб.", "пог."}) {
      if (before < modifier.size() || text.substr(before - modifier.size(), modifier.size()) != modifier) continue;
      size_t start = before - modifier.size();
      if (start) { auto p = read(text, previous(text, start)); if (letter(p.kind) || ignore(p.kind) || p.kind == P::Numeric) continue; }
      return false;
    }
  }
  // A quantity before г./см. disambiguates a sentence-final year/unit from
  // the city/reference prefixes г. Москва / см. Иванов.
  if (language_ == "ru" && (token == "г" || token == "гг" || token == "см")) {
    size_t before = tokenStart;
    while (before && space(read(text, previous(text, before)).kind)) before = previous(text, before);
    const bool numberBefore = before && read(text, previous(text, before)).kind == P::Numeric;
    const bool roman = !nextWord.empty() && std::all_of(nextWord.begin(), nextWord.end(), [](char c) { return c == 'I' || c == 'V' || c == 'X'; });
    if (token == "гг" && roman) return true;
    if (numberBefore && following.kind == P::Upper) return false;
  }
  // Protect internal dots in a complete multi-part rule, including "z. B.".
  for (const auto& rule : rules) {
    if (rule.language != ruleLanguage) continue;
    for (size_t dot = rule.text.find('.'); dot != std::string_view::npos; dot = rule.text.find('.', dot + 1)) {
      if (period < dot) continue;
      size_t start = period - dot, end = start + rule.text.size();
      if (end >= text.size() || text[end] != '.' || text.substr(start, rule.text.size()) != rule.text) continue;
      if (start) { auto p = read(text, previous(text, start)); if ((letter(p.kind) || ignore(p.kind) || p.kind == P::Numeric) && !(mixed && p.kind == P::OLetter && text[start] < 127)) continue; }
      return true;
    }
  }

  // Name initials and dotted initialisms, without a language-sized alphabet list.
  bool initialism = !token.empty(); size_t letters = 0;
  for (size_t i = 0; initialism && i < token.size();) {
    auto p = read(token, i); i = p.end;
    if (p.kind != P::Upper) { initialism = false; break; }
    ++letters;
    while (i < token.size() && ignore(read(token, i).kind)) i = read(token, i).end;
    if (i < token.size()) { if (token[i] != '.') { initialism = false; break; } ++i; }
  }
  if (initialism && letters && applies(Kind::Name) && (!(startsSentence(ruleLanguage, nextWord) || startsSentence(language_, nextWord)) || (wordEnd < text.size() && text[wordEnd] == '.'))) return true;
  // Rules may include internal dots and spaces (e.g. German "z. B.").
  size_t start = period;
  const Rule* selected = nullptr;
  while (start && period - start < 64) {
    start = previous(text, start);
    if (separator(read(text, start).kind)) break;
    if (start) { auto p = read(text, previous(text, start)); if ((letter(p.kind) || ignore(p.kind) || p.kind == P::Numeric) && !(mixed && p.kind == P::OLetter && text[start] < 127)) continue; }
    Rule key{ruleLanguage, text.substr(start, period - start), Kind::Name};
    auto found = std::lower_bound(rules.begin(), rules.end(), key, lessRule);
    if (found != rules.end() && found->language == key.language && found->text == key.text) {
      selected = &*found; // Prefer a complete multi-part rule over its suffix (т. д. vs д.).
    }
  }
  if (selected) {
    if (ruleLanguage == "en" && selected->text == "St" && startsSentence(ruleLanguage, nextWord)) return false;
    return applies(selected->kind);
  }
  return false;
}

std::vector<size_t> Segmenter::boundaries(std::string_view text, Profile profile) const {
  std::vector<size_t> result{0};
  P last = P::Other, beforeLast = P::Other, rawLast = P::Other;
  bool active = false, afterSpace = false, dotTail = false, followedByLower = false;
  size_t termAt = 0;
  bool content = false, quotedTail = false, bracketTail = false;
  size_t protectedUrlEnd = 0, bracketDepth = 0;
  for (size_t at = 0; at < text.size();) {
    auto point = read(text, at); P p = point.kind;
    if (profile == Profile::Translation) {
      if (at >= protectedUrlEnd && point.value == 'h') protectedUrlEnd = urlEnd(text, at);
      if (at < protectedUrlEnd && term(p)) p = P::Other;
      if ((language_ == "en" || language_ == "ru") && point.value == 0x2026) p = P::ATerm;
    }
    if (profile == Profile::Translation && language_ == "el" && (point.value == ';' || point.value == 0x37E)) p = P::STerm;
    if (profile == Profile::Translation && p == P::Close && opening(point.value)) p = P::Other;
    bool cut = false;
    if (at) {
      if (rawLast == P::CR && p == P::LF) {} // SB3
      else if (separator(rawLast)) cut = true; // SB4
      else if (ignore(p)) {} // SB5
      else if (last == P::ATerm && p == P::Numeric) {} // SB6
      else if (last == P::ATerm && (beforeLast == P::Upper || beforeLast == P::Lower) && p == P::Upper) {} // SB7
      else if (active && dotTail && followedByLower) {} // SB8
      else if (active && (p == P::SContinue || term(p))) {} // SB8a
      else if (active && !afterSpace && (p == P::Close || p == P::Sp || separator(p))) {} // SB9
      else if (active && (p == P::Sp || separator(p))) {} // SB10
      else if (active) cut = true; // SB11
    }
    if (profile == Profile::Translation && active && afterSpace && !separator(rawLast)) {
      // UAX SB8 conservatively merges every lowercase continuation. In English
      // and Russian prose/chat, use the explicit abbreviation rules instead.
      if ((language_ == "en" || language_ == "ru") && dotTail && p == P::Lower &&
          text[termAt] == '.' && (!termAt || text[termAt-1] != '.')) cut = true;
      // SB8 may look through a number, @mention or opening quote to a lowercase
      // word and swallow the boundary. A spaced single period can end Russian
      // text before these starters too; abbreviation protection still runs below.
      if (language_ == "ru" && dotTail && text[termAt] == '.' && (!termAt || text[termAt-1] != '.') &&
          (p == P::Numeric || point.value == '@' || opening(point.value) || point.value == '"')) cut = true;
      // A spaced leading ellipsis can open the next sentence: "Конец. …Начало".
      // Leave sentence-internal pauses and the Unicode-default profile alone.
      if (language_ == "ru" && content && point.value == 0x2026 && point.end < text.size() &&
          read(text, point.end).kind == P::Upper) cut = true;
      if (p == P::SContinue && (point.value == '-' || point.value == 0x2013 || point.value == 0x2014)) {
        size_t look = point.end;
        while (look < text.size() && space(read(text, look).kind)) look = read(text, look).end;
        if (look < text.size() && read(text, look).kind == P::Upper) cut = true;
        if (language_ == "ru" && russianDialogue(text, at)) cut = true;
      }
    }
    // Translation profile: an attached ASCII ellipsis denotes a pause. A spaced
    // ellipsis still follows normal sentence rules. Unicode conformance is unchanged.
    if (cut && profile == Profile::Translation && dotTail && at == termAt + 1 && termAt &&
        text[termAt] == '.' && text[termAt - 1] == '.') cut = false;
    if (cut && profile == Profile::Translation && dotTail && !separator(rawLast) && protect(text, termAt, at)) cut = false;
    if (cut && profile == Profile::Translation && !separator(rawLast)) {
      // A leading punctuation run attached to a word is not a separate sentence.
      // Preserve a boundary when separated by whitespace (e.g. ". Scientists ...").
      if (!content && !afterSpace) cut = false;
      if (quotedTail && quoteSuffix(language_, text.substr(at))) cut = false;
      if ((quotedTail || bracketTail) && (p == P::Lower || (bracketTail && cjk(language_) && p == P::OLetter))) cut = false;
    }
    if (cut) { result.push_back(at); content = false; }
    if (!space(p) && !term(p) && p != P::Close && !ignore(p)) content = true;
    if (!ignore(p) || separator(rawLast)) {
      if (term(p)) {
        active = true; afterSpace = false; dotTail = p == P::ATerm; termAt = at;
        quotedTail = false; bracketTail = bracketDepth != 0;
        followedByLower = dotTail && lowerAhead(text, point.end);
      } else if (active && (p == P::Sp || (p == P::Close && !afterSpace))) {
        if (p == P::Sp) afterSpace = true;
        if (quote(point.value)) quotedTail = true;
      } else active = false;
      beforeLast = last; last = p;
    }
    if (openBracket(point.value)) ++bracketDepth;
    if (closeBracket(point.value) && bracketDepth) --bracketDepth;
    rawLast = p; at = point.end;
  }
  if (!text.empty()) result.push_back(text.size());
  return result;
}

std::vector<std::string_view> Segmenter::sentences(std::string_view text, Mode mode) const {
  std::vector<std::string_view> result;
  // Validate even in presegmented mode; never expose a range inside malformed UTF-8.
  for (size_t at = 0; at < text.size();) at = read(text, at).end;
  auto append = [&](std::string_view chunk) {
    if (mode == Mode::Sentence) { auto s = trim(chunk); if (!s.empty()) result.push_back(s); return; }
    auto cuts = boundaries(chunk);
    for (size_t i = 1; i < cuts.size(); ++i) {
      auto s = trim(chunk.substr(cuts[i - 1], cuts[i] - cuts[i - 1]));
      if (!s.empty()) result.push_back(s);
    }
  };
  if (mode != Mode::Wrapped) {
    size_t begin = 0;
    for (size_t at = 0; at < text.size();) {
      auto p = read(text, at);
      if (separator(p.kind)) {
        append(text.substr(begin, at - begin));
        if (p.kind == P::CR && p.end < text.size() && text[p.end] == '\n') ++p.end;
        begin = p.end;
      }
      at = p.end;
    }
    append(text.substr(begin));
  } else {
    // Soft newlines behave as spaces for boundary decisions, but original bytes remain untouched.
    // Boundary positions for this profile are produced by a same-width newline substitution.
    // Only wrapped mode allocates a text copy; paragraph mode never copies the source.
    std::string normalized(text);
    for (char& c : normalized) if (c == '\r' || c == '\n') c = ' ';
    size_t begin = 0;
    for (size_t at = 0; at < text.size();) {
      size_t newline = text.find('\n', at);
      if (newline == std::string_view::npos) break;
      size_t next = newline + 1;
      while (next < text.size() && (text[next] == ' ' || text[next] == '\t' || text[next] == '\r')) ++next;
      if (next < text.size() && text[next] == '\n') {
        auto chunk = std::string_view(normalized).substr(begin, newline - begin);
        auto cuts = boundaries(chunk);
        for (size_t i = 1; i < cuts.size(); ++i) {
          auto s = trim(text.substr(begin + cuts[i - 1], cuts[i] - cuts[i - 1]));
          if (!s.empty()) result.push_back(s);
        }
        begin = next + 1; at = begin;
      } else at = newline + 1;
    }
    auto cuts = boundaries(std::string_view(normalized).substr(begin));
    for (size_t i = 1; i < cuts.size(); ++i) { auto s = trim(text.substr(begin + cuts[i - 1], cuts[i] - cuts[i - 1])); if (!s.empty()) result.push_back(s); }
  }
  return result;
}
} // namespace foxlet::sentence
