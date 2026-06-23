import http from './http'
import type { Page } from './upstream'

// 租户模型领域类型（V10 重建：所有资源以 tenant_id 隔离，不存在全局模型目录）。
// status 字段由后端从 publish_status + enabled + deleted_at 派生；前端不存储 deletedAt。

export type TenantModelStatus = 'DRAFT' | 'ENABLED' | 'DISABLED' | 'DELETED'
export type CandidateStatus = 'ENABLED' | 'DISABLED'

export interface TenantModelSummary {
  id: number
  tenantId: number
  tenantName: string | null
  modelCode: string
  displayName: string
  description: string | null
  supportsStreaming: boolean
  supportsToolCalling: boolean
  supportsVision: boolean
  supportsCache: boolean
  status: TenantModelStatus
  mappingCount: number
  hasActivePrice: boolean
  routeCount: number
  createdAt: string
  updatedAt: string
}

export interface TenantModelStats {
  total: number
  enabled: number
  disabled: number
  draft: number
  missingPrice: number
  missingRoute: number
}

export interface ProviderChannelModelSummary {
  id: number
  tenantId: number
  providerChannelId: number
  channelName: string
  upstreamModelId: string
  upstreamDisplayName: string
  sourceType: 'MANUAL' | 'SYNCED'
  supportsStreaming: boolean
  supportsToolCalling: boolean
  supportsVision: boolean
  supportsCache: boolean
  status: CandidateStatus
  lastSyncedAt: string | null
  lastSyncSummary: string | null
  createdAt: string
  updatedAt: string
}

export interface TenantModelCandidateMappingSummary {
  id: number
  tenantId: number
  tenantModelId: number
  providerChannelModelId: number
  providerChannelId: number
  channelName: string
  upstreamModelId: string
  upstreamDisplayName: string
  supportsStreaming: boolean
  supportsToolCalling: boolean
  supportsVision: boolean
  supportsCache: boolean
  enabled: boolean
  candidateAvailable: boolean
  remark: string | null
  createdAt: string
  updatedAt: string
}

export interface TenantModelQuery {
  tenantId?: number | null
  keyword?: string
  status?: '' | 'DRAFT' | 'ENABLED' | 'DISABLED'
  page?: number
  size?: number
}

export interface TenantModelPayload {
  /** 平台管理员必须传 tenantId；租户管理员忽略此字段，后端强制使用 JWT 当前租户 */
  tenantId?: number | null
  modelCode: string
  displayName: string
  description?: string
  supportsStreaming?: boolean
  supportsToolCalling?: boolean
  supportsVision?: boolean
  supportsCache?: boolean
}

export interface CandidatePayload {
  upstreamModelId: string
  upstreamDisplayName?: string
  supportsStreaming?: boolean
  supportsToolCalling?: boolean
  supportsVision?: boolean
  supportsCache?: boolean
  enabled?: boolean
}

export interface MappingPayload {
  providerChannelModelId: number
  remark?: string
}

export interface MappingUpdatePayload {
  enabled?: boolean
  remark?: string
}

// ---------------- TenantModel ----------------

export async function listTenantModels(params: TenantModelQuery): Promise<Page<TenantModelSummary>> {
  return (await http.get('/api/tenant-models', { params })).data.data
}

export async function getTenantModelStats(tenantId?: number | null): Promise<TenantModelStats> {
  return (await http.get('/api/tenant-models/stats', { params: { tenantId } })).data.data
}

export async function getTenantModel(id: number): Promise<TenantModelSummary> {
  return (await http.get(`/api/tenant-models/${id}`)).data.data
}

export async function createTenantModel(payload: TenantModelPayload): Promise<TenantModelSummary> {
  return (await http.post('/api/tenant-models', payload)).data.data
}

export async function updateTenantModel(id: number, payload: TenantModelPayload): Promise<TenantModelSummary> {
  return (await http.put(`/api/tenant-models/${id}`, payload)).data.data
}

export async function enableTenantModel(id: number): Promise<void> {
  await http.post(`/api/tenant-models/${id}/enable`)
}
export async function disableTenantModel(id: number): Promise<void> {
  await http.post(`/api/tenant-models/${id}/disable`)
}
export async function deleteTenantModel(id: number): Promise<void> {
  await http.delete(`/api/tenant-models/${id}`)
}

// ---------------- 候选映射 ----------------

export async function listMappings(tenantModelId: number): Promise<TenantModelCandidateMappingSummary[]> {
  return (await http.get(`/api/tenant-models/${tenantModelId}/candidate-mappings`)).data.data
}

export async function createMapping(tenantModelId: number, payload: MappingPayload): Promise<TenantModelCandidateMappingSummary> {
  return (await http.post(`/api/tenant-models/${tenantModelId}/candidate-mappings`, payload)).data.data
}

export async function updateMapping(tenantModelId: number, mappingId: number, payload: MappingUpdatePayload): Promise<void> {
  await http.put(`/api/tenant-models/${tenantModelId}/candidate-mappings/${mappingId}`, payload)
}

export async function deleteMapping(tenantModelId: number, mappingId: number): Promise<void> {
  await http.delete(`/api/tenant-models/${tenantModelId}/candidate-mappings/${mappingId}`)
}

// ---------------- ProviderChannelModel（通道候选） ----------------

export async function listChannelCandidates(channelId: number): Promise<ProviderChannelModelSummary[]> {
  return (await http.get(`/api/provider-channels/${channelId}/models`)).data.data
}

export async function createChannelCandidate(channelId: number, payload: CandidatePayload): Promise<ProviderChannelModelSummary> {
  return (await http.post(`/api/provider-channels/${channelId}/models`, payload)).data.data
}

export async function updateChannelCandidate(channelId: number, id: number, payload: CandidatePayload): Promise<ProviderChannelModelSummary> {
  return (await http.put(`/api/provider-channels/${channelId}/models/${id}`, payload)).data.data
}

export async function enableChannelCandidate(channelId: number, id: number): Promise<void> {
  await http.post(`/api/provider-channels/${channelId}/models/${id}/enable`)
}
export async function disableChannelCandidate(channelId: number, id: number): Promise<void> {
  await http.post(`/api/provider-channels/${channelId}/models/${id}/disable`)
}
export async function deleteChannelCandidate(channelId: number, id: number): Promise<void> {
  await http.delete(`/api/provider-channels/${channelId}/models/${id}`)
}
