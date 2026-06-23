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
  const canReadTenants = computed(() => user.value?.permissions?.includes('TENANT_READ') ?? false)
  const canCreateTenant = computed(() => user.value?.permissions?.includes('TENANT_CREATE') ?? false)
  const canUpdateTenant = computed(() => user.value?.permissions?.includes('TENANT_UPDATE') ?? false)
  const canEnableTenant = computed(() => user.value?.permissions?.includes('TENANT_ENABLE') ?? false)
  const canDisableTenant = computed(() => user.value?.permissions?.includes('TENANT_DISABLE') ?? false)
  const canDeleteTenant = computed(() => user.value?.permissions?.includes('TENANT_DELETE') ?? false)
  const canSetTenantExpire = computed(() => user.value?.permissions?.includes('TENANT_EXPIRE_SET') ?? false)
  // 成员管理细粒度权限（V4 引入），同时授予平台管理员与租户管理员，
  // 跨租户与角色升级保护放在后端服务层强制
  const canReadMembers = computed(() => user.value?.permissions?.includes('MEMBER_READ') ?? false)
  const canCreateMember = computed(() => user.value?.permissions?.includes('MEMBER_CREATE') ?? false)
  const canUpdateMember = computed(() => user.value?.permissions?.includes('MEMBER_UPDATE') ?? false)
  const canEnableMember = computed(() => user.value?.permissions?.includes('MEMBER_ENABLE') ?? false)
  const canDisableMember = computed(() => user.value?.permissions?.includes('MEMBER_DISABLE') ?? false)
  const canDeleteMember = computed(() => user.value?.permissions?.includes('MEMBER_DELETE') ?? false)
  const canResetMemberPassword = computed(() => user.value?.permissions?.includes('MEMBER_PASSWORD_RESET') ?? false)
  // API Key 权限（V5 引入）
  const canManageOwnApiKeys = computed(() => user.value?.permissions?.includes('API_KEY_SELF_MANAGE') ?? false)
  const canManageTenantApiKeys = computed(() => user.value?.permissions?.includes('API_KEY_TENANT_MANAGE') ?? false)
  const canManageCrossTenantApiKeys = computed(() => user.value?.permissions?.includes('API_KEY_CROSS_TENANT_MANAGE') ?? false)
  // 额度权限（V5 引入）
  const canReadOwnCredit = computed(() => user.value?.permissions?.includes('CREDIT_SELF_READ') ?? false)
  const canReadTenantCredit = computed(() => user.value?.permissions?.includes('CREDIT_TENANT_READ') ?? false)
  const canAdjustTenantCredit = computed(() => user.value?.permissions?.includes('CREDIT_TENANT_ADJUST') ?? false)
  const canAdjustCrossTenantCredit = computed(() => user.value?.permissions?.includes('CREDIT_CROSS_TENANT_ADJUST') ?? false)
  // 卡密权限（V6 引入）
  const canRedeemCards = computed(() => user.value?.permissions?.includes('CARD_SELF_REDEEM') ?? false)
  const canManageCards = computed(() => user.value?.permissions?.includes('CARD_TENANT_MANAGE') ?? false)
  const canManageCrossTenantCards = computed(() => user.value?.permissions?.includes('CARD_CROSS_TENANT_MANAGE') ?? false)
  const canReadCardRecords = computed(() => user.value?.permissions?.includes('CARD_RECORD_READ_TENANT') ?? false)
  // 上游配置权限：页面可见性仅用于体验，服务端仍是最终鉴权边界。
  const canReadUpstream = computed(() => user.value?.permissions?.includes('UPSTREAM_READ') ?? false)
  const canCreateUpstream = computed(() => user.value?.permissions?.includes('UPSTREAM_CREATE') ?? false)
  const canUpdateUpstream = computed(() => user.value?.permissions?.includes('UPSTREAM_UPDATE') ?? false)
  const canEnableUpstream = computed(() => user.value?.permissions?.includes('UPSTREAM_ENABLE') ?? false)
  const canDisableUpstream = computed(() => user.value?.permissions?.includes('UPSTREAM_DISABLE') ?? false)
  const canDeleteUpstream = computed(() => user.value?.permissions?.includes('UPSTREAM_DELETE') ?? false)
  const canReadModelCatalog = computed(() => user.value?.permissions?.includes('MODEL_CATALOG_READ') ?? false)
  const canManagePlatformModels = computed(() => user.value?.permissions?.includes('MODEL_PLATFORM_MANAGE') ?? false)
  const canManageTenantModels = computed(() => user.value?.permissions?.includes('MODEL_CATALOG_MANAGE') ?? false)

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
    canReadTenants,
    canCreateTenant,
    canUpdateTenant,
    canEnableTenant,
    canDisableTenant,
    canDeleteTenant,
    canSetTenantExpire,
    canReadMembers,
    canCreateMember,
    canUpdateMember,
    canEnableMember,
    canDisableMember,
    canDeleteMember,
    canResetMemberPassword,
    canManageOwnApiKeys,
    canManageTenantApiKeys,
    canManageCrossTenantApiKeys,
    canReadOwnCredit,
    canReadTenantCredit,
    canAdjustTenantCredit,
    canAdjustCrossTenantCredit,
    canRedeemCards,
    canManageCards,
    canManageCrossTenantCards,
    canReadCardRecords,
    canReadUpstream,
    canCreateUpstream,
    canUpdateUpstream,
    canEnableUpstream,
    canDisableUpstream,
    canDeleteUpstream,
    canReadModelCatalog,
    canManagePlatformModels,
    canManageTenantModels,
    loginAction,
    checkAuth,
    logoutAction,
    checkSelfOperatedStatus,
    initSelfOperated,
  }
})
