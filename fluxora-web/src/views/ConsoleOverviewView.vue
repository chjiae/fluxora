<script setup lang="ts">
/**
 * 控制台总览。
 *
 * 设计目标：从「孤零零欢迎语」升级为有信息密度的入口页，但坚守
 * impeccable / AGENT.md「克制、可靠」的产品调性：
 *   - 不用「hero metric template」（大数字 + 渐变图标 + 小标签）；
 *   - 不用 identical card grid；用一行指标条 + 两列结构不同的实用列表；
 *   - 数值仅在异常时染色（已停用、已过期、即将到期）；
 *   - 数据全部来自真实接口，平台管理员可见租户聚合，租户管理员仅可见成员聚合。
 */
import { computed, h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, ArrowRight, Building2, Users } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import {
  fetchTenantStats,
  listTenants,
  type Tenant,
  type TenantStats,
} from '@/services/tenant'
import {
  fetchMemberStatsInCurrentTenant,
  type MemberStats,
} from '@/services/member'

const auth = useAuthStore()
const router = useRouter()

// ---------- 平台管理员：租户聚合 + 即将到期 / 最近创建两列列表 ----------
const tenantStats = ref<TenantStats | null>(null)
const tenantStatsLoading = ref(false)
const expiringTenants = ref<Tenant[]>([])
const recentTenants = ref<Tenant[]>([])
const listsLoading = ref(false)

async function loadPlatformOverview() {
  tenantStatsLoading.value = true
  listsLoading.value = true
  try {
    const [stats, expired, recent] = await Promise.all([
      fetchTenantStats(30),
      // 当前 list 接口仅支持 EXPIRED 过滤；先用 EXPIRED 兜底「需关注」列表，
      // 「即将到期」精确计数由 stats.expiringSoon 提供。
      listTenants({ page: 1, size: 5, status: 'EXPIRED' }),
      listTenants({ page: 1, size: 5 }),
    ])
    tenantStats.value = stats
    expiringTenants.value = expired.items
    recentTenants.value = recent.items
  } catch {
    tenantStats.value = null
    expiringTenants.value = []
    recentTenants.value = []
  } finally {
    tenantStatsLoading.value = false
    listsLoading.value = false
  }
}

// ---------- 租户管理员：本租户成员聚合 ----------
const memberStats = ref<MemberStats | null>(null)
const memberStatsLoading = ref(false)
async function loadTenantOverview() {
  memberStatsLoading.value = true
  try {
    memberStats.value = await fetchMemberStatsInCurrentTenant()
  } catch {
    memberStats.value = null
  } finally {
    memberStatsLoading.value = false
  }
}

onMounted(() => {
  if (auth.canReadTenants) loadPlatformOverview()
  else if (auth.canReadMembers) loadTenantOverview()
})

// ---------- 指标条数据 ----------
const platformMetrics = computed(() => [
  { label: '租户总数', value: tenantStats.value?.total ?? null, hint: '未删除' },
  { label: '启用中', value: tenantStats.value?.enabled ?? null },
  { label: '已停用', value: tenantStats.value?.disabled ?? null, tone: 'warn' as const },
  { label: '已过期', value: tenantStats.value?.expired ?? null, tone: 'danger' as const },
  { label: '即将到期', value: tenantStats.value?.expiringSoon ?? null, hint: '30 天内', tone: 'warn' as const },
  { label: '自营租户', value: tenantStats.value?.selfOperated ?? null },
])
const tenantMetrics = computed(() => [
  { label: '成员总数', value: memberStats.value?.total ?? null },
  { label: '启用中', value: memberStats.value?.enabled ?? null },
  { label: '已停用', value: memberStats.value?.disabled ?? null, tone: 'warn' as const },
  { label: '租户管理员', value: memberStats.value?.tenantAdmins ?? null },
  { label: '普通成员', value: memberStats.value?.tenantMembers ?? null },
])

const roleLabel = computed(() => {
  if (auth.isPlatformAdmin) return '平台管理员'
  if (auth.isTenantAdmin) return '租户管理员'
  return '租户成员'
})

function tenantStatusTone(s: string) {
  return ({ ENABLED: 'enabled', DISABLED: 'disabled', EXPIRED: 'expired', DELETED: 'deleted' } as Record<string, string>)[s] || 'enabled'
}
function tenantStatusLabel(s: string) {
  return ({ ENABLED: '启用', DISABLED: '停用', EXPIRED: '过期', DELETED: '已删除' } as Record<string, string>)[s] || s
}
function formatDate(iso: string | null | undefined) {
  return iso ? iso.slice(0, 10) : '永不过期'
}

function gotoTenant(t: Tenant) {
  // 平台管理员从总览跳转直接进成员管理（最高频的下一步操作）；
  // 若用户更习惯先看租户详情，可在右侧 kebab 里完成。
  router.push(`/console/tenants/${t.id}/members`)
}
</script>

