package com.aistock.research.filing;

import com.aistock.research.config.LiveDataProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Optional;

@Service
public class FilingPdfTextService {

    private static final Logger logger = LoggerFactory.getLogger(FilingPdfTextService.class);
    private static final int MAX_PDF_BYTES = 12 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 12000;

    private final RestClient restClient;
    private final LiveDataProperties properties;

    public FilingPdfTextService(RestClient restClient, LiveDataProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public Optional<FilingTextSnapshot> extract(FilingDocument document) {
        if (document.downloadUrl() == null || document.downloadUrl().isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] bytes = restClient.get()
                    .uri(URI.create(document.downloadUrl()))
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            if (bytes.length > MAX_PDF_BYTES) {
                logger.info("公告 PDF 过大，跳过正文解析：{} bytes={}", document.documentId(), bytes.length);
                return Optional.empty();
            }
            return Optional.of(readPdf(document, bytes));
        } catch (Exception exception) {
            logger.warn("公告 PDF 正文解析失败：{}，原因：{}", document.documentId(), exception.getMessage());
            logger.debug("公告 PDF 正文解析异常详情", exception);
            return Optional.empty();
        }
    }

    private FilingTextSnapshot readPdf(FilingDocument document, byte[] bytes) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            int pages = Math.max(1, Math.min(pdf.getNumberOfPages(), pdfMaxPages()));
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            String text = normalize(stripper.getText(pdf));
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS);
            }
            return new FilingTextSnapshot(document.documentId(), document.title(), pages, text);
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00A0', ' ')
                .replaceAll("[\\t\\r ]+", " ")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }

    private int pdfMaxPages() {
        Integer value = properties.filingPdfMaxPages();
        if (value == null || value <= 0) {
            return 6;
        }
        return Math.min(value, 20);
    }
}
