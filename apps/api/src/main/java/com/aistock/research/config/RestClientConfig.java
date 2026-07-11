package com.aistock.research.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5));
        proxyFromEnvironment().ifPresent(address -> httpClientBuilder.proxy(ProxySelector.of(address)));
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClientBuilder.build());
        requestFactory.setReadTimeout(Duration.ofSeconds(12));
        return builder
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "Mozilla/5.0 AI-Stock-Research/0.1")
                .defaultHeader("Accept", "application/json,text/plain,*/*")
                .build();
    }

    private java.util.Optional<InetSocketAddress> proxyFromEnvironment() {
        String proxyUrl = firstNonBlank(
                System.getenv("https_proxy"),
                System.getenv("HTTPS_PROXY"),
                System.getenv("http_proxy"),
                System.getenv("HTTP_PROXY")
        );
        if (proxyUrl == null) {
            return java.util.Optional.empty();
        }
        try {
            URI uri = URI.create(proxyUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank() || port < 0) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new InetSocketAddress(host, port));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
