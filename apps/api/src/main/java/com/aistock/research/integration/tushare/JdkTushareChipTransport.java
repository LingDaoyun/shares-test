package com.aistock.research.integration.tushare;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class JdkTushareChipTransport implements TushareChipTransport {

    @Override
    public TushareHttpResponse post(TushareChipRequest request) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(request.connectTimeoutMs()))
                .build();
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(Duration.ofMillis(request.readTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "AI-Stock-Research/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(request.jsonBody()))
                .build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return new TushareHttpResponse(response.statusCode(), response.body());
    }
}
