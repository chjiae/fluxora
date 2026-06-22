<script setup lang="ts">
/**
 * 租户成员管理页面。
 *
 * 复用 TenantManagementView 的设计语言：4 行 Grid、.page-hdr/.toolbar/.table-region/.page-foot
 * 骨架、n-modal + .manage-section 分段管理面板、行内 kebab、状态点 + 文字。
 *
 * 双入口语义：
 *   - 通过 props.tenantId 指定时（来自 /console/tenants/:tenantId/members）走
 *     listMembersByTenant；适用于平台管理员。
 *   - props.tenantId 为空时（来自 /console/members）走 listMembersInCurrentTenant；
 *     适用于租户管理员，后端从 JWT 中的 tenantId 推断作用域。
 *
 * 所有错误经 src/services/http.ts 拦截后产生 e.userMessage，本页只展示 message.error()
 * 包装后的中文兜底文案；不暴露 HTTP 状态码、业务编码、堆栈、SQL。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NDropdown, NIcon } from 'naive-ui'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import {
  ArrowLeft,
  KeyRound,
  MoreHorizontal,
  Pencil,
  Plus,
  Power,
  Search,
  ShieldAlert,
  Trash2,
  Users,
  X,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import {
  createMember,
  deleteMember,
  disableMember,
  enableMember,
  fetchAssignableRoles,
  fetchMemberStatsByTenant,
  fetchMemberStatsInCurrentTenant,
  listMembersByTenant,
  listMembersInCurrentTenant,
  resetMemberPassword,
  updateMemberProfile,
  updateMemberRole,
  type Member,
  type MemberPage,
  type MemberStats,
  type RoleOption,
} from '@/services/member'
import { getTenant, type Tenant } from '@/services/tenant'

const props = defineProps<{ tenantId?: number }>()

const auth = useAuthStore()
const router = useRouter()
const message = useMessage()
const dialog = useDialog()

// ---------- 当前作用域：平台管理员选定的租户 / 租户管理员的自身租户 ----------
const scopedTenant = ref<Tenant | null>(null)
const isPlatformView = computed(() => props.tenantId != null)

// ---------- 列表状态 ----------
const members = ref<Member[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const statusFilter = ref<'' | 'ENABLED' | 'DISABLED'>('')
const roleFilter = ref<'' | 'TENANT_ADMIN' | 'TENANT_MEMBER'>('')
const loading = ref(false)

// ---------- 顶部指标条：聚合 SQL，单次拉取 ----------
const stats = ref<MemberStats | null>(null)
const statsLoading = ref(false)
async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = isPlatformView.value
      ? await fetchMemberStatsByTenant(props.tenantId!)
      : await fetchMemberStatsInCurrentTenant()
  } catch {
    stats.value = null
  } finally {
    statsLoading.value = false
  }
}
const metricItems = computed(() => [
  { label: '成员总数', value: stats.value?.total ?? null },
  { label: '启用中', value: stats.value?.enabled ?? null },
  { label: '已停用', value: stats.value?.disabled ?? null, tone: 'warn' as const },
  { label: '租户管理员', value: stats.value?.tenantAdmins ?? null },
  { label: '普通成员', value: stats.value?.tenantMembers ?? null },
])

// ---------- Modal 状态 ----------
type ModalMode = 'hidden' | 'detail' | 'edit' | 'create'
const modalMode = ref<ModalMode>('hidden')
const selectedMember = ref<Member | null>(null)
const formSubmitting = ref(false)
const profileSaving = ref(false)
const roleSaving = ref(false)
const statusSaving = ref(false)
const passwordSaving = ref(false)

const createForm = ref({
  username: '',
  displayName: '',
  email: '',
  password: '',
  confirmPassword: '',
  roleCode: '' as string,
})
const editForm = ref({ displayName: '', email: '' })
const passwordForm = ref({ newPassword: '', confirmPassword: '' })
const roleSelection = ref<string>('')

const createFormRef = ref<FormInst | null>(null)
const editFormRef = ref<FormInst | null>(null)
const passwordFormRef = ref<FormInst | null>(null)

// 可分配角色列表（来自后端，按操作者权限过滤）
const assignableRoles = ref<RoleOption[]>([])

// ---------- 表单校验规则：所有提示都是中文，不依赖后端原始 message ----------
function passwordStrength(_rule: unknown, value: string) {
  if (!value || value.length < 8) {
    return new Error('密码至少需要 8 位')
  }
  const hasLetter = /[A-Za-z]/.test(value)
  const hasDigit = /[0-9]/.test(value)
  if (!hasLetter || !hasDigit) {
    return new Error('密码需包含字母与数字')
  }
  return true
}

const createFormRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: ['input', 'blur'] },
    { pattern: /^[A-Za-z0-9_.-]{3,32}$/, message: '用户名 3-32 位，仅支持字母、数字、._-', trigger: ['input', 'blur'] },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: ['input', 'blur'] },
    { validator: passwordStrength, trigger: ['input', 'blur'] },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: ['input', 'blur'] },
    {
      validator: (_r, v) =>
        v === createForm.value.password ? true : new Error('两次输入的密码不一致'),
      trigger: ['input', 'blur'],
    },
  ],
  roleCode: [{ required: true, message: '请选择成员角色', trigger: ['change', 'blur'] }],
}

const editFormRules: FormRules = {
  displayName: [{ max: 128, message: '显示名称最多 128 个字符', trigger: ['input', 'blur'] }],
  email: [
    {
      validator: (_r, v) => {
        if (!v) return true
        return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) ? true : new Error('请输入有效的邮箱地址')
      },
      trigger: ['input', 'blur'],
    },
  ],
}

const passwordFormRules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: ['input', 'blur'] },
    { validator: passwordStrength, trigger: ['input', 'blur'] },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: ['input', 'blur'] },
    {
      validator: (_r, v) =>
        v === passwordForm.value.newPassword ? true : new Error('两次输入的密码不一致'),
      trigger: ['input', 'blur'],
    },
  ],
}

const statusOptions = [
  { label: '全部', value: '' as const },
  { label: '启用', value: 'ENABLED' as const },
  { label: '停用', value: 'DISABLED' as const },
]
const roleOptions = [
  { label: '全部', value: '' as const },
  { label: '租户管理员', value: 'TENANT_ADMIN' as const },
  { label: '成员', value: 'TENANT_MEMBER' as const },
]

function statusLabel(s: string) {
  return ({ ENABLED: '启用', DISABLED: '停用' } as Record<string, string>)[s] || s
}
function roleLabel(code: string) {
  return ({ TENANT_ADMIN: '租户管理员', TENANT_MEMBER: '成员' } as Record<string, string>)[code] || code
}
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

// ---------- 加载 ----------
async function loadMembers() {
  loading.value = true
  try {
    const params = {
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
      roleCode: roleFilter.value || undefined,
      page: page.value,
      size: size.value,
    }
    const r: MemberPage = isPlatformView.value
      ? await listMembersByTenant(props.tenantId!, params)
      : await listMembersInCurrentTenant(params)
    members.value = r.items
    total.value = r.total
  } catch {
    message.error('加载成员列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function loadScopedTenant() {
  // 平台管理员视角下加载租户卡片信息（名称、自营徽标）；
  // 租户管理员视角下若有 TENANT_READ 权限也可拉取，否则跳过。
  if (!isPlatformView.value) {
    scopedTenant.value = null
    return
  }
  try {
    scopedTenant.value = await getTenant(props.tenantId!)
  } catch {
    scopedTenant.value = null
  }
}

async function loadAssignableRoles() {
  try {
    assignableRoles.value = await fetchAssignableRoles()
  } catch {
    assignableRoles.value = []
  }
}

function resetFilters() {
  keyword.value = ''
  statusFilter.value = ''
  roleFilter.value = ''
  page.value = 1
  loadMembers()
}

watch([keyword, statusFilter, roleFilter], () => {
  page.value = 1
  loadMembers()
})
watch(page, () => loadMembers())
// 路由 tenantId 切换时重新拉取（如平台管理员从一个租户跳到另一个）
watch(() => props.tenantId, () => {
  page.value = 1
  loadScopedTenant()
  loadMembers()
})
onMounted(async () => {
  await Promise.all([loadScopedTenant(), loadAssignableRoles()])
  await Promise.all([loadMembers(), loadStats()])
})

/**
 * 写操作后的刷新：列表 + 指标条并行重拉。
 * 指标条静默失败，不打扰用户的成功反馈。
 */
