package com.aistock.research.stocksearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockKeywordMatcherTest {
    @Test
    void chineseHomophonesAndPinyinReturnTheSameSetIgnoringToneAndCase() {
        List<String> names = List.of("羊业股份", "太平洋", "阳光电源", "扬子新材", "杨杰科技", "祥云股份", "东阳光");
        List<String> expected = List.of("羊业股份", "太平洋", "阳光电源", "扬子新材", "杨杰科技", "东阳光");
        for (String query : List.of("羊", "洋", "阳", "扬", "yang", "YANG", "YÁNG", "yang2")) {
            var matcher = new StockKeywordMatcher(query);
            assertThat(names.stream().filter(name -> matcher.matches("600001", name)).toList()).as(query).isEqualTo(expected);
        }
    }

    @Test
    void matchesConsecutiveWholeSyllablesInsteadOfArbitraryPinyinSubstringsOrInitials() {
        for (String query : List.of("yangguang", "YANG GUANG", "yáng'guāng", "yang2guang1")) {
            assertThat(new StockKeywordMatcher(query).matches("600001", "东阳光")).as(query).isTrue();
        }
        for (String query : List.of("ang", "y", "yg", "yangg", "iyang", "ya2ng", "yangx")) {
            assertThat(new StockKeywordMatcher(query).matches("600001", "海阳光")).as(query).isFalse();
        }
        assertThat(new StockKeywordMatcher("yang").matches("600001", "祥光股份")).isFalse();
    }

    @Test
    void chineseQueriesKeepCharacterBoundariesAndExplicitPinyinSeparatorsKeepSyllableBoundaries() {
        assertThat(new StockKeywordMatcher("西安").matches("600001", "锡安股份")).isTrue();
        assertThat(new StockKeywordMatcher("西安").matches("600001", "先导股份")).isFalse();
        assertThat(new StockKeywordMatcher("xian").matches("600001", "先导股份")).isTrue();
        assertThat(new StockKeywordMatcher("xian").matches("600001", "西安股份")).isTrue();
        assertThat(new StockKeywordMatcher("xi'an").matches("600001", "先导股份")).isFalse();
        assertThat(new StockKeywordMatcher("xi'an").matches("600001", "西安股份")).isTrue();
    }

    @Test
    void includesAllDictionaryReadingsForPolyphonicCharacters() {
        for (String query : List.of("chong", "zhong", "崇", "中", "重")) {
            assertThat(new StockKeywordMatcher(query).matches("600001", "重庆钢铁")).as(query).isTrue();
        }
        assertThat(new StockKeywordMatcher("chongqing").matches("600001", "重庆钢铁")).isTrue();
        assertThat(new StockKeywordMatcher("zhongqing").matches("600001", "重庆钢铁")).isTrue();
    }

    @Test
    void retainsTheDifferenceBetweenUAndUmlautWhileIgnoringTone() {
        for (String query : List.of("lv", "lü", "lǚ", "lu:3", "鋁")) {
            assertThat(new StockKeywordMatcher(query).matches("600001", "铝业股份")).as(query).isTrue();
        }
        assertThat(new StockKeywordMatcher("lu").matches("600001", "铝业股份")).isFalse();
    }

    @Test
    void retainsDirectNameCodeAndEmptyKeywordMatchingAndDoesNotSkipNonChineseCharacters() {
        assertThat(new StockKeywordMatcher("3000").matches("300001", "普通股份")).isTrue();
        assertThat(new StockKeywordMatcher("ST阳").matches("600001", "*ST阳光")).isTrue();
        assertThat(new StockKeywordMatcher("ABC").matches("600001", "Abc股份")).isTrue();
        assertThat(new StockKeywordMatcher("").matches("600001", "普通股份")).isTrue();
        assertThat(new StockKeywordMatcher("𠀀").matches("600001", "𠀀股份")).isTrue();
        assertThat(new StockKeywordMatcher("yangguang").matches("600001", "阳A光")).isFalse();
        assertThat(new StockKeywordMatcher("羊光").matches("600001", "阳A光")).isFalse();
        assertThat(new StockKeywordMatcher("yang").matches("600001", "阳A光")).isTrue();
    }
}
