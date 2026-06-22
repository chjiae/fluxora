<script setup lang="ts">
/**
 * 租户管理页面
 *
 * 设计目标（区别于传统后台管理模板）：
 * - 整页采用 CSS Grid 分区，仅"表格区"内部滚动，工具栏与分页常驻可见，避免上下滚动后失去操作上下文。
 * - 行点击即打开详情 Modal，删去重复的"查看详情/更多操作"列，行末常驻 kebab 触发上下文菜单。
 * - 详情态只读浏览；"管理租户"作为统一编辑面板，资料 / 状态 / 危险操作三段都在同一 Modal 内完成。
 * - 工具栏使用 inline chip 切换类型/状态，去掉对租户场景几乎无价值的日期筛选控件。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NDropdown, NIcon } from 'naive-ui'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import {
  Building2,
  Clock,
  MoreHorizontal,
  Pencil,
  Plus,
  Power,
  Search,
  ShieldAlert,
  Trash2,
  X,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import {
  createTenant,
  deleteTenant,
  disableTenant,
  enableTenant,
  fetchTenantStats,
  listTenants,
  setTenantExpire,
  updateTenant,
  type Tenant,
  type TenantPage,
  type TenantStats,
} from '@/services/tenant'

const auth = useAuthStore()
const router = useRouter()
const message = useMessage()
const dialog = useDialog()

// ---------- 列表状态 ----------
const tenants = ref<Tenant[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const typeFilter = ref<'' | 'SELF_OPERATED' | 'STANDARD'>('')
const statusFilter = ref<'' | 'ENABLED' | 'DISABLED' | 'EXPIRED'>('')
const loading = ref(false)

// ---------- 顶部指标条：聚合 SQL，单次拉取，与列表查询解耦 ----------
const stats = ref<TenantStats | null>(null)
const statsLoading = ref(false)
async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await fetchTenantStats(30)
  } catch {
    // 指标条失败不影响主表；静默处理，由表格的错误兜底承担显式反馈
    stats.value = null
  } finally {
    statsLoading.value = false
  }
}
const metricItems = computed(() => [
  { label: '租户总数', value: stats.value?.total ?? null, hint: '未删除' },
  { label: '启用中', value: stats.value?.enabled ?? null },
  { label: '已停用', value: stats.value?.disabled ?? null, tone: 'warn' as const },
  { label: '已过期', value: stats.value?.expired ?? null, tone: 'danger' as const },
  { label: '即将到期', value: stats.value?.expiringSoon ?? null, hint: '30 天内', tone: 'warn' as const },
  { label: '自营租户', value: stats.value?.selfOperated ?? null },
])

// ---------- Modal 状态 ----------
// 'edit' 是一个统一的管理面板，内部三段独立操作；不再为"过期时间"开第二个 Modal。
type ModalMode = 'hidden' | 'detail' | 'edit' | 'create'
const modalMode = ref<ModalMode>('hidden')
const selectedTenant = ref<Tenant | null>(null)
const formSubmitting = ref(false)
// 各区块独立的保存中状态，避免一个 spinner 让用户分不清是改名称还是改过期。
const profileSaving = ref(false)
const expireSaving = ref(false)
const statusSaving = ref(false)

const createForm = ref({ tenantCode: '', name: '', description: '', type: 'STANDARD' })
const editForm = ref({ name: '', description: '' })
const expireAtInput = ref<number | null>(null)
const createFormRef = ref<FormInst | null>(null)
const editFormRef = ref<FormInst | null>(null)

// 仅允许安全的中文校验反馈，避免把接口异常暴露到表单。
const createFormRules: FormRules = {
  tenantCode: [{ required: true, pattern: /^[a-z0-9-]+$/, message: '租户码仅支持小写字母、数字和连字符', trigger: ['input', 'blur'] }],
  name: [{ required: true, message: '请输入租户名称', trigger: ['input', 'blur'] }],
}
const editFormRules: FormRules = {
  name: [{ required: true, message: '请输入租户名称', trigger: ['input', 'blur'] }],
}

const typeOptions = [
  { label: '全部', value: '' as const },
  { label: '自营', value: 'SELF_OPERATED' as const },
  { label: '标准', value: 'STANDARD' as const },
]
const statusOptions = [
  { label: '全部', value: '' as const },
  { label: '启用', value: 'ENABLED' as const },
  { label: '停用', value: 'DISABLED' as const },
  { label: '过期', value: 'EXPIRED' as const },
]

// ---------- 工具函数 ----------
function statusLabel(s: string) {
  return ({ ENABLED: '启用', DISABLED: '停用', EXPIRED: '过期' } as Record<string, string>)[s] || s
}

/** 列表使用相对时间，1 天内显示"x 小时前"，更利于扫读。 */
function timeAgo(iso: string | null | undefined) {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return '—'
  const diff = Date.now() - t
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} 天前`
  return iso.slice(0, 10)
}

// ---------- 列表加载 ----------
async function loadTenants() {
  loading.value = true
  try {
    const r: TenantPage = await listTenants({
      keyword: keyword.value || undefined,
      type: typeFilter.value || undefined,
      status: statusFilter.value || undefined,
      page: page.value,
      size: size.value,
    })
    tenants.value = r.items
    total.value = r.total
  } catch {
    message.error('加载租户列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
/**
 * 写操作后的轻量刷新：表格与指标条并行重拉。
 * 指标条静默失败，不打扰用户的当前操作反馈。
 */
async function refreshAfterWrite() {
  await Promise.all([loadTenants(), loadStats()])
}
function resetFilters() {
  keyword.value = ''
  typeFilter.value = ''
  statusFilter.value = ''
  page.value = 1
  loadTenants()
}

watch([keyword, typeFilter, statusFilter], () => {
  page.value = 1
  loadTenants()
})
watch(page, () => loadTenants())
onMounted(() => {
  // 列表与指标并行加载，互不阻塞
  loadStats()
  loadTenants()
})

// ---------- Modal 控制 ----------
function openDetail(t: Tenant) {
  selectedTenant.value = t
  modalMode.value = 'detail'
}
function openCreate() {
  createForm.value = { tenantCode: '', name: '', description: '', type: 'STANDARD' }
  modalMode.value = 'create'
}
function enterEdit() {
  if (!selectedTenant.value) return
  // 一次性加载到面板：资料字段 + 过期时间。状态切换无表单，按按钮触发。
  editForm.value = {
    name: selectedTenant.value.name,
    description: selectedTenant.value.description || '',
  }
  expireAtInput.value = selectedTenant.value.expireAt
    ? new Date(selectedTenant.value.expireAt).getTime()
    : null
  modalMode.value = 'edit'
}
function closeModal() {
  modalMode.value = 'hidden'
  selectedTenant.value = null
}
function backToDetail() {
  modalMode.value = 'detail'
}

// ---------- 写操作 ----------
async function handleCreate() {
  try {
    await createFormRef.value?.validate()
  } catch { return }
  formSubmitting.value = true
  try {
    await createTenant({ ...createForm.value, enabled: true })
    closeModal()
    message.success('租户已创建')
    await refreshAfterWrite()
  } catch {
    message.error('创建租户失败，请稍后重试')
  } finally {
    formSubmitting.value = false
  }
}

/** 资料段保存：只在 name 或 description 有变化时调接口。 */
async function saveProfile() {
  if (!selectedTenant.value) return
  try {
    await editFormRef.value?.validate()
  } catch { return }
  profileSaving.value = true
  try {
    const updated = await updateTenant(selectedTenant.value.id, editForm.value)
    selectedTenant.value = updated
    message.success('租户资料已保存')
    await refreshAfterWrite()
  } catch {
    message.error('保存租户资料失败，请稍后重试')
  } finally {
    profileSaving.value = false
  }
}

async function doEnable() {
  if (!selectedTenant.value) return
  const t = selectedTenant.value
  statusSaving.value = true
  try {
    const updated = await enableTenant(t.id)
    selectedTenant.value = updated
    message.success('租户已启用')
    await refreshAfterWrite()
  } catch {
    message.error('启用租户失败，请稍后重试')
  } finally {
    statusSaving.value = false
  }
}

async function doDisable() {
  if (!selectedTenant.value || selectedTenant.value.type === 'SELF_OPERATED') return
  const t = selectedTenant.value
  statusSaving.value = true
  try {
    const updated = await disableTenant(t.id)
    selectedTenant.value = updated
    message.success('租户已停用')
    await refreshAfterWrite()
  } catch {
    message.error('停用租户失败，请稍后重试')
  } finally {
    statusSaving.value = false
  }
}

async function doDelete() {
  if (!selectedTenant.value || selectedTenant.value.type === 'SELF_OPERATED') return
  const t = selectedTenant.value
  try {
    await deleteTenant(t.id)
    closeModal()
    message.success('租户已删除')
    await refreshAfterWrite()
  } catch {
    message.error('删除租户失败，请稍后重试')
  }
}

/** 过期时间段保存：留空 = 永不过期。保存后不关闭面板，让用户继续修改其他字段。 */
async function saveExpireAt() {
  if (!selectedTenant.value || selectedTenant.value.type === 'SELF_OPERATED') return
  expireSaving.value = true
  try {
    const updated = await setTenantExpire(
      selectedTenant.value.id,
      expireAtInput.value ? new Date(expireAtInput.value).toISOString() : null,
    )
    selectedTenant.value = updated
    message.success('过期时间已更新')
    await refreshAfterWrite()
  } catch {
    message.error('更新过期时间失败，请稍后重试')
  } finally {
    expireSaving.value = false
  }
}

function confirmDisable() {
  if (!selectedTenant.value) return
  const name = selectedTenant.value.name
  dialog.warning({
    title: '确认停用',
    content: `停用后 ${name} 下所有用户将无法登录。确定停用？`,
    positiveText: '确认停用',
    negativeText: '取消',
    onPositiveClick: doDisable,
  })
}
function confirmDelete() {
  if (!selectedTenant.value) return
  const name = selectedTenant.value.name
  dialog.error({
    title: '确认删除',
    content: `删除 ${name} 后不可恢复。确定删除？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: doDelete,
  })
}

