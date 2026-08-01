package com.aistock.research.integration.tushare;

import com.aistock.research.configuration.ShortTermChipSettings;
import com.aistock.research.shortterm.chip.ExternalChipPerformance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class TushareChipClient {

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String FIELDS = "ts_code,trade_date,cost_5pct,cost_15pct,cost_50pct,"
            + "cost_85pct,cost_95pct,weight_avg,winner_rate";

    private final TushareChipTransport transport;
    private final ObjectMapper objectMapper;
    private final ShortTermChipSettings settings;
    private final TushareChipResponseParser parser;
    private final Semaphore requestSlots;

    @Autowired
    public TushareChipClient(ObjectMapper objectMapper, ShortTermChipSettings settings) {
        this(new JdkTushareChipTransport(), objectMapper, settings);
    }

    TushareChipClient(
            TushareChipTransport transport,
            ObjectMapper objectMapper,
            ShortTermChipSettings settings
    ) {
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.parser = new TushareChipResponseParser(objectMapper);
        this.requestSlots = new Semaphore(settings.tushareMaxConcurrency(), true);
    }

    public TushareChipFetchResult fetchPerformance(String symbol, LocalDate tradeDate) {
        String token = settings.tushareToken();
        if (!settings.tushareEnabled() || token == null || token.isBlank()) {
            return TushareChipFetchResult.failure("Tushare筹码认证未配置", null);
        }
        String tsCode = toTsCode(symbol);
        if (tsCode == null || tradeDate == null) {
            return TushareChipFetchResult.failure("Tushare筹码请求参数无效", null);
        }
        boolean acquired = false;
        try {
            acquired = requestSlots.tryAcquire(settings.tushareReadTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                return TushareChipFetchResult.failure("Tushare筹码认证并发排队超时", null);
            }
            TushareChipRequest request = new TushareChipRequest(
                    settings.tushareBaseUrl(),
                    requestBody(token, tsCode, tradeDate),
                    settings.tushareConnectTimeoutMs(),
                    settings.tushareReadTimeoutMs()
            );
            TushareHttpResponse response = transport.post(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return TushareChipFetchResult.failure(
                        "Tushare筹码请求失败: HTTP " + response.statusCode(), response.statusCode());
            }
            TushareChipResponseParser.ParseResult parsed = parser.parse(response.body());
            if (parsed.value().isEmpty()) {
                return TushareChipFetchResult.failure(parsed.errorSummary(), response.statusCode());
            }
            ExternalChipPerformance value = parsed.value().orElseThrow();
            if (!symbol.equals(value.symbol())) {
                return TushareChipFetchResult.failure("Tushare筹码股票代码不一致", response.statusCode());
            }
            return TushareChipFetchResult.success(value, response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return TushareChipFetchResult.failure("Tushare筹码认证排队被中断", null);
        } catch (Exception exception) {
            return TushareChipFetchResult.failure(
                    "Tushare筹码请求失败: " + exception.getClass().getSimpleName(), null);
        } finally {
            if (acquired) {
                requestSlots.release();
            }
        }
    }

    private String requestBody(String token, String tsCode, LocalDate tradeDate) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("api_name", "cyq_perf");
        root.put("token", token);
        ObjectNode params = root.putObject("params");
        params.put("ts_code", tsCode);
        params.put("trade_date", BASIC_DATE.format(tradeDate));
        root.put("fields", FIELDS);
        return objectMapper.writeValueAsString(root);
    }

    private String toTsCode(String symbol) {
        if (symbol == null || !symbol.matches("\\d{6}")) {
            return null;
        }
        if (symbol.startsWith("6")) {
            return symbol + ".SH";
        }
        if (symbol.startsWith("4") || symbol.startsWith("8") || symbol.startsWith("92")) {
            return symbol + ".BJ";
        }
        return symbol + ".SZ";
    }
}
