import http from './http'

/** 模型价格均以字符串传输，避免 JavaScript Number 损失账务精度。 */
export interface PlatformModelSummary {
  id: number; code: string; displayName: string; description: string | null; modelType: string | null; tags: string | null
  supportsStreaming: boolean; supportsTools: boolean; supportsVision: boolean; supportsCache: boolean
  contextLength: number | null; maxOutputLength: number | null; status: 'ENABLED' | 'DISABLED'; createdAt: string; updatedAt: string
}
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface PlatformModelPayload { code: string; displayName: string; description?: string; modelType?: string; tags?: string; supportsStreaming?: boolean; supportsTools?: boolean; supportsVision?: boolean; supportsCache?: boolean; contextLength?: number | null; maxOutputLength?: number | null; enabled?: boolean }
export async function listPlatformModels(params: { keyword?: string; enabled?: boolean; page?: number; size?: number }): Promise<Page<PlatformModelSummary>> { return (await http.get('/api/platform-models', { params })).data.data }
export async function createPlatformModel(payload: PlatformModelPayload): Promise<PlatformModelSummary> { return (await http.post('/api/platform-models', payload)).data.data }
export async function updatePlatformModel(id: number, payload: PlatformModelPayload): Promise<PlatformModelSummary> { return (await http.put(`/api/platform-models/${id}`, payload)).data.data }
export async function setPlatformModelEnabled(id: number, enabled: boolean): Promise<void> { await http.put(`/api/platform-models/${id}/${enabled ? 'enable' : 'disable'}`, {}) }
export async function currentPlatformPrice(id:number): Promise<PriceView> { return (await http.get(`/api/platform-models/${id}/prices/current`)).data.data }
export async function platformPriceHistory(id:number): Promise<PriceView[]> { return (await http.get(`/api/platform-models/${id}/prices`)).data.data }
export async function setPlatformPrice(id:number,payload:{inputPrice:string;outputPrice:string;cacheWritePrice?:string;cacheReadPrice?:string}): Promise<PriceView> { return (await http.post(`/api/platform-models/${id}/prices`,payload)).data.data }
export interface TenantModelSummary { id:number; tenantId:number; platformModelId:number; platformCode:string; displayName:string; description:string|null; publishStatus:string; priceMode:string; supportsStreaming:boolean; supportsTools:boolean; supportsVision:boolean; supportsCache:boolean }
export async function listTenantModels(tenantId?:number): Promise<Page<TenantModelSummary>> { return (await http.get('/api/tenant-models',{params:{tenantId,page:1,size:100}})).data.data }
export async function publishTenantModel(platformModelId:number,displayName?:string,description?:string,tenantId?:number): Promise<TenantModelSummary> { return (await http.post('/api/tenant-models',{tenantId,platformModelId,displayName,description})).data.data }
export async function listPublicModels(): Promise<any[]> { return (await http.get('/api/models')).data.data }

