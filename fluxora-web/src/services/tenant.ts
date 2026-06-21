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
  type: string
  enabled: boolean
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
  enabled?: boolean | null
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
  type: string
  enabled: boolean
}): Promise<Tenant> {
  const res = await http.post('/api/tenant', req)
  return res.data.data
}

export async function updateTenant(id: number, req: {
  name: string
  enabled: boolean
  expireAt?: string | null
}): Promise<Tenant> {
  const res = await http.put(`/api/tenant/${id}`, req)
  return res.data.data
}

export async function toggleTenant(id: number, enabled: boolean): Promise<Tenant> {
  const res = await http.put(`/api/tenant/${id}/toggle`, { enabled })
  return res.data.data
}

export async function deleteTenant(id: number): Promise<void> {
  await http.delete(`/api/tenant/${id}`)
}
