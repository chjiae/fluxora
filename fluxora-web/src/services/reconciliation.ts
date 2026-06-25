import http from './http'

/** 待对账预冻结记录。只展示安全字段，不包含 API Key、请求正文、上游地址或凭证。 */
export interface BillingReservationView {
  requestId: string
  tenantId: number
  userId: number
  tenantModelCode: string
  status: string
  reservationAmount: string
  actualAmount: string | null
  releasedAmount: string
  outstandingAmount: string
  reasonCode: string | null
  upstreamDispatchState: string | null
  createdAt: string
  updatedAt: string
}

export interface ReconciliationPage {
  items: BillingReservationView[]
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

export async function confirmRelease(requestId: string, payload: ReconciliationActionRequest): Promise<BillingReservationView> {
  const res = await http.post(`/api/admin/billing/reconciliations/${encodeURIComponent(requestId)}/release`, payload)
  return res.data.data
}

export async function confirmSettle(requestId: string, payload: ReconciliationActionRequest): Promise<BillingReservationView> {
  const res = await http.post(`/api/admin/billing/reconciliations/${encodeURIComponent(requestId)}/settle`, payload)
  return res.data.data
}