async function refreshAfterWrite() {
  await Promise.all([loadMembers(), loadStats()])
}

// ---------- Modal ----------
function openDetail(m: Member) {
  selectedMember.value = m
  modalMode.value = 'detail'
}
function openCreate() {
  // 默认角色：可分配列表的第一项（租户管理员看到的是 TENANT_MEMBER；平台管理员是 TENANT_ADMIN）
  const defaultRole = assignableRoles.value[0]?.code ?? 'TENANT_MEMBER'
  createForm.value = {
    username: '',
    displayName: '',
    email: '',
    password: '',
    confirmPassword: '',
    roleCode: defaultRole,
  }
  modalMode.value = 'create'
}
function enterEdit() {
  if (!selectedMember.value) return
  editForm.value = {
    displayName: selectedMember.value.displayName || '',
    email: selectedMember.value.email || '',
  }
  passwordForm.value = { newPassword: '', confirmPassword: '' }
  roleSelection.value = selectedMember.value.roleCode
  modalMode.value = 'edit'
}
function closeModal() {
  modalMode.value = 'hidden'
  selectedMember.value = null
}
function backToDetail() {
  modalMode.value = 'detail'
}

// ---------- 写操作 ----------
async function handleCreate() {
  try {
    await createFormRef.value?.validate()
  } catch { return }
  if (!isPlatformView.value) {
    // 租户管理员的创建走 /api/tenant/{currentTenantId}/members；
    // 因为后端 service 会从 JWT 强制使用 currentUser.tenantId，
    // 这里前端需要一个明确的 tenantId 作为路径参数。
    // 租户管理员场景下我们使用 auth.user.tenantId。
    if (!auth.user?.tenantId) {
      message.error('当前账号未关联租户，无法创建成员')
      return
    }
  }
  const targetTenant = isPlatformView.value ? props.tenantId! : (auth.user!.tenantId as number)

  formSubmitting.value = true
  try {
    await createMember(targetTenant, {
      username: createForm.value.username.trim(),
      displayName: createForm.value.displayName.trim() || undefined,
      email: createForm.value.email.trim() || undefined,
      password: createForm.value.password,
      roleCode: createForm.value.roleCode,
    })
    closeModal()
    message.success('成员已创建')
    await refreshAfterWrite()
  } catch (e: any) {
    message.error(e?.userMessage || '创建成员失败，请稍后重试')
  } finally {
    formSubmitting.value = false
  }
}

