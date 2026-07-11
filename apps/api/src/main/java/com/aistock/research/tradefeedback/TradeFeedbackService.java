package com.aistock.research.tradefeedback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TradeFeedbackService {

    private final TradeCaseRepository caseRepository;
    private final TradeFillRepository fillRepository;
    private final ObjectMapper objectMapper;
    private final TradeLedgerCalculator ledgerCalculator;
    private final TransactionTemplate createCaseTransaction;

    public TradeFeedbackService(
            TradeCaseRepository caseRepository,
            TradeFillRepository fillRepository,
            ObjectMapper objectMapper,
            TradeLedgerCalculator ledgerCalculator,
            PlatformTransactionManager transactionManager
    ) {
        this.caseRepository = caseRepository;
        this.fillRepository = fillRepository;
        this.objectMapper = objectMapper;
        this.ledgerCalculator = ledgerCalculator;
        this.createCaseTransaction = new TransactionTemplate(transactionManager);
        this.createCaseTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TradeCaseEntity createCase(CreateTradeCaseRequest request) {
        ValidatedCaseRequest validated = validateCaseRequest(request);
        try {
            return createCaseTransaction.execute(status -> createOrFindCase(validated));
        } catch (DataIntegrityViolationException exception) {
            return caseRepository.findByRecommendationFingerprint(validated.fingerprint())
                    .orElseThrow(() -> exception);
        }
    }

    private TradeCaseEntity createOrFindCase(ValidatedCaseRequest validated) {
        Optional<TradeCaseEntity> existing = caseRepository.findByRecommendationFingerprint(validated.fingerprint());
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = Instant.now();
        TradeCaseEntity entity = TradeCaseEntity.planned(
                UUID.randomUUID().toString(),
                validated.fingerprint(),
                validated.decisionId(),
                validated.symbol(),
                validated.companyName(),
                validated.sourceModule(),
                validated.recommendationAction(),
                validated.recommendationScore(),
                validated.ruleVersion(),
                validated.recommendedPrice(),
                validated.recommendedAt(),
                serializePayload(validated.recommendationPayload()),
                now
        );
        return caseRepository.saveAndFlush(entity);
    }

    @Transactional
    public TradeCaseEntity addFill(String caseId, UpsertTradeFillRequest request) {
        TradeCaseEntity tradeCase = requireCaseForUpdate(caseId);
        ensureFillAllowed(tradeCase);
        validateFillRequest(tradeCase, request);

        Instant now = Instant.now();
        TradeFillEntity newFill = TradeFillEntity.create(
                UUID.randomUUID().toString(),
                tradeCase.getCaseId(),
                request.side().name(),
                request.executedAt(),
                request.price(),
                request.quantity(),
                now
        );
        List<TradeFillEntity> prospective = new ArrayList<>(fillRepository.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId()));
        prospective.add(newFill);
        TradeLedgerSummary ledger = ledgerCalculator.calculate(prospective, null);

        fillRepository.save(newFill);
        return saveStatus(tradeCase, ledger, prospective.size(), now);
    }

    @Transactional
    public TradeCaseEntity updateFill(String caseId, String fillId, UpsertTradeFillRequest request) {
        TradeCaseEntity tradeCase = requireCaseForUpdate(caseId);
        ensureFillAllowed(tradeCase);
        validateFillRequest(tradeCase, request);
        TradeFillEntity existing = requireFill(tradeCase, fillId);

        List<TradeFillEntity> current = fillRepository.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId());
        List<TradeFillEntity> prospective = current.stream()
                .map(fill -> fill.getFillId().equals(existing.getFillId())
                        ? TradeFillEntity.create(
                        fill.getFillId(),
                        fill.getCaseId(),
                        request.side().name(),
                        request.executedAt(),
                        request.price(),
                        request.quantity(),
                        fill.getCreatedAt())
                        : fill)
                .toList();
        TradeLedgerSummary ledger = ledgerCalculator.calculate(prospective, null);

        Instant now = Instant.now();
        existing.revise(request.side().name(), request.executedAt(), request.price(), request.quantity(), now);
        fillRepository.save(existing);
        return saveStatus(tradeCase, ledger, prospective.size(), now);
    }

    @Transactional
    public TradeCaseEntity deleteFill(String caseId, String fillId) {
        TradeCaseEntity tradeCase = requireCaseForUpdate(caseId);
        ensureFillAllowed(tradeCase);
        TradeFillEntity existing = requireFill(tradeCase, fillId);

        List<TradeFillEntity> prospective = fillRepository
                .findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId())
                .stream()
                .filter(fill -> !fill.getFillId().equals(existing.getFillId()))
                .toList();
        TradeLedgerSummary ledger = ledgerCalculator.calculate(prospective, null);

        Instant now = Instant.now();
        fillRepository.delete(existing);
        return saveStatus(tradeCase, ledger, prospective.size(), now);
    }

    @Transactional
    public TradeCaseEntity cancelCase(String caseId) {
        TradeCaseEntity tradeCase = requireCaseForUpdate(caseId);
        if (!TradeCaseStatus.PLANNED.name().equals(tradeCase.getStatus())
                || !fillRepository.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId()).isEmpty()) {
            throw new TradeFeedbackConflictException("只有尚未成交的计划可以取消");
        }
        tradeCase.updateStatus(TradeCaseStatus.CANCELLED.name(), Instant.now());
        return caseRepository.save(tradeCase);
    }

    @Transactional(readOnly = true)
    public List<TradeCaseEntity> listCases() {
        return caseRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public TradeCaseEntity getCase(String caseId) {
        return requireCase(caseId);
    }

    @Transactional(readOnly = true)
    public List<TradeFillEntity> fills(String caseId) {
        TradeCaseEntity tradeCase = requireCase(caseId);
        return fillRepository.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(tradeCase.getCaseId());
    }

    @Transactional(readOnly = true)
    public TradeLedgerSummary ledger(String caseId, BigDecimal latestPrice) {
        if (latestPrice != null && latestPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("最新价格必须大于零");
        }
        return ledgerCalculator.calculate(fills(caseId), latestPrice);
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

    private TradeFillEntity requireFill(TradeCaseEntity tradeCase, String fillId) {
        TradeFillEntity fill = fillRepository.findById(requiredText(fillId, "成交 ID 不能为空"))
                .orElseThrow(() -> new TradeFeedbackNotFoundException("成交记录不存在"));
        if (!tradeCase.getCaseId().equals(fill.getCaseId())) {
            throw new TradeFeedbackNotFoundException("成交记录不存在");
        }
        return fill;
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
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("成交价格必须大于零");
        }
        if (request.quantity() <= 0) {
            throw new IllegalArgumentException("成交股数必须为正整数");
        }
    }

    private ValidatedCaseRequest validateCaseRequest(CreateTradeCaseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("复盘单请求不能为空");
        }
        String symbol = requiredText(request.symbol(), "股票代码不能为空");
        if (!symbol.matches("\\d{6}")) {
            throw new IllegalArgumentException("股票代码必须是 6 位数字");
        }
        String companyName = requiredText(request.companyName(), "公司名称不能为空");
        String sourceModule = requiredText(request.sourceModule(), "来源模块不能为空");
        String recommendationAction = requiredText(request.recommendationAction(), "推荐动作不能为空");
        String ruleVersion = requiredText(request.ruleVersion(), "规则版本不能为空");
        if (request.recommendedPrice() == null || request.recommendedPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("推荐价格必须大于零");
        }
        if (request.recommendedAt() == null) {
            throw new IllegalArgumentException("推荐时间不能为空");
        }
        String decisionId = optionalText(request.decisionId());
        String fingerprint = fingerprint(symbol, sourceModule, ruleVersion, request.recommendedAt(), decisionId);
        return new ValidatedCaseRequest(
                decisionId,
                symbol,
                companyName,
                sourceModule,
                recommendationAction,
                request.recommendationScore(),
                ruleVersion,
                request.recommendedPrice(),
                request.recommendedAt(),
                request.recommendationPayload(),
                fingerprint
        );
    }

    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("推荐载荷无法序列化", exception);
        }
    }

    private String fingerprint(String symbol, String sourceModule, String ruleVersion, Instant recommendedAt, String decisionId) {
        String value = String.join(
                "|",
                normalizedKey(symbol),
                normalizedKey(sourceModule),
                normalizedKey(ruleVersion),
                recommendedAt.toString(),
                decisionId == null ? "" : normalizedKey(decisionId)
        );
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String requiredText(String value, String message) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizedKey(String value) {
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private record ValidatedCaseRequest(
            String decisionId,
            String symbol,
            String companyName,
            String sourceModule,
            String recommendationAction,
            BigDecimal recommendationScore,
            String ruleVersion,
            BigDecimal recommendedPrice,
            Instant recommendedAt,
            Object recommendationPayload,
            String fingerprint
    ) {
    }
}
