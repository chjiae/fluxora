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
  defaultOutputTokens: number
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
  /** 通道所属协议（OPENAI / ANTHROPIC）；用于前端按路由协议过滤可选候选 */
  channelProtocol: InboundProtocol
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

/** 单条同步结果项；只携带安全原因，绝不含上游原始响应或凭证。 */
export interface SyncItemResult {
  upstreamModelId: string | null
  result: string
  reason: string
}

/** 同步操作整体结果。 */
export interface SyncResult {
  existingBeforeSync: number
  added: number
  updated: number
  /** 本次未返回的候选数量；保留映射 / 路由不变 */
  missing: number
  failed: number
  failures: SyncItemResult[]
}

export interface SyncPayload {
  /** 可选：指定使用的凭证 ID；未传时后端按固定规则选第一个启用凭证 */
  credentialId?: number | null
}

export interface MappingPayload {
  providerChannelModelId: number
  remark?: string
}

export interface MappingUpdatePayload {
  enabled?: boolean
  remark?: string
}

// 价格视图：金额字段一律字符串，禁止 IEEE-754 Number；historical 版本携带 expiredAt。
export interface TenantModelPriceView {
  id: number
  tenantId: number
  tenantModelId: number
  currencyCode: string
  inputPricePerMillion: string
  outputPricePerMillion: string
  cacheWritePricePerMillion: string | null
  cacheReadPricePerMillion: string | null
  version: number
  effectiveAt: string
  /** 当前有效版本为 null */
  expiredAt: string | null
  createdAt: string
}

export interface PricePublishPayload {
  /** 十进制字符串：禁止 number 类型，禁止科学计数法，最多 8 位小数 */
  inputPricePerMillion: string
  outputPricePerMillion: string
  /** 不支持缓存的模型必须传 null/undefined；支持缓存的模型必须同时传两项 */
  cacheWritePricePerMillion?: string | null
  cacheReadPricePerMillion?: string | null
}

// C 端公开目录：绝不包含通道、上游模型、候选、映射、路由、版本号、deletedAt。
export interface PublicTenantModel {
  id: number
  modelCode: string
  displayName: string
  description: string | null
  supportsStreaming: boolean
  supportsToolCalling: boolean
  supportsVision: boolean
  supportsCache: boolean
  currencyCode: string
  inputPricePerMillion: string
  outputPricePerMillion: string
  cacheWritePricePerMillion: string | null
  cacheReadPricePerMillion: string | null
}

export type InboundProtocol = 'OPENAI' | 'ANTHROPIC'

export interface ModelRouteSummary {
  id: number
  tenantId: number
  tenantModelId: number
  inboundProtocol: InboundProtocol
  enabled: boolean
  remark: string | null
  targetCount: number
  createdAt: string
  updatedAt: string
}

export interface RouteCreatePayload {
  inboundProtocol: InboundProtocol
  remark?: string
}

export interface RouteUpdatePayload {
  enabled?: boolean
  remark?: string
}

export interface RouteTargetSummary {
  id: number
  tenantId: number
  modelRouteId: number
  tenantModelCandidateMappingId: number
  providerChannelId: number
  channelName: string
  upstreamModelIdSnapshot: string
  enabled: boolean
  priority: number
  weight: number
  remark: string | null
  mappingAvailable: boolean
  createdAt: string
  updatedAt: string
}

export interface TargetCreatePayload {
  tenantModelCandidateMappingId: number
  priority?: number
  weight?: number
  remark?: string
}

export interface TargetUpdatePayload {
  enabled?: boolean
  priority?: number
  weight?: number
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

/**
 * 触发该通道下的上游模型同步。
 * 后端按通道协议自动选择实现（OPENAI / ANTHROPIC）；凭证明文在服务端短暂解密，
 * 前端不接收任何明文或密文；同步失败不删除已有候选 / 映射 / 路由 / 价格。
 */
export async function syncChannelCandidates(channelId: number, payload?: SyncPayload): Promise<SyncResult> {
  return (await http.post(`/api/provider-channels/${channelId}/models/sync`, payload ?? {})).data.data
}

// ---------------- 价格 ----------------

export async function getCurrentPrice(tenantModelId: number): Promise<TenantModelPriceView | null> {
  return (await http.get(`/api/tenant-models/${tenantModelId}/prices/current`)).data.data
}

export async function getPriceHistory(tenantModelId: number): Promise<TenantModelPriceView[]> {
  return (await http.get(`/api/tenant-models/${tenantModelId}/prices`)).data.data
}

export async function publishPrice(tenantModelId: number, payload: PricePublishPayload): Promise<TenantModelPriceView> {
  return (await http.post(`/api/tenant-models/${tenantModelId}/prices`, payload)).data.data
}

// ---------------- C 端公开目录 ----------------

export async function listPublicModels(): Promise<PublicTenantModel[]> {
  return (await http.get('/api/models')).data.data
}

// ---------------- 路由 ----------------

export async function listRoutes(tenantModelId: number): Promise<ModelRouteSummary[]> {
  return (await http.get(`/api/tenant-models/${tenantModelId}/routes`)).data.data
}

export async function createRoute(tenantModelId: number, payload: RouteCreatePayload): Promise<ModelRouteSummary> {
  return (await http.post(`/api/tenant-models/${tenantModelId}/routes`, payload)).data.data
}

export async function updateRoute(routeId: number, payload: RouteUpdatePayload): Promise<void> {
  await http.put(`/api/routes/${routeId}`, payload)
}

export async function deleteRoute(routeId: number): Promise<void> {
  await http.delete(`/api/routes/${routeId}`)
}

// ---------------- 路由目标 ----------------

export async function listRouteTargets(routeId: number): Promise<RouteTargetSummary[]> {
  return (await http.get(`/api/routes/${routeId}/targets`)).data.data
}

export async function createRouteTarget(routeId: number, payload: TargetCreatePayload): Promise<RouteTargetSummary> {
  return (await http.post(`/api/routes/${routeId}/targets`, payload)).data.data
}

export async function updateRouteTarget(routeId: number, targetId: number, payload: TargetUpdatePayload): Promise<RouteTargetSummary> {
  return (await http.put(`/api/routes/${routeId}/targets/${targetId}`, payload)).data.data
}

export async function deleteRouteTarget(routeId: number, targetId: number): Promise<void> {
  await http.delete(`/api/routes/${routeId}/targets/${targetId}`)
}
