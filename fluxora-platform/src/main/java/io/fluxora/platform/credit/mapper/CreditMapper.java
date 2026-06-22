package io.fluxora.platform.credit.mapper;

import io.fluxora.platform.credit.CreditTransaction;
import io.fluxora.platform.credit.dto.AdjustableUserOption;
import io.fluxora.platform.credit.dto.CreditAccountView;
import io.fluxora.platform.credit.dto.CreditStats;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 额度账户与流水 MyBatis Mapper。
 *
 * 关键约束：
 *   - {@link #adjustBalance(Long, BigDecimal)} 是唯一允许变更 balance 的入口；
 *     任何业务路径都不应再直接更新该字段；
 *   - {@link #ensureAccount(Long, Long)} 幂等，可被多个创建路径调用；
 *   - 本接口**不**提供 {@code updateTransaction} 或 {@code deleteTransaction}：
 *     流水写入后即不可变（满足 AGENT.md 流水不可篡改要求）。
 */
@Mapper
public interface CreditMapper {

    /**
     * 幂等创建账户：若 user_id 已存在则 ON CONFLICT DO NOTHING；
     * 供 MemberService.createMember 与 V5 回填脚本共用。
     */
    void ensureAccount(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /** 查询账户视图（含 join 用户/租户字段） */
    Optional<CreditAccountView> findAccountByUserId(@Param("userId") Long userId);

    /**
     * 原子调整余额。
     *
     * SQL：{@code UPDATE … SET balance = balance + delta WHERE user_id = ?
     * AND balance + delta >= 0 RETURNING balance AS balance_after,
     * balance - delta AS balance_before}.
     *
     * 返回 null 表示 0 行受影响——可能因：
     *   1. 余额不足（balance + delta &lt; 0）；
     *   2. 该用户没有账户（user_id 不存在）。
     * service 在调用前已校验账户存在，所以 null 视为 CREDIT_INSUFFICIENT。
     *
     * delta 可正可负：CREDIT 传 +amount，DEBIT 传 -amount。
     * 整个 UPDATE 是单条 SQL，在 Postgres 中天然原子（取行锁、改值、释放锁），
     * 并发 8 个 DEBIT 100 同时打到余额 500 的账户也只会成功 5 次。
     */
    BalanceAdjustResult adjustBalance(@Param("userId") Long userId, @Param("delta") BigDecimal delta);

    /** 插入流水；调用方必须保证已通过 adjustBalance 完成余额更新 */
    void insertTransaction(CreditTransaction txn);

    /** 流水列表行（含 join） */
    List<CreditTransactionRow> findTransactionRows(@Param("tenantId") Long tenantId,
                                                    @Param("userId") Long userId,
                                                    @Param("keyword") String keyword,
                                                    @Param("direction") String direction,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to,
                                                    @Param("offset") int offset,
                                                    @Param("limit") int limit);

    long countTransactions(@Param("tenantId") Long tenantId,
                           @Param("userId") Long userId,
                           @Param("keyword") String keyword,
                           @Param("direction") String direction,
                           @Param("from") Instant from,
                           @Param("to") Instant to);

    /**
     * 可调整额度的用户选项；service 决定 tenantId 过滤（租户管理员强制本租户）。
     * keyword 模糊匹配 username / display_name。
     */
    List<AdjustableUserOption> findAdjustableUsers(@Param("tenantId") Long tenantId,
                                                    @Param("keyword") String keyword,
                                                    @Param("limit") int limit);

    /** 聚合统计 */
    CreditStats stats(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /**
     * 插入卡密充值流水：与 insertTransaction 同模式，额外写入 source='CARD_REDEEM' 与 card_id。
     * 与 mapper.adjustBalance 在同一事务中调用；DB 层 uk_credit_txn_card 部分唯一索引
     * 保证同一张卡密最多一条 CARD_REDEEM 流水（防重复入账）。
     */
    void insertCardRedemptionTransaction(@Param("txn") CreditTransaction txn,
                                         @Param("cardId") Long cardId);
}
