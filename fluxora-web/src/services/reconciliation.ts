import http from './http'

/** 待对账结算记录。只展示安全字段，不包含 API Key、请求正文、上游地址或凭证。 */
export interface BillingSettlementView {
  requestId: string
  tenantId: number
  userId: number
  tenantModelCode: string
  status: string
  actualAmount: string | null
  outstandingAmount: string
  reasonCode: string | null
  upstreamDispatchState: string | null
  createdAt: string
  updatedAt: string
}

export interface ReconciliationPage {
  items: BillingSettlementView[]
  total: number
  page: number
  size: number
}

export interface ReconciliationActionRequest {
  finalAmount?: string
  reason: string
}

export async function listPendingReconciliations(params: { tenantId?: number; page?: number; size?: number }): Promise<ReconciliationPage> {
  const res = await http.get('/api/admin/billing/reconciliations', { params })
  return res.data.data
}

export async function confirmNoCharge(requestId: string, payload: ReconciliationActionRequest): Promise<BillingSettlementView> {
  const res = await http.post(`/api/admin/billing/reconciliations/${encodeURIComponent(requestId)}/no-charge`, payload)
  return res.data.data
}

export async function confirmSettle(requestId: string, payload: ReconciliationActionRequest): Promise<BillingSettlementView> {
  const res = await http.post(`/api/admin/billing/reconciliations/${encodeURIComponent(requestId)}/settle`, payload)
  return res.data.data
}