// ---------- 行内 kebab 菜单：精简到不与"管理面板"重复 ----------
// 高频动作（启用/停用/过期）已经由"管理"按钮统一承载；这里只保留
// "打开管理"作为快捷入口，以及"删除"这种从详情态进入两步太重的操作。
function rowMenuOptions(t: Tenant) {
  const opts: { key: string; label: string; disabled?: boolean }[] = []
  if (auth.canUpdateTenant) opts.push({ key: 'manage', label: '管理租户' })
  // 成员管理入口：受 MEMBER_READ 权限控制，已删除租户不展示
  if (auth.canReadMembers && t.status !== 'DELETED') opts.push({ key: 'members', label: '管理成员' })
  if (auth.canDeleteTenant && t.type !== 'SELF_OPERATED') opts.push({ key: 'delete', label: '删除' })
  return opts
}
function handleRowMenu(key: string, t: Tenant) {
  if (key === 'manage') {
    selectedTenant.value = t
    enterEdit()
  } else if (key === 'members') {
    router.push(`/console/tenants/${t.id}/members`)
  } else if (key === 'delete') {
    selectedTenant.value = t
    confirmDelete()
  }
}

// ---------- 表格列定义 ----------
// 状态点 + 标签的紧凑写法，避免 Tag 在密集列表里制造色块感。
function statusDot(s: string) {
  return h('span', { class: ['status-row', `status-${s.toLowerCase()}`] }, [
    h('span', { class: 'status-dot' }),
    h('span', null, statusLabel(s)),
  ])
}

