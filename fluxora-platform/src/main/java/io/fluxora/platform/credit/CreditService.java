package io.fluxora.platform.credit;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.credit.dto.AdjustCreditRequest;
import io.fluxora.platform.credit.dto.AdjustableUserOption;
import io.fluxora.platform.credit.dto.CreditAccountView;
import io.fluxora.platform.credit.dto.CreditStats;
import io.fluxora.platform.credit.dto.CreditTransactionPageResponse;
import io.fluxora.platform.credit.dto.CreditTransactionQuery;
import io.fluxora.platform.credit.dto.CreditTransactionView;
import io.fluxora.platform.credit.mapper.BalanceAdjustResult;
import io.fluxora.platform.credit.mapper.CreditMapper;
import io.fluxora.platform.credit.mapper.CreditTransactionRow;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import io.fluxora.platform.tenant.Tenant;
import io.fluxora.platform.tenant.TenantException;
import io.fluxora.platform.tenant.TenantMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户额度账户与流水服务。
 *
 * 核心不变量：
 *   - 余额非负：由 mapper 的原子 UPDATE…WHERE balance + delta >= 0 强制；
 *     单 SQL 同时保证并发安全（Postgres 行锁隐式持有），无须 SELECT FOR UPDATE。
 *   - 余额变更与流水写入同事务：本类的 {@link #adjust} 加 @Transactional，
 *     mapper.adjustBalance 与 mapper.insertTransaction 在同连接执行，任一失败回滚。
 *   - 流水不可篡改：mapper 不提供 update / delete 接口；仅 INSERT。
 *   - 跨租户隔离：所有按 targetUserId 操作前都校验当前用户能操作该目标。
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private final CreditMapper creditMapper;
    private final IdentityMapper identityMapper;
    private final TenantMapper tenantMapper;

    public CreditService(CreditMapper creditMapper, IdentityMapper identityMapper, TenantMapper tenantMapper) {
        this.creditMapper = creditMapper;
        this.identityMapper = identityMapper;
        this.tenantMapper = tenantMapper;
    }

    // ============================================================
    // 账户查询
    // ============================================================

    /** 当前用户自己的账户；PLATFORM 用户没有账户，返回 CREDIT_ACCOUNT_NOT_FOUND */
    @Transactional(readOnly = true)
    public CreditAccountView getMyAccount(UserAccount currentUser) {
        if (!"TENANT".equals(currentUser.getScopeType()) || currentUser.getTenantId() == null) {
            throw new CreditException(BusinessErrorCode.CREDIT_ACCOUNT_NOT_FOUND);
        }
        return creditMapper.findAccountByUserId(currentUser.getId())
                .orElseThrow(() -> new CreditException(BusinessErrorCode.CREDIT_ACCOUNT_NOT_FOUND));
    }

    /** 管理员视角：查询指定用户的账户；service 内做跨租户与权限校验 */
    @Transactional(readOnly = true)
    public CreditAccountView getUserAccount(UserAccount currentUser, Long targetUserId) {
        assertCanReadUserCredit(currentUser, targetUserId);
        return creditMapper.findAccountByUserId(targetUserId)
                .orElseThrow(() -> new CreditException(BusinessErrorCode.CREDIT_ACCOUNT_NOT_FOUND));
    }

    // ============================================================
    // 流水
    // ============================================================

    @Transactional(readOnly = true)
    public CreditTransactionPageResponse listTransactions(UserAccount currentUser,
                                                          CreditScope scope, CreditTransactionQuery q) {
        ScopeFilter f = resolveScope(currentUser, scope, q);
        int page = q.pageOrDefault();
        int size = q.sizeOrDefault();
        int offset = (page - 1) * size;

        List<CreditTransactionRow> rows = creditMapper.findTransactionRows(
                f.tenantId, f.userId, blankToNull(q.keyword()), blankToNull(q.direction()),
                q.parseFrom(), q.parseTo(), offset, size);
        long total = creditMapper.countTransactions(
                f.tenantId, f.userId, blankToNull(q.keyword()), blankToNull(q.direction()),
                q.parseFrom(), q.parseTo());

        List<CreditTransactionView> items = rows.stream().map(this::toView).toList();
        return new CreditTransactionPageResponse(items, total, page, size);
    }

    // ============================================================
    // 调整额度（核心：原子 UPDATE…RETURNING + 流水同事务写入）
    // ============================================================

    /**
     * 增加或扣减额度，并写入一条流水。整个方法在单事务内执行；任一步骤失败回滚。
     *
     * 安全要点：
     *   1. 入参校验：direction ∈ {CREDIT, DEBIT}；amount > 0；reason 非空 ≤256；
     *   2. 跨租户校验：assertCanAdjustUserCredit；
     *   3. 目标用户租户必须可写（未删除、enabled、未过期）；
     *   4. mapper.adjustBalance 单 SQL 完成 (a) 取行锁 (b) 校验 balance + delta >= 0
     *      (c) 写入新余额 (d) RETURNING before/after；
     *   5. 返回 null 视为余额不足，抛 CREDIT_INSUFFICIENT，事务回滚；
     *   6. 成功则插入流水（balance_before/after 来自同一 SQL 的 RETURNING，
     *      保证审计连贯，没有并发窗口）。
     */
    @Transactional
    public CreditTransactionView adjust(UserAccount currentUser, Long targetUserId, AdjustCreditRequest req) {
        BigDecimal amount;
        try {
            // 额度请求统一从字符串转换，拒绝科学计数法并固定为八位原子精度。
            amount = io.fluxora.platform.billing.CnyPrecisionPolicy.toDecimal(req == null ? null : req.amount());
        } catch (Exception ex) {
            throw new CreditException(BusinessErrorCode.CREDIT_AMOUNT_INVALID, "调整金额必须为正数");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CreditException(BusinessErrorCode.CREDIT_AMOUNT_INVALID);
        }
        if (req.direction() == null
                || (!"CREDIT".equals(req.direction()) && !"DEBIT".equals(req.direction()))) {
            throw new CreditException(BusinessErrorCode.VALIDATION_ERROR);
        }
        if (req.reason() == null || req.reason().isBlank() || req.reason().length() > 256) {
            throw new CreditException(BusinessErrorCode.CREDIT_REASON_REQUIRED);
        }
        assertCanAdjustUserCredit(currentUser, targetUserId);

        UserAccount target = identityMapper.findById(targetUserId)
                .orElseThrow(() -> new CreditException(BusinessErrorCode.RESOURCE_NOT_FOUND));
        if (!"TENANT".equals(target.getScopeType()) || target.getTenantId() == null) {
            throw new CreditException(BusinessErrorCode.CREDIT_ACCOUNT_NOT_FOUND);
        }
        assertTenantWritable(target.getTenantId());

        // 提前校验账户存在，避免 mapper.adjustBalance 返回 null 时无法区分
        // "余额不足" 与 "账户不存在"——后者本身就是配置错误
        creditMapper.findAccountByUserId(targetUserId)
                .orElseThrow(() -> new CreditException(BusinessErrorCode.CREDIT_ACCOUNT_NOT_FOUND));

        BigDecimal delta = "CREDIT".equals(req.direction()) ? amount : amount.negate();
        BalanceAdjustResult r = creditMapper.adjustBalance(targetUserId, delta);
        if (r == null) {
            throw new CreditException(BusinessErrorCode.CREDIT_INSUFFICIENT);
        }

        CreditTransaction txn = new CreditTransaction();
        txn.setTenantId(target.getTenantId());
        txn.setUserId(targetUserId);
        txn.setDirection(req.direction());
        txn.setDelta(amount);
        txn.setBalanceBefore(r.balanceBefore());
        txn.setBalanceAfter(r.balanceAfter());
        txn.setFrozenBalanceBefore(r.frozenBalanceBefore());
        txn.setFrozenBalanceAfter(r.frozenBalanceAfter());
        txn.setReason(req.reason().trim());
        txn.setOperatorId(currentUser.getId());
        txn.setOperatorName(currentUser.getDisplayName() != null
                ? currentUser.getDisplayName() : currentUser.getUsername());
        creditMapper.insertTransaction(txn);

        log.info("额度已调整：userId={}, direction={}, amount={}, balanceBefore={}, balanceAfter={}, operatorId={}",
                targetUserId, req.direction(), amount,
                r.balanceBefore(), r.balanceAfter(), currentUser.getId());

        // 回查刚插入的流水（按时间倒序第一条 = 当前事务写入的那条）
        List<CreditTransactionRow> rows = creditMapper.findTransactionRows(
                target.getTenantId(), targetUserId, null, null, null, null, 0, 1);
        return toView(rows.get(0));
    }

    // ============================================================
    // 可调整用户列表 / 聚合统计
    // ============================================================

    @Transactional(readOnly = true)
    public List<AdjustableUserOption> listAdjustableUsers(UserAccount currentUser,
                                                          Long tenantId, String keyword) {
        Long resolvedTenant;
        if (isPlatformAdmin(currentUser)) {
            resolvedTenant = tenantId;
        } else if (isTenantAdmin(currentUser)) {
            resolvedTenant = currentUser.getTenantId();
            if (resolvedTenant == null) {
                throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
        } else {
            throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
        return creditMapper.findAdjustableUsers(resolvedTenant, blankToNull(keyword), 50);
    }

    @Transactional(readOnly = true)
    public CreditStats getStats(UserAccount currentUser, CreditScope scope, Long explicitTenantId) {
        CreditTransactionQuery q = new CreditTransactionQuery(
                null, null, null, explicitTenantId, null, null, null, null);
        ScopeFilter f = resolveScope(currentUser, scope, q);
        return creditMapper.stats(f.tenantId, f.userId);
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    public enum CreditScope { SELF, TENANT, PLATFORM }

    private record ScopeFilter(Long tenantId, Long userId) {}

    private ScopeFilter resolveScope(UserAccount currentUser, CreditScope scope, CreditTransactionQuery q) {
        switch (scope) {
            case SELF -> {
                if (!"TENANT".equals(currentUser.getScopeType()) || currentUser.getTenantId() == null) {
                    throw new CreditException(BusinessErrorCode.CREDIT_ACCOUNT_NOT_FOUND);
                }
                return new ScopeFilter(currentUser.getTenantId(), currentUser.getId());
            }
            case TENANT -> {
                Long tenantId = q.tenantId();
                if (tenantId == null) throw new CreditException(BusinessErrorCode.VALIDATION_ERROR);
                if (!isPlatformAdmin(currentUser)) {
                    if (currentUser.getTenantId() == null
                            || !Objects.equals(currentUser.getTenantId(), tenantId)) {
                        throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
                    }
                    if (!isTenantAdmin(currentUser)) {
                        throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
                    }
                }
                return new ScopeFilter(tenantId, q.userId());
            }
            case PLATFORM -> {
                if (!isPlatformAdmin(currentUser)) {
                    throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
                }
                return new ScopeFilter(q.tenantId(), q.userId());
            }
            default -> throw new CreditException(BusinessErrorCode.ACCESS_DENIED);
        }
    }

    private void assertCanReadUserCredit(UserAccount currentUser, Long targetUserId) {
        if (isPlatformAdmin(currentUser)) return;
        UserAccount target = identityMapper.findById(targetUserId)
                .orElseThrow(() -> new CreditException(BusinessErrorCode.RESOURCE_NOT_FOUND));
        if (isTenantAdmin(currentUser)) {
            if (currentUser.getTenantId() != null
                    && Objects.equals(currentUser.getTenantId(), target.getTenantId())) return;
            throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
        // 普通用户只能读自己
        if (Objects.equals(currentUser.getId(), targetUserId)) return;
        throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
    }

    private void assertCanAdjustUserCredit(UserAccount currentUser, Long targetUserId) {
        if (isPlatformAdmin(currentUser)) return;
        UserAccount target = identityMapper.findById(targetUserId)
                .orElseThrow(() -> new CreditException(BusinessErrorCode.RESOURCE_NOT_FOUND));
        if (isTenantAdmin(currentUser)) {
            if (currentUser.getTenantId() != null
                    && Objects.equals(currentUser.getTenantId(), target.getTenantId())) return;
            throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
        // 普通用户不可调整自己的额度
        throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
    }

    private void assertTenantWritable(Long tenantId) {
        Tenant t = tenantMapper.findByIdIncludeDeleted(tenantId).orElse(null);
        if (t == null || t.isDeleted()) {
            throw new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!t.isEnabled()) {
            throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
        if (t.getExpireAt() != null && t.getExpireAt().isBefore(Instant.now())) {
            throw new CreditException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
    }

    private CreditTransactionView toView(CreditTransactionRow row) {
        return new CreditTransactionView(
                row.getId(), row.getTenantId(), row.getTenantCode(), row.getTenantName(),
                row.getUserId(), row.getUsername(), row.getUserDisplayName(),
                row.getDirection(), row.getDelta(), row.getBalanceBefore(), row.getBalanceAfter(),
                row.getFrozenBalanceBefore(), row.getFrozenBalanceAfter(),
                row.getTransactionType(), row.getReservationId(),
                row.getReason(), row.getOperatorId(), row.getOperatorName(), row.getCreatedAt());
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
}