/** 通道候选、租户路由和价格均用字符串传递金额，避免浏览器 Number 损失八位原子精度。 */
export interface ChannelModelSummary { id:number; providerChannelId:number; platformModelId:number|null; upstreamModelId:string; displayName:string; sourceType:'MANUAL'|'SYNCED'; status:'ENABLED'|'DISABLED'; lastSyncedAt:string|null; lastSyncSummary:string|null }
/** 与后端同步汇总一致：added 为本次发现并写入（含更新）的候选数。 */
export interface SyncItemResult { upstreamModelId:string|null; result:'ADDED'|'UPDATED'|'SKIPPED'|'FAILED'; reason:string }
export interface SyncResult { added:number; updated:number; skipped:number; failed:number; items:SyncItemResult[] }
export interface PriceView { currencyCode:string; inputPrice:string; outputPrice:string; cacheWritePrice:string|null; cacheReadPrice:string|null; effectiveAt:string; expiresAt:string|null; sourceType:string }
export interface ModelRouteSummary { id:number; tenantModelId:number; inboundProtocol:'OPENAI'|'ANTHROPIC'; status:'ENABLED'|'DISABLED'; remark:string|null }
export interface RouteTargetSummary { id:number; modelRouteId:number; providerChannelId:number; providerChannelModelId:number; protocol:string; channelName:string; upstreamModelIdSnapshot:string; status:string; priority:number; weight:number; remark:string|null }
export async function listChannelModels(channelId:number): Promise<Page<ChannelModelSummary>> { return (await http.get(`/api/provider-channels/${channelId}/models`, { params:{page:1,size:100} })).data.data }
export async function createChannelModel(channelId:number, upstreamModelId:string, displayName:string): Promise<ChannelModelSummary> { return (await http.post(`/api/provider-channels/${channelId}/models`, { upstreamModelId, displayName })).data.data }
export async function updateChannelModel(channelId:number,id:number,displayName:string,enabled:boolean): Promise<ChannelModelSummary> { return (await http.put(`/api/provider-channels/${channelId}/models/${id}`,{displayName,enabled})).data.data }
export async function deleteChannelModel(channelId:number,id:number): Promise<void> { await http.delete(`/api/provider-channels/${channelId}/models/${id}`) }
export async function mapChannelModel(channelId:number,id:number,platformModelId:number|null): Promise<ChannelModelSummary> { return (await http.put(`/api/provider-channels/${channelId}/models/${id}/mapping`,{platformModelId})).data.data }
export async function syncChannelModels(channelId:number, credentialId?:number): Promise<SyncResult> { return (await http.post(`/api/provider-channels/${channelId}/models/sync`, { credentialId })).data.data }
export async function currentTenantPrice(id:number): Promise<PriceView> { return (await http.get(`/api/tenant-models/${id}/prices/current`)).data.data }
export async function tenantPriceHistory(id:number): Promise<PriceView[]> { return (await http.get(`/api/tenant-models/${id}/prices`)).data.data }
export async function setTenantCustomPrice(id:number, payload:{inputPrice:string;outputPrice:string;cacheWritePrice?:string;cacheReadPrice?:string}): Promise<PriceView> { return (await http.post(`/api/tenant-models/${id}/prices/custom`, payload)).data.data }
export async function inheritTenantPrice(id:number): Promise<void> { await http.put(`/api/tenant-models/${id}/prices/inherit`, {}) }
export async function enableTenantModel(id:number): Promise<void> { await http.put(`/api/tenant-models/${id}/enable`, {}) }
export async function listModelRoutes(id:number): Promise<ModelRouteSummary[]> { return (await http.get(`/api/tenant-models/${id}/routes`)).data.data }
export async function createModelRoute(id:number, inboundProtocol:'OPENAI'|'ANTHROPIC', remark?:string): Promise<ModelRouteSummary> { return (await http.post(`/api/tenant-models/${id}/routes`, { inboundProtocol, remark })).data.data }
export async function updateModelRoute(id:number, enabled:boolean, remark?:string): Promise<void> { await http.put(`/api/tenant-models/routes/${id}`, { enabled, remark }) }
export async function deleteModelRoute(id:number): Promise<void> { await http.delete(`/api/tenant-models/routes/${id}`) }
export async function listRouteTargets(routeId:number): Promise<RouteTargetSummary[]> { return (await http.get(`/api/tenant-models/routes/${routeId}/targets`)).data.data }
export async function createRouteTarget(routeId:number, payload:{providerChannelId:number;providerChannelModelId:number;priority?:number;weight?:number;remark?:string}): Promise<void> { await http.post(`/api/tenant-models/routes/${routeId}/targets`, payload) }
export async function updateRouteTarget(routeId:number,id:number,payload:{priority:number;weight:number;enabled:boolean;remark?:string}): Promise<void> { await http.put(`/api/tenant-models/routes/${routeId}/targets/${id}`,payload) }
export async function deleteRouteTarget(routeId:number,id:number): Promise<void> { await http.delete(`/api/tenant-models/routes/${routeId}/targets/${id}`) }
