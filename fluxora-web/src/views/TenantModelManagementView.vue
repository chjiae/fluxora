<script setup lang="ts">
/**
 * 租户模型管理页（V10 重建）。
 *
 * 设计：
 * - 五行 Grid：页头 / 指标条 / 工具栏 / 表格区(1fr) / 分页；
 * - 指标条数据来自 /api/tenant-models/stats 单次聚合 SQL；
 * - 列表行点击进入详情抽屉，抽屉内嵌「基础信息 / 候选映射 / 价格 / 路由 + 路由目标」四段；
 * - 平台管理员代管：在工具栏显式选择目标租户后，所有读写接口都带 tenantId；
 *   切换租户后自动刷新列表 + 指标 + 已打开详情；
 * - 错误一律走 e.userMessage（http.ts 已映射所有 TENANT_MODEL_* 业务码）。
 */
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NButton, NDropdown, NIcon, useDialog, useMessage } from 'naive-ui'
import { MoreHorizontal, Plus, Search, X } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import StatusDot from '@/components/StatusDot.vue'
import {
  // 模型
  createTenantModel, deleteTenantModel, disableTenantModel, enableTenantModel,
  getTenantModel, getTenantModelStats, listTenantModels, updateTenantModel,
  type TenantModelSummary, type TenantModelStats, type TenantModelPayload,
  // 映射
  createMapping, deleteMapping, listMappings, updateMapping,
  type TenantModelCandidateMappingSummary,
  // 价格
  getCurrentPrice, getPriceHistory, publishPrice,
  type TenantModelPriceView, type PricePublishPayload,
  // 路由 + 目标
  createRoute, createRouteTarget, deleteRoute, deleteRouteTarget,
  listRoutes, listRouteTargets, updateRoute, updateRouteTarget,
  type ModelRouteSummary, type RouteTargetSummary, type InboundProtocol,
  // 候选池（供「新增映射」选择器使用：枚举各通道的所有候选）
  listChannelCandidates,
  type ProviderChannelModelSummary,
} from '@/services/tenantModel'
import { listProviderChannels, type ProviderChannelSummary } from '@/services/upstream'
import { listTenants, type Tenant } from '@/services/tenant'

const auth = useAuthStore()
const dialog = useDialog()
const message = useMessage()

// ========== 列表与指标 ==========

const rows = ref<TenantModelSummary[]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const size = 20
const keyword = ref('')
const statusFilter = ref<'' | 'DRAFT' | 'ENABLED' | 'DISABLED'>('')

const stats = ref<TenantModelStats | null>(null)
const statsLoading = ref(false)

// 平台管理员选择的目标租户（普通管理员忽略）
const targetTenantId = ref<number | null>(null)
const tenantsForPicker = ref<Tenant[]>([])

const metricItems = computed(() => [
  { label: '模型总数', value: stats.value?.total ?? null },
  { label: '已启用', value: stats.value?.enabled ?? null },
  { label: '草稿', value: stats.value?.draft ?? null },
  { label: '缺价格', value: stats.value?.missingPrice ?? null, tone: 'warn' as const },
  { label: '缺路由', value: stats.value?.missingRoute ?? null, tone: 'warn' as const },
])

const hasFilter = computed(() => !!keyword.value || statusFilter.value !== '')

/** 列表与指标的目标租户：平台管理员显式选择，租户管理员强制本租户。
 *  对于平台管理员未选择租户时，allow null（后端按全量聚合）。 */
function effectiveTenantId(): number | null | undefined {
  if (auth.isPlatformAdmin) return targetTenantId.value
  return undefined  // 后端忽略；服务层根据 JWT 自动取当前租户
}

