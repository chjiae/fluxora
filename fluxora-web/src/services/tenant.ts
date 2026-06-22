import http from './http'

export interface SelfOperatedStatus {
  initialized: boolean
}

export interface SelfOperatedInitRequest {
  tenantName: string
  adminUsername: string
  adminPassword: string
  adminDisplayName: string
}

export interface SelfOperatedInitResult {
  tenantId: number
  tenantCode: string
  adminUserId: number
  adminUsername: string
}

export interface Tenant {
  id: number
  tenantCode: string
  name: string
  description: string | null
  type: string
  enabled: boolean
  status: string
  expireAt: string | null
  createdAt: string
  updatedAt: string
}

export interface TenantPage {
  items: Tenant[]
  total: number
  page: number
  size: number
}

/**
 * 租户聚合统计。
 * 字段与后端 TenantStats 对齐；前端「概览」与「租户管理」指标条共用。
 */
export interface TenantStats {
  total: number
  enabled: number
  disabled: number
  expired: number
  expiringSoon: number
  selfOperated: number
}

export async function fetchSelfOperatedStatus(): Promise<SelfOperatedStatus> {
  const res = await http.get('/api/tenant/self-operated/status')
  return res.data.data
}

export async function initializeSelfOperated(req: SelfOperatedInitRequest): Promise<SelfOperatedInitResult> {
  const res = await http.post('/api/tenant/self-operated/initialize', req)
  return res.data.data
}

export async function listTenants(params: {
  keyword?: string
  type?: string
  status?: string
  expireFrom?: string
  expireTo?: string
  page?: number
  size?: number
}): Promise<TenantPage> {
  const res = await http.get('/api/tenant', { params })
  return res.data.data
}

export async function getTenant(id: number): Promise<Tenant> {
  const res = await http.get(`/api/tenant/${id}`)
  return res.data.data
}

export async function createTenant(req: {
  tenantCode: string
  name: string
  description?: string
  type: string
  enabled: boolean
}): Promise<Tenant> {
  const res = await http.post('/api/tenant', req)
  return res.data.data
}

export async function updateTenant(id: number, req: {
  name: string
  description?: string
}): Promise<Tenant> {
  const res = await http.put(`/api/tenant/${id}`, req)
  return res.data.data
}

export async function enableTenant(id: number): Promise<Tenant> {
  const res = await http.put(`/api/tenant/${id}/enable`, {})
  return res.data.data
}

export async function disableTenant(id: number): Promise<Tenant> {
  const res = await http.put(`/api/tenant/${id}/disable`, {})
  return res.data.data
}

export async function setTenantExpire(id: number, expireAt: string | null): Promise<Tenant> {
  const res = await http.put(`/api/tenant/${id}/expire`, { expireAt })
  return res.data.data
}

export async function deleteTenant(id: number): Promise<void> {
  await http.delete(`/api/tenant/${id}`)
}

/**
 * 拉取租户聚合统计，供「概览」与「租户管理」顶部指标条使用。
 * expiringWithinDays 控制「即将到期」窗口，默认 30 天。
 */
export async function fetchTenantStats(expiringWithinDays = 30): Promise<TenantStats> {
  const res = await http.get('/api/tenant/stats', { params: { expiringWithinDays } })
  return res.data.data
}
