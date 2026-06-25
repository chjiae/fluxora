import http from './http'

/** 运行时故障状态记录。仅返回 runtimeState != 'AVAILABLE' 的资源。 */
export interface RuntimeStateRow {
  tenantId: number
  scopeType: string
  scopeKey: string
  runtimeState: string
  lastFailureKind: string | null
  lastFailedAt: string | null
  cooldownUntil: string | null
  updatedAt: string
  resourceLabel: string
}

/** 列出所有非 AVAILABLE 的运行时状态。平台管理员可查全部；租户管理员仅见本租户。 */
export async function listRuntimeStates(): Promise<RuntimeStateRow[]> {
  return (await http.get('/api/runtime-states')).data.data
}

/** 手动恢复指定资源为 AVAILABLE 状态。 */
export async function recoverRuntimeState(scopeType: string, scopeKey: string): Promise<void> {
  await http.post(`/api/runtime-states/${encodeURIComponent(scopeType)}/${encodeURIComponent(scopeKey)}/recover`)
}

/** scope_type 中文映射 */
export const scopeTypeLabels: Record<string, string> = {
  PROVIDER_CHANNEL: '上游通道',
  PROVIDER_CHANNEL_MODEL: '通道候选模型',
  PROVIDER_CHANNEL_CREDENTIAL: '通道凭证绑定',
  CREDENTIAL: '凭证',
  ROUTE_TARGET: '路由目标',
  BILLING_ACCOUNT_GROUP: '计费组',
  QUOTA_SCOPE: '配额域',
}

/** runtime_state 中文映射与语义色调 */
export const runtimeStateMeta: Record<string, { label: string; tone: 'danger' | 'warn' }> = {
  QUARANTINED: { label: '已隔离', tone: 'danger' },
  AUTH_FAILED: { label: '认证失败', tone: 'danger' },
  BILLING_EXHAUSTED: { label: '余额耗尽', tone: 'danger' },
  RATE_LIMITED: { label: '限流中', tone: 'warn' },
  MODEL_MAPPING_INVALID: { label: '模型映射无效', tone: 'danger' },
  PERMISSION_DENIED: { label: '权限拒绝', tone: 'danger' },
}
