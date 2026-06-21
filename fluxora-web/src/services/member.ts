import http from './http'

/**
 * 成员管理 API service。
 *
 * 路由分为两类：
 *   - /api/tenant/{tenantId}/members*：平台管理员显式指定租户，传入 tenantId；
 *   - /api/members*：当前上下文租户（租户管理员的默认入口，后端从 JWT 解析）。
 *
 * 错误处理由 src/services/http.ts 的拦截器接管，UI 看到统一中文文案。
 */

export interface Member {
  id: number
  username: string
  displayName: string | null
  email: string | null
  roleCode: string
  roleName: string
  /** 状态：ENABLED / DISABLED；DELETED 已被后端默认过滤 */
  status: string
  tenantId: number
  tenantCode: string
  tenantName: string
  createdAt: string
  updatedAt: string
}

export interface MemberPage {
  items: Member[]
  total: number
  page: number
  size: number
}

export interface RoleOption {
  code: string
  name: string
}

export interface MemberQuery {
  keyword?: string
  status?: '' | 'ENABLED' | 'DISABLED'
  roleCode?: '' | 'TENANT_ADMIN' | 'TENANT_MEMBER'
  page?: number
  size?: number
}

export interface CreateMemberRequest {
  username: string
  displayName?: string
  email?: string
  password: string
  roleCode: string
}

export interface UpdateProfileRequest {
  displayName?: string
  email?: string
}

/** 平台管理员视角：指定租户分页查询 */
export async function listMembersByTenant(tenantId: number, params: MemberQuery): Promise<MemberPage> {
  const res = await http.get(`/api/tenant/${tenantId}/members`, { params })
  return res.data.data
}

/** 租户管理员视角：当前用户所属租户分页查询 */
export async function listMembersInCurrentTenant(params: MemberQuery): Promise<MemberPage> {
  const res = await http.get('/api/members', { params })
  return res.data.data
}

export async function getMember(id: number): Promise<Member> {
  const res = await http.get(`/api/members/${id}`)
  return res.data.data
}

export async function createMember(tenantId: number, req: CreateMemberRequest): Promise<Member> {
  const res = await http.post(`/api/tenant/${tenantId}/members`, req)
  return res.data.data
}

export async function updateMemberProfile(id: number, req: UpdateProfileRequest): Promise<Member> {
  const res = await http.put(`/api/members/${id}`, req)
  return res.data.data
}

export async function updateMemberRole(id: number, roleCode: string): Promise<Member> {
  const res = await http.put(`/api/members/${id}/role`, { roleCode })
  return res.data.data
}

export async function enableMember(id: number): Promise<Member> {
  const res = await http.put(`/api/members/${id}/enable`, {})
  return res.data.data
}

export async function disableMember(id: number): Promise<Member> {
  const res = await http.put(`/api/members/${id}/disable`, {})
  return res.data.data
}

export async function deleteMember(id: number): Promise<void> {
  await http.delete(`/api/members/${id}`)
}

export async function resetMemberPassword(id: number, newPassword: string): Promise<void> {
  await http.put(`/api/members/${id}/password`, { newPassword })
}

export async function fetchAssignableRoles(): Promise<RoleOption[]> {
  const res = await http.get('/api/members/assignable-roles')
  return res.data.data
}
