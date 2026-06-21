import axios from 'axios'

const http = axios.create({
  baseURL: 'http://localhost:8080',
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