async function loadList() {
  loading.value = true
  try {
    const r = await listTenantModels({
      tenantId: effectiveTenantId(),
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
      page: page.value, size,
    })
    rows.value = r.items
    total.value = r.total
  } catch (e: any) {
    message.error(e.userMessage || '加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await getTenantModelStats(effectiveTenantId() ?? null)
  } catch { /* 静默；指标失败不打扰主操作 */ }
  finally { statsLoading.value = false }
}

async function refreshAfterWrite() {
  await Promise.all([loadList(), loadStats()])
}

function resetFilters() {
  keyword.value = ''; statusFilter.value = ''; page.value = 1
  void loadList()
}

watch(targetTenantId, () => {
  page.value = 1
  detailOpen.value = false
  void refreshAfterWrite()
})

onMounted(async () => {
  if (auth.isPlatformAdmin) {
    try {
      const r = await listTenants({ page: 1, size: 200 })
      tenantsForPicker.value = r.items
    } catch { /* 静默 */ }
  }
  await refreshAfterWrite()
})

// ========== 创建 / 编辑模型 ==========

const editorOpen = ref(false)
const editing = ref<TenantModelSummary | null>(null)
const saving = ref(false)
const form = reactive<{
  modelCode: string; displayName: string; description: string
  supportsStreaming: boolean; supportsToolCalling: boolean
  supportsVision: boolean; supportsCache: boolean
}>({
  modelCode: '', displayName: '', description: '',
  supportsStreaming: false, supportsToolCalling: false,
  supportsVision: false, supportsCache: false,
})

function openEditor(r?: TenantModelSummary) {
  if (r) {
    editing.value = r
    Object.assign(form, {
      modelCode: r.modelCode, displayName: r.displayName,
      description: r.description || '',
      supportsStreaming: r.supportsStreaming, supportsToolCalling: r.supportsToolCalling,
      supportsVision: r.supportsVision, supportsCache: r.supportsCache,
    })
  } else {
    editing.value = null
    Object.assign(form, {
      modelCode: '', displayName: '', description: '',
      supportsStreaming: false, supportsToolCalling: false,
      supportsVision: false, supportsCache: false,
    })
  }
  editorOpen.value = true
}

async function save() {
  const code = form.modelCode.trim()
  const name = form.displayName.trim()
  if (!code || !name) { message.warning('请填写模型编码与展示名称'); return }
  // 平台管理员代管：必须先选择目标租户
  if (!editing.value && auth.isPlatformAdmin && targetTenantId.value == null) {
    message.warning('请先在工具栏选择目标租户后再创建模型')
    return
  }
  const payload: TenantModelPayload = {
    tenantId: editing.value ? undefined : (auth.isPlatformAdmin ? targetTenantId.value : undefined),
    modelCode: code, displayName: name,
    description: form.description.trim() || undefined,
    supportsStreaming: form.supportsStreaming,
    supportsToolCalling: form.supportsToolCalling,
    supportsVision: form.supportsVision,
    supportsCache: form.supportsCache,
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateTenantModel(editing.value.id, payload)
      message.success('模型已更新')
    } else {
      await createTenantModel(payload)
      message.success('模型已创建')
    }
    editorOpen.value = false
    await refreshAfterWrite()
  } catch (e: any) {
    message.error(e.userMessage || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(r: TenantModelSummary) {
  try {
    if (r.status === 'ENABLED') {
      await disableTenantModel(r.id)
      message.success('已停用')
    } else {
      await enableTenantModel(r.id)
      message.success('已启用')
    }
    await refreshAfterWrite()
    if (detailOpen.value && currentDetailId.value === r.id) await reloadDetail()
  } catch (e: any) {
    message.error(e.userMessage || '状态更新失败，请稍后重试')
  }
}

function confirmDelete(r: TenantModelSummary) {
  dialog.warning({
    title: '删除租户模型',
    content: `确定删除「${r.displayName}」（${r.modelCode}）吗？删除后将无法对外提供，价格历史与映射保留但不再生效。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteTenantModel(r.id)
        message.success('已删除')
        if (currentDetailId.value === r.id) detailOpen.value = false
        await refreshAfterWrite()
      } catch (e: any) {
        message.error(e.userMessage || '删除失败，请稍后重试')
      }
    },
  })
}

// ========== 详情抽屉 ==========

const detailOpen = ref(false)
const currentDetailId = ref<number | null>(null)
const detail = ref<TenantModelSummary | null>(null)
const detailLoading = ref(false)

// 详情子区域数据
const mappings = ref<TenantModelCandidateMappingSummary[]>([])
const mappingsLoading = ref(false)
const currentPrice = ref<TenantModelPriceView | null>(null)
const priceHistory = ref<TenantModelPriceView[]>([])
const priceLoading = ref(false)
const routes = ref<ModelRouteSummary[]>([])
const routesLoading = ref(false)
const expandedRouteId = ref<number | null>(null)
const targetsByRoute = ref<Map<number, RouteTargetSummary[]>>(new Map())

async function openDetail(r: TenantModelSummary) {
  currentDetailId.value = r.id
  detail.value = r
  detailOpen.value = true
  await reloadDetail()
}

async function reloadDetail() {
  if (currentDetailId.value == null) return
  const id = currentDetailId.value
  detailLoading.value = true
  try {
    detail.value = await getTenantModel(id)
  } catch (e: any) {
    message.error(e.userMessage || '加载详情失败')
    detailOpen.value = false
    return
  } finally {
    detailLoading.value = false
  }
  await Promise.all([reloadMappings(), reloadPrices(), reloadRoutes()])
}

// ---- 映射 ----

async function reloadMappings() {
  if (currentDetailId.value == null) return
  mappingsLoading.value = true
  try {
    mappings.value = await listMappings(currentDetailId.value)
  } catch (e: any) {
    message.error(e.userMessage || '映射加载失败')
  } finally {
    mappingsLoading.value = false
  }
}

const mappingAddOpen = ref(false)
const candidatePool = ref<Array<ProviderChannelModelSummary & { channelName: string }>>([])
const candidatePoolLoading = ref(false)
const newMappingCandidateId = ref<number | null>(null)
const newMappingRemark = ref('')
/** 因能力不匹配被过滤的候选数（用于提示用户） */
const capabilityMismatchCount = ref(0)

async function openAddMapping() {
  newMappingCandidateId.value = null
  newMappingRemark.value = ''
  capabilityMismatchCount.value = 0
  candidatePoolLoading.value = true
  candidatePool.value = []
  try {
    // 当前租户所有通道；后端会按当前用户租户过滤
    // 平台管理员代管时按目标租户过滤；为了把当前租户的全部候选汇成一张可选清单，
    // 先列出通道，再并发列出每个通道的候选，最后排除已映射候选与能力不匹配候选
    const channels = await listProviderChannels({
      tenantId: auth.isPlatformAdmin ? targetTenantId.value ?? undefined : undefined,
      page: 1, size: 200,
    })
    const usedIds = new Set(mappings.value.map(m => m.providerChannelModelId))
    const model = detail.value
    const batches = await Promise.all(channels.items.map(async (ch: ProviderChannelSummary) => {
      try {
        const list = await listChannelCandidates(ch.id)
        return list
          .filter(c => {
            if (c.status !== 'ENABLED' || usedIds.has(c.id)) return false
            // 能力匹配：租户模型声明的能力必须被候选支撑，避免用户配置后启用时报错
            // 采用 AND 关系：模型声明了 N 项能力，候选必须全部满足
            if (model?.supportsStreaming && !c.supportsStreaming) { capabilityMismatchCount.value++; return false }
            if (model?.supportsToolCalling && !c.supportsToolCalling) { capabilityMismatchCount.value++; return false }
            if (model?.supportsVision && !c.supportsVision) { capabilityMismatchCount.value++; return false }
            if (model?.supportsCache && !c.supportsCache) { capabilityMismatchCount.value++; return false }
            return true
          })
          .map(c => ({ ...c, channelName: ch.name }))
      } catch {
        return []
      }
    }))
    candidatePool.value = batches.flat()
  } catch (e: any) {
    message.error(e.userMessage || '候选列表加载失败')
  } finally {
    candidatePoolLoading.value = false
    mappingAddOpen.value = true
  }
}

async function saveMapping() {
  if (currentDetailId.value == null || newMappingCandidateId.value == null) {
    message.warning('请选择要映射的上游候选'); return
  }
  try {
    await createMapping(currentDetailId.value, {
      providerChannelModelId: newMappingCandidateId.value,
      remark: newMappingRemark.value.trim() || undefined,
    })
    message.success('映射已添加')
    mappingAddOpen.value = false
    await Promise.all([reloadMappings(), refreshAfterWrite()])
  } catch (e: any) {
    message.error(e.userMessage || '添加失败，请稍后重试')
  }
}

async function toggleMapping(m: TenantModelCandidateMappingSummary) {
  if (currentDetailId.value == null) return
  try {
    await updateMapping(currentDetailId.value, m.id, { enabled: !m.enabled })
    await reloadMappings()
  } catch (e: any) {
    message.error(e.userMessage || '状态更新失败')
  }
}

function confirmDeleteMapping(m: TenantModelCandidateMappingSummary) {
  dialog.warning({
    title: '删除候选映射',
    content: `确定删除候选「${m.upstreamDisplayName || m.upstreamModelId}」的映射吗？若仍被启用的路由目标引用，删除将被拒绝，请先处理关联路由目标。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      if (currentDetailId.value == null) return
      try {
        await deleteMapping(currentDetailId.value, m.id)
        message.success('已删除')
        await Promise.all([reloadMappings(), refreshAfterWrite()])
      } catch (e: any) {
        message.error(e.userMessage || '删除失败')
      }
    },
  })
}

// ---- 价格 ----

async function reloadPrices() {
  if (currentDetailId.value == null) return
  priceLoading.value = true
  try {
    const [cur, hist] = await Promise.all([
      getCurrentPrice(currentDetailId.value),
      getPriceHistory(currentDetailId.value),
    ])
    currentPrice.value = cur
    priceHistory.value = hist
  } catch (e: any) {
    message.error(e.userMessage || '价格加载失败')
  } finally {
    priceLoading.value = false
  }
}

const priceModalOpen = ref(false)
const priceForm = reactive<PricePublishPayload>({
  inputPricePerMillion: '',
  outputPricePerMillion: '',
  cacheWritePricePerMillion: null,
  cacheReadPricePerMillion: null,
})

function openPublishPrice() {
  priceForm.inputPricePerMillion = currentPrice.value?.inputPricePerMillion ?? ''
  priceForm.outputPricePerMillion = currentPrice.value?.outputPricePerMillion ?? ''
  priceForm.cacheWritePricePerMillion = currentPrice.value?.cacheWritePricePerMillion ?? null
  priceForm.cacheReadPricePerMillion = currentPrice.value?.cacheReadPricePerMillion ?? null
  priceModalOpen.value = true
}

/** 前端校验：8 位小数 + 非负 + 非科学计数；与后端 CnyPrecisionPolicy 同步 */
const PRICE_PATTERN = /^(?:0|[1-9]\d*)(?:\.\d{1,8})?$/
function validatePrice(v: string, label: string): string | null {
  if (!PRICE_PATTERN.test(v)) return `${label}格式不正确，最多 8 位小数`
  return null
}

async function savePrice() {
  if (!detail.value) return
  const input = priceForm.inputPricePerMillion.trim()
  const output = priceForm.outputPricePerMillion.trim()
  const ce = validatePrice(input, '输入单价') || validatePrice(output, '输出单价')
  if (ce) { message.warning(ce); return }
  let cacheWrite: string | null = null
  let cacheRead: string | null = null
  if (detail.value.supportsCache) {
    const cw = (priceForm.cacheWritePricePerMillion ?? '').toString().trim()
    const cr = (priceForm.cacheReadPricePerMillion ?? '').toString().trim()
    if (!cw || !cr) { message.warning('支持缓存的模型必须同时配置缓存读写价格'); return }
    const ce2 = validatePrice(cw, '缓存写入价') || validatePrice(cr, '缓存读取价')
    if (ce2) { message.warning(ce2); return }
    cacheWrite = cw
    cacheRead = cr
  }
  try {
    await publishPrice(detail.value.id, {
      inputPricePerMillion: input,
      outputPricePerMillion: output,
      cacheWritePricePerMillion: cacheWrite,
      cacheReadPricePerMillion: cacheRead,
    })
    message.success('已发布新价格版本')
    priceModalOpen.value = false
    await Promise.all([reloadPrices(), refreshAfterWrite()])
  } catch (e: any) {
    message.error(e.userMessage || '发布失败，请稍后重试')
  }
}

// ---- 路由 + 路由目标 ----

async function reloadRoutes() {
  if (currentDetailId.value == null) return
  routesLoading.value = true
  try {
    routes.value = await listRoutes(currentDetailId.value)
    // 已展开路由的目标列表同步刷新；其他路由按需懒加载
    if (expandedRouteId.value != null) await loadTargets(expandedRouteId.value)
  } catch (e: any) {
    message.error(e.userMessage || '路由加载失败')
  } finally {
    routesLoading.value = false
  }
}

async function toggleRouteExpand(routeId: number) {
  if (expandedRouteId.value === routeId) {
    expandedRouteId.value = null
    return
  }
  expandedRouteId.value = routeId
  if (!targetsByRoute.value.has(routeId)) await loadTargets(routeId)
}

async function loadTargets(routeId: number) {
  try {
    const list = await listRouteTargets(routeId)
    targetsByRoute.value.set(routeId, list)
    // Trigger reactivity by replacing the Map ref
    targetsByRoute.value = new Map(targetsByRoute.value)
  } catch (e: any) {
    message.error(e.userMessage || '路由目标加载失败')
  }
}

const routeAddOpen = ref(false)
const newRouteProtocol = ref<InboundProtocol>('OPENAI')

function openAddRoute() {
  newRouteProtocol.value = 'OPENAI'
  routeAddOpen.value = true
}

async function saveRoute() {
  if (currentDetailId.value == null) return
  try {
    await createRoute(currentDetailId.value, { inboundProtocol: newRouteProtocol.value })
    message.success('路由已创建')
    routeAddOpen.value = false
    await Promise.all([reloadRoutes(), refreshAfterWrite()])
  } catch (e: any) {
    message.error(e.userMessage || '创建失败，请稍后重试')
  }
}

async function toggleRoute(r: ModelRouteSummary) {
  try {
    await updateRoute(r.id, { enabled: !r.enabled })
    await reloadRoutes()
  } catch (e: any) {
    message.error(e.userMessage || '状态更新失败')
  }
}

function confirmDeleteRoute(r: ModelRouteSummary) {
  dialog.warning({
    title: '删除路由',
    content: `确定删除 ${r.inboundProtocol} 路由吗？删除后该协议下的所有路由目标将不再生效。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteRoute(r.id)
        message.success('已删除')
        if (expandedRouteId.value === r.id) expandedRouteId.value = null
        targetsByRoute.value.delete(r.id)
        await Promise.all([reloadRoutes(), refreshAfterWrite()])
      } catch (e: any) {
        message.error(e.userMessage || '删除失败')
      }
    },
  })
}

const targetAddOpen = ref(false)
const targetAddRouteId = ref<number | null>(null)
const newTargetMappingId = ref<number | null>(null)
const newTargetPriority = ref(100)
const newTargetWeight = ref(100)

function openAddTarget(routeId: number) {
  targetAddRouteId.value = routeId
  newTargetMappingId.value = null
  newTargetPriority.value = 100
  newTargetWeight.value = 100
  targetAddOpen.value = true
}

/** 候选映射选择器：仅展示与该路由协议兼容、且未被同路由占用的映射 */
function availableMappingsForRoute(routeId: number): TenantModelCandidateMappingSummary[] {
  const route = routes.value.find(r => r.id === routeId)
  if (!route) return []
  const used = new Set((targetsByRoute.value.get(routeId) || [])
    .filter(t => true)
    .map(t => t.tenantModelCandidateMappingId))
  // 前端按路由协议过滤：OPENAI 路由只展示 OPENAI 通道的映射；ANTHROPIC 同理
  return mappings.value.filter(m =>
    m.enabled && m.candidateAvailable
    && m.channelProtocol === route.inboundProtocol
    && !used.has(m.id))
}

async function saveTarget() {
  if (targetAddRouteId.value == null || newTargetMappingId.value == null) {
    message.warning('请选择候选映射'); return
  }
  try {
    await createRouteTarget(targetAddRouteId.value, {
      tenantModelCandidateMappingId: newTargetMappingId.value,
      priority: newTargetPriority.value,
      weight: newTargetWeight.value,
    })
    message.success('路由目标已添加')
    targetAddOpen.value = false
    await Promise.all([loadTargets(targetAddRouteId.value), reloadRoutes(), refreshAfterWrite()])
  } catch (e: any) {
    message.error(e.userMessage || '添加失败，请稍后重试')
  }
}

async function toggleTarget(routeId: number, t: RouteTargetSummary) {
  try {
    await updateRouteTarget(routeId, t.id, { enabled: !t.enabled })
    await loadTargets(routeId)
  } catch (e: any) {
    message.error(e.userMessage || '状态更新失败')
  }
}

function confirmDeleteTarget(routeId: number, t: RouteTargetSummary) {
  dialog.warning({
    title: '删除路由目标',
    content: `确定删除路由目标「${t.channelName} · ${t.upstreamModelIdSnapshot}」吗？`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteRouteTarget(routeId, t.id)
        message.success('已删除')
        await Promise.all([loadTargets(routeId), reloadRoutes(), refreshAfterWrite()])
      } catch (e: any) {
        message.error(e.userMessage || '删除失败')
      }
    },
  })
}

// ========== 表格列 ==========

function rowMenuOpts(r: TenantModelSummary) {
  const opts: { key: string; label: string; disabled?: boolean }[] = []
  if (auth.canManageTenantModels || auth.canCrossTenantManageModels) {
    opts.push({ key: 'edit', label: '编辑' })
    opts.push({ key: 'toggle', label: r.status === 'ENABLED' ? '停用' : '启用' })
    opts.push({ key: 'delete', label: '删除' })
  }
  return opts
}

function handleRowMenu(key: string, r: TenantModelSummary) {
  if (key === 'edit') openEditor(r)
  else if (key === 'toggle') toggleEnabled(r)
  else if (key === 'delete') confirmDelete(r)
}

function rowProps(r: TenantModelSummary) {
  return { style: 'cursor: pointer', onClick: () => openDetail(r) }
}

const columns = computed<any>(() => [
  {
    title: '模型', key: 'modelCode', minWidth: 220,
    render: (r: TenantModelSummary) => h('div', { class: 'cell-duo' }, [
      h('div', { class: 'cell-primary' }, r.displayName),
      h('div', { class: 'cell-meta' }, r.modelCode),
    ]),
  },
  ...(auth.isPlatformAdmin ? [{
    title: '租户', key: 'tenantName', width: 140,
    render: (r: TenantModelSummary) => r.tenantName || '—',
  }] : []),
  {
    title: '能力', key: 'caps', minWidth: 160,
    render: (r: TenantModelSummary) => {
      const caps: string[] = []
      if (r.supportsStreaming) caps.push('流式')
      if (r.supportsToolCalling) caps.push('工具')
      if (r.supportsVision) caps.push('视觉')
      if (r.supportsCache) caps.push('缓存')
      return caps.length === 0
        ? h('span', { class: 'cell-meta' }, '—')
        : h('div', { class: 'cap-row' }, caps.map(c => h('span', { class: 'cap-chip' }, c)))
    },
  },
  {
    title: '映射 / 路由', key: 'counts', width: 130, align: 'right',
    render: (r: TenantModelSummary) => h('span', { class: 'counts' }, `${r.mappingCount} / ${r.routeCount}`),
  },
  {
    title: '价格', key: 'hasActivePrice', width: 90, align: 'center',
    render: (r: TenantModelSummary) => r.hasActivePrice
      ? h('span', { class: 'price-ok' }, '已配置')
      : h('span', { class: 'price-miss' }, '缺价'),
  },
  { title: '状态', key: 'status', width: 100, render: (r: TenantModelSummary) => h(StatusDot, { status: r.status }) },
  {
    title: '', key: '__row_actions', width: 44, align: 'right', className: 'col-row-actions',
    render: (r: TenantModelSummary) => {
      const opts = rowMenuOpts(r)
      if (!opts.length) return null
      return h(NDropdown, { trigger: 'click', placement: 'bottom-end', options: opts, onSelect: (k: string) => handleRowMenu(k, r) }, {
        default: () => h(NButton, { text: true, class: 'row-kebab', 'aria-label': '更多操作', onClick: (e: MouseEvent) => e.stopPropagation() }, {
          default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }),
        }),
      })
    },
  },
])

// 详情中租户管理员只能管理自己的模型；平台管理员代管必须显式 tenantId
const canManageCurrentDetail = computed(() => {
  if (!detail.value) return false
  if (auth.isPlatformAdmin) return true
  return auth.isTenantAdmin && detail.value.tenantId === auth.user?.tenantId
})

/** 价格金额展示：去尾零，便于阅读；不参与计算 */
function fmtPrice(v: string | null | undefined): string {
  if (v == null) return '—'
  return v.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
}
</script>

<template>
  <section class="tm-page">
    <header class="page-hdr">
      <div class="page-hdr-text">
        <h1>租户模型</h1>
        <p>管理当前租户对外提供的模型、候选映射、价格与协议路由。模型编码同租户内唯一，不同租户允许重复。</p>
      </div>
      <n-button v-if="auth.canManageTenantModels || auth.canCrossTenantManageModels" type="primary" @click="openEditor()">
        <template #icon><n-icon><Plus /></n-icon></template>新增模型
      </n-button>
    </header>

    <MetricStrip :items="metricItems" :loading="statsLoading" />

    <div class="toolbar">
      <label class="search">
        <n-icon class="search-icon" :size="16"><Search /></n-icon>
        <input v-model="keyword" class="search-input" type="text" placeholder="搜索模型编码或名称" @keyup.enter="page = 1; loadList()" />
        <button v-if="keyword" type="button" class="search-clear" @click="keyword = ''; page = 1; loadList()"><n-icon :size="14"><X /></n-icon></button>
      </label>
      <div class="chip-group">
        <button v-for="o in [{l:'全部',v:''},{l:'草稿',v:'DRAFT'},{l:'启用',v:'ENABLED'},{l:'停用',v:'DISABLED'}]"
                :key="'st' + o.v" type="button"
                class="chip" :class="{ 'is-active': statusFilter === o.v }"
                @click="statusFilter = o.v as any; page = 1; loadList()">{{ o.l }}</button>
      </div>
      <div v-if="auth.isPlatformAdmin" class="tenant-picker">
        <span class="picker-label">目标租户</span>
        <n-select
          v-model:value="targetTenantId"
          clearable
          placeholder="选择租户（必须指定后才可写入）"
          :options="tenantsForPicker.map(t => ({ label: t.name + ' (' + t.tenantCode + ')', value: t.id }))"
          style="min-width: 240px"
        />
      </div>
      <button v-if="hasFilter" type="button" class="reset-btn" @click="resetFilters">重置</button>
    </div>

    <div class="table-region">
      <n-data-table
        v-if="rows.length || loading"
        :columns="columns" :data="rows" :loading="loading"
        :pagination="false" :bordered="false" :single-line="false"
        :row-props="rowProps" :scroll-x="900" flex-height size="medium"
      />
      <div v-else class="empty">
        <n-empty :description="hasFilter ? '没有符合筛选条件的模型' : '还没有租户模型'">
          <template #extra>
            <n-button v-if="!hasFilter && (auth.canManageTenantModels || auth.canCrossTenantManageModels)" type="primary" @click="openEditor()">新增第一个模型</n-button>
          </template>
        </n-empty>
      </div>
    </div>

    <footer class="page-foot">
      <span>共 <strong>{{ total }}</strong> 个模型</span>
      <n-pagination v-model:page="page" :item-count="total" :page-size="size" @update:page="loadList" />
    </footer>

    <!-- ============== 模型创建 / 编辑 Modal ============== -->
    <n-modal v-model:show="editorOpen" preset="card" :title="editing ? '编辑模型' : '新增模型'" style="max-width: 540px">
      <n-form label-placement="top">
        <n-form-item label="模型编码" required>
          <n-input v-model:value="form.modelCode" :disabled="!!editing" placeholder="例如 gpt-4o, claude-sonnet" />
          <template #feedback>同租户内唯一；删除后允许重新使用</template>
        </n-form-item>
        <n-form-item label="展示名称" required>
          <n-input v-model:value="form.displayName" placeholder="对外展示给 C 端用户的友好名称" />
        </n-form-item>
        <n-form-item label="描述">
          <n-input v-model:value="form.description" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="可选；C 端目录将原样展示" />
        </n-form-item>
        <n-form-item label="能力声明">
          <div class="cap-toggle">
            <n-checkbox v-model:checked="form.supportsStreaming">流式</n-checkbox>
            <n-checkbox v-model:checked="form.supportsToolCalling">工具调用</n-checkbox>
            <n-checkbox v-model:checked="form.supportsVision">视觉</n-checkbox>
            <n-checkbox v-model:checked="form.supportsCache">缓存</n-checkbox>
          </div>
          <template #feedback>启用模型前必须有至少一个候选支撑所声明的全部能力</template>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="editorOpen = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="save">保存</n-button>
      </template>
    </n-modal>

    <!-- ============== 详情抽屉（嵌套候选映射 / 价格 / 路由 + 目标） ============== -->
    <n-drawer v-model:show="detailOpen" :width="720" placement="right">
      <n-drawer-content v-if="detail" :title="detail.displayName" closable>
        <div class="detail-meta">
          <div><span>模型编码</span><strong class="mono">{{ detail.modelCode }}</strong></div>
          <div><span>状态</span><StatusDot :status="detail.status" /></div>
          <div v-if="auth.isPlatformAdmin"><span>所属租户</span><strong>{{ detail.tenantName || '—' }}</strong></div>
          <div><span>映射 / 路由</span><strong>{{ detail.mappingCount }} / {{ detail.routeCount }}</strong></div>
        </div>

        <n-divider style="margin:16px 0">上游候选映射</n-divider>
        <div class="sub-hdr">
          <span class="sub-title">{{ mappings.length }} 个映射</span>
          <n-button v-if="canManageCurrentDetail" size="small" type="primary" @click="openAddMapping">
            <template #icon><n-icon><Plus /></n-icon></template>添加候选映射
          </n-button>
        </div>
        <n-data-table
          v-if="mappings.length"
          :columns="[
            { title: '候选', key: 'cand', minWidth: 180, render: (m: TenantModelCandidateMappingSummary) => h('div', { class: 'cell-duo' }, [
              h('div', { class: 'cell-primary' }, m.upstreamDisplayName || m.upstreamModelId),
              h('div', { class: 'cell-meta' }, m.channelName + ' · ' + m.upstreamModelId),
            ]) },
            { title: '能力', key: 'caps', minWidth: 140, render: (m: TenantModelCandidateMappingSummary) => {
              const caps: string[] = []
              if (m.supportsStreaming) caps.push('流式')
              if (m.supportsToolCalling) caps.push('工具')
              if (m.supportsVision) caps.push('视觉')
              if (m.supportsCache) caps.push('缓存')
              return caps.length === 0 ? h('span', { class: 'cell-meta' }, '—') : h('div', { class: 'cap-row' }, caps.map(c => h('span', { class: 'cap-chip' }, c)))
            } },
            { title: '可用', key: 'avail', width: 80, align: 'center', render: (m: TenantModelCandidateMappingSummary) => m.candidateAvailable ? h('span', { class: 'price-ok' }, '可用') : h('span', { class: 'price-miss' }, '不可用') },
            { title: '状态', key: 'enabled', width: 90, render: (m: TenantModelCandidateMappingSummary) => h(StatusDot, { status: m.enabled ? 'ENABLED' : 'DISABLED' }) },
            { title: '', key: '__row_actions', width: 44, align: 'right', render: (m: TenantModelCandidateMappingSummary) => {
              if (!canManageCurrentDetail) return null
              const opts = [
                { key: 'toggle', label: m.enabled ? '停用' : '启用' },
                { key: 'delete', label: '删除' },
              ]
              return h(NDropdown, { trigger: 'click', placement: 'bottom-end', options: opts, onSelect: (k: string) => {
                if (k === 'toggle') toggleMapping(m); else if (k === 'delete') confirmDeleteMapping(m)
              } }, {
                default: () => h(NButton, { text: true, class: 'row-kebab', 'aria-label': '更多操作' }, { default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }) }),
              })
            } },
          ]"
          :data="mappings" :loading="mappingsLoading"
          :pagination="false" :bordered="false" :single-line="false" size="small"
        />
        <div v-else class="empty-sub">尚未添加候选映射；请先在「上游通道 → 候选」中创建候选。</div>

        <n-divider style="margin:16px 0">价格（CNY · 每百万 Token）</n-divider>
        <div class="sub-hdr">
          <span class="sub-title">
            <template v-if="currentPrice">
              当前 v{{ currentPrice.version }} · 输入 ￥{{ fmtPrice(currentPrice.inputPricePerMillion) }} · 输出 ￥{{ fmtPrice(currentPrice.outputPricePerMillion) }}
              <template v-if="detail.supportsCache"> · 缓存写 ￥{{ fmtPrice(currentPrice.cacheWritePricePerMillion) }} · 缓存读 ￥{{ fmtPrice(currentPrice.cacheReadPricePerMillion) }}</template>
            </template>
            <template v-else>尚未发布价格</template>
          </span>
          <n-button v-if="canManageCurrentDetail" size="small" type="primary" @click="openPublishPrice">
            发布新价格版本
          </n-button>
        </div>
        <n-data-table
          v-if="priceHistory.length"
          :columns="[
            { title: '版本', key: 'version', width: 70 },
            { title: '输入', key: 'inputPricePerMillion', minWidth: 110, render: (p: TenantModelPriceView) => '￥' + fmtPrice(p.inputPricePerMillion) },
            { title: '输出', key: 'outputPricePerMillion', minWidth: 110, render: (p: TenantModelPriceView) => '￥' + fmtPrice(p.outputPricePerMillion) },
            { title: '缓存写', key: 'cacheWritePricePerMillion', minWidth: 110, render: (p: TenantModelPriceView) => p.cacheWritePricePerMillion ? '￥' + fmtPrice(p.cacheWritePricePerMillion) : '—' },
            { title: '缓存读', key: 'cacheReadPricePerMillion', minWidth: 110, render: (p: TenantModelPriceView) => p.cacheReadPricePerMillion ? '￥' + fmtPrice(p.cacheReadPricePerMillion) : '—' },
            { title: '生效', key: 'effectiveAt', minWidth: 160 },
            { title: '失效', key: 'expiredAt', minWidth: 160, render: (p: TenantModelPriceView) => p.expiredAt || '当前' },
          ]"
          :data="priceHistory" :loading="priceLoading"
          :pagination="false" :bordered="false" :single-line="false" size="small"
        />

        <n-divider style="margin:16px 0">协议路由 + 路由目标</n-divider>
        <div class="sub-hdr">
          <span class="sub-title">{{ routes.length }} 条路由</span>
          <n-button v-if="canManageCurrentDetail" size="small" type="primary" @click="openAddRoute">
            <template #icon><n-icon><Plus /></n-icon></template>新增路由
          </n-button>
        </div>
        <div v-if="routes.length" class="routes-list">
          <article v-for="r in routes" :key="r.id" class="route-row">
            <div class="route-hdr" @click="toggleRouteExpand(r.id)">
              <strong class="route-proto">{{ r.inboundProtocol }}</strong>
              <StatusDot :status="r.enabled ? 'ENABLED' : 'DISABLED'" />
              <span class="route-meta">{{ r.targetCount }} 个目标</span>
              <span class="spacer" />
              <n-button v-if="canManageCurrentDetail" size="tiny" quaternary @click.stop="toggleRoute(r)">{{ r.enabled ? '停用' : '启用' }}</n-button>
              <n-button v-if="canManageCurrentDetail" size="tiny" quaternary @click.stop="confirmDeleteRoute(r)">删除</n-button>
              <span class="caret" :class="{ open: expandedRouteId === r.id }">▾</span>
            </div>
            <div v-if="expandedRouteId === r.id" class="route-body">
              <div class="targets-hdr">
                <span class="sub-title">{{ (targetsByRoute.get(r.id) || []).length }} 个目标</span>
                <n-button v-if="canManageCurrentDetail" size="tiny" type="primary" @click="openAddTarget(r.id)">
                  <template #icon><n-icon><Plus /></n-icon></template>添加目标
                </n-button>
              </div>
              <div v-if="(targetsByRoute.get(r.id) || []).length === 0" class="empty-sub">尚未添加路由目标</div>
              <div v-else class="targets-list">
                <div v-for="t in (targetsByRoute.get(r.id) || [])" :key="t.id" class="target-row">
                  <div class="target-main">
                    <strong>{{ t.channelName }} · {{ t.upstreamModelIdSnapshot }}</strong>
                    <span class="target-meta">优先级 {{ t.priority }} · 权重 {{ t.weight }}</span>
                  </div>
                  <span v-if="!t.mappingAvailable" class="price-miss">候选已停用</span>
                  <StatusDot :status="t.enabled ? 'ENABLED' : 'DISABLED'" />
                  <n-button v-if="canManageCurrentDetail" size="tiny" quaternary @click="toggleTarget(r.id, t)">{{ t.enabled ? '停用' : '启用' }}</n-button>
                  <n-button v-if="canManageCurrentDetail" size="tiny" quaternary @click="confirmDeleteTarget(r.id, t)">删除</n-button>
                </div>
              </div>
            </div>
          </article>
        </div>
        <div v-else class="empty-sub">尚未配置任何路由；启用模型需要至少一条路由 + 一个路由目标。</div>
      </n-drawer-content>
    </n-drawer>

    <!-- ============== 添加候选映射 Modal ============== -->
    <n-modal v-model:show="mappingAddOpen" preset="card" title="添加候选映射" style="max-width: 520px">
      <n-form label-placement="top">
        <n-form-item label="选择上游候选" required>
          <n-select
            v-model:value="newMappingCandidateId"
            :loading="candidatePoolLoading"
            :placeholder="candidatePool.length === 0 && !candidatePoolLoading ? '当前没有符合条件的候选' : '仅展示已启用、未映射且能力匹配的候选'"
            :options="candidatePool.map(c => ({ label: `${c.channelName} · ${c.upstreamDisplayName || c.upstreamModelId}`, value: c.id }))"
            :consistent-menu-width="false"
          />
          <template #feedback>
            <span>候选必须属于当前租户的启用通道；已排除已映射和能力不匹配的候选。</span>
            <span v-if="capabilityMismatchCount > 0" style="color: var(--warning); display: block; margin-top: 4px">
              {{ capabilityMismatchCount }} 个候选因不支持该模型声明的能力（流式/工具调用/视觉/缓存）已被过滤，
              请在上游通道中补充具备对应能力的候选后再添加映射。
            </span>
          </template>
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="newMappingRemark" placeholder="可选" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="mappingAddOpen = false">取消</n-button>
        <n-button type="primary" @click="saveMapping">保存</n-button>
      </template>
    </n-modal>

    <!-- ============== 发布价格 Modal ============== -->
    <n-modal v-model:show="priceModalOpen" preset="card" title="发布新价格版本" style="max-width: 480px">
      <n-form label-placement="top">
        <n-form-item label="输入单价 (CNY / 百万 Token)" required>
          <n-input v-model:value="priceForm.inputPricePerMillion" placeholder="例如 1.20" />
          <template #feedback>最多 8 位小数；不允许科学计数法或负数</template>
        </n-form-item>
        <n-form-item label="输出单价 (CNY / 百万 Token)" required>
          <n-input v-model:value="priceForm.outputPricePerMillion" placeholder="例如 3.60" />
        </n-form-item>
        <template v-if="detail?.supportsCache">
          <n-form-item label="缓存写入单价 (CNY / 百万 Token)" required>
            <n-input :value="priceForm.cacheWritePricePerMillion ?? ''" @update:value="(v: string) => priceForm.cacheWritePricePerMillion = v" placeholder="模型声明支持缓存时必填" />
          </n-form-item>
          <n-form-item label="缓存读取单价 (CNY / 百万 Token)" required>
            <n-input :value="priceForm.cacheReadPricePerMillion ?? ''" @update:value="(v: string) => priceForm.cacheReadPricePerMillion = v" placeholder="模型声明支持缓存时必填" />
          </n-form-item>
        </template>
        <n-alert v-else type="info" :bordered="false">当前模型未声明缓存能力，缓存读写价格无法配置。如需配置，请先在模型设置中开启「缓存」能力。</n-alert>
      </n-form>
      <template #footer>
        <n-button @click="priceModalOpen = false">取消</n-button>
        <n-button type="primary" @click="savePrice">发布</n-button>
      </template>
    </n-modal>

    <!-- ============== 新增路由 Modal ============== -->
    <n-modal v-model:show="routeAddOpen" preset="card" title="新增协议路由" style="max-width: 420px">
      <n-form label-placement="top">
        <n-form-item label="入站协议" required>
          <n-select
            v-model:value="newRouteProtocol"
            :options="[
              { label: 'OPENAI', value: 'OPENAI' },
              { label: 'ANTHROPIC', value: 'ANTHROPIC' },
            ]"
          />
          <template #feedback>同一模型同一协议只允许一条未删除路由</template>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="routeAddOpen = false">取消</n-button>
        <n-button type="primary" @click="saveRoute">保存</n-button>
      </template>
    </n-modal>

    <!-- ============== 添加路由目标 Modal ============== -->
    <n-modal v-model:show="targetAddOpen" preset="card" title="添加路由目标" style="max-width: 520px">
      <n-form label-placement="top">
        <n-form-item label="选择候选映射" required>
          <n-select
            v-model:value="newTargetMappingId"
            placeholder="仅展示当前模型启用的候选映射"
            :options="(targetAddRouteId != null ? availableMappingsForRoute(targetAddRouteId) : []).map(m => ({ label: `${m.channelName} · ${m.upstreamDisplayName || m.upstreamModelId}`, value: m.id }))"
            :consistent-menu-width="false"
          />
          <template #feedback>路由协议与候选通道协议必须一致；后端会兜底校验</template>
        </n-form-item>
        <n-form-item label="优先级 (0-100000)">
          <n-input-number v-model:value="newTargetPriority" :min="0" :max="100000" />
        </n-form-item>
        <n-form-item label="权重 (1-100000)">
          <n-input-number v-model:value="newTargetWeight" :min="1" :max="100000" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="targetAddOpen = false">取消</n-button>
        <n-button type="primary" @click="saveTarget">保存</n-button>
      </template>
    </n-modal>
  </section>
</template>

<style scoped>
.tm-page { height: 100%; display: grid; grid-template-rows: auto auto auto 1fr auto; gap: 20px; min-height: 0; }
.page-hdr { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.page-hdr-text h1 { margin: 0; font-size: 22px; font-weight: 650; letter-spacing: -0.01em; }
.page-hdr-text p { margin: 4px 0 0; color: var(--text-muted); font-size: 13px; max-width: 720px; }

.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { position: relative; display: flex; align-items: center; flex: 1 1 280px; max-width: 320px; }
.search-icon { position: absolute; left: 10px; color: var(--text-muted); pointer-events: none; }
.search-input { width: 100%; height: 34px; padding: 0 32px; font: inherit; font-size: 13.5px; color: var(--text); background: var(--surface); border: 1px solid var(--border); border-radius: 8px; outline: none; transition: border-color .15s ease, box-shadow .15s ease; }
.search-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--focus-ring); }
.search-clear { position: absolute; right: 8px; width: 20px; height: 20px; border: 0; border-radius: 4px; background: transparent; color: var(--text-muted); cursor: pointer; display: inline-flex; align-items: center; justify-content: center; }
.chip-group { display: inline-flex; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.chip { padding: 6px 14px; font-size: 13px; background: var(--surface); border: 0; border-right: 1px solid var(--border); color: var(--text-muted); cursor: pointer; }
.chip:last-child { border-right: 0; }
.chip.is-active { background: var(--accent); color: #fff; }
.tenant-picker { display: inline-flex; align-items: center; gap: 8px; }
.picker-label { font-size: 13px; color: var(--text-muted); }
.reset-btn { padding: 6px 12px; font-size: 13px; background: transparent; border: 1px solid var(--border); border-radius: 8px; color: var(--text-muted); cursor: pointer; }

.table-region { min-height: 0; overflow: hidden; display: flex; flex-direction: column; }
.table-region :deep(.n-data-table) { flex: 1; min-height: 0; }
.empty { display: flex; align-items: center; justify-content: center; height: 100%; min-height: 240px; }
.page-foot { display: flex; align-items: center; justify-content: space-between; color: var(--text-muted); font-size: 13px; }

.cell-duo { display: flex; flex-direction: column; gap: 2px; }
.cell-primary { font-weight: 600; color: var(--text); line-height: 1.3; }
.cell-meta { font-size: 12px; color: var(--text-muted); font-family: 'JetBrains Mono', 'Cascadia Code', monospace; }
.cap-row { display: inline-flex; flex-wrap: wrap; gap: 4px; }
.cap-chip { display: inline-block; padding: 1px 8px; font-size: 12px; border-radius: 6px; background: var(--surface-elevated); color: var(--text-muted); }
.cap-toggle { display: flex; gap: 16px; flex-wrap: wrap; }
.counts { font-variant-numeric: tabular-nums; color: var(--text); }
.price-ok { display: inline-block; font-size: 12px; color: var(--text-muted); }
.price-miss { display: inline-block; font-size: 12px; color: #b45309; }
.mono { font-family: 'JetBrains Mono', 'Cascadia Code', monospace; font-size: 13px; }

/* 详情抽屉子区域 */
.detail-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 6px 16px; padding: 8px 0; }
.detail-meta > div { display: flex; flex-direction: column; gap: 2px; }
.detail-meta span { font-size: 12px; color: var(--text-muted); }
.detail-meta strong { font-size: 14px; color: var(--text); }
.sub-hdr { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 8px; flex-wrap: wrap; }
.sub-title { font-size: 13px; color: var(--text-muted); }
.empty-sub { padding: 12px 0; color: var(--text-muted); font-size: 13px; }

.routes-list { display: flex; flex-direction: column; gap: 8px; }
.route-row { border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
.route-hdr { display: flex; align-items: center; gap: 10px; padding: 10px 12px; cursor: pointer; user-select: none; }
.route-proto { font-family: 'JetBrains Mono', 'Cascadia Code', monospace; font-size: 13px; }
.route-meta { font-size: 12px; color: var(--text-muted); }
.spacer { flex: 1; }
.caret { color: var(--text-muted); transition: transform .15s ease; }
.caret.open { transform: rotate(180deg); }
.route-body { border-top: 1px solid var(--border); padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
.targets-hdr { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.targets-list { display: flex; flex-direction: column; gap: 6px; }
.target-row { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg); }
.target-main { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.target-main strong { font-size: 13px; color: var(--text); }
.target-meta { font-size: 12px; color: var(--text-muted); }

:deep(.col-row-actions) { padding-right: 12px; }
:deep(.row-kebab) { color: var(--text-muted); transition: color .15s ease, background .15s ease; border-radius: 6px; padding: 4px; }
:deep(.n-data-table-tr:hover .row-kebab), :deep(.row-kebab:hover), :deep(.row-kebab:focus-visible) { color: var(--text); background: var(--surface); }
@media (max-width: 720px) {
  .search { max-width: none; }
  .chip-group { flex: 1 1 auto; }
  .tenant-picker { width: 100%; }
}
</style>