const columns = computed<DataTableColumns<Tenant>>(() => [
  {
    title: '租户',
    key: 'name',
    minWidth: 220,
    render: (row) => h('div', { class: 'cell-tenant' }, [
      h('div', { class: 'cell-tenant-name' }, row.name),
      h('div', { class: 'cell-tenant-code' }, row.tenantCode),
    ]),
  },
  {
    title: '类型',
    key: 'type',
    width: 90,
    render: (row) => h('span', { class: 'cell-type' }, row.type === 'SELF_OPERATED' ? '自营' : '标准'),
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) => statusDot(row.status),
  },
  {
    title: '过期',
    key: 'expireAt',
    width: 120,
    render: (row) => h('span', { class: 'cell-muted' }, row.expireAt?.slice(0, 10) || '永不过期'),
  },
  {
    title: '更新',
    key: 'updatedAt',
    width: 110,
    render: (row) => h('span', { class: 'cell-muted' }, timeAgo(row.updatedAt)),
  },
  {
    title: '',
    key: '__row_actions',
    width: 44,
    align: 'right',
    className: 'col-row-actions',
    render: (row) => {
      const opts = rowMenuOptions(row)
      if (!opts.length) return null
      return h(
        NDropdown,
        {
          trigger: 'click',
          placement: 'bottom-end',
          options: opts,
          onSelect: (k: string) => handleRowMenu(k, row),
        },
        {
          default: () => h(
            NButton,
            {
              text: true,
              class: 'row-kebab',
              'aria-label': '更多操作',
              // 行点击会冒泡到 row-click 打开详情，这里需要拦截。
              onClick: (e: MouseEvent) => e.stopPropagation(),
            },
            { default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }) },
          ),
        },
      )
    },
  },
])

