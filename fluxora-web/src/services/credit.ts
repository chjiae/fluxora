import http from './http'

/**
 * 额度账户与流水 API service。
 *
 * 入口分层：
 *   - /api/credit/me*                            自身（普通用户）
 *   - /api/tenant/{tenantId}/credit/*            租户视角（租户管理员、平台管理员）
 *   - /api/admin/credit/*                        平台视角（仅平台管理员）
 */

export interface CreditAccountView {
  userId: number
  username: string
  userDisplayName: string | null
  tenantId: number
  tenantCode: string
  tenantName: string
  /** 可用余额；后端 DECIMAL，前端按 string 保留精度 */
  balance: string
  /** 已预冻结但尚未最终结算/释放的余额 */
  frozenBalance: string
  /** 可用余额 + 冻结余额 */
  totalBalance: string
  createdAt: string
  updatedAt: string
}

export interface CreditTransactionView {
  id: number
  tenantId: number
  tenantCode: string
  tenantName: string
  userId: number
  username: string
  userDisplayName: string | null
  direction: 'CREDIT' | 'DEBIT'
  amount: string
  balanceBefore: string
  balanceAfter: string
  frozenBalanceBefore: string | null
  frozenBalanceAfter: string | null
  transactionType: 'MANUAL_ADJUSTMENT' | 'RESERVE' | 'SETTLE' | 'RELEASE' | string
  reservationId: number | null
  reason: string
  operatorId: number
  operatorName: string | null
  createdAt: string
}

export interface CreditTransactionPage {
  items: CreditTransactionView[]
  total: number
  page: number
  size: number
}

export interface CreditStats {
  totalAccounts: number
  totalBalance: string
  totalFrozenBalance: string
  totalCredits: string
  totalDebits: string
  transactionCount: number
}

export interface AdjustableUserOption {
  userId: number
  username: string
  userDisplayName: string | null
  tenantId: number
  tenantCode: string
  tenantName: string
  balance: string
}

export interface AdjustCreditRequest {
  direction: 'CREDIT' | 'DEBIT'
  /** 金额输入只接受十进制字符串，禁止 Number 与科学计数法。 */
  amount: string
  reason: string
}

export interface CreditTransactionQuery {
  keyword?: string
  direction?: '' | 'CREDIT' | 'DEBIT'
  userId?: number
  from?: string
  to?: string
  page?: number
  size?: number
}

// ---- 自身路径 ----

export async function fetchMyAccount(): Promise<CreditAccountView> {
  const res = await http.get('/api/credit/me')
  return res.data.data
}

export async function listMyTransactions(params: CreditTransactionQuery): Promise<CreditTransactionPage> {
  const res = await http.get('/api/credit/me/transactions', { params })
  return res.data.data
}

// ---- 租户路径 ----

export async function fetchUserAccount(tenantId: number, userId: number): Promise<CreditAccountView> {
  const res = await http.get(`/api/tenant/${tenantId}/credit/accounts/${userId}`)
  return res.data.data
}

export async function listTenantTransactions(tenantId: number, params: CreditTransactionQuery): Promise<CreditTransactionPage> {
  const res = await http.get(`/api/tenant/${tenantId}/credit/transactions`, { params })
  return res.data.data
}

export async function adjustCredit(tenantId: number, userId: number, req: AdjustCreditRequest): Promise<CreditTransactionView> {
  const res = await http.post(`/api/tenant/${tenantId}/credit/adjust`, req, { params: { userId } })
  return res.data.data
}

export async function fetchTenantCreditStats(tenantId: number): Promise<CreditStats> {
  const res = await http.get(`/api/tenant/${tenantId}/credit/stats`)
  return res.data.data
}

// ---- 平台路径 ----

export async function fetchAdminAccount(userId: number): Promise<CreditAccountView> {
  const res = await http.get(`/api/admin/credit/accounts/${userId}`)
  return res.data.data
}

export async function listAllTransactions(params: CreditTransactionQuery & { tenantId?: number }): Promise<CreditTransactionPage> {
  const res = await http.get('/api/admin/credit/transactions', { params })
  return res.data.data
}

export async function fetchAdminCreditStats(): Promise<CreditStats> {
  const res = await http.get('/api/admin/credit/stats')
  return res.data.data
}

export async function fetchAdjustableUsers(params: { tenantId?: number; keyword?: string }): Promise<AdjustableUserOption[]> {
  const res = await http.get('/api/credit/adjustable-users', { params })
  return res.data.data
}
