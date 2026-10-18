package com.aistock.research.integration.gov;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only the published static pagination formulas used by these official sites. Never evaluates JavaScript. */
final class GovHistoryPagination {
    record Result(List<String> nextPages, boolean unrecognized) {
        static Result none() { return new Result(List.of(), false); }
        static Result unsupported() { return new Result(List.of(), true); }
    }

    static Result discover(String configuredUrl, String currentUrl, String html, Function<String, String> scriptLoader) {
        URI current = URI.create(currentUrl);
        URI configured = URI.create(configuredUrl);
        if (!sameOrigin(configured, current)) return Result.unsupported();
        Document document = Jsoup.parse(html, currentUrl);
        String inline = document.select("script:not([src])").stream().map(Element::data)
                .map(GovHistoryPagination::withoutComments).reduce("", (first, next) -> first + "\n" + next);
        String host = current.getHost() == null ? "" : current.getHost().toLowerCase(Locale.ROOT);
        try {
            if (host.equals("www.ndrc.gov.cn") && inline.contains("createPageHTML")) {
                Matcher call = Pattern.compile("(?m)(?:^|[;\\s])createPageHTML\\(\\s*(\\d{1,5})\\s*,\\s*(\\d{1,5})\\s*,\\s*['\"]([a-zA-Z0-9_-]{1,64})['\"]\\s*,\\s*['\"](html?)['\"]\\s*\\)").matcher(inline);
                if (!call.find()) return Result.unsupported();
                String formula = null;
                for (Element script : document.select("script[src]")) {
                    URI scriptUri = URI.create(script.absUrl("src")).normalize();
                    if (sameOrigin(current, scriptUri) && scriptUri.getPath().equals("/jslm/fenye/fenye.js")
                            && scriptUri.getUserInfo() == null && scriptUri.getFragment() == null) {
                        formula = withoutComments(scriptLoader.apply(scriptUri.toString()));
                        break;
                    }
                }
                if (formula == null || !matches(formula, "function\\s+createPageHTML\\(\\s*_nPageCount\\s*,\\s*_nCurrIndex\\s*,\\s*_sPageName\\s*,\\s*_sPageExt\\s*\\)")
                        || !matches(formula, "nextPageNum\\s*=\\s*nCurrIndex\\s*\\+\\s*1")
                        || !matches(formula, "_sPageName\\s*\\+\\s*['\"]_['\"]\\s*\\+\\s*nextPageNum\\s*\\+\\s*['\"]\\.['\"]\\s*\\+\\s*_sPageExt")) {
                    return Result.unsupported();
                }
                return next(configured, current, Integer.parseInt(call.group(1)), Integer.parseInt(call.group(2)), call.group(3), call.group(4), "");
            }
            if (host.equals("www.mof.gov.cn") && inline.contains("countPage")) {
                Integer count = integerAssignment(inline, "countPage");
                Integer index = integerAssignment(inline, "currentPage");
                if (count == null || index == null || !matches(inline, "nextPage\\s*=\\s*currentPage\\s*\\+\\s*1")
                        || !matches(inline, "['\"]index['\"]\\s*\\+\\s*['\"]_['\"]\\s*\\+\\s*nextPage\\s*\\+\\s*['\"]\\.['\"]\\s*\\+\\s*['\"]htm(?:\\\\)?['\"]")) {
                    return Result.unsupported();
                }
                return next(configured, current, count, index, "index", "htm", "");
            }
            if (host.equals("www.moa.gov.cn") && inline.contains("PageContext.PageNav")) {
                Integer records = integerAssignment(inline, "m_nRecordCount");
                Integer size = integerAssignment(inline, "m_nPageSize");
                Integer index = integerAssignment(inline, "m_nCurrPage");
                String name = stringAssignment(inline, "m_sPageName");
                String extension = stringAssignment(inline, "m_sPageExt");
                if (records == null || size == null || size <= 0 || index == null || name == null || extension == null
                        || !matches(inline, "PageContext\\.PageNav\\.go\\s*=\\s*function\\(\\s*_iPage\\s*,\\s*_maxPage\\s*\\)")
                        || !matches(inline, "sURL\\s*=\\s*m_sPageName\\s*\\+\\s*['\"]_['\"]\\s*\\+\\s*\\(\\s*_iPage\\s*-\\s*1\\s*\\)\\s*\\+\\s*['\"]\\.['\"]\\s*\\+\\s*m_sPageExt")
                        || !matches(inline, "window\\.location\\.href\\s*=\\s*sURL\\s*\\+\\s*['\"]\\?id=['\"]\\s*\\+\\s*cid")) {
                    return Result.unsupported();
                }
                String id = "";
                if (current.getRawQuery() != null) {
                    Matcher parameter = Pattern.compile("(?:^|&)id=([^&]*)").matcher(current.getRawQuery());
                    if (parameter.find()) id = parameter.group(1);
                }
                // The public formula switches to a dynamic search service after page 20. Never follow that branch.
                int count = Math.min(20, (records + size - 1) / size);
                return next(configured, current, count, index, name, extension, "?id=" + id);
            }
            boolean hasPaginationMarker = inline.contains("createPageHTML") || inline.contains("pagination_script_config")
                    || inline.contains("PageContext.PageNav") || inline.contains("countPage");
            return hasPaginationMarker ? Result.unsupported() : Result.none();
        } catch (RuntimeException exception) { return Result.unsupported(); }
    }