// 整行可点击：与"行尾 kebab"组合，去掉了重复的"查看详情"按钮。
function rowProps(row: Tenant) {
  return {
    style: 'cursor: pointer',
    onClick: () => openDetail(row),
  }
}

const showPagination = computed(() => total.value > size.value)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const hasFilter = computed(() => !!keyword.value || !!typeFilter.value || !!statusFilter.value)

// 资料段是否有未保存修改：未变化时禁用"保存资料"按钮，避免空提交。
const profileDirty = computed(() => {
  if (!selectedTenant.value) return false
  return (
    editForm.value.name.trim() !== selectedTenant.value.name ||
    (editForm.value.description || '') !== (selectedTenant.value.description || '')
  )
})
// 过期时间是否有未保存修改：当前值（ms）与租户已存值（ISO→ms）对比。
const expireDirty = computed(() => {
  if (!selectedTenant.value) return false
  const current = selectedTenant.value.expireAt
    ? new Date(selectedTenant.value.expireAt).getTime()
    : null
  return expireAtInput.value !== current
})
</script>

<template>
  <section class="tenant-page">
    <!-- 行 1：页头。常驻可见，不参与表格滚动。 -->
    <header class="page-hdr">
      <div class="page-hdr-text">
        <h1>租户管理</h1>
        <p>管理平台中的租户状态、资料和到期时间。</p>
      </div>
      <n-button
        v-if="auth.canCreateTenant"
        type="primary"
        class="btn-primary-action"
        @click="openCreate"
      >
        <template #icon>
          <n-icon><Plus /></n-icon>
        </template>
        新增租户
      </n-button>
    </header>

    <!-- 行 2：指标条。聚合 SQL 提供租户总数、启停、过期、即将到期等上下文 -->
    <MetricStrip :items="metricItems" :loading="statsLoading" />

    <!-- 行 3：工具栏。搜索 + chip 类型 + chip 状态。日期筛选已移除。 -->
    <div class="toolbar">
      <label class="search">
        <n-icon class="search-icon" :size="16"><Search /></n-icon>
        <input
          v-model="keyword"
          class="search-input"
          type="text"
          placeholder="搜索租户名称或租户码"
          aria-label="搜索租户"
        />
        <button
          v-if="keyword"
          type="button"
          class="search-clear"
          aria-label="清除搜索"
          @click="keyword = ''"
        >
          <n-icon :size="14"><X /></n-icon>
        </button>
      </label>

      <div class="chip-group" role="radiogroup" aria-label="类型筛选">
        <button
          v-for="opt in typeOptions"
          :key="`type-${opt.value}`"
          type="button"
          class="chip"
          :class="{ 'is-active': typeFilter === opt.value }"
          role="radio"
          :aria-checked="typeFilter === opt.value"
          @click="typeFilter = opt.value"
        >{{ opt.label }}</button>
      </div>

      <div class="chip-group" role="radiogroup" aria-label="状态筛选">
        <button
          v-for="opt in statusOptions"
          :key="`status-${opt.value}`"
          type="button"
          class="chip"
          :class="{ 'is-active': statusFilter === opt.value }"
          role="radio"
          :aria-checked="statusFilter === opt.value"
          @click="statusFilter = opt.value"
        >{{ opt.label }}</button>
      </div>

      <button
        v-if="hasFilter"
        type="button"
        class="reset-btn"
        @click="resetFilters"
      >重置</button>
    </div>

    <!-- 行 3：表格区。仅这里内部滚动。 -->
    <div class="table-region">
      <n-data-table
        v-if="tenants.length || loading"
        :columns="columns"
        :data="tenants"
        :loading="loading"
        :pagination="false"
        :bordered="false"
        :single-line="false"
        :row-props="rowProps"
        flex-height
        class="tenant-table"
        size="medium"
      />
      <div v-else class="empty">
        <n-empty :description="hasFilter ? '没有符合筛选条件的租户' : '还没有租户'">
          <template #icon>
            <n-icon :size="36"><Building2 /></n-icon>
          </template>
          <template #extra>
            <n-button
              v-if="!hasFilter && auth.canCreateTenant"
              type="primary"
              @click="openCreate"
            >新增第一个租户</n-button>
            <n-button v-else-if="hasFilter" @click="resetFilters">清除筛选条件</n-button>
          </template>
        </n-empty>
      </div>
    </div>

    <!-- 行 4：分页。常驻可见。 -->
    <footer class="page-foot">
      <span class="page-foot-summary">
        共 <strong>{{ total }}</strong> 个租户
      </span>
      <n-pagination
        v-if="showPagination"
        v-model:page="page"
        :page-count="pageCount"
        :page-size="size"
        size="small"
      />
    </footer>

    <!-- ============================================================ -->
    <!-- 详情 / 编辑 / 创建 / 设置过期时间 统一在居中 Modal 中处理     -->
    <!-- ============================================================ -->

    <!-- 详情态：只读视图 + 顶部 kebab + 底部编辑按钮 -->
    <n-modal
      :show="modalMode === 'detail' && !!selectedTenant"
      preset="card"
      :title="''"
      class="tenant-modal"
      :bordered="false"
      :segmented="{ content: 'soft' }"
      style="width: min(560px, calc(100vw - 32px))"
      @update:show="(v: boolean) => { if (!v) closeModal() }"
    >
      <template v-if="selectedTenant" #header>
        <div class="modal-head">
          <div class="modal-head-text">
            <h2>{{ selectedTenant.name }}</h2>
            <span class="modal-head-code">{{ selectedTenant.tenantCode }}</span>
          </div>
          <n-dropdown
            v-if="rowMenuOptions(selectedTenant).length"
            :options="rowMenuOptions(selectedTenant)"
            trigger="click"
            placement="bottom-end"
            @select="(k: string) => handleRowMenu(k, selectedTenant!)"
          >
            <n-button text aria-label="更多操作">
              <template #icon>
                <n-icon :size="18"><MoreHorizontal /></n-icon>
              </template>
            </n-button>
          </n-dropdown>
        </div>
      </template>
      <template v-if="selectedTenant">
        <dl class="detail-list">
          <div class="detail-row">
            <dt>类型</dt>
            <dd>{{ selectedTenant.type === 'SELF_OPERATED' ? '自营' : '标准' }}</dd>
          </div>
          <div class="detail-row">
            <dt>状态</dt>
            <dd>
              <span class="status-row" :class="`status-${selectedTenant.status.toLowerCase()}`">
                <span class="status-dot" /><span>{{ statusLabel(selectedTenant.status) }}</span>
              </span>
            </dd>
          </div>
          <div class="detail-row">
            <dt>过期时间</dt>
            <dd>{{ selectedTenant.expireAt ? selectedTenant.expireAt.slice(0, 10) : '永不过期' }}</dd>
          </div>
          <div class="detail-row">
            <dt>描述</dt>
            <dd>{{ selectedTenant.description || '—' }}</dd>
          </div>
          <div class="detail-row">
            <dt>创建时间</dt>
            <dd>{{ selectedTenant.createdAt?.slice(0, 10) || '—' }}</dd>
          </div>
          <div class="detail-row">
            <dt>更新时间</dt>
            <dd>{{ timeAgo(selectedTenant.updatedAt) }}</dd>
          </div>
        </dl>

        <div v-if="selectedTenant.type === 'SELF_OPERATED'" class="self-op-note">
          <n-icon :size="14"><ShieldAlert /></n-icon>
          <span>自营租户受保护：不能停用、删除或设置过期时间。</span>
        </div>
      </template>
      <template #footer>
        <div class="modal-foot">
          <n-button @click="closeModal">关闭</n-button>
          <n-button
            v-if="auth.canUpdateTenant"
            type="primary"
            @click="enterEdit"
          >
            <template #icon>
              <n-icon><Pencil /></n-icon>
            </template>
            管理租户
          </n-button>
        </div>
      </template>
    </n-modal>

    <!-- 管理面板：资料 / 状态 / 危险区，分段独立保存 -->
    <n-modal
      :show="modalMode === 'edit' && !!selectedTenant"
      preset="card"
      class="tenant-modal tenant-manage-modal"
      :bordered="false"
      :segmented="{ content: 'soft' }"
      style="width: min(620px, calc(100vw - 32px))"
      @update:show="(v: boolean) => { if (!v) backToDetail() }"
    >
      <template v-if="selectedTenant" #header>
        <div class="modal-head">
          <div class="modal-head-text">
            <h2>管理租户</h2>
            <span class="modal-head-code">{{ selectedTenant.name }} · {{ selectedTenant.tenantCode }}</span>
          </div>
        </div>
      </template>

      <div v-if="selectedTenant" class="manage-body">
        <!-- 区块 1：资料 -->
        <section class="manage-section">
          <header class="manage-section-hd">
            <h3>资料</h3>
            <p>租户的显示信息，仅影响展示，不影响接入与计费。</p>
          </header>
          <n-form
            ref="editFormRef"
            :model="editForm"
            :rules="editFormRules"
            label-placement="top"
            require-mark-placement="right-hanging"
          >
            <n-form-item label="名称" path="name">
              <n-input v-model:value="editForm.name" placeholder="请输入租户显示名称" />
            </n-form-item>
            <n-form-item label="描述">
              <n-input
                v-model:value="editForm.description"
                type="textarea"
                placeholder="可选描述"
                :autosize="{ minRows: 3, maxRows: 6 }"
              />
            </n-form-item>
          </n-form>
          <div class="section-action">
            <n-button
              :disabled="!profileDirty"
              :loading="profileSaving"
              type="primary"
              @click="saveProfile"
            >保存资料</n-button>
          </div>
        </section>

        <!-- 区块 2：状态与过期 -->
        <section
          v-if="selectedTenant.type !== 'SELF_OPERATED'"
          class="manage-section"
        >
          <header class="manage-section-hd">
            <h3>状态与有效期</h3>
            <p>停用后该租户下用户将无法登录；过期时间到达后自动停用。</p>
          </header>

          <div class="status-row-block">
            <div class="status-current">
              <span class="status-row" :class="`status-${selectedTenant.status.toLowerCase()}`">
                <span class="status-dot" /><span>当前：{{ statusLabel(selectedTenant.status) }}</span>
              </span>
            </div>
            <n-button
              v-if="selectedTenant.status === 'ENABLED' && auth.canDisableTenant"
              :loading="statusSaving"
              @click="confirmDisable"
            >
              <template #icon>
                <n-icon><Power /></n-icon>
              </template>
              停用
            </n-button>
            <n-button
              v-else-if="selectedTenant.status !== 'ENABLED' && auth.canEnableTenant"
              :loading="statusSaving"
              type="primary"
              ghost
              @click="doEnable"
            >
              <template #icon>
                <n-icon><Power /></n-icon>
              </template>
              启用
            </n-button>
          </div>

          <div v-if="auth.canSetTenantExpire" class="expire-block">
            <div class="expire-block-label">
              <n-icon :size="14"><Clock /></n-icon>
              <span>过期时间</span>
              <span class="expire-hint">留空表示永不过期</span>
            </div>
            <div class="expire-block-control">
              <n-date-picker
                v-model:value="expireAtInput"
                type="datetime"
                clearable
                style="flex: 1"
              />
              <n-button
                :disabled="!expireDirty"
                :loading="expireSaving"
                @click="saveExpireAt"
              >保存</n-button>
            </div>
          </div>
        </section>

        <div v-else class="self-op-note">
          <n-icon :size="14"><ShieldAlert /></n-icon>
          <span>自营租户受保护：不能停用、删除或设置过期时间。</span>
        </div>

        <!-- 区块 3：危险操作 -->
        <section
          v-if="auth.canDeleteTenant && selectedTenant.type !== 'SELF_OPERATED'"
          class="manage-section danger-section"
        >
          <header class="manage-section-hd">
            <h3>危险操作</h3>
            <p>删除后该租户的所有用户与配置将不可恢复。</p>
          </header>
          <div class="section-action">
            <n-button type="error" ghost @click="confirmDelete">
              <template #icon>
                <n-icon><Trash2 /></n-icon>
              </template>
              删除租户
            </n-button>
          </div>
        </section>
      </div>

      <template #footer>
        <div class="modal-foot">
          <n-button @click="backToDetail">返回详情</n-button>
          <n-button type="primary" @click="closeModal">完成</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 创建态 -->
    <n-modal
      :show="modalMode === 'create'"
      preset="card"
      class="tenant-modal"
      :bordered="false"
      :segmented="{ content: 'soft' }"
      style="width: min(560px, calc(100vw - 32px))"
      @update:show="(v: boolean) => { if (!v) closeModal() }"
    >
      <template #header>
        <div class="modal-head">
          <div class="modal-head-text">
            <h2>新增租户</h2>
            <span class="modal-head-code">租户码创建后不可修改</span>
          </div>
        </div>
      </template>
      <n-form
        ref="createFormRef"
        :model="createForm"
        :rules="createFormRules"
        label-placement="top"
        require-mark-placement="right-hanging"
      >
        <n-form-item label="租户码" path="tenantCode">
          <n-input v-model:value="createForm.tenantCode" placeholder="例如 team-alpha" />
        </n-form-item>
        <n-form-item label="名称" path="name">
          <n-input v-model:value="createForm.name" placeholder="请输入租户显示名称" />
        </n-form-item>
        <n-form-item label="描述">
          <n-input
            v-model:value="createForm.description"
            type="textarea"
            placeholder="可选描述"
            :autosize="{ minRows: 3, maxRows: 6 }"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <div class="modal-foot">
          <n-button @click="closeModal">取消</n-button>
          <n-button type="primary" :loading="formSubmitting" @click="handleCreate">创建租户</n-button>
        </div>
      </template>
    </n-modal>
  </section>
