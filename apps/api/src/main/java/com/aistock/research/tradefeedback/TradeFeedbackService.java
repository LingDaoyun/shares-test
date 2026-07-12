package com.aistock.research.tradefeedback;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TradeFeedbackService {

    private static final Duration DEFAULT_FILL_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final TradeCaseRepository caseRepository;
    private final TradeFillRepository fillRepository;
    private final TradeFillRevisionRepository revisionRepository;
    private final TradeFillProjector fillProjector;
    private final RecommendationAttestationService attestationService;
    private final TradeLedgerCalculator ledgerCalculator;
    private final TransactionTemplate createCaseTransaction;
    private final Clock clock;
    private final Duration fillClockSkew;

    @Autowired
    public TradeFeedbackService(
            TradeCaseRepository caseRepository,
            TradeFillRepository fillRepository,
            TradeFillRevisionRepository revisionRepository,
            TradeFillProjector fillProjector,
            RecommendationAttestationService attestationService,
            TradeLedgerCalculator ledgerCalculator,
            PlatformTransactionManager transactionManager,
            @Value("${trade-feedback.fill-clock-skew:PT5M}") Duration fillClockSkew
    ) {
        this(
                caseRepository,
                fillRepository,
                revisionRepository,
                fillProjector,
                attestationService,
                ledgerCalculator,
                transactionManager,
                Clock.systemUTC(),
                fillClockSkew);
    }

    TradeFeedbackService(
            TradeCaseRepository caseRepository,
            TradeFillRepository fillRepository,
            TradeFillRevisionRepository revisionRepository,
            TradeFillProjector fillProjector,
            RecommendationAttestationService attestationService,
            TradeLedgerCalculator ledgerCalculator,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration fillClockSkew
    ) {
        this.caseRepository = caseRepository;
        this.fillRepository = fillRepository;
        this.revisionRepository = revisionRepository;
        this.fillProjector = fillProjector;
        this.attestationService = attestationService;
        this.ledgerCalculator = ledgerCalculator;
        this.clock = clock;
        if (fillClockSkew == null || fillClockSkew.isNegative()) {
            throw new IllegalArgumentException("成交时间容差不能为负数");
        }
        this.fillClockSkew = fillClockSkew;
        this.createCaseTransaction = new TransactionTemplate(transactionManager);
        this.createCaseTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TradeCaseEntity createCase(CreateTradeCaseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("复盘单请求不能为空");
        }
        String token = requiredText(request.attestationToken(), "推荐凭证不能为空");
        VerifiedRecommendationSnapshot snapshot = attestationService.require(token);
        String fingerprint = fingerprint(snapshot.attestationId());
        Optional<TradeCaseEntity> existing = caseRepository.findByRecommendationFingerprint(fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return createCaseTransaction.execute(status -> createOrFindCase(snapshot, fingerprint));
        } catch (DataIntegrityViolationException exception) {
            return caseRepository.findByRecommendationFingerprint(fingerprint)
                    .orElseThrow(() -> exception);
        }
    }

    private TradeCaseEntity createOrFindCase(
            VerifiedRecommendationSnapshot snapshot,
            String fingerprint
    ) {
        Optional<TradeCaseEntity> existing = caseRepository.findByRecommendationFingerprint(fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        TradeCaseEntity entity = TradeCaseEntity.verifiedPlanned(
                UUID.randomUUID().toString(),
                fingerprint,
                snapshot.attestationId(),
                snapshot.symbol(),
                snapshot.companyName(),
                snapshot.sourceModule(),
                snapshot.recommendationAction(),
                snapshot.recommendationScore(),
                snapshot.ruleVersion(),
                snapshot.recommendedPrice(),
                snapshot.recommendedAt(),
                snapshot.recommendationPayloadJson(),
                now);
        return caseRepository.saveAndFlush(entity);
    }

    @Transactional
    public TradeCaseEntity addFill(String caseId, UpsertTradeFillRequest request) {
        TradeCaseEntity tradeCase = requireCaseForUpdate(caseId);
        ensureFillAllowed(tradeCase);
        validateFillRequest(tradeCase, request);

        Instant now = clock.instant();
        TradeFillEntity newFill = TradeFillEntity.create(
                UUID.randomUUID().toString(),
                tradeCase.getCaseId(),
                request.side().name(),
                request.executedAt(),
                request.price(),
                request.quantity(),
                now);
        List<TradeFillSnapshot> prospective = new ArrayList<>(activeFills(tradeCase.getCaseId()));
        prospective.add(snapshot(newFill));
        TradeLedgerSummary ledger = calculateLedger(prospective, null);

        fillRepository.save(newFill);
        return saveStatus(tradeCase, ledger, prospective.size(), now);
    }

    @Transactional
    public TradeCaseEntity updateFill(String caseId, String fillId, UpsertTradeFillRequest request) {
        TradeCaseEntity tradeCase = requireCaseForUpdate(caseId);
        ensureFillAllowed(tradeCase);
        validateFillRequest(tradeCase, request);
        List<TradeFillSnapshot> current = activeFills(tradeCase.getCaseId());
        TradeFillSnapshot existing = requireActiveFill(current, fillId);

        Instant now = clock.instant();
        TradeFillSnapshot corrected = new TradeFillSnapshot(
                existing.fillId(),
                existing.caseId(),
                request.side().name(),
                request.executedAt(),
                request.price(),
                request.quantity(),
                existing.createdAt(),
                now);
        List<TradeFillSnapshot> prospective = current.stream()
                .map(fill -> fill.fillId().equals(existing.fillId()) ? corrected : fill)
                .toList();
        TradeLedgerSummary ledger = calculateLedger(prospective, null);

        revisionRepository.save(TradeFillRevisionEntity.correction(
                UUID.randomUUID().toString(),
                existing.fillId(),
                tradeCase.getCaseId(),
                corrected.side(),
                corrected.executedAt(),
                corrected.price(),
                corrected.quantity(),
                now));
        return saveStatus(tradeCase, ledger, prospective.size(), now);
    }

    @Transactional
    public TradeCaseEntity deleteFill(String caseId, String fillId) {
        TradeCaseEntity tradeCase = requireCaseForUpdate(caseId);
        ensureFillAllowed(tradeCase);
        List<TradeFillSnapshot> current = activeFills(tradeCase.getCaseId());
        TradeFillSnapshot existing = requireActiveFill(current, fillId);
        List<TradeFillSnapshot> prospective = current.stream()
                .filter(fill -> !fill.fillId().equals(existing.fillId()))
                .toList();
        TradeLedgerSummary ledger = calculateLedger(prospective, null);

        Instant now = clock.instant();
        revisionRepository.save(TradeFillRevisionEntity.voided(
                UUID.randomUUID().toString(),
                existing.fillId(),
                tradeCase.getCaseId(),
                existing.side(),
                existing.executedAt(),
                existing.price(),
                existing.quantity(),
                now));
        return saveStatus(tradeCase, ledger, prospective.size(), now);
    }

    @Transactional
    public TradeCaseEntity cancelCase(String caseId) {
        TradeCaseEntity tradeCase = requireCaseForUpdate(caseId);
        if (!TradeCaseStatus.PLANNED.name().equals(tradeCase.getStatus())
                || !activeFills(tradeCase.getCaseId()).isEmpty()) {
            throw new TradeFeedbackConflictException("只有尚未成交的计划可以取消");
        }
        tradeCase.updateStatus(TradeCaseStatus.CANCELLED.name(), clock.instant());
        return caseRepository.save(tradeCase);
    }

    @Transactional(readOnly = true)
    public List<TradeCaseEntity> listCases() {
        return listCases(null, null, null, null, DEFAULT_PAGE_SIZE);
    }

    @Transactional(readOnly = true)
    public List<TradeCaseEntity> listCases(
            String status,
            String symbol,
            Instant beforeCreatedAt,
            String beforeCaseId,
            int limit
    ) {
        boolean hasCursorTime = beforeCreatedAt != null;
        boolean hasCursorId = beforeCaseId != null && !beforeCaseId.isBlank();
        if (hasCursorTime != hasCursorId) {
            throw new IllegalArgumentException("分页游标必须同时包含 beforeCreatedAt 和 beforeCaseId");
        }
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        Specification<TradeCaseEntity> filters = (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                predicates.add(builder.equal(
                        builder.upper(root.get("status")), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (symbol != null && !symbol.isBlank()) {
                predicates.add(builder.equal(root.get("symbol"), symbol.trim()));
            }
            if (beforeCreatedAt != null) {
                String cursorCaseId = beforeCaseId.trim();
                predicates.add(builder.or(
                        builder.lessThan(root.get("createdAt"), beforeCreatedAt),
                        builder.and(
                                builder.equal(root.get("createdAt"), beforeCreatedAt),
                                builder.lessThan(root.get("caseId"), cursorCaseId))));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        PageRequest page = PageRequest.of(
                0,
                boundedLimit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("caseId")));
        return caseRepository.findAll(filters, page).getContent();
    }

    @Transactional(readOnly = true)
    public TradeCaseEntity getCase(String caseId) {
        return requireCase(caseId);
    }

    @Transactional(readOnly = true)
    public List<TradeFillSnapshot> fills(String caseId) {
        TradeCaseEntity tradeCase = requireCase(caseId);
        return activeFills(tradeCase.getCaseId());
    }

    @Transactional(readOnly = true)
    public TradeLedgerSummary ledger(String caseId, BigDecimal latestPrice) {
        if (latestPrice != null && latestPrice.signum() <= 0) {
            throw new IllegalArgumentException("最新价格必须大于零");
        }
        return calculateLedger(fills(caseId), latestPrice);
    }

    @Transactional(readOnly = true)
    public Map<String, TradeLedgerSummary> ledgers(
            Collection<String> caseIds,
            Map<String, BigDecimal> latestPriceByCaseId
    ) {
        List<String> normalizedCaseIds = caseIds == null ? List.of() : caseIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(caseId -> !caseId.isEmpty())
                .distinct()
                .toList();
        if (normalizedCaseIds.isEmpty()) {
            return Map.of();
        }
        List<TradeFillEntity> originals = fillRepository
                .findByCaseIdInOrderByCaseIdAscExecutedAtAscCreatedAtAscFillIdAsc(normalizedCaseIds);
        List<TradeFillRevisionEntity> revisions = revisionRepository
                .findByCaseIdInOrderByCaseIdAscCreatedAtAscRevisionIdAsc(normalizedCaseIds);
        Map<String, List<TradeFillEntity>> originalsByCase = originals.stream()
                .collect(Collectors.groupingBy(TradeFillEntity::getCaseId));
        Map<String, List<TradeFillRevisionEntity>> revisionsByCase = revisions.stream()
                .collect(Collectors.groupingBy(TradeFillRevisionEntity::getCaseId));
        Map<String, BigDecimal> prices = latestPriceByCaseId == null ? Map.of() : latestPriceByCaseId;
        Map<String, TradeLedgerSummary> result = new LinkedHashMap<>();
        for (String caseId : normalizedCaseIds) {
            List<TradeFillSnapshot> active = fillProjector.project(
                    originalsByCase.getOrDefault(caseId, List.of()),
                    revisionsByCase.getOrDefault(caseId, List.of()));
            result.put(caseId, calculateLedger(active, prices.get(caseId)));
        }
        return Map.copyOf(result);
    }

    private List<TradeFillSnapshot> activeFills(String caseId) {
        return fillProjector.project(
                fillRepository.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(caseId),
                revisionRepository.findByCaseIdOrderByCreatedAtAscRevisionIdAsc(caseId));
    }

    private TradeLedgerSummary calculateLedger(List<TradeFillSnapshot> fills, BigDecimal latestPrice) {
        return ledgerCalculator.calculate(fills.stream().map(TradeFillSnapshot::toLedgerFill).toList(), latestPrice);
    }

    private TradeCaseEntity saveStatus(
            TradeCaseEntity tradeCase,
            TradeLedgerSummary ledger,
            int fillCount,
            Instant now
    ) {
        TradeCaseStatus status = fillCount == 0
                ? TradeCaseStatus.PLANNED
                : ledger.positionQuantity() == 0 ? TradeCaseStatus.CLOSED : TradeCaseStatus.HOLDING;
        tradeCase.updateStatus(status.name(), now);
        return caseRepository.save(tradeCase);
    }

    private TradeCaseEntity requireCase(String caseId) {
        String normalizedCaseId = requiredText(caseId, "复盘单 ID 不能为空");
        return caseRepository.findById(normalizedCaseId)
                .orElseThrow(() -> new TradeFeedbackNotFoundException("复盘单不存在"));
    }

    private TradeCaseEntity requireCaseForUpdate(String caseId) {
        String normalizedCaseId = requiredText(caseId, "复盘单 ID 不能为空");
        return caseRepository.findByIdForUpdate(normalizedCaseId)
                .orElseThrow(() -> new TradeFeedbackNotFoundException("复盘单不存在"));
    }

    private TradeFillSnapshot requireActiveFill(List<TradeFillSnapshot> fills, String fillId) {
        String normalizedFillId = requiredText(fillId, "成交 ID 不能为空");
        return fills.stream()
                .filter(fill -> normalizedFillId.equals(fill.fillId()))
                .findFirst()
                .orElseThrow(() -> new TradeFeedbackNotFoundException("成交记录不存在"));
    }

    private void ensureFillAllowed(TradeCaseEntity tradeCase) {
        if (TradeCaseStatus.CANCELLED.name().equals(tradeCase.getStatus())) {
            throw new TradeFeedbackConflictException("已取消的复盘单不能录入成交");
        }
    }

    private void validateFillRequest(TradeCaseEntity tradeCase, UpsertTradeFillRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("成交请求不能为空");
        }
        if (request.side() == null) {
            throw new IllegalArgumentException("成交方向必须为 BUY 或 SELL");
        }
        if (request.executedAt() == null) {
            throw new IllegalArgumentException("成交时间不能为空");
        }
        if (request.executedAt().isBefore(tradeCase.getRecommendedAt())) {
            throw new IllegalArgumentException("成交时间不能早于推荐时间");
        }
        if (request.executedAt().isAfter(clock.instant().plus(fillClockSkew))) {
            throw new IllegalArgumentException("成交时间不能超过当前时间允许的未来容差");
        }
        if (request.price() == null || request.price().signum() <= 0) {
            throw new IllegalArgumentException("成交价格必须大于零");
        }
        if (request.quantity() <= 0) {
            throw new IllegalArgumentException("成交股数必须为正整数");
        }
    }

    private TradeFillSnapshot snapshot(TradeFillEntity fill) {
        return new TradeFillSnapshot(
                fill.getFillId(),
                fill.getCaseId(),
                fill.getSide(),
                fill.getExecutedAt(),
                fill.getPrice(),
                fill.getQuantity(),
                fill.getCreatedAt(),
                fill.getUpdatedAt());
    }

    private String fingerprint(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
