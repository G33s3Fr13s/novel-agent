import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})

// 请求拦截器：自动带 access token
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：401 自动刷新 token
let refreshPromise: Promise<any> | null = null
api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config
    const url = original?.url || ''
    // 登录/注册/刷新/登出接口的 401 是业务错误（密码错、令牌失效），
    // 不能触发「静默刷新 token」逻辑，否则会整页刷新把错误提示冲掉
    const isAuthEndpoint = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout']
      .some((p) => url.includes(p))
    if (error.response?.status === 401 && !original._retry && !isAuthEndpoint) {
      // 多个请求同时 401，只发一次 refresh 请求
      if (!refreshPromise) {
        refreshPromise = axios.post('/api/auth/refresh', {}, { withCredentials: true })
          .finally(() => { refreshPromise = null })
      }
      try {
        const { data } = await refreshPromise
        const newToken = data.data.accessToken
        localStorage.setItem('accessToken', newToken)
        original._retry = true
        original.headers.Authorization = `Bearer ${newToken}`
        return api(original)
      } catch {
        // refresh 也失败了 → 会话过期
        localStorage.removeItem('accessToken')
        // 记住当前页面，登录后可以跳回来
        sessionStorage.setItem('loginRedirect', window.location.pathname + window.location.search)
        // 通知应用层
        window.dispatchEvent(new CustomEvent('auth:expired'))
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

export default api
