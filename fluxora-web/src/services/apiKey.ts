import http from './http'

/**
 * API Key API service。
 *
 * 三组入口：
 *   - /api/api-keys*               自身路径（普通用户、租户管理员、平台用户共用）
 *   - /api/tenant/{id}/api-keys*   租户路径（租户管理员、平台管理员）
 *   - /api/admin/api-keys*         平台路径（仅平台管理员）
 *
 * 完整 plaintext 只在 createKey 的响应中返回一次；调用方收到后仅可短暂
 * 内存保留，绝不持久化。
 */

export interface ApiKeySummary {
  id: number
  tenantId: number
  tenantCode: string
  tenantName: string
  userId: number
  username: string
  userDisplayName: string | null
  name: string
  keyPrefix: string
  status: string
  expireAt: string | null
  lastUsedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ApiKeyPage {
  items: ApiKeySummary[]
  total: number
  page: number
  size: number
}

export interface ApiKeyStats {
  total: number
  enabled: number
  disabled: number
  expired: number
  expiringSoon: number
}

export interface CreatedApiKeyResponse {
  summary: ApiKeySummary
  /** 完整 plaintext；仅本响应一次性返回 */
  plaintext: string
}

export interface ApiKeyQuery {
  keyword?: string
  status?: '' | 'ENABLED' | 'DISABLED' | 'EXPIRED'
  userId?: number
  tenantId?: number
  page?: number
  size?: number
}

export interface CreateApiKeyRequest {
  name: string
  forUserId?: number
  expireAt?: string | null
}

export interface UpdateApiKeyRequest {
  name?: string
  expireAtAction?: 'SET' | 'CLEAR'
  expireAt?: string | null
}

// ---- 自身路径 ----

export async function listMyApiKeys(params: ApiKeyQuery): Promise<ApiKeyPage> {
  const res = await http.get('/api/api-keys', { params })
  return res.data.data
}

export async function fetchMyApiKeyStats(): Promise<ApiKeyStats> {
  const res = await http.get('/api/api-keys/stats')
  return res.data.data
}

export async function createMyApiKey(req: CreateApiKeyRequest): Promise<CreatedApiKeyResponse> {
  const res = await http.post('/api/api-keys', req)
  return res.data.data
}

export async function getApiKey(id: number): Promise<ApiKeySummary> {
  const res = await http.get(`/api/api-keys/${id}`)
  return res.data.data
}

export async function updateApiKey(id: number, req: UpdateApiKeyRequest): Promise<ApiKeySummary> {
  const res = await http.put(`/api/api-keys/${id}`, req)
  return res.data.data
}

export async function enableApiKey(id: number): Promise<ApiKeySummary> {
  const res = await http.put(`/api/api-keys/${id}/enable`, {})
  return res.data.data
}

export async function disableApiKey(id: number): Promise<ApiKeySummary> {
  const res = await http.put(`/api/api-keys/${id}/disable`, {})
  return res.data.data
}

export async function deleteApiKey(id: number): Promise<void> {
  await http.delete(`/api/api-keys/${id}`)
}

// ---- 租户路径 ----

export async function listTenantApiKeys(tenantId: number, params: ApiKeyQuery): Promise<ApiKeyPage> {
  const res = await http.get(`/api/tenant/${tenantId}/api-keys`, { params })
  return res.data.data
}

export async function fetchTenantApiKeyStats(tenantId: number): Promise<ApiKeyStats> {
  const res = await http.get(`/api/tenant/${tenantId}/api-keys/stats`)
  return res.data.data
}

export async function createTenantApiKey(tenantId: number, req: CreateApiKeyRequest): Promise<CreatedApiKeyResponse> {
  const res = await http.post(`/api/tenant/${tenantId}/api-keys`, req)
  return res.data.data
}

// ---- 平台路径 ----

export async function listAllApiKeys(params: ApiKeyQuery): Promise<ApiKeyPage> {
  const res = await http.get('/api/admin/api-keys', { params })
  return res.data.data
}

export async function fetchAllApiKeyStats(): Promise<ApiKeyStats> {
  const res = await http.get('/api/admin/api-keys/stats')
  return res.data.data
}