    private static Result next(URI configured, URI current, int count, int index, String name, String extension, String suffix) {
        if (count < 1 || count > 10000 || index < 0 || index >= count || !name.matches("[a-zA-Z0-9_-]{1,64}")
                || !extension.matches("html?")) return Result.unsupported();
        String configuredPath = configured.getPath();
        String directory = configuredPath.substring(0, configuredPath.lastIndexOf('/') + 1);
        String expected = directory + name + (index == 0 ? "" : "_" + index) + "." + extension;
        if (!current.getPath().equals(expected) && !(index == 0 && current.getPath().equals(directory))) return Result.unsupported();
        if (index + 1 >= count) return Result.none();
        URI target = current.resolve(name + "_" + (index + 1) + "." + extension + suffix).normalize();
        if (!sameOrigin(configured, target) || !target.getPath().startsWith(directory) || target.getFragment() != null) return Result.unsupported();
        return new Result(List.of(target.toString()), false);
    }

    private static boolean sameOrigin(URI first, URI second) {
        return java.util.Objects.equals(first.getScheme(), second.getScheme())
                && java.util.Objects.equals(first.getHost(), second.getHost()) && first.getPort() == second.getPort()
                && second.getUserInfo() == null;
    }

    private static Integer integerAssignment(String script, String variable) {
        Matcher match = Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*=\\s*['\"]?(\\d{1,7})['\"]?(?=\\s*[,;\\r\\n]|$)").matcher(script);
        return match.find() ? Integer.parseInt(match.group(1)) : null;
    }

    private static String stringAssignment(String script, String variable) {
        Matcher match = Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*=\\s*['\"]([a-zA-Z0-9_-]{1,64})['\"]").matcher(script);
        return match.find() ? match.group(1) : null;
    }

    private static boolean matches(String input, String regex) { return Pattern.compile(regex).matcher(input).find(); }

    /** Keep quoted strings (including https:// URLs) while removing JavaScript comments and legacy HTML wrappers. */
    private static String withoutComments(String script) {
        if (script == null) return "";
        StringBuilder result = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (quote != 0) {
                result.append(c);
                if (c == '\\' && i + 1 < script.length()) result.append(script.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') { quote = c; result.append(c); continue; }
            if (script.startsWith("<!--", i)) { i += 3; continue; }
            if (script.startsWith("-->", i)) { i += 2; continue; }
            if (script.startsWith("//", i)) {
                while (i < script.length() && script.charAt(i) != '\n' && script.charAt(i) != '\r') i++;
                result.append('\n'); continue;
            }
            if (script.startsWith("/*", i)) {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? script.length() : end + 1;
                result.append(' '); continue;
            }
            result.append(c);
        }
        return result.toString();
    }
}
