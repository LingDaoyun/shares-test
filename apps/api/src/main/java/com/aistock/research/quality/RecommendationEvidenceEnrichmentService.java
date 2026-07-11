package com.aistock.research.quality;

import com.aistock.research.committee.AgentCommitteeService;
import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.valuation.ValuationHistoryReport;
import com.aistock.research.valuation.ValuationHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class RecommendationEvidenceEnrichmentService {

    private static final long LIST_CACHE_MILLIS = 10 * 60 * 1000L;
    private static final long INTERACTIVE_WAIT_MILLIS = 45_000L;
    private static final String QUEUED_GAP = "深度证据复核已进入后台队列，刷新后显示结果。";

    private final ValuationHistoryService valuationHistoryService;
    private final AgentCommitteeService agentCommitteeService;
    private final Map<String, CachedBundle> listCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<RecommendationEvidenceBundle>> inFlight = new ConcurrentHashMap<>();

    public RecommendationEvidenceEnrichmentService() {
        this(null, null);
    }

    @Autowired
    public RecommendationEvidenceEnrichmentService(
            ValuationHistoryService valuationHistoryService,
            AgentCommitteeService agentCommitteeService
    ) {
        this.valuationHistoryService = valuationHistoryService;
        this.agentCommitteeService = agentCommitteeService;
    }

    public RecommendationEvidenceBundle enrich(String symbol) {
        List<String> dataGaps = new ArrayList<>();
        PeerValuationBrief peerValuation = peerValuation(symbol, dataGaps);
        AgentConsensusBrief agentConsensus = agentConsensus(symbol, dataGaps);
        return new RecommendationEvidenceBundle(symbol, peerValuation, agentConsensus, dataGaps);
    }

    public RecommendationEvidenceBundle enrichForList(String symbol) {
        String key = normalize(symbol);
        long now = System.currentTimeMillis();
        CachedBundle cached = listCache.get(key);
        if (cached != null && now < cached.expiresAt()) {
            return cached.bundle();
        }
        inFlight.computeIfAbsent(key, this::startBackgroundEnrichment);
        return RecommendationEvidenceBundle.unavailable(symbol, QUEUED_GAP);
    }

    public RecommendationEvidenceBundle enrichForInteractiveCard(String symbol) {
        String key = normalize(symbol);
        CachedBundle cached = liveCachedBundle(key);
        if (cached != null) {
            return cached.bundle();
        }
        CompletableFuture<RecommendationEvidenceBundle> running = inFlight.get(key);
        if (running != null) {
            try {
                return running.get(INTERACTIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return RecommendationEvidenceBundle.unavailable(symbol, "深度证据复核被中断，请稍后重试。");
            } catch (ExecutionException exception) {
                return RecommendationEvidenceBundle.unavailable(symbol, rootCauseMessage("深度证据复核失败", exception));
            } catch (TimeoutException exception) {
                return RecommendationEvidenceBundle.unavailable(symbol, "深度证据复核超时，请稍后重试。");
            }
        }
        cached = liveCachedBundle(key);
        if (cached != null) {
            return cached.bundle();
        }
        RecommendationEvidenceBundle bundle = enrich(key);
        return cache(key, bundle);
    }

    private PeerValuationBrief peerValuation(String symbol, List<String> dataGaps) {
        if (valuationHistoryService == null) {
            return PeerValuationBrief.unavailable("行业估值对比暂不可用：估值服务未接入");
        }
        try {
            ValuationHistoryReport report = valuationHistoryService.history(symbol, 10);
            if (report == null) {
                return PeerValuationBrief.unavailable("行业估值对比暂不可用：估值服务返回空结果");
            }
            if (report.dataGaps() != null) {
                dataGaps.addAll(report.dataGaps());
            }
            return PeerValuationBrief.from(report.peerValuation());
        } catch (RuntimeException exception) {
            return PeerValuationBrief.unavailable("行业估值对比暂不可用：" + exception.getMessage());
        }
    }

    private AgentConsensusBrief agentConsensus(String symbol, List<String> dataGaps) {
        if (agentCommitteeService == null) {
            return AgentConsensusBrief.unavailable("多 Agent 共识暂不可用：Agent 服务未接入");
        }
        try {
            AgentConsensusReport report = agentCommitteeService.discuss(symbol);
            return AgentConsensusBrief.from(report);
        } catch (RuntimeException exception) {
            return AgentConsensusBrief.unavailable("多 Agent 共识暂不可用：" + exception.getMessage());
        }
    }

    private String normalize(String symbol) {
        return symbol == null || symbol.isBlank() ? "UNKNOWN" : symbol.trim();
    }

    private CompletableFuture<RecommendationEvidenceBundle> startBackgroundEnrichment(String key) {
        return CompletableFuture.supplyAsync(() -> cache(key, enrich(key)))
                .whenComplete((bundle, throwable) -> inFlight.remove(key));
    }

    private RecommendationEvidenceBundle cache(String key, RecommendationEvidenceBundle bundle) {
        if (bundle != null) {
            listCache.put(key, new CachedBundle(bundle, System.currentTimeMillis() + LIST_CACHE_MILLIS));
        }
        return bundle;
    }

    private CachedBundle liveCachedBundle(String key) {
        long now = System.currentTimeMillis();
        CachedBundle cached = listCache.get(key);
        if (cached != null && now < cached.expiresAt()) {
            return cached;
        }
        if (cached != null) {
            listCache.remove(key);
        }
        return null;
    }

    private String rootCauseMessage(String prefix, ExecutionException exception) {
        Throwable cause = exception.getCause();
        String message = cause == null ? exception.getMessage() : cause.getMessage();
        if (message == null || message.isBlank()) {
            return prefix;
        }
        return prefix + "：" + message;
    }

    private record CachedBundle(RecommendationEvidenceBundle bundle, long expiresAt) {
    }
}