</template>

<style scoped>
/* ============================================================ */
/* 整页 Grid：仅 .table-region 内部滚动，header/toolbar/foot 常驻 */
/* ============================================================ */
.tenant-page {
  /* console-content 的 padding 已经包了外边距，这里完全填满父容器 */
  height: 100%;
  display: grid;
  /* 行：页头 / 指标条 / 工具栏 / 表格(1fr) / 分页。指标条引入后页头与表格之间有了节奏 */
  grid-template-rows: auto auto auto 1fr auto;
  gap: 20px;
  min-height: 0; /* 让 1fr 行能正确收缩，避免溢出父容器 */
}

/* -------- 页头 -------- */
.page-hdr {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}
.page-hdr-text h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.01em;
}
.page-hdr-text p {
  margin: 4px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

/* -------- 工具栏 -------- */
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.search {
  position: relative;
  display: flex;
  align-items: center;
  flex: 1 1 280px;
  max-width: 360px;
}
.search-icon {
  position: absolute;
  left: 10px;
  color: var(--text-muted);
  pointer-events: none;
}
.search-input {
  width: 100%;
  height: 34px;
  padding: 0 32px 0 32px;
  font: inherit;
  font-size: 13.5px;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.search-input::placeholder {
  color: var(--text-muted);
}
.search-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--focus-ring);
}
.search-clear {
  position: absolute;
  right: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}
