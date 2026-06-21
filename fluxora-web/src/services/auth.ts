import http from './http'

export interface LoginRequest {
  username: string
  password: string
}

export interface UserInfo {
  userId: number
  username: string
  displayName: string
  scopeType: string
  tenantId: number | null
  permissions: string[]
}

export async function login(req: LoginRequest): Promise<UserInfo> {
  const res = await http.post('/api/auth/login', req)
  return res.data.data
}

export async function fetchCurrentUser(): Promise<UserInfo> {
  const res = await http.get('/api/auth/me')
  return res.data.data
}

export async function logout(): Promise<void> {
  await http.post('/api/auth/logout')
}
