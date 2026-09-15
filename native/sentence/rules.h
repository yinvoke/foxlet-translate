// SPDX-License-Identifier: MIT
// Common abbreviations curated for concrete name/reference/continuation cases.
// This is not a copy or a filtered version of a Moses word list.
#pragma once
#include <array>
#include <string_view>
namespace foxlet::sentence {
enum class Kind { Name, Number, Continuation, Lower };
struct Rule { std::string_view language, text; Kind kind; };
inline constexpr Rule rawRules[] = {
  {"en", "Mr", Kind::Name}, {"en", "Mrs", Kind::Name}, {"en", "Ms", Kind::Name},
  {"en", "Dr", Kind::Name}, {"en", "Prof", Kind::Name}, {"en", "St", Kind::Name},
  {"en", "No", Kind::Number}, {"en", "Fig", Kind::Number}, {"en", "Eq", Kind::Number},
  {"en", "Sec", Kind::Number}, {"en", "Ch", Kind::Number}, {"en", "pp", Kind::Number},
  {"en", "e.g", Kind::Continuation}, {"en", "i.e", Kind::Continuation}, {"en", "vs", Kind::Continuation},
  {"en", "Drs", Kind::Name}, {"en", "Capt", Kind::Name}, {"en", "Lt", Kind::Name},
  {"en", "Sgt", Kind::Name}, {"en", "Rev", Kind::Name}, {"en", "ext", Kind::Number},
  {"en", "Jan", Kind::Number}, {"en", "Feb", Kind::Number}, {"en", "Mar", Kind::Number},
  {"en", "Apr", Kind::Number}, {"en", "Jun", Kind::Number}, {"en", "Jul", Kind::Number},
  {"en", "Aug", Kind::Number}, {"en", "Sep", Kind::Number}, {"en", "Sept", Kind::Number},
  {"en", "Oct", Kind::Number}, {"en", "Nov", Kind::Number}, {"en", "Dec", Kind::Number},
  {"en", "etc", Kind::Lower}, {"en", "a.m", Kind::Lower}, {"en", "p.m", Kind::Lower},
  {"en", "Inc", Kind::Lower}, {"en", "Ltd", Kind::Lower}, {"en", "Co", Kind::Lower},
  {"en", "P.S", Kind::Continuation}, {"en", "PS", Kind::Continuation},
  {"en", "sq", Kind::Lower}, {"en", "cu", Kind::Lower},
  {"de", "Dr", Kind::Name}, {"de", "Prof", Kind::Name}, {"de", "Hr", Kind::Name},
  {"de", "Fr", Kind::Name}, {"de", "Dipl.-Ing", Kind::Name},
  {"de", "Nr", Kind::Number}, {"de", "Abb", Kind::Number}, {"de", "S", Kind::Number},
  {"de", "z.B", Kind::Continuation}, {"de", "z. B", Kind::Continuation},
  {"de", "d.h", Kind::Continuation}, {"de", "d. h", Kind::Continuation},
  {"fr", "M", Kind::Name}, {"fr", "MM", Kind::Name}, {"fr", "Prof", Kind::Name},
  {"fr", "p", Kind::Number}, {"fr", "pp", Kind::Number}, {"fr", "fig", Kind::Number},
  {"fr", "p. ex", Kind::Continuation},
  {"es", "Sr", Kind::Name}, {"es", "Sra", Kind::Name}, {"es", "Srta", Kind::Name},
  {"es", "Dr", Kind::Name}, {"es", "Dra", Kind::Name}, {"es", "Prof", Kind::Name},
  {"es", "pág", Kind::Number}, {"es", "núm", Kind::Number}, {"es", "fig", Kind::Number},
  {"es", "p. ej", Kind::Continuation}, {"es", "p.ej", Kind::Continuation},
  {"pt", "Sr", Kind::Name}, {"pt", "Sra", Kind::Name}, {"pt", "Dr", Kind::Name},
  {"pt", "Dra", Kind::Name}, {"pt", "Prof", Kind::Name}, {"pt", "Profa", Kind::Name},
  {"pt", "pág", Kind::Number}, {"pt", "fig", Kind::Number}, {"pt", "p. ex", Kind::Continuation},
  {"it", "Sig", Kind::Name}, {"it", "sig", Kind::Name}, {"it", "Sig.ra", Kind::Name},
  {"it", "Dott", Kind::Name}, {"it", "dott", Kind::Name}, {"it", "Prof", Kind::Name},
  {"it", "Ing", Kind::Name}, {"it", "pag", Kind::Number}, {"it", "fig", Kind::Number},
  {"it", "n", Kind::Number},
  {"ru", "г", Kind::Name}, {"ru", "ул", Kind::Name}, {"ru", "им", Kind::Name},
  {"ru", "проф", Kind::Name}, {"ru", "стр", Kind::Number}, {"ru", "рис", Kind::Number},
  {"ru", "д", Kind::Number},
  // кв. precedes lowercase area units (км, м, см, ...) as well as apartment numbers.
  // A following uppercase sentence remains a boundary, e.g. after a quarter label.
  {"ru", "кв", Kind::Lower},
  {"ru", "куб", Kind::Lower}, {"ru", "пог", Kind::Lower},
  {"ru", "л. с", Kind::Lower}, {"ru", "л.с", Kind::Lower},
  {"ru", "долл", Kind::Lower}, {"ru", "шт", Kind::Lower},
  {"ru", "гг", Kind::Lower}, {"ru", "руб", Kind::Lower}, {"ru", "коп", Kind::Lower},
  {"ru", "тыс", Kind::Lower}, {"ru", "мин", Kind::Lower}, {"ru", "чел", Kind::Lower},
  {"ru", "др", Kind::Lower}, {"ru", "т.д", Kind::Lower}, {"ru", "т. д", Kind::Lower},
  {"ru", "т.п", Kind::Lower}, {"ru", "т. п", Kind::Lower},
  {"ru", "см", Kind::Continuation}, {"ru", "См", Kind::Continuation},
  {"ru", "ред", Kind::Name}, {"ru", "итал", Kind::Name}, {"ru", "лат", Kind::Name},
  {"ru", "англ", Kind::Name}, {"ru", "ст", Kind::Number}, {"ru", "т", Kind::Number},
  {"ru", "т.е", Kind::Continuation}, {"ru", "т. е", Kind::Continuation},
  {"ru", "Т.е", Kind::Continuation}, {"ru", "Т. е", Kind::Continuation},
  {"ru", "и.о", Kind::Continuation}, {"ru", "и. о", Kind::Continuation},
  {"ru", "И.о", Kind::Continuation}, {"ru", "И. о", Kind::Continuation},
  {"ru", "в", Kind::Lower}, {"ru", "вв", Kind::Lower}, {"ru", "пр", Kind::Lower},
  {"ru", "н.э", Kind::Lower}, {"ru", "н. э", Kind::Lower},
  {"ru", "тел", Kind::Number}, {"ru", "доб", Kind::Number},
  {"ru", "п", Kind::Number}, {"ru", "род", Kind::Number}, {"ru", "ум", Kind::Number},
  // Tolerate common informal dotted spellings; standard млн/млрд omit the dot.
  {"ru", "млн", Kind::Lower}, {"ru", "млрд", Kind::Lower}, {"ru", "гр", Kind::Lower},
  {"tr", "Dr", Kind::Name}, {"tr", "Prof", Kind::Name}, {"tr", "Doç", Kind::Name},
  {"tr", "Av", Kind::Name}, {"tr", "Sn", Kind::Name}, {"tr", "No", Kind::Number},
  {"tr", "s", Kind::Number}, {"tr", "sf", Kind::Number},
};
constexpr bool lessRule(const Rule& a, const Rule& b) {
  return a.language < b.language || (a.language == b.language && a.text < b.text);
}
template<size_t N> constexpr auto sortedRules(const Rule (&input)[N]) {
  std::array<Rule, N> result{};
  for (size_t i = 0; i < N; ++i) {
    auto value = input[i]; size_t j = i;
    while (j && lessRule(value, result[j - 1])) { result[j] = result[j - 1]; --j; }
    result[j] = value;
  }
  return result;
}
inline constexpr auto rules = sortedRules(rawRules);
inline constexpr std::string_view ruleLanguages[] = {"en", "de", "fr", "es", "pt", "it", "ru", "tr"};
// CJK prose reuses these Latin abbreviations only at Latin word/script boundaries.
// No invented Chinese, Japanese or Korean word lists are required.
struct RuleAlias { std::string_view language, rules; };
inline constexpr RuleAlias ruleAliases[] = {{"zh", "en"}, {"ja", "en"}, {"ko", "en"}};
} // namespace foxlet::sentence