.search-clear:hover {
  background: var(--surface-elevated);
  color: var(--text);
}

/* Chip 切换组：替代下拉选择，作为常用筛选 */
.chip-group {
  display: inline-flex;
  padding: 3px;
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: 9px;
}
.chip {
  appearance: none;
  border: 0;
  background: transparent;
  padding: 4px 12px;
  font: inherit;
  font-size: 12.5px;
  color: var(--text-muted);
  border-radius: 6px;
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;
}
.chip:hover {
  color: var(--text);
}
.chip.is-active {
  background: var(--surface);
  color: var(--text);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}
[data-theme="dark"] .chip.is-active {
  background: #2a2a28;
  color: var(--text);
  box-shadow: none;
}

.reset-btn {
  appearance: none;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
}
.reset-btn:hover {
  color: var(--text);
  background: var(--surface-elevated);
}

/* -------- 表格区 -------- */
.table-region {
  min-height: 0; /* 关键：允许 1fr 行内部撑开滚动条 */
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}
.tenant-table {
  flex: 1;
  min-height: 0;
}
.empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
}

/* 行内单元格样式：扁平、去色块 */
:deep(.tenant-table .n-data-table-th) {
  font-weight: 550;
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: 0.01em;
  background: transparent;
}
:deep(.tenant-table .n-data-table-td) {
  padding-top: 12px;
  padding-bottom: 12px;
  font-size: 13.5px;
}
:deep(.tenant-table .n-data-table-tr:hover .n-data-table-td) {
  background: var(--surface-elevated);
}
:deep(.tenant-table .col-row-actions) {
  padding-right: 12px;
}

