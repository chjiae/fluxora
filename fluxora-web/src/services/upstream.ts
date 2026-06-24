import http from './http'

// 上游配置公开视图模型。
// 刻意不包含凭证明文、密文、随机向量、指纹或加密版本；status 由后端派生，不暴露 deletedAt。

export type ScopeType = 'PLATFORM_SHARED' | 'TENANT_PRIVATE'
export type Protocol = 'OPENAI' | 'ANTHROPIC'
export type Status = 'ENABLED' | 'DISABLED' | 'DELETED'
export type CredentialAuthType = 'BEARER' | 'X_API_KEY' | 'NONE'

export interface ProviderSummary {
  id: number; name: string; code: string; scopeType: ScopeType
  tenantId: number | null; tenantName: string | null
  description: string | null; status: Status; createdAt: string; updatedAt: string
}
export interface ProviderStats { total: number; platformShared: number; tenantPrivate: number; enabled: number; disabled: number }

export interface ProviderBaseUrlSummary {
  id: number; providerId: number; protocol: Protocol
  originalBaseUrl: string; normalizedBaseUrl: string
  displayName: string | null; remark: string | null
  status: Status; createdAt: string; updatedAt: string
}
export interface ProviderBaseUrlStats { total: number; enabled: number; disabled: number; openai: number; anthropic: number }

export interface ProviderChannelSummary {
  id: number; tenantId: number; tenantName: string | null
  providerId: number; providerName: string; providerBaseUrlId: number
  protocol: Protocol; normalizedBaseUrl: string; name: string; status: Status
  priority: number; weight: number; connectTimeoutMs: number; readTimeoutMs: number
  remark: string | null; credentialCount: number; createdAt: string; updatedAt: string
}
export interface ProviderChannelStats { total: number; enabled: number; disabled: number }

export interface ProviderCredentialSummary {
  id: number; tenantId: number; providerChannelId: number
  name: string; credentialType: 'API_KEY'; authType: CredentialAuthType; maskedValue: string; status: Status
  priority: number; weight: number; remark: string | null; createdAt: string; updatedAt: string
}
export interface ProviderCredentialStats { total: number; enabled: number; disabled: number }

export interface Page<T> { items: T[]; total: number; page: number; size: number }

export interface ProviderQuery { keyword?: string; scopeType?: ScopeType | ''; enabled?: boolean; page?: number; size?: number }
export interface ChannelQuery { keyword?: string; providerId?: number; protocol?: Protocol | ''; enabled?: boolean; tenantId?: number; page?: number; size?: number }
export interface CredentialQuery { keyword?: string; maskedValue?: string; enabled?: boolean; page?: number; size?: number }

export interface ProviderPayload { name: string; code: string; scopeType: ScopeType; description?: string; enabled?: boolean; tenantId?: number | null }
export interface BaseUrlPayload { providerId: number; protocol: Protocol; baseUrl: string; displayName?: string; remark?: string }
export interface ChannelPayload { tenantId?: number | null; providerBaseUrlId: number; name: string; enabled?: boolean; priority?: number; weight?: number; connectTimeoutMs?: number; readTimeoutMs?: number; remark?: string }
export interface CredentialPayload { providerChannelId: number; plaintext?: string; name?: string; priority?: number; weight?: number; remark?: string; authType?: CredentialAuthType }
export interface CredentialMetadataPayload { name: string; priority: number; weight: number; remark?: string; authType?: CredentialAuthType }
export interface CredentialImportPayload { providerChannelId: number; lines: string[]; namePrefix?: string; priority?: number; weight?: number; remark?: string }

export type CredentialImportItemResult = 'IMPORTED' | 'SKIPPED_BATCH_DUPLICATE' | 'SKIPPED_EXISTING' | 'INVALID' | 'OVER_LIMIT' | 'SKIPPED_CONCURRENT'
export interface CredentialImportItem { lineNumber: number; maskedValue: string | null; result: CredentialImportItemResult; reason: string }
export interface CredentialImportSummary { totalRead: number; imported: number; skippedBatchDuplicate: number; skippedExisting: number; invalid: number; overLimit: number; concurrentDuplicate: number }
export interface CredentialImportResult { summary: CredentialImportSummary; items: CredentialImportItem[] }

