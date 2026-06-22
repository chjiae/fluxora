import http from './http'

/**
 * 卡密 API service。
 *
 * 入口分层：
 *   - /api/cards/redeem                              普通用户核销
 *   - /api/cards/{id}/enable|disable                 单张卡密状态管理
 *   - /api/tenant/{tenantId}/cards/batches*          租户批次管理（租户管理员 / 平台管理员）
 *   - /api/admin/cards/*                             跨租户（仅平台管理员）
 *
 * 完整 plaintext 只在 createBatch 响应中返回一次；调用方收到后仅可短暂
 * 内存保留，绝不持久化。
 */

export interface CardBatchSummary {
  id: number
  tenantId: number
  tenantCode: string
  tenantName: string
  batchCode: string
  name: string | null
  /** 面额（与额度账户精度一致；前端按 string 保留精度） */
  denomination: string
  totalCount: number
  availableCount: number
  usedCount: number
  disabledCount: number
  expiredCount: number
  status: 'ENABLED' | 'DISABLED'
  expireAt: string | null
  createdById: number
  createdByName: string | null
  createdAt: string
  updatedAt: string
}

export interface CardSummary {
  id: number
  tenantId: number
  batchId: number
  batchCode: string
  cardPrefix: string
  denomination: string
  status: 'ENABLED' | 'DISABLED' | 'REDEEMED' | 'EXPIRED'
  expireAt: string | null
  redeemedUserId: number | null
  redeemedUsername: string | null
  redeemedUserDisplayName: string | null
  redeemedAt: string | null
  createdAt: string
}

export interface BatchPage {
  items: CardBatchSummary[]
  total: number
  page: number
  size: number
}

export interface CardPage {
  items: CardSummary[]
  total: number
  page: number
  size: number
}

export interface CreatedBatchResponse {
  batches: CardBatchSummary[]
  /** 完整 plaintext；仅本响应一次性返回 */
  plaintexts: string[]
}

export interface DenominationGroup {
  denomination: string | number
  count: number
  name?: string
  expireAt?: string | null
}

export interface CreateBatchRequest {
  groups: DenominationGroup[]
}

export interface RedeemResponse {
  cardId: number
  cardPrefix: string
  amount: string
  newBalance: string
  redeemedAt: string
}

export interface BatchQuery {
  keyword?: string
  status?: '' | 'ENABLED' | 'DISABLED'
  denomination?: string | number
  page?: number
  size?: number
}

export interface CardQuery {
  batchId?: number
  prefixKeyword?: string
  status?: '' | 'ENABLED' | 'DISABLED' | 'REDEEMED' | 'EXPIRED'
  page?: number
  size?: number
}

// ---- 用户核销 ----

export async function redeemCard(code: string): Promise<RedeemResponse> {
  const res = await http.post('/api/cards/redeem', { code })
  return res.data.data
}

// ---- 租户路径 ----

export async function createBatch(tenantId: number, req: CreateBatchRequest): Promise<CreatedBatchResponse> {
  const res = await http.post(`/api/tenant/${tenantId}/cards/batches`, req)
  return res.data.data
}

export async function listBatches(tenantId: number, params: BatchQuery): Promise<BatchPage> {
  const res = await http.get(`/api/tenant/${tenantId}/cards/batches`, { params })
  return res.data.data
}

export async function fetchBatchDetail(tenantId: number, batchId: number): Promise<CardBatchSummary> {
  const res = await http.get(`/api/tenant/${tenantId}/cards/batches/${batchId}`)
  return res.data.data
}

export async function listCardsInBatch(tenantId: number, batchId: number, params: CardQuery): Promise<CardPage> {
  const res = await http.get(`/api/tenant/${tenantId}/cards/batches/${batchId}/cards`, { params })
  return res.data.data
}

export async function enableBatch(tenantId: number, batchId: number): Promise<CardBatchSummary> {
  const res = await http.put(`/api/tenant/${tenantId}/cards/batches/${batchId}/enable`, {})
  return res.data.data
}

export async function disableBatch(tenantId: number, batchId: number): Promise<CardBatchSummary> {
  const res = await http.put(`/api/tenant/${tenantId}/cards/batches/${batchId}/disable`, {})
  return res.data.data
}

export async function enableCard(cardId: number): Promise<CardSummary> {
  const res = await http.put(`/api/cards/${cardId}/enable`, {})
  return res.data.data
}

export async function disableCard(cardId: number): Promise<CardSummary> {
  const res = await http.put(`/api/cards/${cardId}/disable`, {})
  return res.data.data
}

// ---- 平台路径 ----

export async function listAllBatches(params: BatchQuery & { tenantId?: number }): Promise<BatchPage> {
  const res = await http.get('/api/admin/cards/batches', { params })
  return res.data.data
}