<template>
  <section class="overview">
    <!-- 行 1：标题 + 说明 + 角色徽标 -->
    <header class="overview-hdr">
      <div class="overview-hdr-text">
        <h1>你好，{{ auth.user?.displayName || auth.user?.username || '用户' }}。</h1>
        <p>当前以 <strong>{{ roleLabel }}</strong> 身份登录，这里是你的工作起点。</p>
      </div>
    </header>

    <!-- 行 2：指标条。平台管理员看租户聚合；租户管理员看成员聚合 -->
    <MetricStrip
      v-if="auth.canReadTenants"
      :items="platformMetrics"
      :loading="tenantStatsLoading"
    />
    <MetricStrip
      v-else-if="auth.canReadMembers"
      :items="tenantMetrics"
      :loading="memberStatsLoading"
    />

    <!-- 行 3：两列实用区。平台管理员看「需关注 + 最近创建」；
         租户管理员只看一条「快速入口」横幅，避免空旷感同时不伪造数据 -->
    <div v-if="auth.canReadTenants" class="overview-grid">
      <!-- 左：需关注（已过期 / 即将到期） -->
      <section class="panel">
        <header class="panel-hdr">
          <h2><AlertTriangle :size="16" class="panel-icon" /> 需关注</h2>
          <RouterLink to="/console/tenants?status=EXPIRED" class="panel-link">
            查看全部 <ArrowRight :size="14" />
          </RouterLink>
        </header>
        <p v-if="listsLoading" class="panel-empty">正在加载…</p>
        <p
          v-else-if="!expiringTenants.length && (tenantStats?.expiringSoon ?? 0) === 0"
          class="panel-empty"
        >没有需要立即关注的租户。</p>
        <ul v-else class="panel-list">
          <li
            v-for="t in expiringTenants"
            :key="`exp-${t.id}`"
            class="panel-row"
            tabindex="0"
            role="button"
            @click="gotoTenant(t)"
            @keyup.enter="gotoTenant(t)"
          >
            <div class="row-main">
              <span class="row-name">{{ t.name }}</span>
              <span class="row-meta">
                <span :class="['row-status', `s-${tenantStatusTone(t.status)}`]">
                  <span class="row-dot" />{{ tenantStatusLabel(t.status) }}
                </span>
                <span class="row-code">{{ t.tenantCode }}</span>
              </span>
            </div>
            <span class="row-expire">{{ formatDate(t.expireAt) }} 到期</span>
          </li>
          <li v-if="(tenantStats?.expiringSoon ?? 0) > 0" class="panel-foot">
            另有 <strong>{{ tenantStats?.expiringSoon }}</strong> 个租户将在 30 天内到期
          </li>
        </ul>
      </section>

      <!-- 右：最近创建 -->
      <section class="panel">
        <header class="panel-hdr">
          <h2><Building2 :size="16" class="panel-icon" /> 最近创建</h2>
          <RouterLink to="/console/tenants" class="panel-link">
            管理租户 <ArrowRight :size="14" />
          </RouterLink>
        </header>
        <p v-if="listsLoading" class="panel-empty">正在加载…</p>
        <p v-else-if="!recentTenants.length" class="panel-empty">还没有租户。</p>
        <ul v-else class="panel-list">
          <li
            v-for="t in recentTenants"
            :key="`rec-${t.id}`"
            class="panel-row"
            tabindex="0"
            role="button"
            @click="gotoTenant(t)"
            @keyup.enter="gotoTenant(t)"
          >
            <div class="row-main">
              <span class="row-name">{{ t.name }}</span>
              <span class="row-meta">
                <span :class="['row-status', `s-${tenantStatusTone(t.status)}`]">
                  <span class="row-dot" />{{ tenantStatusLabel(t.status) }}
                </span>
                <span class="row-code">{{ t.tenantCode }}</span>
              </span>
            </div>
            <span class="row-expire">{{ t.createdAt.slice(0, 10) }} 创建</span>
          </li>
        </ul>
      </section>
    </div>

    <!-- 租户管理员：一条快速入口，不堆砌假数据 -->
    <section v-else-if="auth.canReadMembers" class="panel single">
      <header class="panel-hdr">
        <h2><Users :size="16" class="panel-icon" /> 快速入口</h2>
      </header>
      <RouterLink to="/console/members" class="quick-link">
        进入成员管理 <ArrowRight :size="14" />
      </RouterLink>
    </section>
  </section>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 24px;
  /* 让总览页有节奏的「呼吸」：每段之间 24px，比管理页的 20px 稍宽 */
}

.overview-hdr {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}
.overview-hdr-text h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.015em;
}
.overview-hdr-text p {
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 13.5px;
}
.overview-hdr-text strong {
  font-weight: 600;
  color: var(--text);
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}

.panel {
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px 18px 8px;
}
.panel.single { padding-bottom: 16px; }

.panel-hdr {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 6px;
}
.panel-hdr h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.005em;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.panel-icon { color: var(--text-muted); }

.panel-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12.5px;
  color: var(--text-muted);
  border-radius: 4px;
  padding: 2px 4px;
  transition: color 0.15s ease;
}
.panel-link:hover { color: var(--text); }
.panel-link:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.panel-empty {
  margin: 8px 4px;
  font-size: 13px;
  color: var(--text-muted);
}

.panel-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
}
.panel-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  transition: background 0.15s ease;
}
.panel-row:last-of-type { border-bottom: 0; }
.panel-row:hover { background: var(--surface-elevated); }
.panel-row:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
  border-radius: 4px;
}
.row-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}
.row-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row-meta {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--text-muted);
}
.row-code {
  font-family: var(--font-mono), monospace;
  font-size: 11.5px;
  letter-spacing: -0.01em;
}
.row-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.row-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}
.s-enabled { color: #20a779; }
.s-disabled { color: var(--text-muted); }
.s-expired { color: #d98b20; }
.s-deleted { color: var(--danger); }

.row-expire {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.panel-foot {
  margin-top: 4px;
  padding: 10px 4px 4px;
  font-size: 12px;
  color: var(--text-muted);
}
.panel-foot strong {
  color: var(--text);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.quick-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  padding: 8px 0;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text);
  text-decoration: none;
  border-radius: 4px;
}
.quick-link:hover { color: var(--accent); }
.quick-link:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

@media (max-width: 720px) {
  .overview { gap: 18px; }
  .overview-hdr-text h1 { font-size: 21px; }
  .overview-grid { gap: 12px; }
  .panel { padding: 14px 14px 6px; }
}

@media (prefers-reduced-motion: reduce) {
  .panel-row, .panel-link, .quick-link { transition: none; }
}
</style>