// ---------------- Provider ----------------
export async function listProviders(params: ProviderQuery): Promise<Page<ProviderSummary>> { return (await http.get('/api/providers', { params })).data.data }
export async function getProviderStats(): Promise<ProviderStats> { return (await http.get('/api/providers/stats')).data.data }
export async function getProvider(id: number): Promise<ProviderSummary> { return (await http.get(`/api/providers/${id}`)).data.data }
export async function createProvider(payload: ProviderPayload): Promise<ProviderSummary> { return (await http.post('/api/providers', payload)).data.data }
export async function updateProvider(id: number, payload: Pick<ProviderPayload, 'name' | 'description'>): Promise<ProviderSummary> { return (await http.put(`/api/providers/${id}`, payload)).data.data }
export async function setProviderEnabled(id: number, enabled: boolean): Promise<void> { await http.put(`/api/providers/${id}/${enabled ? 'enable' : 'disable'}`, {}) }
export async function deleteProvider(id: number): Promise<void> { await http.delete(`/api/providers/${id}`) }

// ---------------- ProviderBaseUrl ----------------
export async function listProviderBaseUrls(providerId: number): Promise<ProviderBaseUrlSummary[]> { return (await http.get('/api/provider-base-urls', { params: { providerId } })).data.data }
export async function getProviderBaseUrlStats(providerId: number): Promise<ProviderBaseUrlStats> { return (await http.get('/api/provider-base-urls/stats', { params: { providerId } })).data.data }
export async function createProviderBaseUrl(payload: BaseUrlPayload): Promise<ProviderBaseUrlSummary> { return (await http.post('/api/provider-base-urls', payload)).data.data }
export async function updateProviderBaseUrl(id: number, payload: BaseUrlPayload): Promise<ProviderBaseUrlSummary> { return (await http.put(`/api/provider-base-urls/${id}`, payload)).data.data }
export async function setProviderBaseUrlEnabled(id: number, enabled: boolean): Promise<void> { await http.put(`/api/provider-base-urls/${id}/${enabled ? 'enable' : 'disable'}`, {}) }
export async function deleteProviderBaseUrl(id: number): Promise<void> { await http.delete(`/api/provider-base-urls/${id}`) }

// ---------------- ProviderChannel ----------------
export async function listProviderChannels(params: ChannelQuery): Promise<Page<ProviderChannelSummary>> { return (await http.get('/api/provider-channels', { params })).data.data }
export async function getProviderChannelStats(tenantId?: number): Promise<ProviderChannelStats> { return (await http.get('/api/provider-channels/stats', { params: { tenantId } })).data.data }
export async function getProviderChannel(id: number): Promise<ProviderChannelSummary> { return (await http.get(`/api/provider-channels/${id}`)).data.data }
export async function createProviderChannel(payload: ChannelPayload): Promise<ProviderChannelSummary> { return (await http.post('/api/provider-channels', payload)).data.data }
export async function updateProviderChannel(id: number, payload: ChannelPayload): Promise<ProviderChannelSummary> { return (await http.put(`/api/provider-channels/${id}`, payload)).data.data }
export async function setProviderChannelEnabled(id: number, enabled: boolean): Promise<void> { await http.put(`/api/provider-channels/${id}/${enabled ? 'enable' : 'disable'}`, {}) }
export async function deleteProviderChannel(id: number): Promise<void> { await http.delete(`/api/provider-channels/${id}`) }

// ---------------- ProviderCredential ----------------
export async function listCredentials(providerChannelId: number, params: CredentialQuery): Promise<Page<ProviderCredentialSummary>> { return (await http.get('/api/provider-credentials', { params: { providerChannelId, ...params } })).data.data }
export async function getCredentialStats(providerChannelId: number): Promise<ProviderCredentialStats> { return (await http.get('/api/provider-credentials/stats', { params: { providerChannelId } })).data.data }
export async function getCredential(id: number): Promise<ProviderCredentialSummary> { return (await http.get(`/api/provider-credentials/${id}`)).data.data }
export async function createCredential(payload: CredentialPayload): Promise<ProviderCredentialSummary> { return (await http.post('/api/provider-credentials', payload)).data.data }
export async function updateCredential(id: number, payload: CredentialMetadataPayload): Promise<ProviderCredentialSummary> { return (await http.put(`/api/provider-credentials/${id}`, payload)).data.data }
export async function replaceCredential(id: number, plaintext: string): Promise<ProviderCredentialSummary> { return (await http.put(`/api/provider-credentials/${id}/replace`, { plaintext })).data.data }
export async function setCredentialEnabled(id: number, enabled: boolean): Promise<void> { await http.put(`/api/provider-credentials/${id}/${enabled ? 'enable' : 'disable'}`, {}) }
export async function deleteCredential(id: number): Promise<void> { await http.delete(`/api/provider-credentials/${id}`) }
export async function importCredentials(payload: CredentialImportPayload): Promise<CredentialImportResult> { return (await http.post('/api/provider-credentials/import', payload)).data.data }
