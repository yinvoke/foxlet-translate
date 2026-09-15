// SPDX-License-Identifier: MIT
// Independently written fixtures for the five product-priority languages.
#include "sentence/segmenter.h"
#include <string>
#include <vector>
using namespace foxlet::sentence;
void expect(const Segmenter&, std::string_view, std::vector<std::string_view>, Mode);
void coreCases() {
  auto e = [](std::string_view lang, std::string_view input, std::vector<std::string_view> expected) {
    expect(Segmenter(lang), input, expected, Mode::Paragraph);
  };
  for (auto lang : {"zh", "en", "ja", "ko", "ru"}) {
    e(lang, "Dr. Green arrived. Next comes work.", {"Dr. Green arrived.", "Next comes work."});
    e(lang, "Open https://example.com/find?q=a.b&x=1. Next.", {"Open https://example.com/find?q=a.b&x=1.", "Next."});
    e(lang, "Open https://example.com/a!b?q=1. Next.", {"Open https://example.com/a!b?q=1.", "Next."});
    e(lang, "A value is 3.14. Next.", {"A value is 3.14.", "Next."});
    e(lang, "你好😀。再见👨‍👩‍👧！", {"你好😀。", "再见👨‍👩‍👧！"});
    e(lang, "First.\u00a0Second.\u3000Third.", {"First.", "Second.", "Third."});
    e(lang, "First.\r\nSecond.\u2028Third.", {"First.", "Second.", "Third."});
  }
  e("zh-Hans", "他说：“出发吧！”然后关门。", {"他说：“出发吧！”", "然后关门。"});
  e("zh-Hant", "「準備好了嗎？」她問道。下一位進來了。", {"「準備好了嗎？」她問道。", "下一位進來了。"});
  e("zh", "她说（真的！）马上出发。然后回家。", {"她说（真的！）马上出发。", "然后回家。"});
  e("zh", "我遇见Dr.王。他来自美国。", {"我遇见Dr.王。", "他来自美国。"});
  e("zh", "请看Fig. 3。然后看第4页。", {"请看Fig. 3。", "然后看第4页。"});
  e("zh", "价格为３．１４元。版本是v2.1。", {"价格为３．１４元。", "版本是v2.1。"});
  e("zh", "我……还没想好。明天再说。", {"我……还没想好。", "明天再说。"});
  e("zh", "打开https://example.com/?q=中文。然后继续。", {"打开https://example.com/?q=中文。", "然后继续。"});
  e("en", "We finished. now we can leave.", {"We finished.", "now we can leave."});
  e("en", "Dr. smith arrived. we left.", {"Dr. smith arrived.", "we left."});
  e("en", "We met at 9 a.m. before work. then left.", {"We met at 9 a.m. before work.", "then left."});
  e("en", "We met at 9 a.m. Next came work.", {"We met at 9 a.m.", "Next came work."});
  e("en", "On Sept. 4 we met Capt. Green. We left.", {"On Sept. 4 we met Capt. Green.", "We left."});
  e("en", "Take fruit, etc. for lunch. We left.", {"Take fruit, etc. for lunch.", "We left."});
  e("en", "Take fruit, etc. Next comes bread.", {"Take fruit, etc.", "Next comes bread."});
  e("en", "P.S. I forgot the key. Please wait.", {"P.S. I forgot the key.", "Please wait."});
  e("en", "She said \"Go!\" and left. Next.", {"She said \"Go!\" and left.", "Next."});
  e("en", "She left (really!) before lunch. Next.", {"She left (really!) before lunch.", "Next."});
  e("en", "No. thanks anyway.", {"No.", "thanks anyway."});
  e("en", "Done. - Next step.", {"Done.", "- Next step."});
  e("en", "He waited… Then left.", {"He waited…", "Then left."});
  e("ja-JP", "「本当？」って聞いた。次の話です。", {"「本当？」って聞いた。", "次の話です。"});
  e("ja", "彼は\"行こう！\"と言った。次です。", {"彼は\"行こう！\"と言った。", "次です。"});
  e("ja", "「終わり。」次です。", {"「終わり。」", "次です。"});
  e("ja", "Dr.田中に会った。次はProf. Smithだ。", {"Dr.田中に会った。", "次はProf. Smithだ。"});
  e("ja", "価格は３．１４ドル。バージョン2.1です。", {"価格は３．１４ドル。", "バージョン2.1です。"});
  e("ja", "ええ……まだです。明日にします。", {"ええ……まだです。", "明日にします。"});
  e("ja", "これは（本当に！）便利だ。次です。", {"これは（本当に！）便利だ。", "次です。"});
  e("ko-KR", "그는 “괜찮아?”라고 물었다. 다음이다.", {"그는 “괜찮아?”라고 물었다.", "다음이다."});
  e("ko", "그는 \"가자!\" 하고 말했다. 다음이다.", {"그는 \"가자!\" 하고 말했다.", "다음이다."});
  e("ko", "“끝났다.” 다음 이야기다.", {"“끝났다.”", "다음 이야기다."});
  e("ko", "회의는 2026. 9. 14.에 열린다. 다음이다.", {"회의는 2026. 9. 14.에 열린다.", "다음이다."});
  e("ko", "2026.9.14.부터 시작한다. 다음이다.", {"2026.9.14.부터 시작한다.", "다음이다."});
  e("ko", "오늘은 2026. 9. 14. 다음 날은 휴일이다.", {"오늘은 2026. 9. 14.", "다음 날은 휴일이다."});
  e("ko", "번호는 3. 다음으로 넘어간다.", {"번호는 3.", "다음으로 넘어간다."});
  e("ko", "Dr.김을 만났다. 다음은 Prof. Green이다.", {"Dr.김을 만났다.", "다음은 Prof. Green이다."});
  e("ko", "정말(놀랍게도!) 좋은 소식이다. 다음이다.", {"정말(놀랍게도!) 좋은 소식이다.", "다음이다."});
  e("ko", "가격은 3.14달러다. 다음이다.", {"가격은 3.14달러다.", "다음이다."});
  e("ru-RU", "Это было в 2024 г. Затем мы уехали.", {"Это было в 2024 г.", "Затем мы уехали."});
  e("ru", "В 2024 г. мы уехали. потом вернулись.", {"В 2024 г. мы уехали.", "потом вернулись."});
  e("ru", "г. Москва находится далеко. Мы уехали.", {"г. Москва находится далеко.", "Мы уехали."});
  e("ru", "Цена 50 тыс. руб. Мы заплатили.", {"Цена 50 тыс. руб.", "Мы заплатили."});
  e("ru", "В 1990-х гг. XX века всё изменилось.", {"В 1990-х гг. XX века всё изменилось."});
  e("ru", "Книги и т. д. лежат здесь. Затем мы ушли.", {"Книги и т. д. лежат здесь.", "Затем мы ушли."});
  e("ru", "Книги и т. д. Потом мы ушли.", {"Книги и т. д.", "Потом мы ушли."});
  e("ru", "См. рис. 3 и стр. 5. Потом обсудим.", {"См. рис. 3 и стр. 5.", "Потом обсудим."});
  e("ru", "А. С. Пушкин пришёл. Мы ушли.", {"А. С. Пушкин пришёл.", "Мы ушли."});
  e("ru", "А́. С. Пушкин пришёл.", {"А́. С. Пушкин пришёл."});
  e("ru", "План А. Затем план Б.", {"План А.", "Затем план Б."});
  e("ru", "«Готово!» — сказал он. Затем ушёл.", {"«Готово!» — сказал он.", "Затем ушёл."});
  e("ru", "Он (правда!) пришёл. Затем ушёл.", {"Он (правда!) пришёл.", "Затем ушёл."});
  e("ru", "Он ушёл… Потом вернулся.", {"Он ушёл…", "Потом вернулся."});
}
