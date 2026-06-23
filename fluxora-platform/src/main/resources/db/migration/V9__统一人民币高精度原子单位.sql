-- Fluxora V9：统一人民币余额与模型价格的 8 位小数原子精度。
-- 1 CNY = 100,000,000 原子单位；扩展 scale 是兼容操作，既有 4 位小数金额数值不变。
-- 禁止在其他模块自行定义精度或舍入；转换和未来计费统一由 CnyPrecisionPolicy 处理。

ALTER TABLE user_credit_account ALTER COLUMN balance TYPE NUMERIC(24,8);
ALTER TABLE credit_transaction ALTER COLUMN delta TYPE NUMERIC(24,8);
ALTER TABLE credit_transaction ALTER COLUMN balance_before TYPE NUMERIC(24,8);
ALTER TABLE credit_transaction ALTER COLUMN balance_after TYPE NUMERIC(24,8);
ALTER TABLE recharge_card_batch ALTER COLUMN denomination TYPE NUMERIC(24,8);
ALTER TABLE recharge_card ALTER COLUMN denomination TYPE NUMERIC(24,8);
ALTER TABLE platform_model_price ALTER COLUMN input_price TYPE NUMERIC(24,8);
ALTER TABLE platform_model_price ALTER COLUMN output_price TYPE NUMERIC(24,8);
ALTER TABLE platform_model_price ALTER COLUMN cache_write_price TYPE NUMERIC(24,8);
ALTER TABLE platform_model_price ALTER COLUMN cache_read_price TYPE NUMERIC(24,8);
ALTER TABLE tenant_model_price ALTER COLUMN input_price TYPE NUMERIC(24,8);
ALTER TABLE tenant_model_price ALTER COLUMN output_price TYPE NUMERIC(24,8);
ALTER TABLE tenant_model_price ALTER COLUMN cache_write_price TYPE NUMERIC(24,8);
ALTER TABLE tenant_model_price ALTER COLUMN cache_read_price TYPE NUMERIC(24,8);

COMMENT ON COLUMN user_credit_account.balance IS '用户余额，CNY 固定 8 位小数原子精度；1 CNY 等于 100000000 原子单位，不丢失模型计费残差';
COMMENT ON COLUMN credit_transaction.delta IS '余额变动额，CNY 固定 8 位小数原子精度；必须与账户余额在同一事务内写入';
COMMENT ON COLUMN recharge_card_batch.denomination IS '卡密面额，CNY 固定 8 位小数原子精度；历史数值安全保留';
COMMENT ON COLUMN platform_model_price.input_price IS '输入单价，每 100 万 Token 的 CNY 固定 8 位小数价格；接口字符串传输';
COMMENT ON COLUMN tenant_model_price.input_price IS '租户输入售价，每 100 万 Token 的 CNY 固定 8 位小数价格；接口字符串传输';