async function saveProfile() {
  if (!selectedMember.value) return
  try {
    await editFormRef.value?.validate()
  } catch { return }
  profileSaving.value = true
  try {
    const updated = await updateMemberProfile(selectedMember.value.id, {
      displayName: editForm.value.displayName || undefined,
      email: editForm.value.email || undefined,
    })
    selectedMember.value = updated
    message.success('成员资料已保存')
    await refreshAfterWrite()
  } catch (e: any) {
    message.error(e?.userMessage || '保存成员资料失败，请稍后重试')
  } finally {
    profileSaving.value = false
  }
}

async function saveRole() {
  if (!selectedMember.value) return
  if (roleSelection.value === selectedMember.value.roleCode) {
    message.info('当前角色未变更')
    return
  }
  roleSaving.value = true
  try {
    const updated = await updateMemberRole(selectedMember.value.id, roleSelection.value)
    selectedMember.value = updated
    message.success('成员角色已调整')
    await refreshAfterWrite()
  } catch (e: any) {
    message.error(e?.userMessage || '调整角色失败，请稍后重试')
  } finally {
    roleSaving.value = false
  }
}

async function doEnable() {
  if (!selectedMember.value) return
  statusSaving.value = true
  try {
    const updated = await enableMember(selectedMember.value.id)
    selectedMember.value = updated
    message.success('成员已启用')
    await refreshAfterWrite()
  } catch (e: any) {
    message.error(e?.userMessage || '启用成员失败，请稍后重试')
  } finally {
    statusSaving.value = false
  }
}

async function doDisable() {
  if (!selectedMember.value) return
  statusSaving.value = true
  try {
    const updated = await disableMember(selectedMember.value.id)
    selectedMember.value = updated
    message.success('成员已停用')
    await refreshAfterWrite()
  } catch (e: any) {
    message.error(e?.userMessage || '停用成员失败，请稍后重试')
  } finally {
    statusSaving.value = false
  }
}

async function doDelete() {
  if (!selectedMember.value) return
  const id = selectedMember.value.id
  try {
    await deleteMember(id)
    closeModal()
    message.success('成员已删除')
    await refreshAfterWrite()
  } catch (e: any) {
    message.error(e?.userMessage || '删除成员失败，请稍后重试')
  }
}

