package io.fluxora.platform.card;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.card.dto.*;
import io.fluxora.platform.card.mapper.CardMapper;
import io.fluxora.platform.credit.CreditTransaction;
import io.fluxora.platform.credit.mapper.BalanceAdjustResult;
import io.fluxora.platform.credit.mapper.CreditMapper;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import io.fluxora.platform.tenant.Tenant;
import io.fluxora.platform.tenant.TenantException;
import io.fluxora.platform.tenant.TenantMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 卡密业务服务。
 *
 * 核心不变量：
 *   - 完整明文绝不入库；plaintext 仅在 {@link #createBatch} 响应中返回一次。
 *   - 同一张卡密只能核销一次：通过单 SQL 原子 UPDATE…RETURNING + DB 部分唯一索引双层保护。
 *   - 卡密、余额变更、流水写入在同一事务（@Transactional）内完成；任一失败回滚。
 *   - 卡密只能由所属租户内的有效用户核销；跨租户核销在 service 层强制拒绝。
 *
 * 权限边界由 controller 的 @PreAuthorize 与 service 的 resolveCreateScope /
 * assertCanManageBatch 等方法双重防御。
 */
@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final CardMapper cardMapper;
    private final CreditMapper creditMapper;
    private final IdentityMapper identityMapper;
    private final TenantMapper tenantMapper;
    private final CardHashingService hashingService;
    private final CardCodeGenerator generator;
    private final int batchMaxCount;

    public CardService(CardMapper cardMapper,
                       CreditMapper creditMapper,
                       IdentityMapper identityMapper,
                       TenantMapper tenantMapper,
                       CardHashingService hashingService,
                       CardCodeGenerator generator,
                       @Value("${fluxora.security.card.batch-max-count:1000}") int batchMaxCount) {
        this.cardMapper = cardMapper;
        this.creditMapper = creditMapper;
        this.identityMapper = identityMapper;
        this.tenantMapper = tenantMapper;
        this.hashingService = hashingService;
        this.generator = generator;
        this.batchMaxCount = batchMaxCount;
    }

    // ============================================================
    // 批量创建批次
    // ============================================================

    /**
     * 一次提交多组面额，每组生成独立批次 + 对应张数的单张卡密。
     * 整个方法在单事务内执行；任一组失败整体回滚。
     *
     * 安全：
     *   - 校验金额 / 数量 / 上限；
     *   - 跨租户保护（资源属于哪个租户由 routeTenantId 决定）；
     *   - 完整明文 plaintexts 仅在响应中返回一次，绝不入库 / 不入日志；
     *   - DB 层 uk_card_hash 唯一索引兜底，碰撞极小概率出现时整事务回滚。
     */
    @Transactional
    public CreatedBatchResponse createBatch(UserAccount currentUser, Long routeTenantId,
                                            CreateBatchRequest req) {
        if (req == null || req.groups() == null || req.groups().isEmpty()) {
            throw new CardException(BusinessErrorCode.VALIDATION_ERROR);
        }
        Long tenantId = resolveCreateTenantId(currentUser, routeTenantId);
        assertTenantWritable(tenantId);

        List<CardBatchSummary> createdBatches = new ArrayList<>();
        List<String> plaintexts = new ArrayList<>();

        for (DenominationGroup g : req.groups()) {
            BigDecimal denomination;
            try {
                // 卡密面额来自 JSON 字符串，禁止 Number 精度截断和科学计数法表达。
                denomination = io.fluxora.platform.billing.CnyPrecisionPolicy.toDecimal(g.denomination());
            } catch (Exception ex) {
                throw new CardException(BusinessErrorCode.VALIDATION_ERROR, "卡密面额必须为正数");
            }
            if (denomination.compareTo(BigDecimal.ZERO) <= 0) {
                throw new CardException(BusinessErrorCode.CREDIT_AMOUNT_INVALID);
            }
            if (g.count() == null || g.count() <= 0) {
                throw new CardException(BusinessErrorCode.CARD_BATCH_COUNT_EXCEEDED);
            }
            if (g.count() > batchMaxCount) {
                throw new CardException(BusinessErrorCode.CARD_BATCH_COUNT_EXCEEDED);
            }
            if (g.expireAt() != null && g.expireAt().isBefore(Instant.now())) {
                throw new CardException(BusinessErrorCode.VALIDATION_ERROR);
            }

            // 创建批次
            RechargeCardBatch batch = new RechargeCardBatch();
            batch.setTenantId(tenantId);
            batch.setBatchCode(generateBatchCode());
            batch.setName(blankToNull(g.name()));
            batch.setDenomination(denomination);
            batch.setTotalCount(g.count());
            batch.setStatus("ENABLED");
            batch.setExpireAt(g.expireAt());
            batch.setCreatedById(currentUser.getId());
            cardMapper.insertBatch(batch);

            // 为该批次生成 count 张卡密
            for (int i = 0; i < g.count(); i++) {
                CardCodeGenerator.GeneratedCard gen = generator.generate();
                RechargeCard card = new RechargeCard();
                card.setTenantId(tenantId);
                card.setBatchId(batch.getId());
                card.setCardPrefix(gen.prefix());
                card.setCardHash(hashingService.hash(gen.plaintext()));
                card.setDenomination(denomination);
                card.setStatus("ENABLED");
                card.setExpireAt(g.expireAt());
                try {
                    cardMapper.insertCard(card);
                } catch (DuplicateKeyException dup) {
                    // 极小概率碰撞：重试一次；仍冲突让事务回滚
                    CardCodeGenerator.GeneratedCard retry = generator.generate();
                    card.setCardPrefix(retry.prefix());
                    card.setCardHash(hashingService.hash(retry.plaintext()));
                    cardMapper.insertCard(card);
                    gen = retry;
                }
                plaintexts.add(gen.plaintext());
            }

            createdBatches.add(loadBatchSummary(batch.getId(), tenantId));
            log.info("卡密批次已创建：tenantId={}, batchId={}, batchCode={}, denomination={}, count={}",
                    tenantId, batch.getId(), batch.getBatchCode(), denomination, g.count());
        }

        return new CreatedBatchResponse(createdBatches, plaintexts);
    }

    // ============================================================
    // 列表 / 详情 / 状态变更
    // ============================================================

    @Transactional(readOnly = true)
    public BatchPageResponse listBatches(UserAccount currentUser, BatchQuery q) {
        Long tenantId = resolveListTenantId(currentUser, q.tenantId());
        int page = q.pageOrDefault(), size = q.sizeOrDefault();
        int offset = (page - 1) * size;
        List<CardBatchSummary> items = cardMapper.findBatchSummaries(
                tenantId, blankToNull(q.keyword()), blankToNull(q.status()), optionalDenomination(q.denomination()), offset, size);
        long total = cardMapper.countBatches(tenantId, blankToNull(q.keyword()),
                blankToNull(q.status()), optionalDenomination(q.denomination()));
        return new BatchPageResponse(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public CardBatchSummary getBatchDetail(UserAccount currentUser, Long batchId) {
        RechargeCardBatch batch = cardMapper.findBatchById(batchId)
                .orElseThrow(() -> new CardException(BusinessErrorCode.CARD_BATCH_NOT_FOUND));
        assertCanReadBatch(currentUser, batch);
        return loadBatchSummary(batch.getId(), null);
    }

    @Transactional(readOnly = true)
    public CardPageResponse listCards(UserAccount currentUser, CardQuery q) {
        Long tenantId;
        if (q.batchId() != null) {
            RechargeCardBatch batch = cardMapper.findBatchById(q.batchId())
                    .orElseThrow(() -> new CardException(BusinessErrorCode.CARD_BATCH_NOT_FOUND));
            assertCanReadBatch(currentUser, batch);
            tenantId = batch.getTenantId();
        } else {
            tenantId = resolveListTenantId(currentUser, null);
        }
        int page = q.pageOrDefault(), size = q.sizeOrDefault();
        int offset = (page - 1) * size;
        List<CardSummary> items = cardMapper.findCardSummaries(
                q.batchId(), tenantId, blankToNull(q.prefixKeyword()),
                blankToNull(q.status()), offset, size);
        long total = cardMapper.countCards(q.batchId(), tenantId,
                blankToNull(q.prefixKeyword()), blankToNull(q.status()));
        return new CardPageResponse(items, total, page, size);
    }

    @Transactional
    public CardBatchSummary setBatchEnabled(UserAccount currentUser, Long batchId, boolean enabled) {
        RechargeCardBatch batch = cardMapper.findBatchById(batchId)
                .orElseThrow(() -> new CardException(BusinessErrorCode.CARD_BATCH_NOT_FOUND));
        assertCanManageBatch(currentUser, batch);
        cardMapper.setBatchStatus(batchId, enabled ? "ENABLED" : "DISABLED");
        return loadBatchSummary(batchId, null);
    }

    @Transactional
    public CardSummary setCardEnabled(UserAccount currentUser, Long cardId, boolean enabled) {
        CardSummary current = cardMapper.findCardSummary(cardId)
                .orElseThrow(() -> new CardException(BusinessErrorCode.CARD_NOT_FOUND));
        // 跨租户校验
        if (!isPlatformAdmin(currentUser)) {
            if (currentUser.getTenantId() == null
                    || !Objects.equals(currentUser.getTenantId(), current.tenantId())) {
                throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
        }
        // 终态卡密拒绝任何变更（核销 / 过期不可逆）
        if ("REDEEMED".equals(current.status()) || "EXPIRED".equals(current.status())) {
            throw new CardException(BusinessErrorCode.VALIDATION_ERROR,
                    "已核销或已过期卡密无法变更状态");
        }
        // 已停用启用 / 已启用停用
        cardMapper.setCardStatus(cardId, enabled ? "ENABLED" : "DISABLED",
                enabled ? null : "manual disable");
        return cardMapper.findCardSummary(cardId)
                .orElseThrow(() -> new CardException(BusinessErrorCode.CARD_NOT_FOUND));
    }

    // ============================================================
    // 核销（核心）
    // ============================================================

    /**
     * 用户核销卡密。
     *
     * 完整事务流程：
     *   1. 规范化用户输入 → 失败 CARD_CODE_INVALID
     *   2. HMAC 计算 → 按 hash 查询 → 不存在 CARD_NOT_FOUND
     *   3. 校验卡密 tenant == currentUser.tenant → CARD_CROSS_TENANT_REDEEM
     *   4. 校验卡密所属租户状态有效（在 assertTenantValidOrThrow）
     *   5. 卡密单独停用 → CARD_DISABLED；已核销 → CARD_ALREADY_REDEEMED；过期 → CARD_EXPIRED
     *   6. 原子 UPDATE：SET REDEEMED + bind user + check ENABLED + check 批次 ENABLED + check 未过期
     *      0 行 → 二次读 + 根据具体状态映射错误码
     *   7. 余额 += 面额：复用 creditMapper.adjustBalance（与 CARD_REDEEM 流水同事务）
     *   8. 写流水 source=CARD_REDEEM, card_id=cardId；DB 部分唯一索引兜底防重复
     *   9. 任一失败回滚整事务
     */
    @Transactional
    public RedeemedResponse redeem(UserAccount currentUser, String userInputCode) {
        // 1. 规范化
        String normalized = CardCodeNormalizer.normalize(userInputCode);
        if (normalized == null) {
            throw new CardException(BusinessErrorCode.CARD_CODE_INVALID);
        }
        // 2. 计算 hash → 查询
        String hash = hashingService.hash(normalized);
        RechargeCard card = cardMapper.findByHash(hash)
                .orElseThrow(() -> new CardException(BusinessErrorCode.CARD_NOT_FOUND));

        // 3. 跨租户校验
        if (currentUser.getTenantId() == null
                || !Objects.equals(currentUser.getTenantId(), card.getTenantId())) {
            throw new CardException(BusinessErrorCode.CARD_CROSS_TENANT_REDEEM);
        }
        // 4. 租户状态
        Tenant tenant = tenantMapper.findByIdIncludeDeleted(card.getTenantId()).orElse(null);
        if (tenant == null || tenant.isDeleted()) {
            throw new CardException(BusinessErrorCode.AUTH_TENANT_DELETED);
        }
        if (!tenant.isEnabled()) {
            throw new CardException(BusinessErrorCode.AUTH_TENANT_DISABLED);
        }
        if (tenant.getExpireAt() != null && tenant.getExpireAt().isBefore(Instant.now())) {
            throw new CardException(BusinessErrorCode.AUTH_TENANT_EXPIRED);
        }

        // 5. 状态预检（便于区分错误码；但最终原子 UPDATE 是真正的"防止并发"防线）
        if ("REDEEMED".equals(card.getStatus())) {
            throw new CardException(BusinessErrorCode.CARD_ALREADY_REDEEMED);
        }
        if ("DISABLED".equals(card.getStatus())) {
            throw new CardException(BusinessErrorCode.CARD_DISABLED);
        }
        if ("EXPIRED".equals(card.getStatus())) {
            throw new CardException(BusinessErrorCode.CARD_EXPIRED);
        }
        if (card.getExpireAt() != null && card.getExpireAt().isBefore(Instant.now())) {
            throw new CardException(BusinessErrorCode.CARD_EXPIRED);
        }
        // 批次状态
        RechargeCardBatch batch = cardMapper.findBatchById(card.getBatchId())
                .orElseThrow(() -> new CardException(BusinessErrorCode.CARD_BATCH_NOT_FOUND));
        if (!"ENABLED".equals(batch.getStatus())) {
            throw new CardException(BusinessErrorCode.CARD_BATCH_DISABLED);
        }

        // 6. 原子核销（行锁隐式持有；0 行表示已被其他请求抢先 / 批次刚被停用 / 刚过期）
        Instant now = Instant.now();
        BigDecimal denomination = cardMapper.atomicRedeem(card.getId(), currentUser.getId(), now);
        if (denomination == null) {
            // 重读卡密判断真正原因（并发 / 批次刚停用 / 刚过期）
            RechargeCard reread = cardMapper.findByHash(hash).orElse(card);
            if ("REDEEMED".equals(reread.getStatus())) {
                throw new CardException(BusinessErrorCode.CARD_ALREADY_REDEEMED);
            }
            if ("DISABLED".equals(reread.getStatus())) {
                throw new CardException(BusinessErrorCode.CARD_DISABLED);
            }
            // 默认归类到已核销（最常见的竞态）
            throw new CardException(BusinessErrorCode.CARD_ALREADY_REDEEMED);
        }

        // 7. 余额 += 面额（adjustBalance 同 SQL 原子；返回 balance_before / balance_after）
        BalanceAdjustResult adjust = creditMapper.adjustBalance(currentUser.getId(), denomination);
        if (adjust == null) {
            // 用户没有 credit_account（理论上 V5 已为所有 TENANT 用户创建；防御性处理）
            throw new CardException(BusinessErrorCode.CREDIT_ACCOUNT_NOT_FOUND);
        }

        // 8. 写卡密充值流水（source=CARD_REDEEM, card_id=card.id）
        //    DB 部分唯一索引 uk_credit_txn_card 兜底防重复入账：
        //    如果某个 bug 让此处被调用第二次，DuplicateKeyException 整事务回滚。
        CreditTransaction txn = new CreditTransaction();
        txn.setTenantId(currentUser.getTenantId());
        txn.setUserId(currentUser.getId());
        txn.setDirection("CREDIT");
        txn.setDelta(denomination);
        txn.setBalanceBefore(adjust.balanceBefore());
        txn.setBalanceAfter(adjust.balanceAfter());
        txn.setReason("卡密充值 " + card.getCardPrefix() + " · 批次 " + batch.getBatchCode());
        txn.setOperatorId(currentUser.getId());
        txn.setOperatorName(currentUser.getDisplayName() != null
                ? currentUser.getDisplayName() : currentUser.getUsername());
        // 通过扩展接口写入 source / card_id
        creditMapper.insertCardRedemptionTransaction(txn, card.getId());

        log.info("卡密已核销：cardId={}, prefix={}, userId={}, amount={}, balanceAfter={}",
                card.getId(), card.getCardPrefix(), currentUser.getId(),
                denomination, adjust.balanceAfter());

        return new RedeemedResponse(card.getId(), card.getCardPrefix(),
                denomination, adjust.balanceAfter(), now);
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    private Long resolveCreateTenantId(UserAccount currentUser, Long routeTenantId) {
        if (isPlatformAdmin(currentUser)) {
            if (routeTenantId == null) throw new CardException(BusinessErrorCode.VALIDATION_ERROR);
            return routeTenantId;
        }
        if (isTenantAdmin(currentUser)) {
            Long own = currentUser.getTenantId();
            if (own == null) throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            if (routeTenantId != null && !own.equals(routeTenantId)) {
                throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
            return own;
        }
        throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
    }

    /** 列表查询：平台管理员 tenantId=null 表示跨租户；租户管理员强制本租户 */
    private Long resolveListTenantId(UserAccount currentUser, Long requestedTenantId) {
        if (isPlatformAdmin(currentUser)) return requestedTenantId;  // null = 跨租户
        if (isTenantAdmin(currentUser)) {
            Long own = currentUser.getTenantId();
            if (own == null) throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            if (requestedTenantId != null && !own.equals(requestedTenantId)) {
                throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
            return own;
        }
        throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
    }

    private void assertCanReadBatch(UserAccount currentUser, RechargeCardBatch batch) {
        if (isPlatformAdmin(currentUser)) return;
        if (currentUser.getTenantId() != null
                && Objects.equals(currentUser.getTenantId(), batch.getTenantId())
                && isTenantAdmin(currentUser)) return;
        throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
    }

    private void assertCanManageBatch(UserAccount currentUser, RechargeCardBatch batch) {
        assertCanReadBatch(currentUser, batch);
        assertTenantWritable(batch.getTenantId());
    }

    private void assertTenantWritable(Long tenantId) {
        Tenant t = tenantMapper.findByIdIncludeDeleted(tenantId).orElse(null);
        if (t == null || t.isDeleted()) {
            throw new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!t.isEnabled()) {
            throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
        if (t.getExpireAt() != null && t.getExpireAt().isBefore(Instant.now())) {
            throw new CardException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
    }

    private CardBatchSummary loadBatchSummary(Long batchId, Long tenantIdFilter) {
        List<CardBatchSummary> rows = cardMapper.findBatchSummaries(
                null, null, null, null, 0, 1000);  // 简化：以 batchId 二次过滤
        for (CardBatchSummary s : rows) {
            if (Objects.equals(s.id(), batchId)
                    && (tenantIdFilter == null || Objects.equals(s.tenantId(), tenantIdFilter))) {
                return s;
            }
        }
        throw new CardException(BusinessErrorCode.CARD_BATCH_NOT_FOUND);
    }

    private String generateBatchCode() {
        String today = DATE_FMT.format(Instant.now());
        int rand = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "RCB-" + today + "-" + rand;
    }

    private boolean isPlatformAdmin(UserAccount user) {
        if (user == null || !"PLATFORM".equals(user.getScopeType())) return false;
        return identityMapper.findRolesByUserId(user.getId()).stream()
                .anyMatch(r -> PLATFORM_ADMIN_ROLE.equals(r.getCode()));
    }

    private boolean isTenantAdmin(UserAccount user) {
        if (user == null || !"TENANT".equals(user.getScopeType())) return false;
        return identityMapper.findRolesByUserId(user.getId()).stream()
                .anyMatch(r -> TENANT_ADMIN_ROLE.equals(r.getCode()));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static BigDecimal optionalDenomination(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return io.fluxora.platform.billing.CnyPrecisionPolicy.toDecimal(text.trim());
        } catch (IllegalArgumentException ex) {
            throw new CardException(BusinessErrorCode.VALIDATION_ERROR, "请输入有效的卡密面额");
        }
    }
}
