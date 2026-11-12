package com.aistock.research.stocksearch;

import net.sourceforge.pinyin4j.PinyinHelper;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Matches complete character readings without expanding every polyphonic combination. */
final class StockKeywordMatcher {
    private final String keyword;
    private final List<Set<String>> chineseReadings;
    private final String pinyin;
    private final Set<Integer> syllableBoundaries = new HashSet<>();

    StockKeywordMatcher(String keyword) {
        this.keyword = keyword.toLowerCase(Locale.ROOT);
        this.chineseReadings = keyword.codePoints().allMatch(StockKeywordMatcher::isChinese)
                ? readings(keyword) : List.of();
        String normalized = normalizePinyin(this.keyword);
        StringBuilder letters = new StringBuilder();
        if (normalized.matches("[a-z1-5\\s']+")) {
            for (char character : normalized.toCharArray()) {
                if (character >= 'a' && character <= 'z') letters.append(character);
                else syllableBoundaries.add(letters.length());
            }
        }
        this.pinyin = letters.toString();
    }

    boolean matches(String symbol, String name) {
        if (symbol.contains(keyword) || name.toLowerCase(Locale.ROOT).contains(keyword)) return true;
        if (chineseReadings.isEmpty() && pinyin.isEmpty()) return false;
        List<Set<String>> nameReadings = readings(name);
        if (!chineseReadings.isEmpty()) return matchesChinese(nameReadings);
        return matchesPinyin(nameReadings);
    }

    private boolean matchesChinese(List<Set<String>> nameReadings) {
        for (int start = 0; start + chineseReadings.size() <= nameReadings.size(); start++) {
            boolean match = true;
            for (int index = 0; index < chineseReadings.size(); index++) {
                if (Collections.disjoint(chineseReadings.get(index), nameReadings.get(start + index))) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private boolean matchesPinyin(List<Set<String>> nameReadings) {
        Set<Integer> offsets = Set.of(0);
        for (Set<String> characterReadings : nameReadings) {
            Set<Integer> next = new HashSet<>();
            for (int offset : offsets) {
                for (String reading : characterReadings) {
                    int end = offset + reading.length();
                    if (!pinyin.startsWith(reading, offset) || crossesBoundary(offset, end)) continue;
                    if (end == pinyin.length()) return true;
                    next.add(end);
                }
            }
            // A new match may start at the next character; non-Chinese characters break a sequence.
            next.add(0);
            offsets = next;
        }
        return false;
    }

    private boolean crossesBoundary(int start, int end) {
        for (int boundary : syllableBoundaries) if (boundary > start && boundary < end) return true;
        return false;
    }

    private static List<Set<String>> readings(String text) {
        return text.codePoints().mapToObj(codePoint -> {
            if (!isChinese(codePoint) || codePoint > Character.MAX_VALUE) return Set.<String>of();
            String[] readings = PinyinHelper.toHanyuPinyinStringArray((char) codePoint);
            if (readings == null) return Set.<String>of();
            return Arrays.stream(readings).map(reading -> normalizePinyin(reading).replaceAll("[1-5]$", ""))
                    .collect(Collectors.toSet());
        }).toList();
    }

    private static boolean isChinese(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static String normalizePinyin(String text) {
        // Preserve ü as v before removing tone marks; lu and lü are different syllables.
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replace("u\u0308", "v").replace("u:", "v").replaceAll("\\p{M}", "");
    }
}
