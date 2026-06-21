import axios from 'axios'

const http = axios.create({
  baseURL: 'http://localhost:8080',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})

// 统一错误处理：将后端业务错误转换为用户可读提示
const USER_MESSAGES: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: '用户名或密码错误，请重新输入',
  AUTH_ACCOUNT_DISABLED: '当前账号已停用，请联系管理员',
  AUTH_TENANT_DISABLED: '所属租户已停用，暂时无法使用',
  AUTH_TENANT_EXPIRED: '所属租户已到期，请联系管理员续期',
  AUTH_TENANT_DELETED: '当前账号所属租户不可用，请联系管理员',
  AUTH_SESSION_EXPIRED: '登录已失效，请重新登录',
  ACCESS_DENIED: '当前账号没有此操作权限',
  TENANT_CODE_DUPLICATE: '该租户码已被使用，请更换后重试',
  INTERNAL_ERROR: '服务暂时不可用，请稍后重试',
}

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.data) {
      const { code, message } = error.response.data
      const userMessage = USER_MESSAGES[code] || message || '请求失败，请稍后重试'
      error.userMessage = userMessage
    } else if (error.code === 'ERR_NETWORK') {
      error.userMessage = '网络连接失败，请检查网络后重试'
    } else if (error.code === 'ECONNABORTED') {
      error.userMessage = '请求超时，请稍后重试'
    } else {
      error.userMessage = '服务暂时不可用，请稍后重试'
    }
    return Promise.reject(error)
  },
)

export default http