/* 行尾 kebab：常驻可见但保持低饱和度，避免在密集表格里抢视觉焦点。
   仍保留 hover 时的强调状态，作为可点击的强反馈。 */
:deep(.tenant-table .row-kebab) {
  color: var(--text-muted);
  transition: color 0.15s ease, background 0.15s ease;
  border-radius: 6px;
  padding: 4px;
}
:deep(.tenant-table .n-data-table-tr:hover .row-kebab),
:deep(.tenant-table .row-kebab:hover),
:deep(.tenant-table .row-kebab:focus-visible) {
  color: var(--text);
  background: var(--surface);
}

/* 紧凑单元格 */
.cell-tenant-name {
  font-weight: 600;
  color: var(--text);
  line-height: 1.3;
}
.cell-tenant-code {
  margin-top: 2px;
  font-family: var(--font-mono), monospace;
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: -0.01em;
}
.cell-type,
.cell-muted {
  color: var(--text-muted);
  font-size: 13px;
}

/* 状态点 + 文字：取代色块 Tag */
.status-row {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text);
}
.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}
.status-enabled {
  color: #20a779;
}
.status-disabled {
  color: var(--text-muted);
}
.status-expired {
  color: #d98b20;
}

/* -------- 分页 -------- */
.page-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.page-foot-summary {
  font-size: 13px;
  color: var(--text-muted);
}
.page-foot-summary strong {
  color: var(--text);
  font-weight: 600;
}

