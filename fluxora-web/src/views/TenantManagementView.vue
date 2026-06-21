<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  listTenants, createTenant, updateTenant, deleteTenant,
  enableTenant, disableTenant, setTenantExpire,
  type Tenant, type TenantPage,
} from '@/services/tenant'
import { Search, Plus, Pencil, Trash2, ShieldCheck, AlertTriangle, X, ChevronRight } from 'lucide-vue-next'

const auth = useAuthStore()

const tenants = ref<Tenant[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const expireFrom = ref('')
const expireTo = ref('')

const loading = ref(false)
const errorMsg = ref('')
const toastMsg = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null

const showCreateDrawer = ref(false)
const detailTenant = ref<Tenant | null>(null)
const editMode = ref(false)
const confirmAction = ref<'disable' | 'delete' | null>(null)
const showExpireDialog = ref(false)

const form = ref({ tenantCode: '', name: '', description: '', type: 'STANDARD', enabled: true, expireAt: '' })
const formError = ref('')
const formSubmitting = ref(false)

const totalPages = computed(() => Math.ceil(total.value / size.value))

function isSelfOperated(t: Tenant) { return t.type === 'SELF_OPERATED' }
function statusLabel(s: string) { return ({ ENABLED: '启用', DISABLED: '停用', EXPIRED: '已过期', DELETED: '已删除' } as any)[s] || s }
function statusClass(s: string) { return ({ ENABLED: 'on', DISABLED: 'off', EXPIRED: 'warn', DELETED: 'del' } as any)[s] || '' }
function typeLabel(t: string) { return t === 'SELF_OPERATED' ? '自营' : '标准' }

async function loadTenants() {
  loading.value = true; errorMsg.value = ''
  try {
    const result: TenantPage = await listTenants({ keyword: keyword.value || undefined, type: typeFilter.value || undefined, status: statusFilter.value || undefined, expireFrom: expireFrom.value || undefined, expireTo: expireTo.value || undefined, page: page.value, size: size.value })
    tenants.value = result.items; total.value = result.total
  } catch (e: any) { errorMsg.value = e.userMessage || '加载失败' } finally { loading.value = false }
}

function showToast(msg: string) { toastMsg.value = msg; if (toastTimer) clearTimeout(toastTimer); toastTimer = setTimeout(() => { toastMsg.value = '' }, 3000) }
function resetFilters() { keyword.value = ''; typeFilter.value = ''; statusFilter.value = ''; expireFrom.value = ''; expireTo.value = ''; page.value = 1; loadTenants() }
function openCreate() { form.value = { tenantCode: '', name: '', description: '', type: 'STANDARD', enabled: true, expireAt: '' }; formError.value = ''; showCreateDrawer.value = true }
function openDetail(t: Tenant) { detailTenant.value = t; editMode.value = false }

async function handleCreate() {
  if (!form.value.tenantCode || !form.value.name) { formError.value = '租户码和名称不能为空'; return }
  formSubmitting.value = true; formError.value = ''
  try { await createTenant({ tenantCode: form.value.tenantCode, name: form.value.name, description: form.value.description, type: form.value.type, enabled: form.value.enabled }); showCreateDrawer.value = false; showToast('创建成功'); loadTenants() }
  catch (e: any) { formError.value = e.userMessage || '创建失败' } finally { formSubmitting.value = false }
}

function startEdit() { if (!detailTenant.value) return; form.value = { tenantCode: detailTenant.value.tenantCode, name: detailTenant.value.name, description: detailTenant.value.description || '', type: detailTenant.value.type, enabled: detailTenant.value.enabled, expireAt: detailTenant.value.expireAt ? detailTenant.value.expireAt.substring(0, 10) : '' }; formError.value = ''; editMode.value = true }

async function handleUpdate() {
  if (!form.value.name) { formError.value = '名称不能为空'; return }
  formSubmitting.value = true; formError.value = ''
  try { await updateTenant(detailTenant.value!.id, { name: form.value.name, description: form.value.description }); editMode.value = false; detailTenant.value = null; showToast('保存成功'); loadTenants() }
  catch (e: any) { formError.value = e.userMessage || '保存失败' } finally { formSubmitting.value = false }
}

async function handleEnable(t: Tenant) { try { await enableTenant(t.id); showToast('已启用'); loadTenants() } catch (e: any) { showToast(e.userMessage || '操作失败') } }

async function handleDisable() { if (!detailTenant.value) return; try { await disableTenant(detailTenant.value.id); confirmAction.value = null; detailTenant.value = null; showToast('已停用'); loadTenants() } catch (e: any) { showToast(e.userMessage || '操作失败') } }

async function handleSetExpire() { if (!detailTenant.value) return; formSubmitting.value = true; try { const at = form.value.expireAt ? form.value.expireAt + 'T23:59:59Z' : null; await setTenantExpire(detailTenant.value.id, at); showExpireDialog.value = false; detailTenant.value = null; showToast('过期时间已更新'); loadTenants() } catch (e: any) { showToast(e.userMessage || '操作失败') } finally { formSubmitting.value = false } }

async function handleDelete() { if (!detailTenant.value) return; formSubmitting.value = true; try { await deleteTenant(detailTenant.value.id); confirmAction.value = null; detailTenant.value = null; showToast('已删除'); loadTenants() } catch (e: any) { showToast(e.userMessage || '删除失败') } finally { formSubmitting.value = false } }

watch([keyword, typeFilter, statusFilter, expireFrom, expireTo], () => { page.value = 1; loadTenants() })
watch(page, () => loadTenants())
onMounted(() => loadTenants())
</script>

<template>
  <div class="tenant-mgmt">
    <div class="page-header">
      <h2>租户管理</h2>
      <button v-if="auth.canCreateTenant" class="primary" @click="openCreate"><Plus :size="16" /> 新增租户</button>
    </div>

    <div class="filters">
      <div class="search-box"><Search :size="16" /><input v-model="keyword" placeholder="搜索租户码或名称..." /></div>
      <select v-model="typeFilter"><option value="">全部类型</option><option value="SELF_OPERATED">自营</option><option value="STANDARD">标准</option></select>
      <select v-model="statusFilter"><option value="">全部状态</option><option value="ENABLED">已启用</option><option value="DISABLED">已停用</option><option value="EXPIRED">已过期</option></select>
      <input v-model="expireFrom" type="date" class="date-input" title="过期起始" />
      <input v-model="expireTo" type="date" class="date-input" title="过期截止" />
      <button class="btn-text" @click="resetFilters">重置</button>
    </div>

    <p v-if="errorMsg" class="error-banner">{{ errorMsg }}</p>
    <div v-if="loading" class="loading">加载中...</div>

    <div v-else-if="tenants.length === 0" class="empty-state">
      <div>暂无租户</div>
      <p class="muted" v-if="auth.canCreateTenant">点击"新增租户"创建第一个租户。</p>
    </div>

    <div v-else class="table-wrap">
      <table class="tenant-table">
        <thead>
          <tr><th>租户名称</th><th>租户码</th><th>类型</th><th>状态</th><th>过期时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="t in tenants" :key="t.id" @click="openDetail(t)" class="clickable">
            <td class="name-cell">
              <span class="tenant-name">{{ t.name }}</span>
              <ShieldCheck v-if="isSelfOperated(t)" :size="14" class="shield-icon" title="自营租户" />
            </td>
            <td class="code-cell"><code>{{ t.tenantCode }}</code></td>
            <td><span class="badge" :class="t.type === 'SELF_OPERATED' ? 'self' : 'std'">{{ typeLabel(t.type) }}</span></td>
            <td><span class="badge" :class="statusClass(t.status)">{{ statusLabel(t.status) }}</span></td>
            <td>{{ t.expireAt ? t.expireAt.substring(0, 10) : '—' }}</td>
            <td><button class="btn-text" @click.stop="openDetail(t)">管理 <ChevronRight :size="14" /></button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="totalPages > 1" class="pagination">
      <button :disabled="page <= 1" @click="page--">上一页</button>
      <span>{{ page }} / {{ totalPages }}</span>
      <button :disabled="page >= totalPages" @click="page++">下一页</button>
    </div>

    <!-- 详情抽屉 -->
    <Teleport to="body">
      <div v-if="detailTenant" class="drawer-overlay" @click.self="detailTenant = null; editMode = false"></div>
      <div v-if="detailTenant" class="drawer" :class="{ 'drawer-edit': editMode }">
        <div class="drawer-header">
          <h3>{{ editMode ? '编辑租户' : detailTenant.name }}</h3>
          <button class="icon-btn" @click="detailTenant = null; editMode = false"><X :size="18" /></button>
        </div>

        <!-- 查看模式 -->
        <template v-if="!editMode">
          <div class="drawer-section">
            <div class="drawer-field"><label>租户码</label><code>{{ detailTenant.tenantCode }}</code></div>
            <div class="drawer-field"><label>名称</label><span>{{ detailTenant.name }}</span></div>
            <div class="drawer-field"><label>描述</label><span>{{ detailTenant.description || '—' }}</span></div>
            <div class="drawer-field"><label>类型</label><span class="badge" :class="detailTenant.type === 'SELF_OPERATED' ? 'self' : 'std'">{{ typeLabel(detailTenant.type) }}</span></div>
            <div class="drawer-field"><label>状态</label><span class="badge" :class="statusClass(detailTenant.status)">{{ statusLabel(detailTenant.status) }}</span></div>
            <div class="drawer-field"><label>过期时间</label><span>{{ detailTenant.expireAt ? detailTenant.expireAt.substring(0, 10) : '永不过期' }}</span></div>
            <div class="drawer-field"><label>创建时间</label><span>{{ detailTenant.createdAt ? detailTenant.createdAt.substring(0, 10) : '—' }}</span></div>
          </div>

          <!-- 自营保护提示 -->
          <div v-if="isSelfOperated(detailTenant)" class="drawer-notice">
            <ShieldCheck :size="16" /> 自营租户 · 受保护：不可停用、删除或设置过期时间
          </div>

          <!-- 基本操作 -->
          <div class="drawer-section">
            <h4>基本操作</h4>
            <button v-if="auth.canUpdateTenant" class="primary" @click="startEdit"><Pencil :size="15" /> 编辑资料</button>
          </div>

          <!-- 租户状态 -->
          <div v-if="!isSelfOperated(detailTenant)" class="drawer-section">
            <h4>租户状态</h4>
            <div class="status-actions">
              <div class="status-row">
                <span>{{ detailTenant.status === 'ENABLED' ? '当前已启用' : '当前已停用' }}</span>
                <div class="status-btns">
                  <button v-if="detailTenant.status === 'DISABLED' && auth.canEnableTenant" class="primary" @click="handleEnable(detailTenant)">启用租户</button>
                  <button v-if="detailTenant.status === 'ENABLED' && auth.canDisableTenant" class="button danger-btn-outline" @click="confirmAction = 'disable'">停用租户</button>
                </div>
              </div>
              <div v-if="auth.canSetTenantExpire" class="status-row">
                <span>过期：{{ detailTenant.expireAt ? detailTenant.expireAt.substring(0, 10) : '永不过期' }}</span>
                <button class="btn-text" @click="form.expireAt = detailTenant.expireAt ? detailTenant.expireAt.substring(0, 10) : ''; showExpireDialog = true">设置过期时间</button>
              </div>
            </div>
          </div>

          <!-- 危险操作 -->
          <div v-if="!isSelfOperated(detailTenant) && auth.canDeleteTenant" class="drawer-section drawer-danger">
            <h4>危险操作</h4>
            <p class="muted">删除后该租户下所有用户将无法登录或访问数据，此操作不可恢复。</p>
            <button class="button danger-btn-outline" @click="confirmAction = 'delete'"><Trash2 :size="15" /> 删除租户</button>
          </div>
        </template>

        <!-- 编辑模式 -->
        <template v-else>
          <form @submit.prevent="handleUpdate" class="drawer-form">
            <div class="field"><label>租户码</label><input :value="form.tenantCode" disabled /><span class="field-hint">创建后不可修改</span></div>
            <div class="field"><label>名称 <span class="required">*</span></label><input v-model="form.name" :disabled="formSubmitting" /></div>
            <div class="field"><label>描述</label><input v-model="form.description" :disabled="formSubmitting" /></div>
            <div class="field"><label>类型</label><input :value="typeLabel(form.type)" disabled /></div>
            <p v-if="formError" class="error-msg">{{ formError }}</p>
            <div class="drawer-actions">
              <button type="button" class="btn-text" @click="editMode = false">取消</button>
              <button type="submit" class="primary" :disabled="formSubmitting">{{ formSubmitting ? '保存中...' : '保存' }}</button>
            </div>
          </form>
        </template>
      </div>
    </Teleport>

    <!-- 新增抽屉 -->
    <Teleport to="body">
      <div v-if="showCreateDrawer" class="drawer-overlay" @click.self="showCreateDrawer = false"></div>
      <div v-if="showCreateDrawer" class="drawer">
        <div class="drawer-header">
          <h3>新增租户</h3>
          <button class="icon-btn" @click="showCreateDrawer = false"><X :size="18" /></button>
        </div>
        <form @submit.prevent="handleCreate" class="drawer-form">
          <div class="field"><label>租户码 <span class="required">*</span></label><input v-model="form.tenantCode" placeholder="唯一标识，如 acme-corp" :disabled="formSubmitting" /><span class="field-hint">创建后不可修改</span></div>
          <div class="field"><label>名称 <span class="required">*</span></label><input v-model="form.name" placeholder="租户显示名称" :disabled="formSubmitting" /></div>
          <div class="field"><label>描述</label><input v-model="form.description" placeholder="可选描述" :disabled="formSubmitting" /></div>
          <div class="field"><label>类型</label><select v-model="form.type" :disabled="formSubmitting"><option value="STANDARD">标准</option></select><span class="field-hint">自营租户仅通过初始化流程创建</span></div>
          <p v-if="formError" class="error-msg">{{ formError }}</p>
          <div class="drawer-actions">
            <button type="button" class="btn-text" @click="showCreateDrawer = false">取消</button>
            <button type="submit" class="primary" :disabled="formSubmitting">{{ formSubmitting ? '创建中...' : '创建租户' }}</button>
          </div>
        </form>
      </div>
    </Teleport>

    <!-- 停用确认 -->
    <Teleport to="body">
      <div v-if="confirmAction === 'disable'" class="dialog-overlay" @click.self="confirmAction = null">
        <div class="dialog dialog-sm"><div class="dialog-header"><h3>确认停用</h3></div><p>停用后该租户下所有用户将无法登录。确定要停用 <b>{{ detailTenant?.name }}</b> 吗？</p><div class="dialog-actions"><button class="btn-text" @click="confirmAction = null">取消</button><button class="primary" @click="handleDisable">确认停用</button></div></div>
      </div>
    </Teleport>

    <!-- 删除确认 -->
    <Teleport to="body">
      <div v-if="confirmAction === 'delete'" class="dialog-overlay" @click.self="confirmAction = null">
        <div class="dialog dialog-sm"><div class="dialog-header"><h3><AlertTriangle :size="18" style="color:var(--danger)"/> 确认删除</h3></div><p class="delete-warn">此操作不可恢复。删除后该租户下所有用户将无法登录或访问数据。</p><div class="dialog-actions"><button class="btn-text" @click="confirmAction = null">取消</button><button class="primary danger-btn" :disabled="formSubmitting" @click="handleDelete">{{ formSubmitting ? '删除中...' : '确认删除' }}</button></div></div>
      </div>
    </Teleport>

    <!-- 过期时间 -->
    <Teleport to="body">
      <div v-if="showExpireDialog" class="dialog-overlay" @click.self="showExpireDialog = false">
        <div class="dialog dialog-sm"><div class="dialog-header"><h3>设置过期时间</h3></div><div class="field"><label>过期日期（留空则永不过期）</label><input v-model="form.expireAt" type="date" :disabled="formSubmitting" /></div><div class="dialog-actions"><button class="btn-text" @click="showExpireDialog = false">取消</button><button class="primary" :disabled="formSubmitting" @click="handleSetExpire">保存</button></div></div>
      </div>
    </Teleport>

    <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>
  </div>
</template>

<style scoped>
.tenant-mgmt { }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px }
.page-header h2 { margin: 0; font-size: 1.25rem; font-weight: 650 }
.primary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; border: none; border-radius: 8px; background: var(--text); color: var(--bg); font-size: 13px; font-weight: 600; cursor: pointer }
.primary:disabled { opacity: .4; cursor: not-allowed }
.filters { display: flex; gap: 8px; align-items: center; margin-bottom: 16px; flex-wrap: wrap }
.search-box { display: flex; align-items: center; gap: 6px; padding: 6px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); flex: 1; min-width: 160px }
.search-box input { border: none; outline: none; font-size: 13px; width: 100%; background: none; color: var(--text) }
.filters select, .date-input { padding: 6px 10px; border: 1px solid var(--border); border-radius: 8px; font-size: 13px; background: var(--surface); outline: none; color: var(--text) }
.date-input { width: 130px }
.btn-text { padding: 6px 12px; border: none; background: none; color: var(--accent); font-size: 13px; cursor: pointer; font-weight: 500 }
.error-msg { color: var(--danger); font-size: 13px; margin: 4px 0 0 }
.error-banner { padding: 10px 14px; border-radius: 8px; background: color-mix(in srgb, var(--danger) 8%, var(--bg)); color: var(--danger); font-size: 13px; margin-bottom: 12px }
.loading { padding: 40px 0; text-align: center; color: var(--text-muted); font-size: 14px }
.table-wrap { overflow-x: auto; margin: 0 -8px; padding: 0 8px }
.tenant-table { width: 100%; border-collapse: collapse; font-size: 13px; min-width: 600px }
.tenant-table th { text-align: left; padding: 10px 12px; border-bottom: 2px solid var(--border); font-weight: 600; color: var(--text-muted); font-size: 12px; white-space: nowrap }
.tenant-table td { padding: 10px 12px; border-bottom: 1px solid var(--border); vertical-align: middle }
.tenant-table .clickable { cursor: pointer; transition: background .1s }
.tenant-table .clickable:hover { background: var(--surface-elevated) }
.name-cell { display: table-cell; font-weight: 600 }
.name-cell .tenant-name { margin-right: 6px }
.shield-icon { color: var(--accent); vertical-align: middle; margin-left: 2px }
.code-cell code { font-family: var(--font-mono); font-size: 12px; background: var(--surface-elevated); padding: 2px 6px; border-radius: 4px; border: 1px solid var(--border); color: var(--text-muted) }
.badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; font-weight: 600; white-space: nowrap }
.badge.self { background: color-mix(in srgb, var(--accent) 12%, transparent); color: var(--accent) }
.badge.std { background: color-mix(in srgb, var(--text-muted) 8%, transparent); color: var(--text-muted) }
.badge.on { background: color-mix(in srgb, #22c55e 12%, transparent); color: #16a34a }
.badge.off { background: color-mix(in srgb, var(--text-muted) 8%, transparent); color: var(--text-muted) }
.badge.warn { background: color-mix(in srgb, #f59e0b 12%, transparent); color: #d97706 }
.pagination { display: flex; justify-content: center; gap: 16px; align-items: center; margin: 20px 0 0; font-size: 13px }
.pagination button { padding: 4px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); cursor: pointer; font-size: 13px; color: var(--text) }
.pagination button:disabled { opacity: .3; cursor: not-allowed }

/* === Drawer === */
.drawer-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.25); z-index: 90 }
.drawer {
  position: fixed; top: 0; right: 0; width: 420px; max-width: 100vw; height: 100dvh;
  background: var(--surface); z-index: 100; overflow-y: auto;
  box-shadow: -4px 0 24px rgba(0,0,0,.08); padding: 28px 28px 40px;
  animation: slideInRight .2s ease-out;
}
@keyframes slideInRight { from { transform: translateX(40px); opacity: .8 } to { transform: translateX(0); opacity: 1 } }
.drawer-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px }
.drawer-header h3 { font-size: 1.125rem; font-weight: 650 }
.drawer-section { margin-bottom: 24px; padding-bottom: 20px; border-bottom: 1px solid var(--border) }
.drawer-section h4 { font-size: 13px; font-weight: 600; color: var(--text-muted); margin-bottom: 12px; text-transform: uppercase; letter-spacing: .04em }
.drawer-field { margin-bottom: 14px }
.drawer-field label { display: block; font-size: 12px; font-weight: 600; color: var(--text-muted); margin-bottom: 3px }
.drawer-field span, .drawer-field code { font-size: 14px }
.drawer-field code { font-family: var(--font-mono); background: var(--surface-elevated); padding: 2px 6px; border-radius: 4px; border: 1px solid var(--border) }
.drawer-notice { padding: 12px; border-radius: 8px; background: color-mix(in srgb, var(--accent) 8%, transparent); color: var(--accent); font-size: 13px; display: flex; align-items: center; gap: 8px; margin-bottom: 20px; font-weight: 500 }
.drawer-danger { border-color: var(--danger) }
.drawer-danger h4 { color: var(--danger) }
.drawer-form { display: flex; flex-direction: column; gap: 14px }
.drawer-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px }
.status-actions { display: flex; flex-direction: column; gap: 12px }
.status-row { display: flex; justify-content: space-between; align-items: center; font-size: 13px }
.status-row span { color: var(--text-muted) }
.danger-btn-outline { border-color: var(--danger); color: var(--danger) }
.danger-btn-outline:hover { background: var(--danger); color: #fff }
.danger-btn { background: var(--danger) !important; color: #fff !important }
.delete-warn { color: var(--danger) !important }

/* === Dialog === */
.dialog-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.25); display: flex; align-items: center; justify-content: center; z-index: 200 }
.dialog { background: var(--surface); border-radius: 12px; padding: 24px; width: 380px; max-width: 90vw; box-shadow: 0 12px 40px rgba(0,0,0,.12) }
.dialog-sm { }
.dialog-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px }
.dialog-header h3 { font-size: 16px; font-weight: 650; display: flex; align-items: center; gap: 8px }
.dialog p { font-size: 14px; color: var(--text-muted); margin-bottom: 20px; line-height: 1.55 }
.dialog .field { margin-bottom: 16px }
.dialog .field label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 4px }
.dialog .field input { width: 100%; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; font-size: 14px; font-family: var(--font-sans); background: var(--bg); outline: none }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px }
.toast { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%); padding: 10px 24px; border-radius: 10px; background: var(--text); color: var(--bg); font-size: 14px; z-index: 300; box-shadow: 0 4px 16px rgba(0,0,0,.15) }
.icon-btn { display: flex; align-items: center; justify-content: center; width: 32px; height: 32px; border: 1px solid var(--border); border-radius: 8px; background: none; cursor: pointer; color: var(--text-muted) }
.icon-btn:hover { color: var(--text) }
.required { color: var(--danger) }
.muted { color: var(--text-muted); font-size: 13px }
@media (max-width: 720px) {
  .filters { flex-direction: column }
  .filters select, .date-input { width: 100% }
  .drawer { width: 100vw }
}
</style>