async function savePassword() {
  if (!selectedMember.value) return
  try {
    await passwordFormRef.value?.validate()
  } catch { return }
  passwordSaving.value = true
  try {
    await resetMemberPassword(selectedMember.value.id, passwordForm.value.newPassword)
    passwordForm.value = { newPassword: '', confirmPassword: '' }
    // 重置后清空表单并提示，不在响应中保留任何密码痕迹
    message.success('密码已重置，新密码立即生效')
  } catch (e: any) {
    message.error(e?.userMessage || '重置密码失败，请稍后重试')
  } finally {
    passwordSaving.value = false
  }
}

function confirmDisable() {
  if (!selectedMember.value) return
  const name = selectedMember.value.displayName || selectedMember.value.username
  dialog.warning({
    title: '确认停用',
    content: `停用后 ${name} 将无法登录。确定停用？`,
    positiveText: '确认停用',
    negativeText: '取消',
    onPositiveClick: doDisable,
  })
}
function confirmDelete() {
  if (!selectedMember.value) return
  const name = selectedMember.value.displayName || selectedMember.value.username
  dialog.error({
    title: '确认删除',
    content: `删除 ${name} 后该成员不可恢复，且新密码登录将立即失效。确定删除？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: doDelete,
  })
}

// ---------- 行内菜单 ----------
function rowMenuOptions(_m: Member) {
  const opts: { key: string; label: string }[] = []
  if (auth.canUpdateMember) opts.push({ key: 'manage', label: '管理成员' })
  if (auth.canDeleteMember) opts.push({ key: 'delete', label: '删除成员' })
  return opts
}
function handleRowMenu(key: string, m: Member) {
  if (key === 'manage') {
    selectedMember.value = m
    enterEdit()
  } else if (key === 'delete') {
    selectedMember.value = m
    confirmDelete()
  }
}

// ---------- 表格列 ----------
function statusDot(s: string) {
  return h('span', { class: ['status-row', `status-${s.toLowerCase()}`] }, [
    h('span', { class: 'status-dot' }),
    h('span', null, statusLabel(s)),
  ])
}

const columns = computed<DataTableColumns<Member>>(() => {
  const cols: DataTableColumns<Member> = [
    {
      title: '成员',
      key: 'displayName',
      minWidth: 200,
      render: (row) => h('div', { class: 'cell-member' }, [
        h('div', { class: 'cell-member-name' }, row.displayName || row.username),
        h('div', { class: 'cell-member-uname' }, row.username),
      ]),
    },
    {
      title: '邮箱',
      key: 'email',
      width: 200,
      render: (row) => h('span', { class: 'cell-muted' }, row.email || '—'),
    },
    {
      title: '角色',
      key: 'roleCode',
      width: 120,
      render: (row) => h('span', { class: 'cell-type' }, roleLabel(row.roleCode)),
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (row) => statusDot(row.status),
    },
    {
      title: '加入',
      key: 'createdAt',
      width: 110,
      render: (row) => h('span', { class: 'cell-muted' }, timeAgo(row.createdAt)),
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
                onClick: (e: MouseEvent) => e.stopPropagation(),
              },
              { default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }) },
            ),
          },
        )
      },
    },
  ]
  return cols
})

function rowProps(row: Member) {
  return {
    style: 'cursor: pointer',
    onClick: () => openDetail(row),
  }
}

const showPagination = computed(() => total.value > size.value)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const hasFilter = computed(() => !!keyword.value || !!statusFilter.value || !!roleFilter.value)

const profileDirty = computed(() => {
  if (!selectedMember.value) return false
  return (
    editForm.value.displayName !== (selectedMember.value.displayName || '') ||
    editForm.value.email !== (selectedMember.value.email || '')
  )
})
const roleDirty = computed(() => {
  if (!selectedMember.value) return false
  return roleSelection.value !== selectedMember.value.roleCode
})

const headerTitle = computed(() => {
  if (isPlatformView.value && scopedTenant.value) return `${scopedTenant.value.name} · 成员管理`
  if (!isPlatformView.value) return '成员管理'
  return '成员管理'
})

const tenantBadge = computed(() => {
  if (!scopedTenant.value) return null
  if (scopedTenant.value.type === 'SELF_OPERATED') return { text: '自营', tone: 'protected' }
  return null
})

const tenantWritable = computed(() => {
  // 仅平台视角下可能遇到「租户已停用/过期/删除」的禁写状态；后端会拒绝写操作。
  // 这里只做 UI 提示，不做权限隐藏（隐藏依然在权限层）。
  if (!scopedTenant.value) return true
  return scopedTenant.value.status === 'ENABLED'
})

function backToTenants() {
  router.push('/console/tenants')
}
</script>

<template>
  <section class="member-page">
    <!-- 行 1：页头 -->
    <header class="page-hdr">
      <div class="page-hdr-text">
        <div class="page-hdr-back" v-if="isPlatformView">
          <n-button text size="small" @click="backToTenants">
            <template #icon>
              <n-icon><ArrowLeft /></n-icon>
            </template>
            返回租户列表
          </n-button>
        </div>
        <h1>
          {{ headerTitle }}
          <span v-if="tenantBadge" class="tenant-badge">{{ tenantBadge.text }}</span>
        </h1>
        <p v-if="isPlatformView && scopedTenant">
          租户码 <code>{{ scopedTenant.tenantCode }}</code> · 状态
          {{ ({ ENABLED: '启用', DISABLED: '停用', EXPIRED: '过期', DELETED: '已删除' } as Record<string, string>)[scopedTenant.status] }}
        </p>
        <p v-else-if="!isPlatformView">
          管理本租户内的成员账号、角色与登录密码。
        </p>
      </div>
      <n-button
        v-if="auth.canCreateMember && tenantWritable"
        type="primary"
        class="btn-primary-action"
        @click="openCreate"
      >
        <template #icon>
          <n-icon><Plus /></n-icon>
        </template>
        新增成员
      </n-button>
    </header>

    <!-- 行 2：指标条。聚合 SQL 一次返回总数 / 启停 / 角色分布 -->
    <MetricStrip :items="metricItems" :loading="statsLoading" />

    <!-- 行 3：工具栏 -->
    <div class="toolbar">
      <label class="search">
        <n-icon class="search-icon" :size="16"><Search /></n-icon>
        <input
          v-model="keyword"
          class="search-input"
          type="text"
          placeholder="搜索用户名、显示名称或邮箱"
          aria-label="搜索成员"
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

      <div class="chip-group" role="radiogroup" aria-label="角色筛选">
        <button
          v-for="opt in roleOptions"
          :key="`role-${opt.value}`"
          type="button"
          class="chip"
          :class="{ 'is-active': roleFilter === opt.value }"
          role="radio"
          :aria-checked="roleFilter === opt.value"
          @click="roleFilter = opt.value"
        >{{ opt.label }}</button>
      </div>

      <button
        v-if="hasFilter"
        type="button"
        class="reset-btn"
        @click="resetFilters"
      >重置</button>
    </div>

    <!-- 行 3：表格区 -->
    <div class="table-region">
      <n-data-table
        v-if="members.length || loading"
        :columns="columns"
        :data="members"
        :loading="loading"
        :pagination="false"
        :bordered="false"
        :single-line="false"
        :row-props="rowProps"
        flex-height
        class="member-table"
        size="medium"
      />
      <div v-else class="empty">
        <n-empty :description="hasFilter ? '没有符合筛选条件的成员' : '还没有成员'">
          <template #icon>
            <n-icon :size="36"><Users /></n-icon>
          </template>
          <template #extra>
            <n-button
              v-if="!hasFilter && auth.canCreateMember && tenantWritable"
              type="primary"
              @click="openCreate"
            >新增第一个成员</n-button>
            <n-button v-else-if="hasFilter" @click="resetFilters">清除筛选条件</n-button>
          </template>
        </n-empty>
      </div>
    </div>

    <!-- 行 4：分页 -->
    <footer class="page-foot">
      <span class="page-foot-summary">
        共 <strong>{{ total }}</strong> 个成员
      </span>
      <n-pagination
        v-if="showPagination"
        v-model:page="page"
        :page-count="pageCount"
        :page-size="size"
        size="small"
      />
    </footer>

    <!-- 详情 Modal -->
    <n-modal
      :show="modalMode === 'detail' && !!selectedMember"
      preset="card"
      class="member-modal"
      :bordered="false"
      :segmented="{ content: 'soft' }"
      style="width: min(560px, calc(100vw - 32px))"
      @update:show="(v: boolean) => { if (!v) closeModal() }"
    >
      <template v-if="selectedMember" #header>
        <div class="modal-head">
          <div class="modal-head-text">
            <h2>{{ selectedMember.displayName || selectedMember.username }}</h2>
            <span class="modal-head-code">{{ selectedMember.username }}</span>
          </div>
        </div>
      </template>
      <template v-if="selectedMember">
        <dl class="detail-list">
          <div class="detail-row"><dt>角色</dt><dd>{{ roleLabel(selectedMember.roleCode) }}</dd></div>
          <div class="detail-row"><dt>状态</dt><dd>
            <span class="status-row" :class="`status-${selectedMember.status.toLowerCase()}`">
              <span class="status-dot" /><span>{{ statusLabel(selectedMember.status) }}</span>
            </span>
          </dd></div>
          <div class="detail-row"><dt>邮箱</dt><dd>{{ selectedMember.email || '—' }}</dd></div>
          <div class="detail-row"><dt>所属租户</dt><dd>{{ selectedMember.tenantName }} <code>{{ selectedMember.tenantCode }}</code></dd></div>
          <div class="detail-row"><dt>创建时间</dt><dd>{{ selectedMember.createdAt?.slice(0, 10) || '—' }}</dd></div>
          <div class="detail-row"><dt>最近更新</dt><dd>{{ timeAgo(selectedMember.updatedAt) }}</dd></div>
        </dl>
      </template>
      <template #footer>
        <div class="modal-foot">
          <n-button @click="closeModal">关闭</n-button>
          <n-button
            v-if="auth.canUpdateMember"
            type="primary"
            @click="enterEdit"
          >
            <template #icon>
              <n-icon><Pencil /></n-icon>
            </template>
            管理成员
          </n-button>
        </div>
      </template>
    </n-modal>

    <!-- 管理面板 Modal：资料 / 角色 / 状态 / 重置密码 / 危险区 -->
    <n-modal
      :show="modalMode === 'edit' && !!selectedMember"
      preset="card"
      class="member-modal member-manage-modal"
      :bordered="false"
      :segmented="{ content: 'soft' }"
      style="width: min(620px, calc(100vw - 32px))"
      @update:show="(v: boolean) => { if (!v) backToDetail() }"
    >
      <template v-if="selectedMember" #header>
        <div class="modal-head">
          <div class="modal-head-text">
            <h2>管理成员</h2>
            <span class="modal-head-code">{{ selectedMember.displayName || selectedMember.username }} · {{ selectedMember.username }}</span>
          </div>
        </div>
      </template>

      <div v-if="selectedMember" class="manage-body">
        <!-- 资料 -->
        <section class="manage-section">
          <header class="manage-section-hd">
            <h3>资料</h3>
            <p>用户名不可修改；显示名称与邮箱可随时调整。</p>
          </header>
          <n-form
            ref="editFormRef"
            :model="editForm"
            :rules="editFormRules"
            label-placement="top"
            require-mark-placement="right-hanging"
          >
            <n-form-item label="显示名称" path="displayName">
              <n-input v-model:value="editForm.displayName" placeholder="例如 张三 / 运营组长" />
            </n-form-item>
            <n-form-item label="邮箱" path="email">
              <n-input v-model:value="editForm.email" placeholder="可选" />
            </n-form-item>
          </n-form>
          <div class="section-action">
            <n-button :disabled="!profileDirty" :loading="profileSaving" type="primary" @click="saveProfile">保存资料</n-button>
          </div>
        </section>

        <!-- 角色 -->
        <section class="manage-section" v-if="assignableRoles.length">
          <header class="manage-section-hd">
            <h3>角色</h3>
            <p>角色决定该成员在租户内的权限边界。租户管理员仅可分配「成员」。</p>
          </header>
          <n-select
            v-model:value="roleSelection"
            :options="assignableRoles.map(r => ({ label: r.name, value: r.code }))"
            placeholder="请选择角色"
          />
          <div class="section-action">
            <n-button :disabled="!roleDirty" :loading="roleSaving" type="primary" @click="saveRole">保存角色</n-button>
          </div>
        </section>

        <!-- 状态 -->
        <section class="manage-section">
          <header class="manage-section-hd">
            <h3>状态</h3>
            <p>停用后该成员立即无法登录或访问受保护接口；重新启用即恢复。</p>
          </header>
          <div class="status-row-block">
            <span class="status-row" :class="`status-${selectedMember.status.toLowerCase()}`">
              <span class="status-dot" /><span>当前：{{ statusLabel(selectedMember.status) }}</span>
            </span>
            <n-button
              v-if="selectedMember.status === 'ENABLED' && auth.canDisableMember"
              :loading="statusSaving"
              @click="confirmDisable"
            >
              <template #icon>
                <n-icon><Power /></n-icon>
              </template>
              停用
            </n-button>
            <n-button
              v-else-if="selectedMember.status === 'DISABLED' && auth.canEnableMember"
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
        </section>

        <!-- 重置密码 -->
        <section class="manage-section" v-if="auth.canResetMemberPassword">
          <header class="manage-section-hd">
            <h3>重置密码</h3>
            <p>新密码立即生效，原密码立即失效；密码不会在任何接口、列表或日志中回显。</p>
          </header>
          <n-form
            ref="passwordFormRef"
            :model="passwordForm"
            :rules="passwordFormRules"
            label-placement="top"
            require-mark-placement="right-hanging"
          >
            <n-form-item label="新密码" path="newPassword">
              <n-input v-model:value="passwordForm.newPassword" type="password" show-password-on="click" placeholder="至少 8 位，含字母和数字" />
            </n-form-item>
            <n-form-item label="确认新密码" path="confirmPassword">
              <n-input v-model:value="passwordForm.confirmPassword" type="password" show-password-on="click" placeholder="再次输入新密码" />
            </n-form-item>
          </n-form>
          <div class="section-action">
            <n-button :loading="passwordSaving" type="primary" @click="savePassword">
              <template #icon>
                <n-icon><KeyRound /></n-icon>
              </template>
              重置密码
            </n-button>
          </div>
        </section>

        <!-- 危险区 -->
        <section class="manage-section danger-section" v-if="auth.canDeleteMember">
          <header class="manage-section-hd">
            <h3>危险操作</h3>
            <p>删除后该成员账号将立即失效且无法恢复，用户名稍后可被复用。</p>
          </header>
          <div class="section-action">
            <n-button type="error" ghost @click="confirmDelete">
              <template #icon>
                <n-icon><Trash2 /></n-icon>
              </template>
              删除成员
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

    <!-- 创建 Modal -->
    <n-modal
      :show="modalMode === 'create'"
      preset="card"
      class="member-modal"
      :bordered="false"
      :segmented="{ content: 'soft' }"
      style="width: min(560px, calc(100vw - 32px))"
      @update:show="(v: boolean) => { if (!v) closeModal() }"
    >
      <template #header>
        <div class="modal-head">
          <div class="modal-head-text">
            <h2>新增成员</h2>
            <span class="modal-head-code">用户名创建后不可修改</span>
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
        <n-form-item label="用户名" path="username">
          <n-input v-model:value="createForm.username" placeholder="3-32 位字母数字或 ._-" />
        </n-form-item>
        <n-form-item label="显示名称">
          <n-input v-model:value="createForm.displayName" placeholder="可选" />
        </n-form-item>
        <n-form-item label="邮箱">
          <n-input v-model:value="createForm.email" placeholder="可选" />
        </n-form-item>
        <n-form-item label="角色" path="roleCode">
          <n-select
            v-model:value="createForm.roleCode"
            :options="assignableRoles.map(r => ({ label: r.name, value: r.code }))"
            placeholder="请选择成员角色"
          />
        </n-form-item>
        <n-form-item label="密码" path="password">
          <n-input v-model:value="createForm.password" type="password" show-password-on="click" placeholder="至少 8 位，含字母和数字" />
        </n-form-item>
        <n-form-item label="确认密码" path="confirmPassword">
          <n-input v-model:value="createForm.confirmPassword" type="password" show-password-on="click" placeholder="再次输入密码" />
        </n-form-item>
      </n-form>
      <template #footer>
        <div class="modal-foot">
          <n-button @click="closeModal">取消</n-button>
          <n-button type="primary" :loading="formSubmitting" @click="handleCreate">创建成员</n-button>
        </div>
      </template>
    </n-modal>
  </section>
</template>

<style scoped>
/* 复用 TenantManagementView 的整页 Grid 与设计 token，确保两页视觉一致 */
.member-page {
  height: 100%;
  display: grid;
  /* 行：页头 / 指标条 / 工具栏 / 表格(1fr) / 分页 */
  grid-template-rows: auto auto auto 1fr auto;
  gap: 20px;
  min-height: 0;
}

.page-hdr {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}
.page-hdr-back {
  margin-bottom: 4px;
}
.page-hdr-text h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.01em;
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.tenant-badge {
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--surface-elevated);
  color: var(--text-muted);
  border: 1px solid var(--border);
}
.page-hdr-text p {
  margin: 4px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}
.page-hdr-text p code {
  font-family: var(--font-mono), monospace;
  font-size: 12px;
}

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
.search-icon { position: absolute; left: 10px; color: var(--text-muted); pointer-events: none; }
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
.search-input::placeholder { color: var(--text-muted); }
.search-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--focus-ring); }
.search-clear {
  position: absolute; right: 8px;
  display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px;
  padding: 0; border: 0; border-radius: 4px;
  background: transparent; color: var(--text-muted); cursor: pointer;
}
.search-clear:hover { background: var(--surface-elevated); color: var(--text); }

.chip-group {
  display: inline-flex;
  padding: 3px;
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: 9px;
}
.chip {
  appearance: none; border: 0; background: transparent;
  padding: 4px 12px;
  font: inherit; font-size: 12.5px;
  color: var(--text-muted);
  border-radius: 6px; cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;
}
.chip:hover { color: var(--text); }
.chip.is-active {
  background: var(--surface);
  color: var(--text);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}
[data-theme="dark"] .chip.is-active { background: #2a2a28; color: var(--text); box-shadow: none; }

.reset-btn {
  appearance: none; border: 0; background: transparent;
  color: var(--text-muted);
  font: inherit; font-size: 13px;
  cursor: pointer;
  padding: 4px 6px; border-radius: 6px;
}
.reset-btn:hover { color: var(--text); background: var(--surface-elevated); }

.table-region {
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}
.member-table { flex: 1; min-height: 0; }
.empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
}

:deep(.member-table .n-data-table-th) {
  font-weight: 550;
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: 0.01em;
  background: transparent;
}
:deep(.member-table .n-data-table-td) {
  padding-top: 12px;
  padding-bottom: 12px;
  font-size: 13.5px;
}
:deep(.member-table .n-data-table-tr:hover .n-data-table-td) {
  background: var(--surface-elevated);
}
:deep(.member-table .col-row-actions) { padding-right: 12px; }
:deep(.member-table .row-kebab) {
  color: var(--text-muted);
  transition: color 0.15s ease, background 0.15s ease;
  border-radius: 6px;
  padding: 4px;
}
:deep(.member-table .n-data-table-tr:hover .row-kebab),
:deep(.member-table .row-kebab:hover),
:deep(.member-table .row-kebab:focus-visible) {
  color: var(--text);
  background: var(--surface);
}

.cell-member-name { font-weight: 600; color: var(--text); line-height: 1.3; }
.cell-member-uname {
  margin-top: 2px;
  font-family: var(--font-mono), monospace;
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: -0.01em;
}
.cell-type, .cell-muted { color: var(--text-muted); font-size: 13px; }

.status-row {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 13px; color: var(--text);
}
.status-dot {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}
.status-enabled { color: #20a779; }
.status-disabled { color: var(--text-muted); }
.status-deleted { color: var(--danger); }

.page-foot {
  display: flex; justify-content: space-between; align-items: center; gap: 12px;
  flex-wrap: wrap;
}
.page-foot-summary { font-size: 13px; color: var(--text-muted); }
.page-foot-summary strong { color: var(--text); font-weight: 600; }

:deep(.member-modal) { border-radius: 14px; }
.modal-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 12px; width: 100%;
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
.detail-row { display: contents; }
.detail-row dt { font-size: 12.5px; color: var(--text-muted); padding-top: 1px; }
.detail-row dd {
  margin: 0;
  font-size: 13.5px;
  color: var(--text);
  word-break: break-word;
}
.detail-row code {
  font-family: var(--font-mono), monospace;
  font-size: 12px;
  color: var(--text-muted);
  margin-left: 4px;
}

.member-manage-modal :deep(.n-card__content) {
  padding-top: 8px;
  padding-bottom: 8px;
}
.manage-body { display: flex; flex-direction: column; gap: 4px; }
.manage-section {
  padding: 18px 0;
  border-bottom: 1px solid var(--border);
}
.manage-section:first-child { padding-top: 6px; }
.manage-section:last-child { border-bottom: 0; padding-bottom: 6px; }
.manage-section-hd { margin-bottom: 12px; }
.manage-section-hd h3 {
  margin: 0; font-size: 14px; font-weight: 600;
  letter-spacing: -0.005em; color: var(--text);
}
.manage-section-hd p { margin: 4px 0 0; font-size: 12.5px; color: var(--text-muted); }
.section-action { display: flex; justify-content: flex-end; margin-top: 8px; }
.status-row-block {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: 8px;
}
.danger-section { padding-top: 22px; }
.danger-section .manage-section-hd h3 { color: var(--danger); }

.modal-foot {
  display: flex; justify-content: flex-end; gap: 8px;
}

@media (max-width: 720px) {
  .page-hdr { align-items: flex-start; }
  .toolbar { gap: 8px; }
  .search { flex: 1 1 100%; max-width: none; }
  .detail-list { grid-template-columns: 84px 1fr; }
}

@media (prefers-reduced-motion: reduce) {
  .search-input, .chip, .row-kebab { transition: none; }
}
</style>