/* ============================================================ */
/* Modal: 详情 / 编辑 / 创建 / 过期时间                          */
/* ============================================================ */
:deep(.tenant-modal) {
  border-radius: 14px;
}
.modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}
.modal-head-text h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -0.005em;
}
.modal-head-code {
  display: block;
  margin-top: 4px;
  font-family: var(--font-mono), monospace;
  font-size: 12px;
  color: var(--text-muted);
}

.detail-list {
  margin: 0;
  display: grid;
  grid-template-columns: 100px 1fr;
  row-gap: 14px;
  column-gap: 16px;
}
.detail-row {
  display: contents;
}
.detail-row dt {
  font-size: 12.5px;
  color: var(--text-muted);
  padding-top: 1px;
}
.detail-row dd {
  margin: 0;
  font-size: 13.5px;
  color: var(--text);
  word-break: break-word;
}

.self-op-note {
  margin-top: 18px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--text-muted);
}

/* ---------- 管理面板：分段独立操作 ---------- */
.tenant-manage-modal :deep(.n-card__content) {
  padding-top: 8px;
  padding-bottom: 8px;
}
.manage-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.manage-section {
  padding: 18px 0;
  border-bottom: 1px solid var(--border);
}
.manage-section:first-child {
  padding-top: 6px;
}
.manage-section:last-child {
  border-bottom: 0;
  padding-bottom: 6px;
}
.manage-section-hd {
  margin-bottom: 12px;
}
.manage-section-hd h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.005em;
  color: var(--text);
}
.manage-section-hd p {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-muted);
}
.section-action {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
.status-row-block {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: 8px;
}
.status-current {
  font-size: 13px;
}
.expire-block {
  margin-top: 12px;
}
.expire-block-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--text-muted);
  margin-bottom: 6px;
}
.expire-block-label .expire-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
}
.expire-block-control {
  display: flex;
  gap: 8px;
  align-items: center;
}
.danger-section {
  /* 危险区与上方区块留更大间距，强化分隔感 */
  padding-top: 22px;
}
.danger-section .manage-section-hd h3 {
  color: var(--danger);
}

.modal-hint {
  margin: 0 0 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 13px;
}

.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* -------- 移动端 -------- */
@media (max-width: 720px) {
  .page-hdr {
    align-items: flex-start;
  }
  .toolbar {
    gap: 8px;
  }
  .search {
    flex: 1 1 100%;
    max-width: none;
  }
  .detail-list {
    grid-template-columns: 84px 1fr;
  }
}

/* 减少动效偏好 */
@media (prefers-reduced-motion: reduce) {
  .search-input,
  .chip,
  .row-kebab {
    transition: none;
  }
}
</style>
