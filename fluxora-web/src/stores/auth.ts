import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, logout as apiLogout, fetchCurrentUser, type UserInfo } from '@/services/auth'
import { fetchSelfOperatedStatus, initializeSelfOperated, type SelfOperatedInitRequest } from '@/services/tenant'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(null)
  const loading = ref(false)
  const error = ref('')
  const selfOperatedInitialized = ref<boolean | null>(null)

  const isLoggedIn = computed(() => !!user.value)
  const isPlatformAdmin = computed(() => user.value?.permissions?.includes('PLATFORM_ADMIN') ?? false)
  const isTenantAdmin = computed(() => user.value?.permissions?.includes('TENANT_ADMIN') ?? false)

  async function loginAction(username: string, password: string) {
    loading.value = true
    error.value = ''
    try {
      const u = await apiLogin({ username, password })
      user.value = u
      return u
    } catch (e: any) {
      error.value = e.userMessage || '登录失败，请稍后重试'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function checkAuth() {
    loading.value = true
    try {
      user.value = await fetchCurrentUser()
    } catch {
      user.value = null
    } finally {
      loading.value = false
    }
  }

  async function logoutAction() {
    try {
      await apiLogout()
    } finally {
      user.value = null
    }
  }

  async function checkSelfOperatedStatus() {
    try {
      const status = await fetchSelfOperatedStatus()
      selfOperatedInitialized.value = status.initialized
      return status.initialized
    } catch {
      return true // 出错时假定已初始化，避免阻塞
    }
  }

  async function initSelfOperated(req: SelfOperatedInitRequest) {
    loading.value = true
    error.value = ''
    try {
      const result = await initializeSelfOperated(req)
      selfOperatedInitialized.value = true
      return result
    } catch (e: any) {
      error.value = e.userMessage || '初始化失败，请稍后重试'
      throw e
    } finally {
      loading.value = false
    }
  }

  return {
    user,
    loading,
    error,
    selfOperatedInitialized,
    isLoggedIn,
    isPlatformAdmin,
    isTenantAdmin,
    loginAction,
    checkAuth,
    logoutAction,
    checkSelfOperatedStatus,
    initSelfOperated,
  }
})
