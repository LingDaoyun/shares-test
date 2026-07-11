package com.aistock.research.ai;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class TrendRequestEnrichmentService {

    private static final Logger logger = LoggerFactory.getLogger(TrendRequestEnrichmentService.class);
    private static final int FETCH_TRIGGER_LENGTH = 280;
    private static final int MAX_CONTENT_LENGTH = 12000;
    private static final List<String> PRIMARY_SELECTORS = List.of(
            "#UCAP-CONTENT",
            ".trs_editor_view",
            ".TRS_Editor",
            "article",
            "main",
            ".pages_content"
    );

    private final RestClient restClient;

    public TrendRequestEnrichmentService(RestClient restClient) {
        this.restClient = restClient;
    }

    public TrendPromptRequest enrich(TrendPromptRequest request) {
        if (request == null || !shouldFetch(request)) {
            return request;
        }

        try {
            String html = fetchHtml(request.sourceUrl());
            String extracted = extractMainContent(html);
            if (extracted == null || extracted.isBlank()) {
                return request;
            }
            return new TrendPromptRequest(
                    request.documentTitle(),
                    request.documentType(),
                    request.sourceOrganization(),
                    request.publishedAt(),
                    request.sourceUrl(),
                    mergeExcerpt(request.contentExcerpt(), extracted),
                    request.focusThemes(),
                    request.knownCompanies()
            );
        } catch (Exception exception) {
            logger.warn("趋势分析正文抓取失败，继续使用原始节选: {}", request.sourceUrl(), exception);
            return request;
        }
    }

    private String fetchHtml(String sourceUrl) {
        try {
            return restClient.get()
                    .uri(URI.create(sourceUrl))
                    .retrieve()
                    .body(String.class);
        } catch (Exception primaryException) {
            logger.warn("正文直连抓取失败，改用 curl 兜底: {}", sourceUrl, primaryException);
            return fetchHtmlWithCurl(sourceUrl, primaryException);
        }
    }

    boolean shouldFetch(TrendPromptRequest request) {
        return request.sourceUrl() != null
                && !request.sourceUrl().isBlank()
                && (request.contentExcerpt() == null || request.contentExcerpt().trim().length() < FETCH_TRIGGER_LENGTH);
    }

    String extractMainContent(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parse(html);
        for (String selector : PRIMARY_SELECTORS) {
            Element element = document.selectFirst(selector);
            String extracted = extractFromElement(element);
            if (!extracted.isBlank()) {
                return truncate(extracted);
            }
        }

        String metaDescription = document.selectFirst("meta[name=description]") != null
                ? document.selectFirst("meta[name=description]").attr("content")
                : "";
        return truncate(normalizeWhitespace(metaDescription));
    }

    private String extractFromElement(Element element) {
        if (element == null) {
            return "";
        }
        element.select("script,style,noscript,iframe,svg").remove();
        List<String> parts = new ArrayList<>();
        for (Element block : element.select("h1,h2,h3,h4,p,li")) {
            String text = normalizeWhitespace(block.text());
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        if (parts.isEmpty()) {
            return normalizeWhitespace(element.text());
        }
        return String.join("\n", parts);
    }

    private String fetchHtmlWithCurl(String sourceUrl, Exception primaryException) {
        try {
            List<String> command = List.of(
                    "curl",
                    "-L",
                    "--compressed",
                    "-sS",
                    "--retry",
                    "1",
                    "--retry-delay",
                    "1",
                    "--retry-all-errors",
                    "--connect-timeout",
                    "5",
                    "--max-time",
                    "20",
                    "-H",
                    "User-Agent: Mozilla/5.0 AI-Stock-Research/0.1",
                    "-H",
                    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    sourceUrl
            );
            Process process = new ProcessBuilder(command).start();
            boolean completed = process.waitFor(25, TimeUnit.SECONDS);
            byte[] stdout = process.getInputStream().readAllBytes();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("curl 正文抓取超时", primaryException);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("curl 正文抓取失败：" + stderr, primaryException);
            }
            return new String(stdout, StandardCharsets.UTF_8);
        } catch (Exception curlException) {
            if (primaryException != null) {
                curlException.addSuppressed(primaryException);
            }
            throw new IllegalStateException("正文抓取失败", curlException);
        }
    }

    private String mergeExcerpt(String original, String extracted) {
        String originalNormalized = normalizeWhitespace(original);
        if (originalNormalized.isBlank()) {
            return truncate(extracted);
        }
        if (extracted.contains(originalNormalized)) {
            return truncate(extracted);
        }
        return truncate(originalNormalized + "\n\n" + extracted);
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String normalized = normalizeWhitespace(value.replace("\u00A0", " "))
                .replace("\n ", "\n")
                .replace(" \n", "\n");
        if (normalized.length() <= MAX_CONTENT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_CONTENT_LENGTH);
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\r", "\n")
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
