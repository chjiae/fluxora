import axios from 'axios'

// 生产环境（Docker / nginx 反向代理）使用相对路径，由顶层 nginx 统一路由 /api/* → platform。
// 本地开发直连 localhost:8080 的 Platform 实例，无需经过 nginx。
const http = axios.create({
  baseURL: import.meta.env.PROD ? '' : 'http://localhost:8080',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})

// 受控业务错误码 -> 用户安全中文文案 映射表
// 仅允许此映射表中的错误码展示对应文案；未知错误一律显示兜底文案
const USER_MESSAGES: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: '用户名或密码错误，请重新输入',
  AUTH_ACCOUNT_DISABLED: '当前账号已停用，请联系管理员',
  AUTH_TENANT_DISABLED: '所属租户已停用，暂时无法使用',
  AUTH_TENANT_EXPIRED: '所属租户已到期，请联系管理员续期',
  AUTH_TENANT_DELETED: '当前账号所属租户不可用，请联系管理员',
  AUTH_SESSION_EXPIRED: '登录已失效，请重新登录',
  ACCESS_DENIED: '当前账号没有此操作权限',
  TENANT_CODE_DUPLICATE: '该租户码已被使用，请更换后重试',
  SELF_OPERATED_TENANT_PROTECTED: '自营租户受保护，无法进行此操作',
  VALIDATION_ERROR: '输入内容不符合要求，请检查后重试',
  RESOURCE_NOT_FOUND: '请求的资源不存在',
  INTERNAL_ERROR: '服务暂时不可用，请稍后重试',
  // 成员管理（V4 引入）
  USERNAME_DUPLICATE: '该用户名已被使用，请更换后重试',
  LAST_TENANT_ADMIN_PROTECTED: '该租户至少需要保留一名启用状态的租户管理员，无法继续操作',
  ROLE_NOT_ASSIGNABLE: '当前账号无权分配该角色',
  MEMBER_NOT_FOUND: '成员不存在或已被删除',
  CROSS_TENANT_ACCESS_DENIED: '当前账号没有此操作权限',
  PASSWORD_WEAK: '密码强度不符合要求，请使用至少 8 位且包含字母与数字',
  // API Key（V5 引入）
  API_KEY_NAME_INVALID: '请填写符合要求的 Key 名称',
  API_KEY_NOT_FOUND: 'API Key 不存在或已被删除',
  API_KEY_DISABLED_STATE: '该 API Key 已停用，暂时无法使用',
  API_KEY_EXPIRED: '该 API Key 已过期，请更新过期时间或创建新的 Key',
  API_KEY_DELETED_STATE: '该 API Key 已删除，无法继续操作',
  // 额度（V5 引入）
  CREDIT_INSUFFICIENT: '当前额度不足，无法完成扣减',
  CREDIT_ACCOUNT_NOT_FOUND: '当前账号没有可用的额度账户',
  CREDIT_AMOUNT_INVALID: '调整金额必须为正数',
  CREDIT_REASON_REQUIRED: '请填写调整原因',
  // 卡密（V6 引入）
  CARD_CODE_INVALID: '卡密格式不正确，请检查后重新输入',
  CARD_NOT_FOUND: '卡密无效，请确认后重试',
  CARD_ALREADY_REDEEMED: '该卡密已被核销，无法重复使用',
  CARD_DISABLED: '该卡密已停用，请联系发卡方',
  CARD_EXPIRED: '该卡密已过期，请联系发卡方',
  CARD_BATCH_DISABLED: '该卡密所属批次已停用，暂时无法使用',
  CARD_CROSS_TENANT_REDEEM: '该卡密无法在当前账号所属租户使用',
  CARD_BATCH_NOT_FOUND: '卡密批次不存在或已被删除',
  CARD_BATCH_COUNT_EXCEEDED: '本次生成数量超出允许范围，请调整后重试',
  // 上游配置（V7 引入）
  UPSTREAM_PROVIDER_CODE_DUPLICATE: '该上游厂商编码已被使用，请更换后重试',
  UPSTREAM_PROVIDER_IN_USE: '该上游厂商仍有关联配置，无法删除',
  UPSTREAM_BASE_URL_INVALID: '请输入有效的 HTTP 或 HTTPS 接入基础地址',
  UPSTREAM_BASE_URL_DUPLICATE: '当前厂商下已存在相同协议和接入地址',
  UPSTREAM_BASE_URL_IN_USE: '该接入地址仍被通道使用，无法删除',
  UPSTREAM_CHANNEL_IN_USE: '该上游通道仍有关联凭证，无法删除',
  UPSTREAM_SHARED_READONLY: '当前账号无权修改平台共享上游配置',
  UPSTREAM_CHANNEL_PARAM_INVALID: '请检查优先级、权重或超时时间后重试',
  UPSTREAM_TENANT_UNAVAILABLE: '所属租户当前不可用，无法操作上游配置',
  CREDENTIAL_REQUIRED: '请输入上游访问凭证',
  CREDENTIAL_REPLACE_FAILED: '凭证更新失败，请检查输入后重试',
  CREDENTIAL_DUPLICATE: '该凭证已存在于当前租户，未重复添加',
  CREDENTIAL_ALL_DUPLICATE: '未导入任何凭证：提交的凭证均已存在或重复',
  CREDENTIAL_LIMIT_EXCEEDED: '本次导入数量超出允许范围，请拆分后重试',
  // 租户模型领域（V10 重建）
  TENANT_MODEL_CODE_DUPLICATE: '当前租户已存在相同模型编码，请更换后重试',
  TENANT_MODEL_NOT_FOUND: '租户模型不存在或已被删除',
  TENANT_MODEL_INVALID: '请检查模型编码、名称或能力配置后重试',
  TENANT_MODEL_NOT_ENABLEABLE: '模型尚未满足启用条件，请补充价格与路由配置后重试',
  TENANT_MODEL_CAPABILITY_UNSUPPORTED: '当前上游能力无法支撑该模型声明，请调整模型能力或路由配置',
  CHANNEL_MODEL_NOT_FOUND: '所选上游候选不可用，请刷新后重试',
  CHANNEL_MODEL_DUPLICATE: '当前通道下已存在相同上游模型标识，请更换后重试',
  CHANNEL_MODEL_CROSS_TENANT: '所选上游候选与目标租户不一致，请刷新后重试',
  TENANT_MODEL_MAPPING_NOT_FOUND: '候选映射不存在或已被删除',
  TENANT_MODEL_MAPPING_DUPLICATE: '该上游候选已映射到当前模型，无需重复添加',
  TENANT_MODEL_MAPPING_TENANT_MISMATCH: '所选模型或上游候选不可用，请刷新后重试',
  TENANT_MODEL_MAPPING_IN_USE: '当前映射仍被路由使用，请先处理关联路由',
}

const FALLBACK_MESSAGE = '服务暂时不可用，请稍后重试'

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.data) {
      const { code, message } = error.response.data
      // 仅使用受控映射表中的文案，不允许使用后端原始 message 作为兜底
      const userMessage = USER_MESSAGES[code]
      if (userMessage) {
        // 对于自营租户保护，后端 message 可能包含具体操作描述，
        // 但我们使用映射表中的固定文案
        error.userMessage = userMessage
      } else {
        error.userMessage = FALLBACK_MESSAGE
      }
    } else if (error.code === 'ERR_NETWORK') {
      error.userMessage = '网络连接失败，请检查网络后重试'
    } else if (error.code === 'ECONNABORTED') {
      error.userMessage = '请求超时，请稍后重试'
    } else {
      error.userMessage = FALLBACK_MESSAGE
    }
    return Promise.reject(error)
  },
)

export default http
